package com.vlad.ducknetview.domain.sort

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.ui.HostGroup

/**
 * Comparators keyed by column name, one registry per table, matching the TUI's
 * sort keys.
 *
 * Every comparator ends in a tie-break on a key that does not change between
 * polls (a row key, a uid, a host). Without it two rows with equal rates swap
 * places on every tick and the table looks like it is vibrating.
 */
object Sorters {

    val CONN_COLUMNS = listOf(
        "pid", "proto", "state", "local", "remote", "process", "scope", "age", "rx", "tx", "rtt",
    )
    val APP_COLUMNS = listOf("conns", "rx", "tx", "name", "today")
    val SERVICE_COLUMNS = listOf("proto", "port", "service", "exposure")
    val DOMAIN_COLUMNS = listOf("name", "lookups", "seen", "app")
    val CLOSED_COLUMNS = listOf("closedAt", "lifetime", "rx", "tx")
    val GROUP_COLUMNS = listOf("conns", "host", "bytes")

    fun columnsFor(table: String): List<String> = when (table) {
        "conns" -> CONN_COLUMNS
        "apps" -> APP_COLUMNS
        "services" -> SERVICE_COLUMNS
        "domains" -> DOMAIN_COLUMNS
        "closed" -> CLOSED_COLUMNS
        "groups" -> GROUP_COLUMNS
        else -> emptyList()
    }

    /**
     * Most-routable first, so a scope sort surfaces internet peers ahead of
     * LAN and loopback ones.
     */
    fun scopeRank(s: Scope): Int = when (s) {
        Scope.PUBLIC -> 0
        Scope.PRIVATE -> 1
        Scope.MULTICAST -> 2
        Scope.LOOPBACK -> 3
    }

    fun conns(col: String, desc: Boolean, mode: ThroughputMode, now: Long): Comparator<ConnRow> {
        val tie = Comparator<ConnRow> { a, b -> a.key.compareTo(b.key) }
        val primary: Comparator<ConnRow>? = when (col) {
            "pid" -> Comparator { a, b -> a.uid.compareTo(b.uid) }
            "proto" -> Comparator { a, b -> a.proto.name.compareTo(b.proto.name) }
            "state" -> Comparator { a, b -> a.state.name.compareTo(b.state.name) }
            "local" -> Comparator { a, b -> a.localPort.compareTo(b.localPort) }
            "remote" -> Comparator { a, b -> text(a.remote, b.remote) }
            "process" -> Comparator { a, b -> text(a.appLabel, b.appLabel) }
            "scope" -> Comparator { a, b -> scopeRank(a.scope).compareTo(scopeRank(b.scope)) }
            "age" -> Comparator { a, b -> a.ageMillis(now).compareTo(b.ageMillis(now)) }
            "rx" -> Comparator { a, b -> connBytes(a, mode, rx = true).compareTo(connBytes(b, mode, rx = true)) }
            "tx" -> Comparator { a, b -> connBytes(a, mode, rx = false).compareTo(connBytes(b, mode, rx = false)) }
            "rtt" -> Comparator { a, b -> rtt(a.rttMillis).compareTo(rtt(b.rttMillis)) }
            else -> null
        }
        return ordered(primary, desc, tie)
    }

    fun domains(col: String, desc: Boolean): Comparator<DomainRow> {
        val tie = Comparator<DomainRow> { a, b -> a.key.compareTo(b.key) }
        val primary: Comparator<DomainRow>? = when (col) {
            "name" -> Comparator { a, b -> text(a.name, b.name) }
            "lookups" -> Comparator { a, b -> a.lookups.compareTo(b.lookups) }
            "seen" -> Comparator { a, b -> a.lastSeen.compareTo(b.lastSeen) }
            "app" -> Comparator { a, b -> text(a.appLabel, b.appLabel) }
            else -> null
        }
        return ordered(primary, desc, tie)
    }

