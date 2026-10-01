package com.vlad.ducknetview.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vlad.ducknetview.DuckApp
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.export.Csv
import com.vlad.ducknetview.domain.export.SnapshotJson
import com.vlad.ducknetview.domain.filter.Filters
import com.vlad.ducknetview.domain.group.Grouping
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.EventLevelFilter
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.search.Search
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.domain.search.SearchResult
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.sort.Sorters
import com.vlad.ducknetview.domain.usage.DailyUsage
import com.vlad.ducknetview.engine.vpn.VpnBridge
import com.vlad.ducknetview.service.MetricsServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app), UiActions {

    private val deps = (app as DuckApp).deps

    private val filtersFlow = MutableStateFlow(QuickFilters())
    private val searchFlow = MutableStateFlow(SearchState())
    private val eventFilterFlow = MutableStateFlow(EventLevelFilter.ALL)
    private val statusFlow = MutableStateFlow<String?>(null)
    private val tabFlow = MutableStateFlow(Tab.OVERVIEW)
    private val frozenFlow = MutableStateFlow<FrozenSnapshot?>(null)
    private val usageFlow = MutableStateFlow<List<DailyUsage>>(emptyList())
    private val usageLoadingFlow = MutableStateFlow(false)
    private val matchCursorFlow = MutableStateFlow(-1)
    private val permissionEpochFlow = MutableStateFlow(0)

    /** A snapshot loaded from a file, browsed instead of the live host. */
    data class FrozenSnapshot(val label: String, val snapshot: NetSnapshot)

    private data class Misc(
        val filters: QuickFilters,
        val search: SearchState,
        val eventFilter: EventLevelFilter,
        val status: String?,
        val frozen: FrozenSnapshot?,
        val usage: List<DailyUsage>,
        val usageLoading: Boolean,
        val domains: List<DomainRow> = emptyList(),
        val metricsRunning: Boolean = false,
        val metricsError: String? = null,
        val matchCursor: Int = -1,
        val permissionEpoch: Int = 0,
    )

    private val miscFlow = combine(
        filtersFlow, searchFlow, eventFilterFlow, statusFlow, frozenFlow,
    ) { f, s, ef, st, fr ->
        Misc(f, s, ef, st, fr, emptyList(), false)
    }.combine(usageFlow) { m, u -> m.copy(usage = u) }
        .combine(usageLoadingFlow) { m, l -> m.copy(usageLoading = l) }
        .combine(deps.metricsServer.running) { m, r -> m.copy(metricsRunning = r) }
        .combine(deps.metricsServer.lastError) { m, e -> m.copy(metricsError = e) }
        .combine(matchCursorFlow) { m, c -> m.copy(matchCursor = c) }
        .combine(permissionEpochFlow) { m, e -> m.copy(permissionEpoch = e) }
        .combine(deps.domainRepo.recent) { m, d -> m.copy(domains = d) }

    val tab: StateFlow<Tab> = tabFlow

    val state: StateFlow<UiState> = combine(
        deps.engine.snapshot,
        deps.settings.settings,
        deps.eventRepo.recent,
        miscFlow,
        VpnBridge.running,
    ) { liveSnap, settings, events, misc, vpnRunning ->
        val filters = misc.filters
        val search = misc.search
        val eventFilter = misc.eventFilter
        val status = misc.status

        // In snapshot mode the file replaces the live host entirely, and the
        // engine's ticks are ignored rather than racing the frozen data.
        val frozen = misc.frozen
        val snap = frozen?.snapshot?.copy(frozen = true, frozenLabel = frozen.label) ?: liveSnap

        val caps = when {
            frozen != null -> Capabilities.of(snap.engine)
            else -> Capabilities.of(if (vpnRunning) EngineMode.VPN else EngineMode.API)
        }
        val compiled = Search.compile(search.query)
        val matcher = compiled.matcher
        val userUids = deps.catalog.userAppUids()

        val connsFiltered = Filters.conns(snap.conns, filters, userUids)
        val connsSearched = Search.apply(
            connsFiltered, matcher, settings.searchMode,
        ) { c ->
            listOf(
                c.local, c.remote, c.resolvedHost.orEmpty(), c.appLabel,
                c.service, c.state.toString(), c.proto.toString(),
            )
        }
        val conns = connsSearched.items.sortedWith(
            Sorters.conns(settings.connsSortCol, settings.connsSortDesc, settings.throughputMode, snap.atMillis)
        )

        val appsFiltered = Filters.apps(snap.apps, filters, userUids)
        val appsSearched = Search.apply(appsFiltered, matcher, settings.searchMode) { a ->
            listOf(a.label, a.packageName, a.uid.toString())
        }
        val apps = appsSearched.items.sortedWith(
            Sorters.apps(settings.appsSortCol, settings.appsSortDesc, settings.throughputMode)
        )

        // The stored row knows a uid; the label for it belongs to the catalog,
        // which can change under a row (an app updating its name) and so is
        // resolved on read rather than frozen into the database.
        val watchlist = watchlistOf(settings.watchlist)
        val domainsLabelled = misc.domains.map { d ->
            val app = deps.catalog.row(d.uid)
            d.copy(
                appLabel = app.label,
                packageName = app.packageName,
                watchlisted = !watchlist.isEmpty &&
                    (watchlist.matches(d.name, d.name) ||
                        d.addresses.any { watchlist.matches(it, d.name) }),
            )
        }
        val domainsFiltered = Filters.domains(domainsLabelled, filters, userUids)
        val domainsSearched = Search.apply(domainsFiltered, matcher, settings.searchMode) { d ->
            listOf(d.name, d.appLabel, d.packageName, d.addresses.joinToString(" "))
        }
        val domains = domainsSearched.items.sortedWith(
            Sorters.domains(settings.domainsSortCol, settings.domainsSortDesc)
        )

        val servicesFiltered = Filters.services(snap.services, filters)
        val servicesSearched = Search.apply(servicesFiltered, matcher, settings.searchMode) { s ->
            listOf(s.port.toString(), s.bindAddr, s.proto.toString(), s.service)
        }
        val services = servicesSearched.items.sortedWith(
            Sorters.services(settings.servicesSortCol, settings.servicesSortDesc)
        )

        val visibleEvents = events.filter {
            when (eventFilter) {
                EventLevelFilter.ALL -> true
                EventLevelFilter.WARN_PLUS ->
                    it.level != com.vlad.ducknetview.domain.model.EventLevel.INFO
                EventLevelFilter.ALERTS ->
                    it.level == com.vlad.ducknetview.domain.model.EventLevel.ALERT
            }
        }
        val eventsSearched = Search.apply(visibleEvents, matcher, settings.searchMode) { e ->
            listOf(e.kind.label, e.subject, e.detail, e.level.name)
        }

        // Both the highlighted rows and the counter describe the one table on
        // screen, so they are read off the same result rather than selected
        // twice. The counter used to sum all four tables, which put "3 matches"
        // above a table showing none of them and made the "n / m" denominator
        // count rows n/N could never walk to.
        val visibleSearch: SearchResult<*>? = when (tabFlow.value) {
            Tab.CONNECTIONS -> connsSearched
            Tab.APPS -> appsSearched
            Tab.SERVICES -> servicesSearched
            Tab.DOMAINS -> domainsSearched
            Tab.EVENTS -> eventsSearched
            else -> null
        }
        val matched = visibleSearch?.matched ?: emptySet()
        val highlighting = settings.searchMode == SearchMode.HIGHLIGHT && search.active

        UiState(
            snapshot = snap,
            caps = caps,
            settings = settings,
            filters = filters,
            search = search.copy(error = compiled.error, mode = settings.searchMode),
            conns = conns,
            closed = snap.closedConns,
            groups = Grouping.of(conns, settings.revDns),
            apps = apps,
            services = services,
            domains = domains,
            events = eventsSearched.items,
            eventFilter = eventFilter,
            unackedAlerts = events.count {
                it.level == com.vlad.ducknetview.domain.model.EventLevel.ALERT &&
                    it.at > settings.alertsAckedAt
            },
            // Android runs at most one VPN. If a VPN link is up that is not
            // ours, capture cannot start, and the Settings switch has to say so
            // rather than sitting there doing nothing when tapped.
            vpnAvailable = vpnRunning ||
                snap.networks.none { it.transport == Transport.VPN },
            vpnRunning = vpnRunning && frozen == null,
            usageAccessGranted = deps.usage.hasAccess(),
            privateDns = snap.networks.firstOrNull { it.isDefault }?.privateDns
                ?: snap.networks.firstNotNullOfOrNull { it.privateDns },
            status = status,
            matchCount = visibleSearch?.matchCount ?: 0,
            matchedRows = if (highlighting) matched else emptySet(),
            matchCursor = if (highlighting) misc.matchCursor else -1,
            wifiPermissionGranted = hasWifiPermission(misc.permissionEpoch),
            usage = misc.usage,
            usageLoading = misc.usageLoading,
            metricsUrl = if (misc.metricsRunning) metricsUrl(snap) else null,
            metricsError = misc.metricsError,
            // Every emission of this combine carries settings read from disk;
            // the seed value below does not. First-run UI has to wait for a
            // real read or it flashes at users who dismissed it long ago.
            settingsLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    init {
        viewModelScope.launch {
            deps.settings.settings.collect { s ->
                deps.engine.applySettings(s)
                tabFlow.value = Tab.fromRoute(s.lastTab)
            }
        }
        // Retire the "starting capture" / consent messages once the service is
        // actually up: a banner still announcing the attempt while the engine
        // is running is the stalest thing on the screen.
        viewModelScope.launch {
            VpnBridge.running.collect { running ->
                if (running && statusFlow.value in VPN_START_MESSAGES) status("capture running")
            }
        }
        deps.engine.start()
    }

    /**
     * The address a scraper elsewhere on the LAN can reach, taken from the
     * default link and falling back to any other link that has one.
     */
    private fun metricsUrl(snap: NetSnapshot): String? {
        val port = deps.metricsServer.boundPort ?: return null
        val default = snap.networks.firstOrNull { it.isDefault }?.addresses.orEmpty()
        return MetricsServer.lanUrl(default, port)
            ?: MetricsServer.lanUrl(snap.networks.filter { !it.isNoise }.flatMap { it.addresses }, port)
    }

    /**
     * Compiling the watchlist means compiling its regexes, which is too much to
     * redo on every snapshot. The entry list is the identity: it changes only
     * when the user edits it.
     */
    private var watchlistCache: Pair<List<String>, Watchlist>? = null

    private fun watchlistOf(entries: List<String>): Watchlist {
        val cached = watchlistCache
        if (cached != null && cached.first == entries) return cached.second
        val built = Watchlist(entries)
        watchlistCache = entries to built
        return built
    }

    private fun edit(block: (AppSettings) -> AppSettings) {
        viewModelScope.launch { deps.settings.update(block) }
    }

    private fun status(message: String) {
        statusFlow.value = message
    }

    override fun setTab(tab: Tab) {
        tabFlow.value = tab
        edit { it.copy(lastTab = tab.route) }
    }

    override fun togglePause() = edit { it.copy(paused = !it.paused) }

    override fun setInterval(seconds: Int) = edit { it.copy(intervalSeconds = seconds) }

    override fun refreshNow() {
        deps.engine.refreshNow()
        status("refreshing")
    }

    override fun toggleRateUnit() = edit {
        it.copy(
            rateUnit = if (it.rateUnit == com.vlad.ducknetview.domain.model.RateUnit.BYTES)
                com.vlad.ducknetview.domain.model.RateUnit.BITS
            else com.vlad.ducknetview.domain.model.RateUnit.BYTES
        )
    }

    override fun toggleThroughputMode() = edit {
        it.copy(
            throughputMode =
            if (it.throughputMode == com.vlad.ducknetview.domain.model.ThroughputMode.RATE)
                com.vlad.ducknetview.domain.model.ThroughputMode.TOTAL
            else com.vlad.ducknetview.domain.model.ThroughputMode.RATE
        )
    }

    override fun toggleHideNoise() = edit { it.copy(hideNoise = !it.hideNoise) }
    override fun toggleRevDns() = edit { it.copy(revDns = !it.revDns) }
    override fun toggleGrouping() = edit { it.copy(groupByHost = !it.groupByHost) }
    override fun toggleShowClosed() = edit { it.copy(showClosed = !it.showClosed) }

    override fun selectNetwork(id: String) {
        // The selected link only affects which detail the Interfaces screen
        // shows; it is not worth persisting across launches.
        statusFlow.value = null
    }

    override fun setSearch(query: String) {
        searchFlow.value = searchFlow.value.copy(query = query)
        matchCursorFlow.value = -1
    }

    override fun nextMatch() = stepMatch(+1)

    override fun prevMatch() = stepMatch(-1)

    /** Wraps at both ends, so walking matches never dead-ends. */
    private fun stepMatch(delta: Int) {
        val matches = state.value.matchedRows.sorted()
        if (matches.isEmpty()) {
            matchCursorFlow.value = -1
            return
        }
        val current = matchCursorFlow.value
        val at = matches.indexOf(current)
        val next = when {
            at < 0 -> if (delta > 0) 0 else matches.lastIndex
            else -> (at + delta + matches.size) % matches.size
        }
        matchCursorFlow.value = matches[next]
    }

    private fun hasWifiPermission(@Suppress("UNUSED_PARAMETER") epoch: Int): Boolean {
        val ctx = getApplication<Application>()
        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return fine
        // From API 33 the SSID/BSSID gate is NEARBY_WIFI_DEVICES; either grant
        // is enough for the radio details this app shows.
        val nearby = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || nearby
    }

    override fun toggleSearchMode() = edit {
        it.copy(
            searchMode = if (it.searchMode == SearchMode.FILTER) SearchMode.HIGHLIGHT
            else SearchMode.FILTER
        )
    }

    override fun clearSearch() {
        searchFlow.value = SearchState()
        matchCursorFlow.value = -1
    }

    override fun setSort(table: String, column: String) = edit { s ->
        when (table) {
            "conns" ->
                if (s.connsSortCol == column) s.copy(connsSortDesc = !s.connsSortDesc)
                else s.copy(connsSortCol = column, connsSortDesc = true)
            "apps" ->
                if (s.appsSortCol == column) s.copy(appsSortDesc = !s.appsSortDesc)
                else s.copy(appsSortCol = column, appsSortDesc = true)
            "services" ->
                if (s.servicesSortCol == column) s.copy(servicesSortDesc = !s.servicesSortDesc)
                else s.copy(servicesSortCol = column, servicesSortDesc = false)
            "domains" ->
                if (s.domainsSortCol == column) s.copy(domainsSortDesc = !s.domainsSortDesc)
                else s.copy(domainsSortCol = column, domainsSortDesc = true)
            else -> s
        }
    }

    override fun setProtoFilter(f: ProtoFilter) {
        filtersFlow.value = filtersFlow.value.copy(proto = f)
    }

    override fun setIpVersionFilter(f: IpVersionFilter) {
        filtersFlow.value = filtersFlow.value.copy(ipVersion = f)
    }

    override fun setStateFilter(f: StateFilter) {
        filtersFlow.value = filtersFlow.value.copy(state = f)
    }

    override fun togglePublicOnly() {
        filtersFlow.value = filtersFlow.value.copy(publicOnly = !filtersFlow.value.publicOnly)
    }

    override fun toggleUserAppsOnly() {
        filtersFlow.value = filtersFlow.value.copy(userAppsOnly = !filtersFlow.value.userAppsOnly)
    }

    override fun setNetworkFilter(network: String?) {
        filtersFlow.value = filtersFlow.value.copy(network = network)
    }

    override fun setUidFilter(uid: Int?) {
        filtersFlow.value = filtersFlow.value.copy(uid = uid)
    }

    override fun startVpn() {
        // Deliberately not "requesting VPN permission": consent is only asked
        // for once, so on every later start that message would sit in the
        // banner describing something that never happened. MainActivity knows
        // which path was taken and reports it through the callbacks below.
        status("starting capture")
        vpnStartRequest.value = true
    }

    /** Consent is actually being asked for: only the first activation. */
    fun onVpnConsentRequested() = status("requesting VPN permission")

    /** The consent dialog came back refused, so capture cannot start. */
    fun onVpnConsentDenied() = status("capture needs VPN permission")

    override fun stopVpn() {
        com.vlad.ducknetview.engine.vpn.DuckVpnService.stop(getApplication())
        status("capture stopped")
    }

    val vpnStartRequest = MutableStateFlow(false)

    fun consumeVpnStartRequest() {
        vpnStartRequest.value = false
    }

    override fun scanServices() {
        status("scanning this device")
        deps.engine.scanServices { rows ->
            status("scan found ${rows.size} listening ${plural(rows.size, "port")}")
        }
    }

    override fun setScanFullRange(full: Boolean) = edit { it.copy(scanFullRange = full) }

    override fun saveBaseline() {
        val rows = state.value.snapshot.services.filter { com.vlad.ducknetview.domain.baseline.baselineEligible(it) }
        edit {
            it.copy(
                baseline = rows.map(ServiceRow::baselineKey),
                baselineAt = System.currentTimeMillis(),
            )
        }
        status("baseline saved: ${rows.size} ${plural(rows.size, "listener")}")
    }

    override fun clearBaseline() {
        edit { it.copy(baseline = emptyList(), baselineAt = 0L) }
        status("baseline cleared")
    }

    override fun acceptIntoBaseline(row: ServiceRow) {
        edit { s ->
            val key = row.baselineKey
            if (key in s.baseline) s.copy(baseline = s.baseline - key)
            else s.copy(baseline = s.baseline + key)
        }
    }

    override fun toggleWatchlist(remote: String) {
        edit { s ->
            if (remote in s.watchlist) s.copy(watchlist = s.watchlist - remote)
            else s.copy(watchlist = s.watchlist + remote)
        }
        status("watchlist updated")
    }

    override fun addWatchlistEntry(entry: String) {
        val e = entry.trim()
        if (e.isEmpty()) return
        edit { s -> if (e in s.watchlist) s else s.copy(watchlist = s.watchlist + e) }
    }

    override fun removeWatchlistEntry(entry: String) {
        edit { s -> s.copy(watchlist = s.watchlist - entry) }
    }

    override fun blockApp(uid: Int, blocked: Boolean) {
        edit { s ->
            s.copy(blockedUids = if (blocked) s.blockedUids + uid else s.blockedUids - uid)
        }
        status(if (blocked) "app blocked" else "app unblocked")
    }

    override fun excludeApp(uid: Int, excluded: Boolean) {
        edit { s ->
            s.copy(excludedUids = if (excluded) s.excludedUids + uid else s.excludedUids - uid)
        }
        status("restart capture to apply the exclusion")
    }

    override fun openAppInfo(packageName: String) {
        if (packageName.isEmpty()) return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    override fun copyText(label: String, text: String) {
        val cm = getApplication<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        status("copied $label")
    }

    override fun exportCurrentTable() {
        val s = state.value
        val csv = when (tabFlow.value) {
            Tab.CONNECTIONS -> Csv.conns(s.conns, s.snapshot.atMillis)
            Tab.APPS -> Csv.apps(s.apps)
            Tab.SERVICES -> Csv.services(s.services)
            Tab.DOMAINS -> Csv.domains(s.domains)
            Tab.EVENTS -> Csv.events(s.events)
            else -> null
        }
        if (csv == null) {
            status("this screen has no table to export")
            return
        }
        share("ducknetview_${tabFlow.value.route}", "text/csv", csv)
    }

    override fun exportSnapshotJson() {
        share("ducknetview_snapshot", "application/json", SnapshotJson.encode(state.value.snapshot))
    }

    override fun exportEvents() {
        share("ducknetview_events", "text/csv", Csv.events(state.value.events))
    }

    private fun share(name: String, mime: String, content: String) {
        viewModelScope.launch {
            val uri = deps.exporter.write(name, content)
            if (uri == null) {
                status("could not write the export file")
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                getApplication<Application>().startActivity(
                    Intent.createChooser(intent, "Share export")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    override fun setEventFilter(f: EventLevelFilter) {
        eventFilterFlow.value = f
    }

    override fun ackAlerts() {
        edit { it.copy(alertsAckedAt = System.currentTimeMillis()) }
        status("alerts acknowledged")
    }

    override fun clearDomains() {
        viewModelScope.launch { deps.domainRepo.clear() }
        status("name history cleared")
    }

    override fun clearEvents() {
        viewModelScope.launch { deps.eventRepo.clear() }
        status("event log cleared")
    }

    override fun refreshExternalIp() {
        viewModelScope.launch {
            runCatching { deps.externalIp.fetch() }
            status("external IP refreshed")
        }
    }

    override fun openSnapshot() {
        snapshotOpenRequest.value = true
    }

    val snapshotOpenRequest = MutableStateFlow(false)

    fun consumeSnapshotOpenRequest() {
        snapshotOpenRequest.value = false
    }

    /** Decodes a picked ducknetview --json file and freezes the UI onto it. */
    fun loadSnapshot(uri: Uri, label: String) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver
                        .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                status("could not read that file")
                return@launch
            }
            val decoded = runCatching { SnapshotJson.decode(text) }.getOrNull()
            if (decoded == null) {
                status("that file is not a ducknetview snapshot")
                return@launch
            }
            frozenFlow.value = FrozenSnapshot(label, decoded)
            deps.engine.setFrozen(true)
            status("browsing $label")
        }
    }

    override fun closeSnapshot() {
        frozenFlow.value = null
        deps.engine.setFrozen(false)
        status("back to the live device")
    }

    override fun refreshUsage() {
        viewModelScope.launch {
            usageLoadingFlow.value = true
            try {
                usageFlow.value = deps.usageRepo.all.first()
            } finally {
                usageLoadingFlow.value = false
            }
        }
    }

    override fun updateSettings(block: (AppSettings) -> AppSettings) = edit(block)

    override fun requestUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
    }

    override fun requestWifiPermission() {
        permissionRequest.value = true
    }

    val permissionRequest = MutableStateFlow(false)

    fun consumePermissionRequest() {
        permissionRequest.value = false
    }

    /** Called once the system dialog closes, so the grant is re-read. */
    fun onPermissionResult() {
        permissionEpochFlow.value += 1
    }

    override fun dismissStatus() {
        statusFlow.value = null
    }

    private fun plural(n: Int, word: String) = if (n == 1) word else "${word}s"

    private companion object {
        /** Transient messages that the running engine supersedes. */
        val VPN_START_MESSAGES = setOf(
            "starting capture",
            "requesting VPN permission",
            "capture needs VPN permission",
        )
    }
}
