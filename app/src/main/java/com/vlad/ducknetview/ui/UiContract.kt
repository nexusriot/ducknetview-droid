package com.vlad.ducknetview.ui

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventLevelFilter
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.usage.DailyUsage

/** The seven destinations, mirroring the TUI's seven tabs. */
enum class Tab(val route: String, val title: String) {
    OVERVIEW("overview", "Overview"),
    INTERFACES("interfaces", "Links"),
    SERVICES("services", "Services"),
    APPS("apps", "Apps"),
    CONNECTIONS("connections", "Conns"),
    ROUTES("routes", "Routes"),
    EVENTS("events", "Events"),
    ;

    companion object {
        fun fromRoute(r: String?): Tab = entries.firstOrNull { it.route == r } ?: OVERVIEW
    }
}

/** Session-only quick filters — not persisted, matching the TUI. */
data class QuickFilters(
    val proto: ProtoFilter = ProtoFilter.ALL,
    val ipVersion: IpVersionFilter = IpVersionFilter.ALL,
    val state: StateFilter = StateFilter.ALL,
    val publicOnly: Boolean = false,
    val userAppsOnly: Boolean = false,
    val network: String? = null,
    val uid: Int? = null,
) {
    val any: Boolean
        get() = proto != ProtoFilter.ALL || ipVersion != IpVersionFilter.ALL ||
            state != StateFilter.ALL || publicOnly || userAppsOnly ||
            network != null || uid != null
}

data class SearchState(
    val query: String = "",
    val mode: SearchMode = SearchMode.FILTER,
    val error: String? = null,
) {
    val active: Boolean get() = query.isNotEmpty()
}

/**
 * Everything the UI renders. Screens are pure functions of this plus
 * [UiActions]; nothing in ui/screens touches a ViewModel or a repository.
 */
data class UiState(
    val snapshot: NetSnapshot = NetSnapshot(),
    val caps: Capabilities = Capabilities.API,
    val settings: AppSettings = AppSettings(),
    val filters: QuickFilters = QuickFilters(),
    val search: SearchState = SearchState(),
    val conns: List<ConnRow> = emptyList(),
    val closed: List<ClosedConn> = emptyList(),
    val groups: List<HostGroup> = emptyList(),
    val apps: List<AppRow> = emptyList(),
    val services: List<ServiceRow> = emptyList(),
    val events: List<Event> = emptyList(),
    val eventFilter: EventLevelFilter = EventLevelFilter.ALL,
    val unackedAlerts: Int = 0,
    val vpnAvailable: Boolean = true,
    val vpnRunning: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val wifiPermissionGranted: Boolean = false,
    val status: String? = null,
    val matchCount: Int = 0,
    /**
     * Indices into the visible tab's primary list that matched the query. Only
     * meaningful in HIGHLIGHT mode; FILTER mode has already dropped the rest.
     */
    val matchedRows: Set<Int> = emptySet(),
    /** Which match `n`/`N` navigation has focused, or -1 for none. */
    val matchCursor: Int = -1,
    val usage: List<DailyUsage> = emptyList(),
    val usageLoading: Boolean = false,
    val metricsUrl: String? = null,
    val metricsError: String? = null,
    /**
     * False for the seed value the state flow starts with, true once real
     * settings have been read. First-run UI keys off this so it does not flash
     * on the frames before DataStore answers.
     */
    val settingsLoaded: Boolean = false,
)

/** A grouped-by-remote-host row: the TUI's `g` view. */
data class HostGroup(
    val host: String,
    val display: String,
    val connCount: Int,
    val rxBps: Long,
    val txBps: Long,
    val rxTotal: Long,
    val txTotal: Long,
    val states: Map<String, Int>,
    val apps: List<String>,
    val scope: com.vlad.ducknetview.domain.model.Scope,
    val watchlisted: Boolean,
)

/** Every user action a screen can raise. Implemented once, by the ViewModel. */
interface UiActions {
    fun setTab(tab: Tab)
    fun togglePause()
    fun setInterval(seconds: Int)
    fun refreshNow()
    fun toggleRateUnit()
    fun toggleThroughputMode()
    fun toggleHideNoise()
    fun toggleRevDns()
    fun toggleGrouping()
    fun toggleShowClosed()
    fun selectNetwork(id: String)

    fun setSearch(query: String)
    fun toggleSearchMode()
    fun clearSearch()

    /** The TUI's `n` / `N`: walk the cursor between highlighted matches. */
    fun nextMatch()
    fun prevMatch()

    fun setSort(table: String, column: String)
    fun setProtoFilter(f: ProtoFilter)
    fun setIpVersionFilter(f: IpVersionFilter)
    fun setStateFilter(f: StateFilter)
    fun togglePublicOnly()
    fun toggleUserAppsOnly()
    fun setNetworkFilter(network: String?)
    fun setUidFilter(uid: Int?)

    fun startVpn()
    fun stopVpn()

    fun scanServices()
    fun setScanFullRange(full: Boolean)
    fun saveBaseline()
    fun clearBaseline()
    fun acceptIntoBaseline(row: ServiceRow)

    fun toggleWatchlist(remote: String)
    fun addWatchlistEntry(entry: String)
    fun removeWatchlistEntry(entry: String)

    fun blockApp(uid: Int, blocked: Boolean)
    fun excludeApp(uid: Int, excluded: Boolean)
    fun openAppInfo(packageName: String)

    fun copyText(label: String, text: String)
    fun exportCurrentTable()
    fun exportSnapshotJson()
    fun exportEvents()

    fun setEventFilter(f: EventLevelFilter)
    fun ackAlerts()
    fun clearEvents()

    fun refreshExternalIp()

    /** Open a ducknetview --json snapshot and browse it frozen. */
    fun openSnapshot()

    /** Leave snapshot mode and go back to the live host. */
    fun closeSnapshot()

    fun refreshUsage()
    fun updateSettings(block: (AppSettings) -> AppSettings)
    fun requestUsageAccess()
    fun requestWifiPermission()
    fun dismissStatus()
}