    fun apps(col: String, desc: Boolean, mode: ThroughputMode): Comparator<AppRow> {
        val tie = Comparator<AppRow> { a, b -> a.uid.compareTo(b.uid) }
        val primary: Comparator<AppRow>? = when (col) {
            "conns" -> Comparator { a, b -> a.connCount.compareTo(b.connCount) }
            "rx" -> Comparator { a, b -> appBytes(a, mode, rx = true).compareTo(appBytes(b, mode, rx = true)) }
            "tx" -> Comparator { a, b -> appBytes(a, mode, rx = false).compareTo(appBytes(b, mode, rx = false)) }
            "name" -> Comparator { a, b -> text(a.label, b.label) }
            "today" -> Comparator { a, b -> (a.todayRx + a.todayTx).compareTo(b.todayRx + b.todayTx) }
            else -> null
        }
        return ordered(primary, desc, tie)
    }

    fun services(col: String, desc: Boolean): Comparator<ServiceRow> {
        val tie = Comparator<ServiceRow> { a, b -> a.key.compareTo(b.key) }
        val primary: Comparator<ServiceRow>? = when (col) {
            "proto" -> Comparator { a, b -> a.proto.name.compareTo(b.proto.name) }
            "port" -> Comparator { a, b -> a.port.compareTo(b.port) }
            "service" -> Comparator { a, b -> text(a.service, b.service) }
            "exposure" -> Comparator { a, b -> a.exposure.ordinal.compareTo(b.exposure.ordinal) }
            else -> null
        }
        return ordered(primary, desc, tie)
    }

    /**
     * Closed rows carry final counters rather than rates, so the throughput
     * mode does not apply to them.
     */
    fun closed(col: String, desc: Boolean): Comparator<ClosedConn> {
        val tie = Comparator<ClosedConn> { a, b -> a.row.key.compareTo(b.row.key) }
        val primary: Comparator<ClosedConn>? = when (col) {
            "closedAt" -> Comparator { a, b -> a.closedAt.compareTo(b.closedAt) }
            "lifetime" -> Comparator { a, b -> a.lifetimeMillis.compareTo(b.lifetimeMillis) }
            "rx" -> Comparator { a, b -> a.finalRx.compareTo(b.finalRx) }
            "tx" -> Comparator { a, b -> a.finalTx.compareTo(b.finalTx) }
            else -> null
        }
        return ordered(primary, desc, tie)
    }

    fun groups(col: String, desc: Boolean, mode: ThroughputMode): Comparator<HostGroup> {
        val tie = Comparator<HostGroup> { a, b -> a.host.compareTo(b.host) }
        val primary: Comparator<HostGroup>? = when (col) {
            "conns" -> Comparator { a, b -> a.connCount.compareTo(b.connCount) }
            "host" -> Comparator { a, b -> text(a.host, b.host) }
            "bytes" -> Comparator { a, b -> groupBytes(a, mode).compareTo(groupBytes(b, mode)) }
            else -> null
        }
        return ordered(primary, desc, tie)
    }

    private fun connBytes(r: ConnRow, mode: ThroughputMode, rx: Boolean): Long = when {
        mode == ThroughputMode.TOTAL && rx -> r.rxBytes
        mode == ThroughputMode.TOTAL -> r.txBytes
        rx -> r.rxBps
        else -> r.txBps
    }

    private fun appBytes(r: AppRow, mode: ThroughputMode, rx: Boolean): Long = when {
        mode == ThroughputMode.TOTAL && rx -> r.sessionRx
        mode == ThroughputMode.TOTAL -> r.sessionTx
        rx -> r.rxBps
        else -> r.txBps
    }

    private fun groupBytes(g: HostGroup, mode: ThroughputMode): Long =
        if (mode == ThroughputMode.TOTAL) g.rxTotal + g.txTotal else g.rxBps + g.txBps

    /** An unmeasured RTT sorts after every measured one instead of first. */
    private fun rtt(ms: Int): Int = if (ms < 0) Int.MAX_VALUE else ms

    private fun text(a: String, b: String): Int {
        val c = a.compareTo(b, ignoreCase = true)
        return if (c != 0) c else a.compareTo(b)
    }

    /**
     * The direction flips the sort key only; the tie-break stays ascending so
     * equal rows keep the same relative order in both directions.
     */
    private fun <T> ordered(primary: Comparator<T>?, desc: Boolean, tie: Comparator<T>): Comparator<T> {
        if (primary == null) return tie
        return (if (desc) primary.reversed() else primary).then(tie)
    }
}
