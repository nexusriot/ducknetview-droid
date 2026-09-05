package com.vlad.ducknetview.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.vlad.ducknetview.domain.model.AppSettings
import java.util.concurrent.TimeUnit

/**
 * The two jobs that make the app useful while it is closed.
 *
 * Unique names plus [ExistingPeriodicWorkPolicy.UPDATE] mean re-scheduling is
 * idempotent: calling [schedule] on every settings change replaces the request
 * in place rather than stacking a second copy of the job.
 */
object WorkScheduler {

    const val SERVICE_SCAN_WORK = "ducknetview.service-scan"
    const val USAGE_ROLLUP_WORK = "ducknetview.usage-rollup"

    const val SCAN_INTERVAL_HOURS = 6L
    const val ROLLUP_INTERVAL_HOURS = 12L

    fun schedule(context: Context, settings: AppSettings) {
        val manager = manager(context) ?: return
        try {
            if (scanEnabled(settings)) {
                manager.enqueueUniquePeriodicWork(
                    SERVICE_SCAN_WORK,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    scanRequest(),
                )
            } else {
                manager.cancelUniqueWork(SERVICE_SCAN_WORK)
            }
            manager.enqueueUniquePeriodicWork(
                USAGE_ROLLUP_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                rollupRequest(),
            )
        } catch (e: Exception) {
            // Scheduling is best-effort; a WorkManager that refuses to enqueue
            // must not take the app down on startup.
        }
    }

    fun cancel(context: Context) {
        val manager = manager(context) ?: return
        try {
            manager.cancelUniqueWork(SERVICE_SCAN_WORK)
            manager.cancelUniqueWork(USAGE_ROLLUP_WORK)
        } catch (e: Exception) {
            // already gone, or no WorkManager to cancel against
        }
    }

    /**
     * A scan with no saved baseline can only ever conclude "nothing to compare
     * against", so scheduling one would burn the radio and the battery to
     * produce no output.
     */
    fun scanEnabled(settings: AppSettings): Boolean = settings.baseline.isNotEmpty()

    fun scanRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<ServiceScanWorker>(SCAN_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

    /** No network constraint: NetworkStatsManager is a local ledger. */
    fun rollupRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UsageRollupWorker>(ROLLUP_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.NONE)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

    /** Throws when the app-startup initializer has not run; that is not fatal here. */
    private fun manager(context: Context): WorkManager? = try {
        WorkManager.getInstance(context.applicationContext)
    } catch (e: Exception) {
        null
    }
}
