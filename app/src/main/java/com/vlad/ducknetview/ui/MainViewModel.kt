package com.vlad.ducknetview.ui

import com.vlad.ducknetview.domain.firewall.Blocklist
import com.vlad.ducknetview.domain.diff.SnapshotDiffer
import com.vlad.ducknetview.domain.firewall.AppPolicy
import com.vlad.ducknetview.domain.firewall.FirewallRule
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.domain.usage.AppBudget
import com.vlad.ducknetview.domain.usage.AppBudgets
import com.vlad.ducknetview.domain.views.SavedView
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
import com.vlad.ducknetview.domain.net.LinkChoice
import com.vlad.ducknetview.domain.search.Search
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.domain.search.SearchResult
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.net.NetworkTrust
import com.vlad.ducknetview.domain.net.Trust
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

    private data class Extras(val blocklistSize: Int, val asnSize: Int, val diff: DiffView?)

    private val diffFlow = MutableStateFlow<DiffView?>(null)

    private val extrasFlow = combine(deps.imports.blocklist, deps.imports.asn, diffFlow) { b, a, d ->
        Extras(b.size, a.size, d)
    }

    /** Which file the next system picker is for, beyond the snapshot viewer. */
    enum class FilePick { BLOCKLIST, ASN, DIFF }

    val filePickRequest = MutableStateFlow<FilePick?>(null)

    fun consumeFilePickRequest() {
        filePickRequest.value = null
    }

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

        // "Entirely" has to include the three tables that come from this
        // device's own database rather than from the snapshot: events, names
        // and usage history. They used to go on screen unchanged under the
        // "browsing <file>" banner, so a snapshot taken on a server was shown
        // interleaved with this phone's event log — two machines' data in one
        // view, with nothing saying which was which. The snapshot format
        // carries none of the three, so under it they are empty.
        val liveEvents = if (frozen != null) emptyList() else events
        val liveDomains = if (frozen != null) emptyList() else misc.domains
        val liveUsage = if (frozen != null) emptyList() else misc.usage

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

        // The closed-connection history went on screen straight off the
        // snapshot, so the filter chips, the sort header and the search box
        // were all inert over it: searching an app's name above a table full
        // of that app's rows answered "0 matches". It is the same shape of row
        // as the live table, so it gets the same three stages.
        val closedFiltered = Filters.closed(snap.closedConns, filters, userUids)
        val closedSearched = Search.apply(
            closedFiltered, matcher, settings.searchMode,
        ) { c ->
            listOf(
                c.row.local, c.row.remote, c.row.resolvedHost.orEmpty(), c.row.appLabel,
                c.row.service, c.row.proto.toString(),
            )
        }
        val closedRows = closedSearched.items.sortedWith(
            Sorters.closedByConnColumn(settings.connsSortCol, settings.connsSortDesc, settings.throughputMode)
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
        val listed = deps.imports.blocklist.value
        val firewall = VpnBridge.firewall
        val domainsLabelled = liveDomains.map { d ->
            val app = deps.catalog.row(d.uid)
            d.copy(
                listed = listed.isNotEmpty() && Blocklist.matches(listed, d.name),
                blocked = firewall.nameVerdict(d.uid, d.name)?.startsWith("rule") == true,
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

        // Found by asking the network rather than by reading a table, so it
        // only exists after a sweep — but it goes through the same search and
        // sort every other table does, which is what TablePipelineTest is for.
        val neighboursSearched = Search.apply(
            snap.neighbours,
            matcher,
            settings.searchMode,
        ) { n -> listOf(n.address, n.hostname) + n.services }
        val neighbours = neighboursSearched.items.sortedWith(
            Sorters.neighbours(settings.neighboursSortCol, settings.neighboursSortDesc)
        )

        val visibleEvents = liveEvents.filter {
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
            // Which of the three the Conns tab is showing is a settings choice,
            // and the counter has to describe the table actually on screen.
            Tab.CONNECTIONS -> if (settings.showClosed) closedSearched else connsSearched
            Tab.APPS -> appsSearched
            Tab.SERVICES -> servicesSearched
            Tab.DOMAINS -> domainsSearched
            Tab.NEIGHBOURS -> neighboursSearched
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
            closed = closedRows,
            groups = Grouping.of(conns, settings.revDns),
            apps = apps,
            services = services,
            domains = domains,
            neighbours = neighbours,
            events = eventsSearched.items,
            eventFilter = eventFilter,
            unackedAlerts = liveEvents.count {
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
            usage = liveUsage,
            usageLoading = misc.usageLoading,
            metricsUrl = if (misc.metricsRunning) metricsUrl(snap) else null,
            metricsError = misc.metricsError,
            // Every emission of this combine carries settings read from disk;
            // the seed value below does not. First-run UI has to wait for a
            // real read or it flashes at users who dismissed it long ago.
            settingsLoaded = true,
        )
    }.combine(extrasFlow) { ui, ex ->
        val s = ui.settings
        ui.copy(
            savedViews = SavedView.decodeAll(s.savedViews),
            firewallRules = s.firewallRules.mapNotNull(FirewallRule::decode),
            appPolicies = AppPolicy.decodeAll(s.appPolicies),
            appBudgets = AppBudgets.decode(s.appBudgets),
            blocklistSize = ex.blocklistSize,
            asnSize = ex.asnSize,
            diff = ex.diff,
            alwaysOn = ui.vpnRunning && VpnBridge.alwaysOn,
            lockdown = ui.vpnRunning && VpnBridge.lockdown,
            vpnConsentGranted = runCatching {
                android.net.VpnService.prepare(getApplication()) == null
            }.getOrDefault(false),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    init {
        viewModelScope.launch {
            // The engine gets settings from the app-lifetime collector in
            // DuckApp; this one only follows the remembered tab.
            deps.settings.settings.collect { s ->
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
        watchUntrustedNetworks()
        deps.engine.start()
    }

    /**
     * The address a scraper elsewhere on the LAN can reach, taken from the
     * default link and falling back to any other link that has one.
     */
    private fun metricsUrl(snap: NetSnapshot): String? {
        val port = deps.metricsServer.boundPort ?: return null
        // Not the default network: while capture is on that is this app's own
        // TUN, and the URL printed here was http://10.215.173.1:9187/metrics —
        // the one address on the device no other machine can reach. The
        // endpoint binds the wildcard, so what to advertise is the address of
        // the link the device is actually reachable on.
        val reachable = LinkChoice.underlying(snap.networks)?.addresses.orEmpty()
        return MetricsServer.lanUrl(reachable, port)
            ?: MetricsServer.lanUrl(
                snap.networks
                    .filter { !it.isNoise && it.transport != Transport.VPN }
                    .flatMap { it.addresses },
                port,
            )
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

            "neighbours" ->
                if (s.neighboursSortCol == column) s.copy(neighboursSortDesc = !s.neighboursSortDesc)
                else s.copy(neighboursSortCol = column, neighboursSortDesc = false)
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

    override fun scanNeighbours() {
        status("sweeping this link for other hosts")
        // DESIGN §5: the banner says what happened, not what was attempted.
        // Left as "sweeping …" it sat there for the rest of the session
        // describing work that had finished a minute earlier — the same
        // defect as the old "requesting VPN permission" message.
        deps.engine.scanNeighbours { rows, subnet ->
            status(
                when {
                    subnet.isEmpty() -> "no IPv4 prefix on this link to sweep"
                    rows.isEmpty() -> "nothing on $subnet answered"
                    else -> "${rows.size} ${plural(rows.size, "host")} answered on $subnet"
                }
            )
        }
    }

    override fun setNetworkTrust(key: String, trust: Trust) {
        edit { NetworkTrust.apply(it, key, trust) }
        status(
            when (trust) {
                Trust.TRUSTED -> "$key marked trusted"
                Trust.UNTRUSTED -> "$key marked untrusted"
                Trust.UNKNOWN -> "$key no longer marked"
            }
        )
    }

    /**
     * Act on arriving at a network the user does not trust.
     *
     * Both responses are the ones a careful person would run by hand on a
     * café network and never remembers to: turn on the capture that makes
     * per-connection accounting possible, and check what this device is
     * exposing to the people on it.
     *
     * Keyed on the snapshot's own verdict, so it cannot disagree with the
     * event the log writes. Once per link rather than once per poll — the
     * condition stands for as long as you are on the network, and re-running
     * a port scan every two seconds would be its own kind of misbehaviour.
     *
     * Capture is only *requested*: the first activation needs a consent
     * dialog an Activity has to show, so on a device that has never granted
     * it this does what the Quick Settings tile does and waits for the user.
     */
    private fun watchUntrustedNetworks() {
        viewModelScope.launch {
            var actedOn: String? = null
            combine(deps.engine.snapshot, deps.settings.settings) { snap, s -> snap to s }
                .collect { (snap, s) ->
                    if (!snap.untrustedDefault || snap.frozen) {
                        actedOn = null
                        return@collect
                    }
                    val link = snap.networks.firstOrNull { it.isDefault && !it.isNoise }
                        ?: return@collect
                    val key = NetworkTrust.keyOf(link) ?: return@collect
                    if (actedOn == key) return@collect
                    actedOn = key
                    if (s.autoCaptureOnUntrusted && !VpnBridge.running.value) startVpn()
                    if (s.autoScanOnUntrusted) scanServices()
                }
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
            // The Connections screen has three modes and this exported the live
            // table in all three: a screen reading "8 closed" wrote fourteen
            // `established` rows, and "4 hosts" wrote the flat table. The file
            // has to be the table that is on screen, which is what picks it.
            Tab.CONNECTIONS -> when {
                s.settings.groupByHost -> Csv.groups(s.groups)
                s.settings.showClosed -> Csv.closed(s.closed)
                else -> Csv.conns(s.conns, s.snapshot.atMillis)
            }
            Tab.APPS -> Csv.apps(s.apps)
            Tab.SERVICES -> Csv.services(s.services)
            Tab.DOMAINS -> Csv.domains(s.domains)
            Tab.NEIGHBOURS -> Csv.neighbours(s.neighbours)
            Tab.EVENTS -> Csv.events(s.events)
            else -> null
        }
        if (csv == null) {
            status("this screen has no table to export")
            return
        }
        // Three tables share one screen, so three files would otherwise share
        // one name and differ only in their columns.
        val suffix = when {
            tabFlow.value != Tab.CONNECTIONS -> ""
            s.settings.groupByHost -> "_hosts"
            s.settings.showClosed -> "_closed"
            else -> ""
        }
        share("ducknetview_${tabFlow.value.route}$suffix", "text/csv", csv)
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
        if (!state.value.settings.externalIpEnabled) return
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

    override fun terminateConnection(key: String) {
        status(if (VpnBridge.terminate(key)) "connection reset" else "that connection has already closed")
    }

    override fun addFirewallRule(pattern: String, packageName: String) {
        val problem = FirewallRule.problem(pattern)
        if (problem != null) {
            status("cannot block \"$pattern\": $problem")
            return
        }
        val rule = FirewallRule(FirewallRule.normalize(pattern), packageName)
        edit { s ->
            val encoded = rule.encode()
            if (encoded in s.firewallRules) s else s.copy(firewallRules = s.firewallRules + encoded)
        }
        status(
            "new connections to ${rule.pattern} are refused" +
                if (packageName.isEmpty()) " for every app" else " for this app"
        )
    }

    override fun removeFirewallRule(rule: FirewallRule) {
        edit { s -> s.copy(firewallRules = s.firewallRules - rule.encode()) }
        status("rule for ${rule.pattern} removed")
    }

    override fun setAppPolicy(policy: AppPolicy) {
        edit { s ->
            val others = s.appPolicies.filterNot { AppPolicy.decode(it)?.uid == policy.uid }.toSet()
            s.copy(appPolicies = if (policy.isEmpty) others else others + policy.encode())
        }
        status("network policy updated")
    }

    override fun setAppBudget(packageName: String, capBytes: Long, block: Boolean) {
        if (capBytes <= 0L) {
            removeAppBudget(packageName)
            return
        }
        edit { s ->
            val others = AppBudgets.decode(s.appBudgets).filterNot { it.packageName == packageName }
            s.copy(appBudgets = AppBudgets.encode(others + AppBudget(packageName, capBytes, block)))
        }
        status("allowance set")
    }

    override fun removeAppBudget(packageName: String) {
        edit { s ->
            s.copy(appBudgets = AppBudgets.encode(
                AppBudgets.decode(s.appBudgets).filterNot { it.packageName == packageName }
            ))
        }
        status("allowance removed")
    }

    override fun importBlocklist() {
        filePickRequest.value = FilePick.BLOCKLIST
    }

    override fun clearBlocklist() {
        viewModelScope.launch { deps.imports.clearBlocklist() }
        status("block list removed")
    }

    override fun importAsn() {
        filePickRequest.value = FilePick.ASN
    }

    override fun clearAsn() {
        viewModelScope.launch { deps.imports.clearAsn() }
        status("AS table removed")
    }

    override fun compareSnapshot() {
        filePickRequest.value = FilePick.DIFF
    }

    override fun closeDiff() {
        diffFlow.value = null
    }

    /** A file came back from the picker the request asked for. */
    fun onFilePicked(kind: FilePick, uri: Uri, label: String) {
        viewModelScope.launch {
            when (kind) {
                FilePick.BLOCKLIST -> runCatching { deps.imports.importBlocklist(uri) }
                    .onSuccess { status("block list imported: $it ${plural(it, "name")}") }
                    .onFailure { status("block list not imported: ${it.message}") }
                FilePick.ASN -> runCatching { deps.imports.importAsn(uri) }
                    .onSuccess { status("AS table imported: $it ${plural(it, "range")}") }
                    .onFailure { status("AS table not imported: ${it.message}") }
                FilePick.DIFF -> {
                    val text = withContext(Dispatchers.IO) {
                        runCatching {
                            getApplication<Application>().contentResolver
                                .openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                    val old = text?.let { runCatching { SnapshotJson.decode(it) }.getOrNull() }
                    if (old == null) {
                        status("that file is not a ducknetview snapshot")
                        return@launch
                    }
                    diffFlow.value = DiffView(label, SnapshotDiffer.diff(old, deps.engine.snapshot.value))
                }
            }
        }
    }

    override fun saveView(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            status("a view needs a name")
            return
        }
        val s = state.value
        val f = s.filters
        val view = SavedView(
            name = trimmed,
            tab = tabFlow.value.route,
            query = s.search.query,
            highlight = s.settings.searchMode == SearchMode.HIGHLIGHT,
            proto = f.proto.name,
            ipVersion = f.ipVersion.name,
            state = f.state.name,
            publicOnly = f.publicOnly,
            userAppsOnly = f.userAppsOnly,
            network = f.network.orEmpty(),
            connsSortCol = s.settings.connsSortCol,
            connsSortDesc = s.settings.connsSortDesc,
            groupByHost = s.settings.groupByHost,
            showClosed = s.settings.showClosed,
            totals = s.settings.throughputMode == ThroughputMode.TOTAL,
            revDns = s.settings.revDns,
        )
        edit { st ->
            val views = SavedView.upsert(SavedView.decodeAll(st.savedViews), view)
            st.copy(savedViews = views.map { it.encode() })
        }
        status("view \"$trimmed\" saved")
    }

    // Read from disk rather than from [state]: a launcher shortcut applies a
    // view before the first settings read has reached the screen.
    override fun applyView(name: String) {
        viewModelScope.launch {
            val view = SavedView.decodeAll(deps.settings.snapshot().savedViews)
                .firstOrNull { it.name == name }
            if (view == null) status("no saved view called \"$name\"") else apply(view)
        }
    }

    private fun apply(view: SavedView) {
        filtersFlow.value = QuickFilters(
            proto = ProtoFilter.entries.firstOrNull { it.name == view.proto } ?: ProtoFilter.ALL,
            ipVersion = IpVersionFilter.entries.firstOrNull { it.name == view.ipVersion } ?: IpVersionFilter.ALL,
            state = StateFilter.entries.firstOrNull { it.name == view.state } ?: StateFilter.ALL,
            publicOnly = view.publicOnly,
            userAppsOnly = view.userAppsOnly,
            network = view.network.ifEmpty { null },
        )
        searchFlow.value = SearchState(query = view.query)
        matchCursorFlow.value = -1
        val tab = Tab.fromRoute(view.tab)
        tabFlow.value = tab
        edit { s ->
            s.copy(
                lastTab = tab.route,
                searchMode = if (view.highlight) SearchMode.HIGHLIGHT else SearchMode.FILTER,
                connsSortCol = view.connsSortCol,
                connsSortDesc = view.connsSortDesc,
                groupByHost = view.groupByHost,
                showClosed = view.showClosed,
                throughputMode = if (view.totals) ThroughputMode.TOTAL else ThroughputMode.RATE,
                revDns = view.revDns,
            )
        }
        status("view \"${view.name}\"")
    }

    override fun deleteView(name: String) {
        edit { s ->
            s.copy(savedViews = SavedView.decodeAll(s.savedViews).filterNot { it.name == name }.map { it.encode() })
        }
        status("view \"$name\" deleted")
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
