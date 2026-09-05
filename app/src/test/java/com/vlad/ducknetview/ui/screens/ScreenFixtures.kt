package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.EventLevelFilter
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.usage.DailyUsage
import com.vlad.ducknetview.ui.HostGroup
import com.vlad.ducknetview.ui.QuickFilters
import com.vlad.ducknetview.ui.SearchState
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState

/** Fixed clock so age/timestamp rendering is deterministic. */
const val SCREEN_NOW = 1_760_000_000_000L

fun screenState(
    conns: List<ConnRow> = emptyList(),
    closed: List<ClosedConn> = emptyList(),
    groups: List<HostGroup> = emptyList(),
    apps: List<AppRow> = emptyList(),
    services: List<ServiceRow> = emptyList(),
    events: List<Event> = emptyList(),
    caps: Capabilities = Capabilities.VPN,
    settings: AppSettings = AppSettings(),
    filters: QuickFilters = QuickFilters(),
    search: SearchState = SearchState(),
    snapshot: NetSnapshot = NetSnapshot(atMillis = SCREEN_NOW),
    eventFilter: EventLevelFilter = EventLevelFilter.ALL,
    unackedAlerts: Int = 0,
    usageAccessGranted: Boolean = false,
    vpnAvailable: Boolean = true,
    vpnRunning: Boolean = false,
    matchCount: Int = 0,
    matchedRows: Set<Int> = emptySet(),
    matchCursor: Int = -1,
    usage: List<DailyUsage> = emptyList(),
    usageLoading: Boolean = false,
): UiState = UiState(
    snapshot = snapshot,
    caps = caps,
    settings = settings,
    filters = filters,
    search = search,
    conns = conns,
    closed = closed,
    groups = groups,
    apps = apps,
    services = services,
    events = events,
    eventFilter = eventFilter,
    unackedAlerts = unackedAlerts,
    vpnAvailable = vpnAvailable,
    vpnRunning = vpnRunning,
    usageAccessGranted = usageAccessGranted,
    matchCount = matchCount,
    matchedRows = matchedRows,
    matchCursor = matchCursor,
    usage = usage,
    usageLoading = usageLoading,
)

fun screenConn(
    key: String = "tcp:10.0.0.2:44321-93.184.216.34:443",
    proto: Proto = Proto.TCP,
    localAddr: String = "10.0.0.2",
    localPort: Int = 44321,
    remoteAddr: String = "93.184.216.34",
    remotePort: Int = 443,
    state: ConnState = ConnState.ESTABLISHED,
    uid: Int = 10123,
    appLabel: String = "Browser",
    packageName: String = "com.example.browser",
    service: String = "https",
    scope: Scope = Scope.PUBLIC,
    rxBytes: Long = 4096,
    txBytes: Long = 2048,
    rxBps: Long = 1024,
    txBps: Long = 512,
    rttMillis: Int = 24,
    firstSeen: Long = SCREEN_NOW - 30_000,
    lastSeen: Long = SCREEN_NOW,
    network: String = "wlan0",
    resolvedHost: String? = "example.com",
    isNew: Boolean = false,
    watchlisted: Boolean = false,
    blocked: Boolean = false,
): ConnRow = ConnRow(
    key = key,
    proto = proto,
    localAddr = localAddr,
    localPort = localPort,
    remoteAddr = remoteAddr,
    remotePort = remotePort,
    state = state,
    uid = uid,
    appLabel = appLabel,
    packageName = packageName,
    service = service,
    scope = scope,
    rxBytes = rxBytes,
    txBytes = txBytes,
    rxBps = rxBps,
    txBps = txBps,
    rttMillis = rttMillis,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    network = network,
    resolvedHost = resolvedHost,
    isNew = isNew,
    watchlisted = watchlisted,
    blocked = blocked,
)

fun screenClosed(
    row: ConnRow = screenConn(key = "tcp:10.0.0.2:5000-1.1.1.1:53"),
    closedAt: Long = SCREEN_NOW - 5_000,
    lifetimeMillis: Long = 61_000,
    finalRx: Long = 9000,
    finalTx: Long = 3000,
): ClosedConn = ClosedConn(row, closedAt, lifetimeMillis, finalRx, finalTx)

