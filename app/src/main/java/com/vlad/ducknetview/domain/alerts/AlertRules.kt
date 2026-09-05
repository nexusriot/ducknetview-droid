package com.vlad.ducknetview.domain.alerts

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.rates.Units

/**
 * Thresholds turn the app from something you watch into something that tells
 * you. All of them are opt-in — a zero threshold disables its rule — because
 * the right number is entirely device- and user-specific.
 */
class AlertRules(
    private val refireMillis: Long = REFIRE_MILLIS,
    private val hostsPerApp: Int = HOSTS_PER_APP,
) {

    private val firedAt = LinkedHashMap<String, Long>()
    private val growth = LinkedHashMap<Int, Int>()
    private var prevConnCounts = LinkedHashMap<Int, Int>()
    private val knownHosts = LinkedHashMap<Int, LinkedHashSet<String>>()

    fun evaluate(cur: NetSnapshot, s: AppSettings, nowMillis: Long): List<Event> {
        val out = ArrayList<Event>()

        if (s.alertAppBps > 0) appBps(cur, s.alertAppBps, nowMillis, out)
        if (s.alertConnBps > 0) connBps(cur, s.alertConnBps, nowMillis, out)
        if (s.alertRttMs > 0) rtt(cur, s.alertRttMs, nowMillis, out)
        if (s.alertConnGrowthPolls > 0) connGrowth(cur, s.alertConnGrowthPolls, nowMillis, out)
        if (s.alertFanoutHosts > 0) fanout(cur, s.alertFanoutHosts, nowMillis, out)

        // Pruning runs unconditionally. Hanging it off one rule's path means a
        // device with only the other rules enabled never prunes at all.
        pruneFired(nowMillis)
        // Uids come from both lists: fanout and growth track uids seen on
        // connections, which in API mode (or before the catalog resolves) are
        // not represented in cur.apps at all. Pruning on apps alone wiped the
        // host memory every tick and made the scan detector re-fire forever.
        val present = HashSet<Int>(cur.apps.size + cur.conns.size)
        cur.apps.mapTo(present) { it.uid }
        cur.conns.mapTo(present) { it.uid }
        pruneApps(present)
        return out
    }

    fun reset() {
        firedAt.clear()
        growth.clear()
        prevConnCounts = LinkedHashMap()
        knownHosts.clear()
    }

    /** Visible for tests: how many suppression entries are being held. */
    val firedCount: Int get() = firedAt.size

    private fun appBps(cur: NetSnapshot, threshold: Long, now: Long, out: MutableList<Event>) {
        for (a in cur.apps) {
            val total = a.rxBps + a.txBps
            if (total < threshold) continue
            if (!shouldFire("app:${a.uid}", now)) continue
            out += Event(
                at = now,
                level = EventLevel.ALERT,
                kind = EventKind.THRESHOLD_APP_BPS,
                subject = subject(a),
                detail = "${Units.rate(total, RateUnit.BYTES)} over threshold " +
                    Units.rate(threshold, RateUnit.BYTES),
            )
        }
    }

    private fun connBps(cur: NetSnapshot, threshold: Long, now: Long, out: MutableList<Event>) {
        for (c in cur.conns) {
            val total = c.rxBps + c.txBps
            if (total < threshold) continue
            if (!shouldFire("conn:${c.key}", now)) continue
            out += Event(
                at = now,
                level = EventLevel.ALERT,
                kind = EventKind.THRESHOLD_CONN_BPS,
                subject = "${c.local} → ${c.remoteDisplay(revDns = true)}",
                detail = joinNonEmpty(
                    "${Units.rate(total, RateUnit.BYTES)} over threshold " +
                        Units.rate(threshold, RateUnit.BYTES),
                    c.appLabel,
                ),
            )
        }
    }

    private fun rtt(cur: NetSnapshot, thresholdMs: Int, now: Long, out: MutableList<Event>) {
        for (c in cur.conns) {
            if (c.rttMillis <= 0 || c.rttMillis < thresholdMs) continue
            if (!shouldFire("rtt:${c.key}", now)) continue
            out += Event(
                at = now,
                level = EventLevel.WARN,
                kind = EventKind.THRESHOLD_RTT,
                subject = "${c.local} → ${c.remoteDisplay(revDns = true)}",
                detail = joinNonEmpty(
                    "RTT ${Units.millis(c.rttMillis)} over threshold ${thresholdMs} ms",
                    c.appLabel,
                ),
            )
        }
    }

    /**
     * A connection count that only ever goes up. One poll of growth is normal;
     * a dozen in a row is an app that never closes anything.
     */
    private fun connGrowth(cur: NetSnapshot, polls: Int, now: Long, out: MutableList<Event>) {
        val counts = LinkedHashMap<Int, Int>()
        for (a in cur.apps) {
            counts[a.uid] = a.connCount
            val prev = prevConnCounts[a.uid]
            if (prev != null) {
                when {
                    a.connCount > prev -> growth[a.uid] = (growth[a.uid] ?: 0) + 1
                    a.connCount < prev -> growth[a.uid] = 0
                }
            }
        }

        for (a in cur.apps) {
            val runLength = growth[a.uid] ?: 0
            if (runLength < polls) continue
            if (shouldFire("leak:${a.uid}", now)) {
                out += Event(
                    at = now,
                    level = EventLevel.WARN,
                    kind = EventKind.CONN_GROWTH,
                    subject = subject(a),
                    detail = "connections grew for $runLength consecutive polls, " +
                        "now ${a.connCount} — possible leak",
                )
            }
            // Reset whether or not it fired, so the next report needs a fresh
            // run of growth rather than arriving one poll after the cooldown.
            growth[a.uid] = 0
        }

        prevConnCounts = counts
    }

    /**
     * One app reaching many new hosts at once is what a scan (or a crawler, or
     * a peer-to-peer client) looks like from the inside.
     */
    private fun fanout(cur: NetSnapshot, threshold: Int, now: Long, out: MutableList<Event>) {
        val fresh = LinkedHashMap<Int, MutableSet<String>>()
        for (c in cur.conns) {
            val remote = c.remoteAddr
            if (remote.isEmpty()) continue
            val seen = knownHosts.getOrPut(c.uid) { LinkedHashSet() }
            if (remote in seen) continue
            fresh.getOrPut(c.uid) { LinkedHashSet() } += remote
        }

        for ((uid, hosts) in fresh) {
            val seen = knownHosts.getOrPut(uid) { LinkedHashSet() }
            for (h in hosts) {
                seen += h
                while (seen.size > hostsPerApp) {
                    val it = seen.iterator()
                    it.next()
                    it.remove()
                }
            }
            if (hosts.size < threshold) continue
            if (!shouldFire("fanout:$uid", now)) continue
            out += Event(
                at = now,
                level = EventLevel.ALERT,
                kind = EventKind.FANOUT,
                subject = subjectFor(cur, uid),
                detail = "${hosts.size} new remote hosts in one poll, threshold $threshold",
            )
        }
    }

    private fun shouldFire(key: String, now: Long): Boolean {
        val last = firedAt[key]
        if (last != null && now - last < refireMillis) return false
        firedAt[key] = now
        return true
    }

    private fun pruneFired(now: Long) {
        val cutoff = 2 * refireMillis
        val it = firedAt.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value > cutoff) it.remove()
        }
    }

    private fun pruneApps(present: Set<Int>) {
        growth.keys.retainAll(present)
        prevConnCounts.keys.retainAll(present)
        knownHosts.keys.retainAll(present)
    }

    private fun subject(a: AppRow): String =
        if (a.label.isNotEmpty()) "${a.label} (uid ${a.uid})" else "uid ${a.uid}"

    private fun subjectFor(cur: NetSnapshot, uid: Int): String {
        val app = cur.apps.firstOrNull { it.uid == uid }
        if (app != null) return subject(app)
        val label = cur.conns.firstOrNull { it.uid == uid && it.appLabel.isNotEmpty() }?.appLabel
        return if (label != null) "$label (uid $uid)" else "uid $uid"
    }

    private fun joinNonEmpty(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        /**
         * How long one subject stays quiet after firing. A breach usually
         * spans many polls, and one event per poll would bury everything else.
         */
        const val REFIRE_MILLIS = 60_000L

        /** Remote hosts remembered per app for the fan-out rule. */
        const val HOSTS_PER_APP = 4096
    }
}
