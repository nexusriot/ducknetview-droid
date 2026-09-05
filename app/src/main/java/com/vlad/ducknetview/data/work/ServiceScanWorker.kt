package com.vlad.ducknetview.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vlad.ducknetview.data.EventRepository
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.data.settings.SettingsRepository
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.engine.api.PortScanner
import com.vlad.ducknetview.service.AlertDelivery
import com.vlad.ducknetview.service.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

/**
 * The self-scan that runs while nobody is looking at the app — ducknetview's
 * `--check-baseline` in cron, moved onto the phone's scheduler.
 *
 * The whole decision is in [ServiceScanLogic]; everything here is the plumbing
 * that reads settings, knocks on the ports, and delivers what came out.
 */
class ServiceScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = try {
            SettingsRepository(applicationContext).snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.success()
        }

        // No baseline is the TUI's exit code 2: there is nothing to compare
        // against, so there is no finding to report. WorkScheduler also cancels
        // the job in this state; this covers the window before the cancel lands.
        if (settings.baseline.isEmpty()) return Result.success()

        val scanned = try {
            PortScanner().scan(
                localAddrs = LocalAddresses.of(applicationContext),
                full = settings.scanFullRange,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Individual probes already swallow their own failures, so reaching
            // here means the scan as a whole could not run — genuinely transient.
            return Result.retry()
        }

        val baseline = Baseline.of(settings.baseline, settings.baselineAt)
        val throttle = AlertThrottleStore(applicationContext)
        val now = System.currentTimeMillis()
        val rows = ServiceScanLogic.offBaselineRows(scanned, baseline, throttle.lastAlerted(), now)
        if (rows.isEmpty()) return Result.success()

        val events = rows.map { ServiceScanLogic.alertFor(it, now) }
        val dao = DuckDatabase.build(applicationContext).events()
        // EventRepository.record() is fire-and-forget; coroutineScope is what
        // makes the insert finish before the worker releases its wakelock.
        coroutineScope { EventRepository(dao, this).record(events) }

        for (event in events) {
            Notifications.notifyEvent(applicationContext, event)
            // The webhook POST inside deliver() is fire-and-forget by contract;
            // it gets whatever time the process has left.
            AlertDelivery.deliver(applicationContext, settings, event)
        }
        throttle.record(rows.map { it.baselineKey }, now)
        return Result.success()
    }
}

/**
 * Which scanned listeners deserve an alert right now. Pure and injectable so
 * the rule can be tested without a WorkManager, a scanner or a clock.
 */
object ServiceScanLogic {

    /**
     * A listener that stays open is one finding, not one finding per scan. Six
     * hours is long enough that a persistent service is reported roughly once a
     * quarter-day and short enough that the user is reminded it is still there.
     */
    const val RATE_LIMIT_MILLIS = 6L * 60L * 60L * 1000L

    /**
     * The off-baseline listeners that are not still inside their rate-limit
     * window. UDP ephemeral ports never appear here: [Baseline.isOffBaseline]
     * defers to `baselineEligible`, without which an outbound DNS socket would
     * look like a brand-new service on every single run.
     */
    fun offBaselineRows(
        scanned: List<ServiceRow>,
        baseline: Baseline,
        lastAlertedAt: Map<String, Long>,
        now: Long,
    ): List<ServiceRow> {
        if (baseline.isEmpty) return emptyList()
        val out = ArrayList<ServiceRow>()
        val emitted = HashSet<String>()
        for (row in scanned) {
            if (!baseline.isOffBaseline(row)) continue
            val key = row.baselineKey
            if (!emitted.add(key)) continue
            if (suppressed(lastAlertedAt[key], now)) continue
            out.add(row)
        }
        return out
    }

    fun offBaselineAlerts(
        scanned: List<ServiceRow>,
        baseline: Baseline,
        lastAlertedAt: Map<String, Long>,
        now: Long,
    ): List<Event> = offBaselineRows(scanned, baseline, lastAlertedAt, now).map { alertFor(it, now) }

    /** Worded exactly like the live EventEngine's, so the log reads as one stream. */
    fun alertFor(row: ServiceRow, now: Long): Event = Event(
        at = now,
        level = EventLevel.ALERT,
        kind = EventKind.OFF_BASELINE,
        subject = "${row.proto} ${row.bindAddr}:${row.port}",
        detail = listOfNotNull(
            "not in the saved baseline",
            row.exposure.name.lowercase(),
            "background scan",
            row.appLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · "),
    )

    /**
     * A stamp in the future means the clock moved backwards; alerting again is
     * the safe reading of that, because the alternative is silence for however
     * far ahead the old stamp sits.
     */
    private fun suppressed(lastAt: Long?, now: Long): Boolean {
        if (lastAt == null) return false
        if (now < lastAt) return false
        return now - lastAt < RATE_LIMIT_MILLIS
    }
}