fun screenGroup(
    host: String = "93.184.216.34",
    display: String = "example.com",
    connCount: Int = 3,
    rxBps: Long = 2048,
    txBps: Long = 1024,
    rxTotal: Long = 40960,
    txTotal: Long = 10240,
    states: Map<String, Int> = mapOf("established" to 2, "new" to 1),
    apps: List<String> = listOf("Browser"),
    scope: Scope = Scope.PUBLIC,
    watchlisted: Boolean = false,
): HostGroup = HostGroup(
    host = host,
    display = display,
    connCount = connCount,
    rxBps = rxBps,
    txBps = txBps,
    rxTotal = rxTotal,
    txTotal = txTotal,
    states = states,
    apps = apps,
    scope = scope,
    watchlisted = watchlisted,
)

fun screenApp(
    uid: Int = 10123,
    packageName: String = "com.example.browser",
    label: String = "Browser",
    isSystem: Boolean = false,
    connCount: Int = 4,
    rxBps: Long = 1024,
    txBps: Long = 256,
    sessionRx: Long = 100_000,
    sessionTx: Long = 20_000,
    todayRx: Long = 5_000_000,
    todayTx: Long = 1_000_000,
    blocked: Boolean = false,
    excludedFromVpn: Boolean = false,
    remoteHosts: Int = 7,
): AppRow = AppRow(
    uid = uid,
    packageName = packageName,
    label = label,
    isSystem = isSystem,
    connCount = connCount,
    rxBps = rxBps,
    txBps = txBps,
    sessionRx = sessionRx,
    sessionTx = sessionTx,
    todayRx = todayRx,
    todayTx = todayTx,
    blocked = blocked,
    excludedFromVpn = excludedFromVpn,
    remoteHosts = remoteHosts,
)

fun screenService(
    proto: Proto = Proto.TCP,
    bindAddr: String = "0.0.0.0",
    port: Int = 8080,
    service: String = "http-alt",
    exposure: Exposure = Exposure.EXPOSED,
    firstSeen: Long = SCREEN_NOW - 600_000,
    lastSeen: Long = SCREEN_NOW,
    isNew: Boolean = false,
    offBaseline: Boolean = false,
    uid: Int = -1,
    appLabel: String = "",
): ServiceRow = ServiceRow(
    proto = proto,
    bindAddr = bindAddr,
    port = port,
    service = service,
    exposure = exposure,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    isNew = isNew,
    offBaseline = offBaseline,
    uid = uid,
    appLabel = appLabel,
)

fun screenEvent(
    id: Long = 1L,
    at: Long = SCREEN_NOW - 10_000,
    level: EventLevel = EventLevel.WARN,
    kind: EventKind = EventKind.WATCHLIST_HIT,
    subject: String = "93.184.216.34",
    detail: String = "Browser connected",
): Event = Event(id = id, at = at, level = level, kind = kind, subject = subject, detail = detail)

/** Records every action a screen raises so tests can assert on them. */
class RecordingActions(initial: AppSettings = AppSettings()) : UiActions {
    val calls = mutableListOf<String>()

    var settings: AppSettings = initial
        private set

    var lastTab: Tab? = null
    var lastInterval: Int? = null
    var lastSearch: String? = null
    var lastSort: Pair<String, String>? = null
    var lastProtoFilter: ProtoFilter? = null
    var lastIpFilter: IpVersionFilter? = null
    var lastStateFilter: StateFilter? = null
    var lastNetworkFilter: String? = null
    var lastUidFilter: Int? = null
    var lastWatchlistToggle: String? = null
    var lastWatchlistAdd: String? = null
    var lastWatchlistRemove: String? = null
    var lastBlock: Pair<Int, Boolean>? = null
    var lastExclude: Pair<Int, Boolean>? = null
    var lastAppInfo: String? = null
    var lastCopy: Pair<String, String>? = null
    var lastEventFilter: EventLevelFilter? = null
    var lastAcceptedService: ServiceRow? = null
    var lastScanFullRange: Boolean? = null
    var startVpnCount = 0
    var stopVpnCount = 0
    var scanCount = 0
    var exportTableCount = 0
    var exportEventsCount = 0
    var exportJsonCount = 0
    var usageAccessCount = 0
    var refreshUsageCount = 0

    private fun rec(name: String) {
        calls.add(name)
    }

