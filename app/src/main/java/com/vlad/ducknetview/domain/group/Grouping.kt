package com.vlad.ducknetview.domain.group

import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.sort.Sorters
import com.vlad.ducknetview.ui.HostGroup

/**
 * The grouped-by-remote-host view: forty connections to one CDN collapse into
 * one row that says how much traffic that CDN is actually worth.
 */
object Grouping {

    /**
     * With [revDns] on, rows sharing a resolved name group together even when
     * they resolve from different addresses; [HostGroup.host] is then the first
     * address seen for that name, since the watchlist acts on addresses.
     */
    fun of(rows: List<ConnRow>, revDns: Boolean = false): List<HostGroup> {
        val buckets = LinkedHashMap<String, Bucket>()

        for (r in rows) {
            val name = r.resolvedHost?.takeIf { revDns && it.isNotEmpty() }
            val key = name ?: r.remoteAddr
            val b = buckets.getOrPut(key) { Bucket(host = r.remoteAddr, display = name ?: r.remoteAddr) }
            b.add(r)
        }

        return buckets.values.map { it.build() }
    }

    private class Bucket(val host: String, val display: String) {
        var count = 0
        var rxBps = 0L
        var txBps = 0L
        var rxTotal = 0L
        var txTotal = 0L
        var watchlisted = false
        var scope: Scope? = null
        val states = LinkedHashMap<String, Int>()
        val apps = LinkedHashSet<String>()

        fun add(r: ConnRow) {
            count++
            rxBps += r.rxBps
            txBps += r.txBps
            rxTotal += r.rxBytes
            txTotal += r.txBytes
            if (r.watchlisted) watchlisted = true
            val s = r.state.toString()
            states[s] = (states[s] ?: 0) + 1
            if (r.appLabel.isNotEmpty()) apps += r.appLabel
            // The most routable scope in the group wins: one public flow to a
            // host is the fact worth colouring the row by.
            val cur = scope
            if (cur == null || Sorters.scopeRank(r.scope) < Sorters.scopeRank(cur)) scope = r.scope
        }

        fun build() = HostGroup(
            host = host,
            display = display,
            connCount = count,
            rxBps = rxBps,
            txBps = txBps,
            rxTotal = rxTotal,
            txTotal = txTotal,
            states = LinkedHashMap(states),
            apps = apps.sorted(),
            scope = scope ?: Scope.PUBLIC,
            watchlisted = watchlisted,
        )
    }
}
