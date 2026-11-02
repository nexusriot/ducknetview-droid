package com.vlad.ducknetview.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vlad.ducknetview.data.ClosedConnRepository
import com.vlad.ducknetview.data.DomainRepository
import com.vlad.ducknetview.data.EventRepository
import com.vlad.ducknetview.data.HostSeenRepository
import com.vlad.ducknetview.data.ImportStore
import com.vlad.ducknetview.data.db.MinuteDao
import com.vlad.ducknetview.data.db.MinuteEntity
import com.vlad.ducknetview.domain.events.FindingEvents
import com.vlad.ducknetview.domain.firewall.AppPolicy
import com.vlad.ducknetview.domain.firewall.Firewall
import com.vlad.ducknetview.domain.firewall.FirewallRule
import com.vlad.ducknetview.domain.firewall.LinkContext
import com.vlad.ducknetview.domain.history.MinuteAccumulator
import com.vlad.ducknetview.domain.history.MinuteSample
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.usage.AppBudgets
import com.vlad.ducknetview.domain.alerts.AlertRules
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.events.DomainEvents
import com.vlad.ducknetview.domain.events.BlockedEvents
import com.vlad.ducknetview.domain.events.EventEngine
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.BlockedAttempt
import com.vlad.ducknetview.domain.model.DomainObservation
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.net.LinkChoice
import com.vlad.ducknetview.domain.net.Subnet
import com.vlad.ducknetview.domain.rates.RateTracker
import com.vlad.ducknetview.domain.rdns.RdnsCache
import com.vlad.ducknetview.domain.totals.SessionTotals
import com.vlad.ducknetview.domain.usage.Quota
import com.vlad.ducknetview.domain.usage.QuotaStatus
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.engine.api.ApiEngine
import com.vlad.ducknetview.engine.api.AppCatalog
import com.vlad.ducknetview.engine.api.ExternalIpFetcher
import com.vlad.ducknetview.engine.api.LatencyProber
import com.vlad.ducknetview.engine.api.NeighbourScanner
import com.vlad.ducknetview.engine.api.PortScanner
import com.vlad.ducknetview.engine.api.UsageHistorySource
import com.vlad.ducknetview.engine.vpn.VpnBridge
import com.vlad.ducknetview.service.AlertDelivery
import com.vlad.ducknetview.service.Notifications
import com.vlad.ducknetview.service.RatesWidget
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the poll loop and produces the snapshot stream.
 *
 * Two cadences, as in the TUI: the cheap counters follow the user's interval,
 * while the expensive work (service scans, usage history) runs far less often.
 */