    override fun setTab(tab: Tab) { lastTab = tab; rec("setTab") }
    override fun togglePause() = rec("togglePause")
    override fun setInterval(seconds: Int) { lastInterval = seconds; rec("setInterval") }
    override fun refreshNow() = rec("refreshNow")
    override fun toggleRateUnit() = rec("toggleRateUnit")
    override fun toggleThroughputMode() = rec("toggleThroughputMode")
    override fun toggleHideNoise() = rec("toggleHideNoise")
    override fun toggleRevDns() = rec("toggleRevDns")
    override fun toggleGrouping() = rec("toggleGrouping")
    override fun toggleShowClosed() = rec("toggleShowClosed")
    override fun selectNetwork(id: String) = rec("selectNetwork")

    override fun setSearch(query: String) { lastSearch = query; rec("setSearch") }
    override fun toggleSearchMode() = rec("toggleSearchMode")
    override fun clearSearch() = rec("clearSearch")

    override fun setSort(table: String, column: String) { lastSort = table to column; rec("setSort") }
    override fun setProtoFilter(f: ProtoFilter) { lastProtoFilter = f; rec("setProtoFilter") }
    override fun setIpVersionFilter(f: IpVersionFilter) { lastIpFilter = f; rec("setIpVersionFilter") }
    override fun setStateFilter(f: StateFilter) { lastStateFilter = f; rec("setStateFilter") }
    override fun togglePublicOnly() = rec("togglePublicOnly")
    override fun toggleUserAppsOnly() = rec("toggleUserAppsOnly")
    override fun setNetworkFilter(network: String?) { lastNetworkFilter = network; rec("setNetworkFilter") }
    override fun setUidFilter(uid: Int?) { lastUidFilter = uid; rec("setUidFilter") }

    override fun startVpn() { startVpnCount++; rec("startVpn") }
    override fun stopVpn() { stopVpnCount++; rec("stopVpn") }

    override fun scanServices() { scanCount++; rec("scanServices") }
    override fun setScanFullRange(full: Boolean) { lastScanFullRange = full; rec("setScanFullRange") }
    override fun saveBaseline() = rec("saveBaseline")
    override fun clearBaseline() = rec("clearBaseline")
    override fun acceptIntoBaseline(row: ServiceRow) { lastAcceptedService = row; rec("acceptIntoBaseline") }

    override fun toggleWatchlist(remote: String) { lastWatchlistToggle = remote; rec("toggleWatchlist") }
    override fun addWatchlistEntry(entry: String) { lastWatchlistAdd = entry; rec("addWatchlistEntry") }
    override fun removeWatchlistEntry(entry: String) { lastWatchlistRemove = entry; rec("removeWatchlistEntry") }

    override fun blockApp(uid: Int, blocked: Boolean) { lastBlock = uid to blocked; rec("blockApp") }
    override fun excludeApp(uid: Int, excluded: Boolean) { lastExclude = uid to excluded; rec("excludeApp") }
    override fun openAppInfo(packageName: String) { lastAppInfo = packageName; rec("openAppInfo") }

    override fun copyText(label: String, text: String) { lastCopy = label to text; rec("copyText") }
    override fun exportCurrentTable() { exportTableCount++; rec("exportCurrentTable") }
    override fun exportSnapshotJson() { exportJsonCount++; rec("exportSnapshotJson") }
    override fun exportEvents() { exportEventsCount++; rec("exportEvents") }

    override fun setEventFilter(f: EventLevelFilter) { lastEventFilter = f; rec("setEventFilter") }
    override fun ackAlerts() = rec("ackAlerts")
    override fun clearEvents() = rec("clearEvents")

    override fun refreshExternalIp() = rec("refreshExternalIp")
    override fun refreshUsage() { refreshUsageCount++; rec("refreshUsage") }
    override fun updateSettings(block: (AppSettings) -> AppSettings) {
        settings = block(settings)
        rec("updateSettings")
    }
    override fun requestUsageAccess() { usageAccessCount++; rec("requestUsageAccess") }
    override fun requestWifiPermission() = rec("requestWifiPermission")
    override fun dismissStatus() = rec("dismissStatus")

    var openSnapshotCount = 0
    override fun openSnapshot() { openSnapshotCount++ }
    var closeSnapshotCount = 0
    override fun closeSnapshot() { closeSnapshotCount++ }

    var nextMatchCount = 0
    override fun nextMatch() { nextMatchCount++ }
    var prevMatchCount = 0
    override fun prevMatch() { prevMatchCount++ }
}
