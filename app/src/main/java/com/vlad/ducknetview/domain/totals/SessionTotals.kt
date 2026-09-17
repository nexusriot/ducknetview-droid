package com.vlad.ducknetview.domain.totals

import com.vlad.ducknetview.domain.model.Talker

/**
 * Rates answer "what is happening now"; totals answer "what happened while I
 * was watching". A transfer that finished thirty seconds ago has no rate at
 * all, but it is usually the thing the app was opened to find.
 */
class SessionTotals(
    private val max: Int = MAX_ENTRIES,
    private val evictTo: Int = EVICT_TO,
) {

    private class Entry(val key: String, var label: String, var rx: Long, var tx: Long) {
        val total: Long get() = rx + tx
    }

    private val apps = LinkedHashMap<Int, Entry>()
    private val hosts = LinkedHashMap<String, Entry>()

    var sessionRx: Long = 0L
        private set

    var sessionTx: Long = 0L
        private set

    val sessionTotal: Long get() = sessionRx + sessionTx

    val appCount: Int get() = apps.size

    val hostCount: Int get() = hosts.size

    /**
     * Books one poll's device-wide delta against the session grand total.
     *
     * The grand total is deliberately *not* fed from [add] or [addHost]. Those
     * describe the flows the capture engine can see, which is nothing at all in
     * API mode — the always-on default — so a grand total built from them read
     * a flat 0 B for the mode most users are in. The device counters are the
     * same source as the "Now" and "Session peak" rows this total sits beside,
     * so integrating them is both available in every mode and consistent with
     * its own card.
     *
     * Bytes that moved while the app was paused or frozen are included: the
     * counter is cumulative, and silently dropping the gap would under-report
     * the session rather than describe it.
     */
    fun addDevice(rx: Long, tx: Long) {
        if (rx <= 0L && tx <= 0L) return
        sessionRx += rx
        sessionTx += tx
    }

    /**
     * Books one poll's delta against an app. Like [addHost] this leaves the
     * session grand total alone — see [addDevice] for where that comes from and
     * why booking it here would both double-count capture mode and leave API
     * mode empty.
     */
    fun add(uid: Int, label: String, rx: Long, tx: Long) {
        if (rx <= 0L && tx <= 0L) return
        val e = apps.getOrPut(uid) { Entry(uid.toString(), label, 0L, 0L) }
        if (e.label.isEmpty()) e.label = label
        e.rx += rx
        e.tx += tx
        evict(apps)
    }

    fun addHost(host: String, rx: Long, tx: Long) {
        if (host.isEmpty()) return
        if (rx <= 0L && tx <= 0L) return
        val e = hosts.getOrPut(host) { Entry(host, host, 0L, 0L) }
        e.rx += rx
        e.tx += tx
        evict(hosts)
    }

    /** Books one connection's delta against its app and its host. */
    fun addConn(uid: Int, label: String, host: String, rx: Long, tx: Long) {
        add(uid, label, rx, tx)
        addHost(host, rx, tx)
    }

    fun topApps(n: Int): List<Talker> = top(apps.values, n)

    fun topHosts(n: Int): List<Talker> = top(hosts.values, n)

    fun app(uid: Int): Talker? = apps[uid]?.let(::toTalker)

    fun host(host: String): Talker? = hosts[host]?.let(::toTalker)

    fun clear() {
        apps.clear()
        hosts.clear()
        sessionRx = 0L
        sessionTx = 0L
    }

    private fun top(all: Collection<Entry>, n: Int): List<Talker> {
        if (n <= 0) return emptyList()
        return all.sortedWith(
            compareByDescending<Entry> { it.total }.thenBy { it.label }.thenBy { it.key },
        ).take(n).map(::toTalker)
    }

    private fun toTalker(e: Entry) = Talker(key = e.key, label = e.label, rx = e.rx, tx = e.tx)

    /**
     * Drops the quietest entries first, so the top talkers — the reason the
     * tables exist — always survive. Cutting back further than the cap keeps
     * eviction amortised instead of running on every poll.
     */
    private fun <K> evict(map: LinkedHashMap<K, Entry>) {
        if (map.size <= max) return
        val keep = map.entries
            .sortedWith(
                compareByDescending<Map.Entry<K, Entry>> { it.value.total }
                    .thenBy { it.value.key },
            )
            .take(evictTo)
            .map { it.key }
            .toHashSet()
        map.keys.retainAll(keep)
    }

    companion object {
        /** A day of browsing touches thousands of hosts; the map cannot grow freely. */
        const val MAX_ENTRIES = 2048
        const val EVICT_TO = 1536
    }
}