class EngineController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: ApiEngine,
    private val catalog: AppCatalog,
    private val prober: LatencyProber,
    private val externalIp: ExternalIpFetcher,
    private val scanner: PortScanner,
    private val neighbours: NeighbourScanner,
    private val usage: UsageHistorySource,
    private val events: EventRepository,
    private val closedRepo: ClosedConnRepository,
    private val hostSeen: HostSeenRepository,
    private val domains: DomainRepository,
    private val rates: RateTracker,
    private val minutes: MinuteDao,
    private val imports: ImportStore,
) {
    private val totals = SessionTotals()
    private val rdns = RdnsCache()
    private val assembler = SnapshotAssembler(catalog, rates, totals, rdns)
    private val eventEngine = EventEngine()
    private val alertRules = AlertRules()
    private val blockedEvents = BlockedEvents()
    private val findingEvents = FindingEvents()
    private val minuteAcc = MinuteAccumulator()

    /** Uids past a blocking per-app budget this cycle; folded into the firewall. */
    @Volatile private var overBudgetUids: Set<Int> = emptySet()
    @Volatile private var cycleUsedByPackage: Map<String, Long> = emptyMap()
    @Volatile private var cycleStartMillis: Long = 0L
    private var widgetAt = 0L

    private val _snapshot = MutableStateFlow(NetSnapshot())
    val snapshot: StateFlow<NetSnapshot> = _snapshot.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _neighbourScanning = MutableStateFlow(false)
    val neighbourScanning: StateFlow<Boolean> = _neighbourScanning.asStateFlow()

    @Volatile private var settings: AppSettings = AppSettings()
    @Volatile private var baseline: Baseline = Baseline(emptySet(), 0L)
    @Volatile private var watchlist: Watchlist = Watchlist(emptyList())
    @Volatile private var latency: List<LatencySample> = emptyList()
    @Volatile private var prevSnapshot: NetSnapshot? = null
    @Volatile private var forceTick = false

    /**
     * While a snapshot file is being browsed the live host is irrelevant, so
     * the poll loop idles instead of burning battery producing data nothing
     * renders. This is the TUI's `--from` mode, which likewise ignores ticks.
     */
    @Volatile private var frozen = false

    private var loop: Job? = null
    private var scanJob: Job? = null
    private var neighbourJob: Job? = null
    private var lifecycleJob: Job? = null

    /** Conflated so a burst of wakeups costs one early tick, not a queue of them. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Names observed since the last tick, waiting to be written.
     *
     * Buffered rather than written where they are seen: the sink is called from
     * the capture engine's packet path, and launching a coroutine per DNS answer
     * would put a database write behind every lookup on the device. The poll
     * tick drains this and writes the batch, which is the cadence everything
     * else in this class already runs on. Bounded, so a name flood is dropped
     * rather than held.
     */
    private val pendingDomains = ArrayList<DomainObservation>()
    private val domainLock = Any()

    /**
     * Refusals observed since the last tick, and the running per-app total
     * the Apps screen renders. Buffered for the same reason names are: the
     * sink is the packet path, and a blocked app retries hard enough that
     * anything more than an append would be felt there.
     */
    private val pendingBlocked = ArrayList<BlockedAttempt>()
    private val blockedLock = Any()
    private val blockedCounts = LinkedHashMap<Int, Long>()

    @Volatile private var interactive: Boolean = true
    private var screenReceiver: BroadcastReceiver? = null

    fun start() {
        if (loop != null) return
        assembler.configure(deviceName(), System.currentTimeMillis())
        api.start(scope)
        VpnBridge.labelResolver = { uid ->
            val row = catalog.row(uid)
            row.label to row.packageName
        }
        VpnBridge.dnsSink = { ip, name -> rdns.put(ip, name) }
        VpnBridge.domainSink = { observation -> queueDomain(observation) }
        VpnBridge.blockedSink = { attempt -> queueBlocked(attempt) }
        // Driven by the capture service's expiry tick (every 5 s), not by the
        // poll loop: the shade does not need a per-second redraw, and rebuilding
        // a notification at poll rate is a battery cost of its own.
        VpnBridge.notificationUpdater = { ctx ->
            val s = _snapshot.value
            runCatching {
                Notifications.ensureChannels(ctx)
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                nm?.notify(
                    Notifications.ENGINE_ID,
                    Notifications.engineNotification(ctx, s, settings.rateUnit),
                )
            }
        }
        registerScreenReceiver()
        watchEngineLifecycle()
        watchImports()
        // Seed the session dedupe from the hosts already on record before the
        // first tick can report them, so a restart is not a burst of "first
        // contact" for hosts this device has talked to for weeks.
        loop = scope.launch {
            runCatching { eventEngine.seedSeenHosts(hostSeen.known()) }
            pollLoop()
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        neighbourJob?.cancel()
        neighbourJob = null
        lifecycleJob?.cancel()
        lifecycleJob = null
        unregisterScreenReceiver()
        api.stop()
    }

    /**
     * Screen state drives the poll cadence, so it is tracked rather than polled.
     * The receiver is registered for the life of the loop and unregistered in
     * [stop]; a context that refuses the registration just leaves the engine on
     * its configured interval.
     */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        interactive = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        }.getOrDefault(true)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> {
                        interactive = true
                        wake.trySend(Unit)
                    }
                    Intent.ACTION_SCREEN_OFF -> interactive = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        val ok = runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        screenReceiver = if (ok) receiver else null
        // Without the broadcast there is nothing to snap the interval back, so
        // an unregistered engine must not sit in the backed-off state.
        if (!ok) interactive = true
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { context.unregisterReceiver(receiver) }
        interactive = true
    }

    fun applySettings(s: AppSettings) {
        settings = s
        // Pausing is the one setting that stops the loop that would otherwise
        // publish it. Without this the tick after "pause" never runs, the
        // snapshot keeps saying `paused = false`, and the UI shows a running
        // app with a Pause button that appears to do nothing — while the engine
        // really has stopped. Republishing the flag here is what makes the
        // control honest; it is idempotent, since a StateFlow drops an equal
        // value.
        if (_snapshot.value.paused != s.paused) {
            _snapshot.value = _snapshot.value.copy(paused = s.paused)
        }
        baseline = Baseline(s.baseline.toSet(), s.baselineAt)
        watchlist = Watchlist(s.watchlist)
        VpnBridge.tunMtu = s.tunMtu
        recompileFirewall()
        VpnBridge.excludedPackages = s.excludedUids
            .mapNotNull { catalog.row(it).packageName.takeIf { p -> p.isNotEmpty() } }
            .toSet()
    }

    /**
     * Rebuild the packet path's decision table from settings, the imported
     * block list and the budget state. Package names become uids here, once,
     * so the packet path never asks the package manager anything.
     */
    private fun recompileFirewall() {
        val s = settings
        val uidByPackage = runCatching { catalog.all().associate { it.packageName to it.uid } }
            .getOrDefault(emptyMap())
        VpnBridge.firewall = Firewall.compile(
            rules = s.firewallRules.mapNotNull(FirewallRule::decode),
            blocklist = if (s.blocklistEnabled) imports.blocklist.value else emptySet(),
            policies = AppPolicy.decodeAll(s.appPolicies),
            blockedUids = s.blockedUids,
            budgetUids = overBudgetUids,
            uidOf = uidByPackage::get,
        )
    }

    /**
     * The imported files load off the main thread at start, and either one
     * changing re-labels or re-enforces without waiting for a settings write.
     */
    private fun watchImports() {
        scope.launch {
            runCatching { imports.load() }
            runCatching { loadDay(System.currentTimeMillis()) }
        }
        scope.launch { imports.blocklist.collect { recompileFirewall() } }
        scope.launch { imports.asn.collect { assembler.setAsn(it) } }
    }

    private suspend fun loadDay(now: Long) {
        val from = now / MinuteAccumulator.MINUTE_MS - MinuteAccumulator.KEEP_MINUTES
        val rows = minutes.since(from).map {
            MinuteSample(it.minute, it.rxPeakBps, it.txPeakBps, it.rxBytes, it.txBytes)
        }
        assembler.setDay(MinuteAccumulator.series(rows, now))
    }

    private suspend fun recordMinute(sample: MinuteSample, now: Long) {
        minutes.upsert(
            MinuteEntity(sample.minute, sample.rxPeakBps, sample.txPeakBps, sample.rxBytes, sample.txBytes)
        )
        minutes.pruneBefore(sample.minute - MinuteAccumulator.KEEP_MINUTES)
        loadDay(now)
    }

    fun refreshNow() {
        forceTick = true
        wake.trySend(Unit)
    }

    fun setFrozen(value: Boolean) {
        frozen = value
        if (!value) wake.trySend(Unit)
    }

    private suspend fun pollLoop() {
        var lastLatencyAt = 0L
        var lastUsageAt = 0L
        while (scope.isActive) {
            val s = settings
            if (!frozen && (!s.paused || forceTick)) {
                forceTick = false
                val now = System.currentTimeMillis()
                runCatching { tick(now) }

                if (now - lastLatencyAt > LATENCY_INTERVAL_MS) {
                    lastLatencyAt = now
                    scope.launch { refreshLatency() }
                }
                if (now - lastUsageAt > USAGE_INTERVAL_MS) {
                    lastUsageAt = now
                    scope.launch { refreshUsage() }
                }
                if (s.externalIpEnabled) {
                    scope.launch { runCatching { externalIp.refreshIfStale() } }
                }
            }
            val waitMs = PollCadence.effectiveIntervalSeconds(
                configured = settings.intervalSeconds,
                interactive = interactive,
                frozen = frozen,
            ) * 1000L
            // A screen-on, a refreshNow() or an unfreeze cuts the wait short
            // instead of leaving the user staring at stale counters.
            withTimeoutOrNull(waitMs) { wake.receive() }
        }
    }

    private fun queueDomain(observation: DomainObservation) {
        synchronized(domainLock) {
            if (pendingDomains.size >= MAX_PENDING_DOMAINS) return
            pendingDomains += observation
        }
    }

    private fun drainDomains(): List<DomainObservation> = synchronized(domainLock) {
        if (pendingDomains.isEmpty()) return emptyList()
        val out = pendingDomains.toList()
        pendingDomains.clear()
        out
    }

    private fun queueBlocked(attempt: BlockedAttempt) {
        synchronized(blockedLock) {
            if (pendingBlocked.size >= MAX_PENDING_BLOCKED) return
            pendingBlocked += attempt
        }
    }

    private fun drainBlocked(): List<BlockedAttempt> = synchronized(blockedLock) {
        if (pendingBlocked.isEmpty()) return emptyList()
        val out = pendingBlocked.toList()
        pendingBlocked.clear()
        out
    }

    private suspend fun tick(now: Long) {
        val sample = api.sample(now)
        val flows = VpnBridge.table
        // The service stamps every new flow with this label but had no writer,
        // so `network` was always empty: the Conns detail said "-", the CSV
        // column exported blank, and the wlan0 / tun0 filter chips compared
        // against "" and so emptied the table whichever one was picked.
        VpnBridge.currentNetworkLabel = LinkChoice.underlyingLabel(sample.networks)
        // Drained before the snapshot is built, not after: the counter is read
        // by buildApps, and a drain on the far side would always render the
        // figure one tick stale — including the first one, which is the tick
        // the user is watching when they press Block.
        val blocked = drainBlocked().map { a ->
            if (a.uid == INVALID_UID) a else a.copy(appLabel = catalog.row(a.uid).label)
        }
        for (a in blocked) blockedCounts[a.uid] = (blockedCounts[a.uid] ?: 0L) + 1L
        assembler.setBlockedAttempts(blockedCounts)
        val snap = assembler.assemble(
            now = now,
            api = sample,
            flows = flows,
            settings = settings,
            baseline = baseline,
            watchlist = watchlist,
            // Off hides the address already fetched, not just the next fetch:
            // a figure that is no longer refreshed would age on screen while
            // the user believes the lookup is switched off.
            externalIp = if (settings.externalIpEnabled) externalIp.value else null,
            externalIpAt = externalIp.fetchedAt,
            latency = latency,
            scanRunning = _scanning.value,
            neighbourScanRunning = _neighbourScanning.value,
        )
        _snapshot.value = snap

        // Where a new connection would leave by, for per-app network policy.
        // The default link while capturing is our own TUN, so this reads the
        // link underneath it, as the flow labels do.
        val under = LinkChoice.underlying(snap.networks)
        VpnBridge.linkContext = LinkContext(
            mobile = under?.transport == Transport.CELLULAR,
            metered = under?.metered == true,
            untrusted = snap.untrustedDefault,
            screenOff = !interactive,
        )

        if (now - widgetAt >= WIDGET_INTERVAL_MS) {
            widgetAt = now
            runCatching { RatesWidget.render(context, snap, settings.rateUnit) }
        }

        minuteAcc.add(now, snap.total.rxBps, snap.total.txBps)?.let { closed ->
            runCatching { recordMinute(closed, now) }
        }

        if (flows != null) {
            val closed = flows.closedSnapshot()
            if (closed.isNotEmpty()) closedRepo.record(closed)
        }

        val prev = prevSnapshot
        val produced = ArrayList<Event>()
        produced += eventEngine.diff(prev, snap, baseline, watchlist)
        // Hosts the engine has just reported on for the first time go into the
        // persistent store, so the next process start seeds its dedupe from
        // them instead of replaying every known host as first contact.
        for (host in eventEngine.lastNewHosts) hostSeen.isNew(host, snap.atMillis)
        // Names are not on the snapshot, so they are a second source with its
        // own producer. The two cannot double-report: this one is keyed on a
        // name and the engine's new_public_host on an address.
        //
        // The store keeps a uid, not a label — an app can rename itself, so the
        // name is resolved on read rather than frozen into the database. That
        // leaves the rows it hands back unlabelled, and a log line reading
        // "first contact with ads.example.com" without saying which app asked
        // is most of the value missing.
        val freshDomains = runCatching { domains.record(drainDomains()) }
            .getOrDefault(emptyList())
            .map { row ->
                val app = catalog.row(row.uid)
                row.copy(appLabel = app.label, packageName = app.packageName)
            }
        produced += DomainEvents.of(freshDomains, watchlist, now)
        produced += blockedEvents.of(blocked, now)
        produced += alertRules.evaluate(snap, settings, now)
        produced += findingEvents.of(snap, settings, now, cycleUsedByPackage, cycleStartMillis)
        record(produced)
        prevSnapshot = snap
    }

    /** Record an event produced outside the poll tick (automation, boot). */
    fun log(event: Event) {
        scope.launch { runCatching { record(listOf(event)) } }
    }

    /**
     * Persist a batch and deliver anything above INFO. Shared by the poll tick
     * and by [watchEngineLifecycle], which produces outside a tick: a capture
     * that Android revokes has to reach the user through the same path a
     * threshold breach does, and duplicating it is how one of them drifts.
     */
    private suspend fun record(produced: List<Event>) {
        if (produced.isEmpty()) return
        events.record(produced)
        for (e in produced) {
            if (e.level == EventLevel.INFO) continue
            Notifications.notifyEvent(context, e)
            AlertDelivery.deliver(context, settings, e)
        }
    }

    /**
     * Capture going up and down is a change in what the rest of the log can
     * even see, so it belongs in the log.
     *
     * Revocation is the reason this is not optional. Android permits one VPN
     * at a time, so another app connecting takes the slot and this one's
     * capture simply ends — no error, no tick, nothing on any screen but a
     * connection table that quietly stops growing. [EventKind.ENGINE_REVOKED]
     * existed for it and had no producer, which made the one externally caused
     * failure the app cannot prevent also the one it never mentioned.
     *
     * One collector rather than two, because `reportRevoked` sets the revoked
     * flag *before* detaching: by the time `running` goes false the reason is
     * already known, and collecting both flows would report a revocation as a
     * revocation and a stop. The first emission is the state at subscribe
     * time, not a transition, so it seeds instead of reporting — the same rule
     * the snapshot diff follows for its first snapshot.
     */
    private fun watchEngineLifecycle() {
        if (lifecycleJob != null) return
        lifecycleJob = scope.launch {
            var reported: Boolean? = null
            VpnBridge.running.collect { running ->
                val was = reported
                reported = running
                runCatching { RatesWidget.render(context, _snapshot.value, settings.rateUnit) }
                if (was == null || was == running) return@collect
                val now = System.currentTimeMillis()
                val event = when {
                    running -> Event(
                        at = now,
                        level = EventLevel.INFO,
                        kind = EventKind.ENGINE_STARTED,
                        subject = "capture",
                        detail = "per-connection accounting is live",
                    )

                    VpnBridge.revoked.value -> Event(
                        at = now,
                        level = EventLevel.WARN,
                        kind = EventKind.ENGINE_REVOKED,
                        subject = "capture",
                        detail = "Android revoked the VPN \u2014 another app took the " +
                            "single VPN slot; the engine has fallen back to API mode",
                    )

                    else -> Event(
                        at = now,
                        level = EventLevel.INFO,
                        kind = EventKind.ENGINE_STOPPED,
                        subject = "capture",
                        detail = "back to API mode",
                    )
                }
                runCatching { record(listOf(event)) }
            }
        }
    }

    private suspend fun refreshLatency() {
        val gateway = _snapshot.value.networks.firstOrNull { it.isDefault }?.gateway
        latency = runCatching { prober.probeAll(settings.latencyTargets, gateway) }
            .getOrDefault(emptyList())
    }

    private suspend fun refreshUsage() {
        if (!usage.hasAccess()) return
        // The result used to be dropped on the floor here, which is why every
        // app's "today" figure was 0 B however much it had moved.
        runCatching { usage.todayPerUid() }
            .onSuccess { assembler.setTodayUsage(it) }
        runCatching { usage.todayBackgroundPerUid() }
            .onSuccess { assembler.setBackgroundUsage(it) }
        refreshQuota()
        refreshAppBudgets()
    }

    /**
     * The billing cycle, on the same slow cadence as the usage query that
     * sits beside it — both are `NetworkStatsManager` round trips, and
     * neither answer changes meaningfully between two polls.
     *
     * The cycle is recomputed rather than cached, so the status is correct on
     * the day it rolls over without anything having to notice the date
     * changed.
     */
    private suspend fun refreshQuota() {
        val s = settings
        if (s.dataCapBytes <= 0L) {
            assembler.setQuota(QuotaStatus())
            return
        }
        val today = LocalDate.now()
        val start = Quota.cycleStart(today, s.cycleStartDay)
        val used = runCatching {
            usage.cycleTotal(usage.startOfDay(start.toEpochDay()), s.quotaMobileOnly)
        }.getOrNull() ?: return
        assembler.setQuota(
            Quota.status(
                today = today,
                startDay = s.cycleStartDay,
                capBytes = s.dataCapBytes,
                usedBytes = used,
                mobileOnly = s.quotaMobileOnly,
            )
        )
    }

    /**
     * Each budgeted app's usage this cycle, and the uids a blocking budget
     * now refuses. The cycle is the device budget's — one reset day for every
     * allowance on the phone, which is how carriers bill.
     */
    private suspend fun refreshAppBudgets() {
        val s = settings
        val budgets = AppBudgets.decode(s.appBudgets)
        if (budgets.isEmpty()) {
            if (overBudgetUids.isNotEmpty()) {
                overBudgetUids = emptySet()
                recompileFirewall()
            }
            cycleUsedByPackage = emptyMap()
            return
        }
        val start = usage.startOfDay(Quota.cycleStart(LocalDate.now(), s.cycleStartDay).toEpochDay())
        val byUid = runCatching { usage.perUidSince(start, s.quotaMobileOnly) }.getOrNull() ?: return
        val byPackage = HashMap<String, Long>()
        for ((uid, bytes) in byUid) {
            val pkg = catalog.row(uid).packageName
            if (pkg.isNotEmpty()) byPackage[pkg] = (byPackage[pkg] ?: 0L) + bytes
        }
        cycleUsedByPackage = byPackage
        cycleStartMillis = start
        val uidByPackage = catalog.all().associate { it.packageName to it.uid }
        val over = AppBudgets.breaches(budgets, byPackage)
            .filter { it.first.block }
            .mapNotNull { uidByPackage[it.first.packageName] }
            .toSet()
        if (over != overBudgetUids) {
            overBudgetUids = over
            recompileFirewall()
        }
    }

    fun scanServices(onDone: (List<ServiceRow>) -> Unit) {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            _scanning.value = true
            try {
                val addrs = _snapshot.value.networks
                    .flatMap { it.addresses }
                    .map { it.substringBefore('/') }
                    .filter { !it.startsWith("fe80") }
                    .distinct()
                // This app's own metrics endpoint is a listener the scan does
                // not have to guess at, and the common-port window does not
                // cover a port the user has moved. See [PortScanner.ports].
                val own = if (settings.metricsEnabled) listOf(settings.metricsPort) else emptyList()
                val rows = withContext(Dispatchers.IO) {
                    scanner.scan(addrs, settings.scanFullRange, own)
                }
                val at = System.currentTimeMillis()
                assembler.setServices(rows, at)
                onDone(rows)
            } finally {
                _scanning.value = false
            }
        }
    }

    fun currentServices(): List<ServiceRow> = _snapshot.value.services

    /**
     * Sweep the default link's own IPv4 prefix for neighbours.
     *
     * On demand only, never on the poll tick: this is the one source in the
     * app that puts packets on the wire to hosts the user did not name, and a
     * background sweep of someone's office network every two seconds is not a
     * thing a monitor should do without being asked.
     */
    fun scanNeighbours(onDone: (List<NeighbourRow>, String) -> Unit = { _, _ -> }) {
        if (neighbourJob?.isActive == true) return
        neighbourJob = scope.launch {
            _neighbourScanning.value = true
            try {
                val snap = _snapshot.value
                val link = LinkChoice.sweepLink(snap.networks)
                val subnet = link?.addresses?.let(Subnet::firstV4)
                val hasV6 = link?.addresses?.any { it.contains(':') } == true
                if (subnet == null && !hasV6) {
                    // An IPv6-only link, or one with nothing assigned. The
                    // screen explains it; recording the empty result is what
                    // lets it tell that apart from "never scanned".
                    assembler.setNeighbours(emptyList(), System.currentTimeMillis(), "")
                    onDone(emptyList(), "")
                    return@launch
                }
                val selfAddresses = snap.networks
                    .flatMap { it.addresses }
                    .map { it.substringBefore('/') }
                    .toSet()
                val rows = withContext(Dispatchers.IO) {
                    neighbours.scan(
                        subnet = subnet ?: "",
                        gateway = link?.gateway,
                        selfAddresses = selfAddresses,
                        ifaceName = link?.ifaceName?.takeIf { hasV6 },
                    )
                }
                val label = listOfNotNull(subnet, if (hasV6) "${NeighbourScanner.ALL_NODES} on ${link?.ifaceName}" else null)
                    .joinToString(" + ")
                assembler.setNeighbours(rows, System.currentTimeMillis(), label)
                onDone(rows, label)
            } finally {
                _neighbourScanning.value = false
            }
        }
    }

    private fun deviceName(): String =
        listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "this device" }

    companion object {
        /** One busy browser tab's worth of names; past this a tick is dropping. */
        private const val MAX_PENDING_DOMAINS = 512

        /**
         * A blocked app retries without pause, so this bound is reached far
         * more easily than the names one. The count on the Apps row is
         * capped with it and that is the honest answer: past this many
         * refusals in one interval the number is "a great many", and holding
         * every one of them to be exact would cost more than the fact is
         * worth.
         */
        private const val MAX_PENDING_BLOCKED = 512

        /** `Process.INVALID_UID`, kept here so the domain stays Android-free. */
        private const val INVALID_UID = -1
        private const val LATENCY_INTERVAL_MS = 15_000L
        private const val USAGE_INTERVAL_MS = 120_000L

        /** A widget redraw is an IPC to the launcher; the shade gets 5 s, this 10. */
        private const val WIDGET_INTERVAL_MS = 10_000L
        val BOOT_ELAPSED: Long get() = SystemClock.elapsedRealtime()
    }
}
