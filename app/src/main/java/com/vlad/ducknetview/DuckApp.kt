package com.vlad.ducknetview

import android.app.Application
import com.vlad.ducknetview.data.ClosedConnRepository
import com.vlad.ducknetview.data.DomainRepository
import com.vlad.ducknetview.data.EventRepository
import com.vlad.ducknetview.data.Exporter
import com.vlad.ducknetview.data.HostSeenRepository
import com.vlad.ducknetview.data.UsageRepository
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.data.settings.SettingsRepository
import com.vlad.ducknetview.data.work.WorkScheduler
import com.vlad.ducknetview.domain.rates.RateTracker
import com.vlad.ducknetview.engine.EngineController
import com.vlad.ducknetview.engine.api.ApiEngine
import com.vlad.ducknetview.engine.api.AppCatalog
import com.vlad.ducknetview.engine.api.ExternalIpFetcher
import com.vlad.ducknetview.engine.api.IcmpEchoProbe
import com.vlad.ducknetview.engine.api.LatencyProber
import com.vlad.ducknetview.engine.api.PortScanner
import com.vlad.ducknetview.engine.api.UsageHistorySource
import com.vlad.ducknetview.service.MetricsServer
import com.vlad.ducknetview.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DuckApp : Application() {

    lateinit var deps: Deps
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        deps = Deps(this)
    }

    /**
     * A hand-wired dependency graph. The app has one object graph and one
     * lifetime, which a DI framework would only obscure.
     */
    class Deps(app: Application) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = DuckDatabase.build(app)
        val settings = SettingsRepository(app)
        val rates = RateTracker()
        val catalog = AppCatalog(app)
        val api = ApiEngine(app, rates)
        // ICMP echo where the kernel permits it, a TCP handshake where it does
        // not; the prober decides per probe and labels which it used.
        val prober = LatencyProber(echo = IcmpEchoProbe())
        val externalIp = ExternalIpFetcher()
        val scanner = PortScanner()
        val usage = UsageHistorySource(app)
        val exporter = Exporter(app)

        val eventRepo = EventRepository(db.events(), scope)
        val closedRepo = ClosedConnRepository(db.closedConns(), scope)
        val usageRepo = UsageRepository(db.usage())
        val hostSeenRepo = HostSeenRepository(db.hostsSeen())
        val domainRepo = DomainRepository(db.domains())

        val engine = EngineController(
            context = app,
            scope = scope,
            api = api,
            catalog = catalog,
            prober = prober,
            externalIp = externalIp,
            scanner = scanner,
            usage = usage,
            events = eventRepo,
            closedRepo = closedRepo,
            hostSeen = hostSeenRepo,
            domains = domainRepo,
            rates = rates,
        )

        val metricsServer = MetricsServer { engine.snapshot.value }

        /**
         * Owns the metrics endpoint's lifetime. Collection is sequential and
         * stop() closes the listening socket before it returns, so toggling the
         * switch quickly can never leave a socket bound behind a newer one; a
         * port change arrives here as one emission and is applied as a restart.
         */
        val metricsControl: Job = scope.launch {
            settings.settings
                .map { it.metricsEnabled to it.metricsPort }
                .distinctUntilChanged()
                .collect { (enabled, port) ->
                    metricsServer.stop()
                    if (enabled) metricsServer.start(port)
                }
        }

        /**
         * Keeps the background jobs in step with settings, on the app-lifetime
         * scope so it dies with the process and nothing else. It re-schedules
         * only when the one input the schedule depends on changes — whether a
         * baseline exists — so ordinary settings writes do not churn
         * WorkManager's database. The flow's first emission is the startup
         * schedule, and it uses what is actually on disk rather than the
         * defaults a synchronous call at onCreate would have to guess with.
         */
        val workScheduling: Job = scope.launch {
            settings.settings
                .distinctUntilChangedBy { WorkScheduler.scanEnabled(it) }
                .collect { WorkScheduler.schedule(app, it) }
        }
    }
}
