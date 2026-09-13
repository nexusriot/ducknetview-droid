package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.baseline.baselineEligible
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.net.IpScope
import com.vlad.ducknetview.domain.watchlist.Watchlist

/**
 * The tables show what is true right now; this records what changed. A
 * listener that appears and disappears between two glances at the screen is
 * invisible in a live view and is exactly what you want to know about later.
 */
class EventEngine(
    private val perKindCap: Int = PER_KIND_CAP,
    private val seenCap: Int = SEEN_CAP,
) {

    private val seenHosts = LinkedHashSet<String>()
    private val seenWatched = LinkedHashSet<String>()
    private val reportedOffBaseline = LinkedHashSet<String>()

    private val newHosts = ArrayList<String>()

    /**
     * Public addresses first seen during the most recent [diff]. The caller
     * persists these so "first contact" survives a process restart; the set
     * above only survives the session. Populated even when the per-kind cap
     * suppressed the event, because the host has still been reported on.
     */
    val lastNewHosts: List<String> get() = newHosts

    /**
     * Teaches the session dedupe about hosts contacted before this process
     * started, so a restart does not replay every known host as first contact.
     */
    fun seedSeenHosts(hosts: Collection<String>) {
        for (h in hosts) if (h.isNotEmpty()) seenHostAdd(seenHosts, h)
    }

    fun diff(
        prev: NetSnapshot?,
        cur: NetSnapshot,
        baseline: Baseline,
        watchlist: Watchlist,
    ): List<Event> {
        val rec = Recorder(cur.atMillis, perKindCap)
        newHosts.clear()
        serviceEvents(prev, cur, baseline, rec)
        connEvents(cur, watchlist, rec)
        networkEvents(prev, cur, rec)
        return rec.finish()
    }

    /** Forgets the per-session dedupe sets, e.g. when the capture restarts. */
    fun reset() {
        seenHosts.clear()
        seenWatched.clear()
        reportedOffBaseline.clear()
        newHosts.clear()
    }

    private fun serviceEvents(
        prev: NetSnapshot?,
        cur: NetSnapshot,
        baseline: Baseline,
        rec: Recorder,
    ) {
        val current = cur.services.filter(::baselineEligible)

        if (prev != null) {
            val before = prev.services.filter(::baselineEligible).associateBy { it.key }
            val after = current.associateBy { it.key }

            // Vanished listeners indexed by the identity a service keeps when
            // it merely rebinds: protocol, port and owning app.
            val gone = LinkedHashMap<String, MutableList<ServiceRow>>()
            for ((key, row) in before) {
                if (key in after) continue
                gone.getOrPut(identity(row)) { ArrayList() } += row
            }

            for (row in current) {
                if (row.key in before) continue

                // A listener vanishing and reappearing on the same port under
                // the same app is one event, not two: a daemon moving from
                // 127.0.0.1 to 0.0.0.0 is the single most important thing this
                // log can say, and as separate up/down lines it reads as noise.
                val candidates = gone[identity(row)]
                if (!candidates.isNullOrEmpty()) {
                    val was = candidates.removeAt(0)
                    val widened = IpScope.exposureRank(row.exposure) >
                        IpScope.exposureRank(was.exposure)
                    rec.add(
                        if (widened) EventLevel.ALERT else EventLevel.INFO,
                        EventKind.SERVICE_MOVED,
                        "${row.proto} ${row.bindAddr}:${row.port}",
                        detail(
                            "was ${was.bindAddr}:${was.port} (${was.exposure.name.lowercase()} " +
                                "→ ${row.exposure.name.lowercase()})",
                            if (widened) "now reachable from further away" else "rebound",
                            row.appLabel,
                        ),
                    )
                    continue
                }

                rec.add(
                    EventLevel.WARN,
                    EventKind.SERVICE_UP,
                    "${row.proto} ${row.bindAddr}:${row.port}",
                    detail(row.exposure.name.lowercase(), row.service, row.appLabel),
                )
            }

            for (rows in gone.values) {
                for (row in rows) {
                    rec.add(
                        EventLevel.INFO,
                        EventKind.SERVICE_DOWN,
                        "${row.proto} ${row.bindAddr}:${row.port}",
                        detail(row.service, row.appLabel),
                    )
                }
            }
        }

        // Off-baseline is reported once per listener per session rather than
        // once per poll: an unexpected listener that stays up is one finding,
        // not one every two seconds.
        for (row in current) {
            if (!baseline.isOffBaseline(row)) continue
            if (!seenHostAdd(reportedOffBaseline, row.baselineKey)) continue
            rec.add(
                EventLevel.ALERT,
                EventKind.OFF_BASELINE,
                "${row.proto} ${row.bindAddr}:${row.port}",
                detail("not in the saved baseline", row.exposure.name.lowercase(), row.appLabel),
            )
        }
    }

    private fun connEvents(cur: NetSnapshot, watchlist: Watchlist, rec: Recorder) {
        for (c in cur.conns) {
            val remote = c.remoteAddr
            if (remote.isEmpty()) continue

            if (!watchlist.isEmpty && watchlist.matches(remote, c.resolvedHost)) {
                if (seenHostAdd(seenWatched, remote)) {
                    rec.add(
                        EventLevel.ALERT,
                        EventKind.WATCHLIST_HIT,
                        c.remoteDisplay(revDns = true),
                        detail(c.appLabel, c.service),
                    )
                }
            }

            // One event per remote host per session: a browser opens dozens of
            // connections to the same endpoint and only the first is news.
            if (IpScope.of(remote) == Scope.PUBLIC && seenHostAdd(seenHosts, remote)) {
                newHosts.add(remote)
                rec.add(
                    EventLevel.INFO,
                    EventKind.NEW_PUBLIC_HOST,
                    c.remoteDisplay(revDns = true),
                    detail("first contact by ${c.appLabel.ifBlank { "uid ${c.uid}" }}", c.service),
                )
            }
        }
    }

    private fun networkEvents(prev: NetSnapshot?, cur: NetSnapshot, rec: Recorder) {
        // The first snapshot is the baseline, not a burst of "network appeared".
        if (prev == null) return

        val before = prev.networks.associateBy { it.id }
        val after = cur.networks.associateBy { it.id }

        for (n in cur.networks) {
            val p = before[n.id]
            when {
                p == null -> rec.add(
                    if (n.up) EventLevel.INFO else EventLevel.WARN,
                    if (n.up) EventKind.NETWORK_UP else EventKind.NETWORK_DOWN,
                    label(n),
                    "appeared",
                )

                p.up != n.up -> rec.add(
                    if (n.up) EventLevel.INFO else EventLevel.WARN,
                    if (n.up) EventKind.NETWORK_UP else EventKind.NETWORK_DOWN,
                    label(n),
                    if (n.up) "down → up" else "up → down",
                )

                else -> {
                    val changes = changes(p, n)
                    if (changes.isNotEmpty()) {
                        rec.add(EventLevel.INFO, EventKind.NETWORK_CHANGED, label(n), changes)
                    }
                }
            }
        }

        for (p in prev.networks) {
            if (p.id in after) continue
            rec.add(EventLevel.WARN, EventKind.NETWORK_DOWN, label(p), "vanished")
        }
    }

    private fun changes(a: NetworkRow, b: NetworkRow): String {
        val parts = ArrayList<String>(4)
        if (a.addresses != b.addresses) {
            parts += "addresses ${a.addresses.joinToString(",")} → ${b.addresses.joinToString(",")}"
        }
        if (a.isDefault != b.isDefault) {
            parts += if (b.isDefault) "became the default network" else "no longer the default network"
        }
        if (a.gateway != b.gateway) parts += "gateway ${a.gateway ?: "-"} → ${b.gateway ?: "-"}"
        if (a.dnsServers != b.dnsServers) {
            parts += "dns ${a.dnsServers.joinToString(",")} → ${b.dnsServers.joinToString(",")}"
        }
        if (a.metered != b.metered) parts += if (b.metered) "now metered" else "no longer metered"
        if (a.validated != b.validated) parts += if (b.validated) "validated" else "lost validation"
        return parts.joinToString(" · ")
    }

    private fun label(n: NetworkRow): String =
        if (n.ifaceName.isEmpty()) n.id else "${n.ifaceName} (${n.transport.name.lowercase()})"

    private fun identity(row: ServiceRow): String = "${row.proto}|${row.port}|${row.appLabel}"

    /** Bounded insert; returns true when the value had not been seen before. */
    private fun seenHostAdd(set: LinkedHashSet<String>, value: String): Boolean {
        if (!set.add(value)) return false
        while (set.size > seenCap) {
            val it = set.iterator()
            it.next()
            it.remove()
        }
        return true
    }

    private fun detail(vararg parts: String?): String =
        parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

    /**
     * Batches one diff so the cap can be applied per kind, with an explicit
     * summary when it truncates. A silent cap reads as "nothing else happened".
     */
    private class Recorder(val at: Long, val cap: Int) {
        private val out = ArrayList<Event>()
        private val counts = LinkedHashMap<EventKind, Int>()
        private val dropped = LinkedHashMap<EventKind, Int>()

        fun add(level: EventLevel, kind: EventKind, subject: String, detail: String) {
            val n = counts[kind] ?: 0
            if (n >= cap) {
                dropped[kind] = (dropped[kind] ?: 0) + 1
                return
            }
            counts[kind] = n + 1
            out += Event(at = at, level = level, kind = kind, subject = subject, detail = detail)
        }

        fun finish(): List<Event> {
            for ((kind, n) in dropped) {
                if (n <= 0) continue
                out += Event(
                    at = at,
                    level = EventLevel.INFO,
                    kind = EventKind.SUPPRESSED,
                    subject = "$n more ${kind.label} suppressed",
                    detail = "truncated to keep the log readable",
                )
            }
            return out
        }
    }

    companion object {
        /** Events of one kind one diff may emit before it starts summarising. */
        const val PER_KIND_CAP = 20

        /** Remote hosts remembered for dedupe, oldest evicted first. */
        const val SEEN_CAP = 2048
    }
}
