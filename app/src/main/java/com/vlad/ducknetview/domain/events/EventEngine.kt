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
import com.vlad.ducknetview.domain.net.Trust
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
     * The link topology as last reported to the user, keyed by [linkKey], and
     * how many consecutive diffs each absent link has been missing for.
     *
     * Deliberately not cleared by [reset]: the set of links this device has is
     * not a per-session dedupe, and it does not change because capture
     * restarted. Clearing it would announce every network as newly appeared.
     */
    private val knownLinks = LinkedHashMap<String, NetworkRow>()
    private val missingTicks = HashMap<String, Int>()

    /** Links currently reported as behind a captive portal, by [linkKey]. */
    private val portalLinks = LinkedHashSet<String>()

    /** The link the untrusted-network line was last written for. */
    private var untrustedReported: String? = null

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
        untrustedEvent(cur, rec)
        return rec.finish()
    }

    /** Forgets the per-session dedupe sets, e.g. when the capture restarts. */
    fun reset() {
        seenHosts.clear()
        seenWatched.clear()
        reportedOffBaseline.clear()
        newHosts.clear()
        untrustedReported = null
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

    /**
     * What makes two snapshots' entries the same link.
     *
     * Not [NetworkRow.id]: that is the platform's `Network` object, whose netId
     * is re-issued whenever Android re-registers a network — which it does
     * every time a VPN comes up or goes down. Keying on it meant that turning
     * this app's own capture on made the Wi-Fi's id step from 785 to 786 while
     * the link never dropped a packet, and the diff below read that as the old
     * network vanishing and a new one appearing: a WARN "network_down …
     * vanished" and an alert notification, for an outage that never happened
     * and that the user had just caused by pressing this app's own switch.
     *
     * An interface name is unique among the networks up at one time and
     * survives re-registration, so it is the identity a person means by "the
     * same link". The id remains the key for byte counters, where a fresh
     * registration really does restart from zero.
     */
    /**
     * The default link being one the user does not trust.
     *
     * Unlike everything in [networkEvents] this fires on the first snapshot
     * too, and deliberately. The topology events are about a *change* — a
     * link appearing is only news against a previous list — but this is a
     * standing property of where the device is right now, and opening the app
     * on a network you marked untrusted is exactly the moment the log should
     * say so. It is also the line that explains the automatic capture or scan
     * that [NetSnapshot.untrustedDefault] has just triggered, so staying
     * silent on the first tick would leave those unexplained.
     *
     * Once per link: a standing condition reported every two seconds is not a
     * log, and the same key coming back after a different network is a real
     * second arrival.
     */
    private fun untrustedEvent(cur: NetSnapshot, rec: Recorder) {
        if (!cur.untrustedDefault) {
            untrustedReported = null
            return
        }
        val def = cur.networks.firstOrNull { it.isDefault && !it.isNoise } ?: return
        val key = linkKey(def)
        if (untrustedReported == key) return
        untrustedReported = key
        rec.add(
            EventLevel.WARN,
            EventKind.NETWORK_UNTRUSTED,
            label(def),
            detail(
                when (def.trust) {
                    Trust.UNTRUSTED -> "marked untrusted"
                    else -> "never seen before, and unknown networks are treated as untrusted"
                },
                "this device's exposure on it is worth checking",
            ),
        )
    }

    private fun linkKey(n: NetworkRow): String =
        if (n.ifaceName.isNotEmpty()) "${n.transport}/${n.ifaceName}" else "id/${n.id}"

    private fun networkEvents(prev: NetSnapshot?, cur: NetSnapshot, rec: Recorder) {
        // The first snapshot is the baseline, not a burst of "network appeared".
        // A portal already in place is seeded rather than announced for the
        // same reason, but it *is* seeded: otherwise the sign-in that follows
        // has nothing to clear and goes unreported too.
        if (prev == null) {
            for (n in cur.networks) {
                val key = linkKey(n)
                knownLinks[key] = n
                if (n.captivePortal) portalLinks += key
            }
            return
        }

        val after = cur.networks.associateBy(::linkKey)

        for (n in cur.networks) {
            val key = linkKey(n)
            // Back before it was ever called down, so its absence was a
            // re-registration rather than an outage: start the count again.
            missingTicks.remove(key)
            val p = knownLinks[key]
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
            captivePortalEvent(key, n, rec)
            knownLinks[key] = n
        }

        // A link is called down only once it has been missing from two
        // consecutive polls. Android re-registers a network by losing it and
        // offering it again, and the two callbacks can straddle a tick, so the
        // published list is briefly without an interface that never stopped
        // carrying traffic. Reporting on the first absence turned that into a
        // WARN "vanished" and an alert notification — a fabricated outage, and
        // one this app caused itself every time its own capture was switched
        // on. A genuine drop lasts longer than a poll, so it still arrives,
        // one interval later.
        for (key in knownLinks.keys.toList()) {
            if (key in after) continue
            val strikes = (missingTicks[key] ?: 0) + 1
            missingTicks[key] = strikes
            if (strikes < DOWN_CONFIRM_POLLS) continue
            val gone = knownLinks.remove(key) ?: continue
            missingTicks.remove(key)
            // Rejoining the same captive network later is news again.
            portalLinks.remove(key)
            rec.add(EventLevel.WARN, EventKind.NETWORK_DOWN, label(gone), "vanished")
        }
    }

    /**
     * A captive portal is the one link condition that invalidates every other
     * reading on the screen at once: latency, external IP, reverse DNS and the
     * webhook are all answered by the portal rather than by the host they name.
     * It has its own kind rather than a line in [changes] for that reason — it
     * is not another property that moved, it is a reason to distrust the rest.
     *
     * Keyed on what was last *reported*, not on the previous row, and the two
     * directions are deliberately asymmetric.
     *
     * `onAvailable` fires before `onCapabilitiesChanged`, so a re-registering
     * link is published for one rebuild carrying no capabilities at all: no
     * portal flag, and no validation either. Comparing adjacent rows would
     * read the first half of that as a sign-in the user never performed — the
     * same fabrication as the Wi-Fi outages in `NetworkIdentityTest` — and
     * would then have nothing left to compare against when the real sign-in
     * arrived, swallowing it. Requiring [NetworkRow.validated] to call a
     * portal cleared closes both ends: a capability gap does not validate, so
     * it neither reports nor consumes the transition.
     *
     * Like [knownLinks], this is link topology rather than a per-session
     * dedupe, so [reset] does not clear it; a link that genuinely vanishes
     * does, because rejoining the same captive network is news again.
     */
    private fun captivePortalEvent(key: String, cur: NetworkRow, rec: Recorder) {
        val reported = key in portalLinks
        if (cur.captivePortal) {
            if (reported) return
            portalLinks += key
            rec.add(
                EventLevel.WARN,
                EventKind.CAPTIVE_PORTAL,
                label(cur),
                detail(
                    "sign-in required",
                    "traffic on this link is intercepted until the portal is satisfied",
                ),
            )
        } else if (reported && cur.validated) {
            portalLinks -= key
            rec.add(
                EventLevel.INFO,
                EventKind.CAPTIVE_PORTAL,
                label(cur),
                "portal cleared \u2014 the link validates again",
            )
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

        /**
         * Consecutive polls a link must be missing for before it is called
         * down. Two, so that a re-registration straddling one tick is not an
         * outage; the cost is one poll interval of delay on a real one.
         */
        const val DOWN_CONFIRM_POLLS = 2
    }
}
