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
import com.vlad.ducknetview.data.EventRepository
import com.vlad.ducknetview.data.HostSeenRepository
import com.vlad.ducknetview.domain.alerts.AlertRules
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.events.EventEngine
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.rates.RateTracker
import com.vlad.ducknetview.domain.rdns.RdnsCache
import com.vlad.ducknetview.domain.totals.SessionTotals
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.engine.api.ApiEngine
import com.vlad.ducknetview.engine.api.AppCatalog
import com.vlad.ducknetview.engine.api.ExternalIpFetcher
import com.vlad.ducknetview.engine.api.LatencyProber
import com.vlad.ducknetview.engine.api.PortScanner
import com.vlad.ducknetview.engine.api.UsageHistorySource
import com.vlad.ducknetview.engine.vpn.VpnBridge
import com.vlad.ducknetview.service.AlertDelivery
import com.vlad.ducknetview.service.Notifications
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
    private val usage: UsageHistorySource,
    private val events: EventRepository,
    private val closedRepo: ClosedConnRepository,
    private val hostSeen: HostSeenRepository,
    private val rates: RateTracker,
) {
    private val totals = SessionTotals()
    private val rdns = RdnsCache()
    private val assembler = SnapshotAssembler(catalog, rates, totals, rdns)
    private val eventEngine = EventEngine()
    private val alertRules = AlertRules()

    private val _snapshot = MutableStateFlow(NetSnapshot())
    val snapshot: StateFlow<NetSnapshot> = _snapshot.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

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

    /** Conflated so a burst of wakeups costs one early tick, not a queue of them. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

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
        loop = scope.launch { pollLoop() }
    }

    fun stop() {
        loop?.cancel()
        loop = null
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
        baseline = Baseline(s.baseline.toSet(), s.baselineAt)
        watchlist = Watchlist(s.watchlist)
        VpnBridge.setBlocked(s.blockedUids)
        VpnBridge.excludedPackages = s.excludedUids
            .mapNotNull { catalog.row(it).packageName.takeIf { p -> p.isNotEmpty() } }
            .toSet()
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

    private suspend fun tick(now: Long) {
        val sample = api.sample(now)
        val flows = VpnBridge.table
        val snap = assembler.assemble(
            now = now,
            api = sample,
            flows = flows,
            settings = settings,
            baseline = baseline,
            watchlist = watchlist,
            externalIp = externalIp.value,
            externalIpAt = externalIp.fetchedAt,
            latency = latency,
            scanRunning = _scanning.value,
        )
        _snapshot.value = snap

        if (flows != null) {
            val closed = flows.closedSnapshot()
            if (closed.isNotEmpty()) closedRepo.record(closed)
        }

        val prev = prevSnapshot
        val produced = ArrayList<Event>()
        produced += eventEngine.diff(prev, snap, baseline, watchlist)
        produced += alertRules.evaluate(snap, settings, now)
        produced += firstContactEvents(snap)
        if (produced.isNotEmpty()) {
            events.record(produced)
            for (e in produced) {
                if (e.level != EventLevel.INFO) {
                    Notifications.notifyEvent(context, e)
                    AlertDelivery.deliver(context, settings, e)
                }
            }
        }
        prevSnapshot = snap
    }

    /**
     * The first connection to each public host is worth one line in the log.
     * Persisted rather than in-memory so a process restart does not replay
     * every existing host as brand new.
     */
    private suspend fun firstContactEvents(snap: NetSnapshot): List<Event> {
        val out = ArrayList<Event>()
        var emitted = 0
        for (c in snap.conns) {
            if (c.scope != com.vlad.ducknetview.domain.model.Scope.PUBLIC) continue
            if (emitted >= FIRST_CONTACT_CAP) break
            if (hostSeen.isNew(c.remoteAddr, snap.atMillis)) {
                emitted++
                out += Event(
                    at = snap.atMillis,
                    level = EventLevel.INFO,
                    kind = EventKind.NEW_PUBLIC_HOST,
                    subject = c.remoteDisplay(true),
                    detail = "first contact by ${c.appLabel.ifEmpty { "uid ${c.uid}" }}",
                )
            }
        }
        return out
    }

    private suspend fun refreshLatency() {
        val gateway = _snapshot.value.networks.firstOrNull { it.isDefault }?.gateway
        latency = runCatching { prober.probeAll(settings.latencyTargets, gateway) }
            .getOrDefault(emptyList())
    }

    private suspend fun refreshUsage() {
        if (!usage.hasAccess()) return
        runCatching { usage.todayPerUid() }
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
                val rows = withContext(Dispatchers.IO) {
                    scanner.scan(addrs, settings.scanFullRange)
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

    private fun deviceName(): String =
        listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "this device" }

    companion object {
        private const val LATENCY_INTERVAL_MS = 15_000L
        private const val USAGE_INTERVAL_MS = 120_000L
        private const val FIRST_CONTACT_CAP = 20
        val BOOT_ELAPSED: Long get() = SystemClock.elapsedRealtime()
    }
}
