package com.vlad.ducknetview.domain.filter

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.net.IpScope
import com.vlad.ducknetview.ui.QuickFilters

/** The session-only quick filter chips, applied before search and sort. */
object Filters {

    fun conns(rows: List<ConnRow>, f: QuickFilters, userAppUids: Set<Int>): List<ConnRow> {
        if (!f.any) return rows
        return rows.filter { connMatches(it, f, userAppUids) }
    }

    /**
     * The closed-connection history is the same rows after the socket is gone,
     * so it takes the same chips. It used to go on screen straight off the
     * snapshot, which left every chip, the search box and the sort header inert
     * over it while the counter above reported matches in the live table.
     *
     * The state chips are skipped deliberately: every row here is closed, so
     * "established" or "active" over this table could only ever empty it.
     */
    fun closed(rows: List<ClosedConn>, f: QuickFilters, userAppUids: Set<Int>): List<ClosedConn> {
        if (!f.any) return rows
        return rows.filter { connMatches(it.row, f, userAppUids, applyState = false) }
    }

    fun connMatches(
        r: ConnRow,
        f: QuickFilters,
        userAppUids: Set<Int>,
        applyState: Boolean = true,
    ): Boolean =
        protoOk(r.proto, f.proto) &&
            versionOk(r.remoteAddr, f.ipVersion) &&
            (!applyState || stateOk(r.state, f.state)) &&
            (!f.publicOnly || r.scope == Scope.PUBLIC) &&
            (!f.userAppsOnly || isUserApp(r.uid, userAppUids)) &&
            (f.network == null || r.network == f.network) &&
            (f.uid == null || r.uid == f.uid)

    fun apps(rows: List<AppRow>, f: QuickFilters, userAppUids: Set<Int>): List<AppRow> {
        if (!f.any) return rows
        return rows.filter { r ->
            (!f.userAppsOnly || (if (userAppUids.isEmpty()) !r.isSystem else r.uid in userAppUids)) &&
                (f.uid == null || r.uid == f.uid)
        }
    }

    /**
     * A name has no protocol, address family or connection state, so only the
     * owner filters apply. Chips that cannot mean anything here are hidden by
     * the screen rather than silently ignored.
     */
    fun domains(rows: List<DomainRow>, f: QuickFilters, userAppUids: Set<Int>): List<DomainRow> {
        if (!f.any) return rows
        return rows.filter { r ->
            (!f.userAppsOnly || isUserApp(r.uid, userAppUids)) &&
                (f.uid == null || r.uid == f.uid)
        }
    }

    fun services(rows: List<ServiceRow>, f: QuickFilters): List<ServiceRow> {
        if (!f.any) return rows
        return rows.filter { r ->
            protoOk(r.proto, f.proto) &&
                versionOk(r.bindAddr, f.ipVersion) &&
                (!f.publicOnly || r.exposure == Exposure.EXPOSED) &&
                (f.uid == null || r.uid == f.uid)
        }
    }

    private fun protoOk(p: Proto, f: ProtoFilter): Boolean = when (f) {
        ProtoFilter.ALL -> true
        ProtoFilter.TCP -> p == Proto.TCP
        ProtoFilter.UDP -> p == Proto.UDP
    }

    /** A wildcard IPv6 bind (`::`) counts as v6 only; `0.0.0.0` as v4 only. */
    private fun versionOk(addr: String, f: IpVersionFilter): Boolean = when (f) {
        IpVersionFilter.ALL -> true
        IpVersionFilter.V6 -> IpScope.isIpv6(addr)
        IpVersionFilter.V4 -> IpScope.isIpv4(addr)
    }

    /** ACTIVE means "still carrying traffic", i.e. anything not closing down. */
    private fun stateOk(s: ConnState, f: StateFilter): Boolean = when (f) {
        StateFilter.ALL -> true
        StateFilter.ESTABLISHED -> s == ConnState.ESTABLISHED
        StateFilter.ACTIVE -> s != ConnState.CLOSED && s != ConnState.CLOSING
    }

    /**
     * An empty uid set means the caller could not enumerate installed apps.
     * Filtering everything out then would read as "you have no traffic", so
     * the filter stands down instead.
     */
    private fun isUserApp(uid: Int, userAppUids: Set<Int>): Boolean =
        userAppUids.isEmpty() || uid in userAppUids
}
