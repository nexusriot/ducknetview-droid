package com.vlad.ducknetview.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vlad.ducknetview.data.UsageRepository
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.domain.usage.DailyUsage
import com.vlad.ducknetview.domain.usage.UsageRollup
import com.vlad.ducknetview.engine.api.AppCatalog
import com.vlad.ducknetview.engine.api.UsageHistorySource
import kotlinx.coroutines.CancellationException

/**
 * Folds the day's traffic into the 40-day history — ducknetview's "merged into
 * usage.json on exit", except a phone app has no exit, so a periodic job stands
 * in for one.
 */
class UsageRollupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val source = UsageHistorySource(applicationContext)
        // PACKAGE_USAGE_STATS is granted in Settings, not by a runtime prompt,
        // so "not granted" is a normal steady state and must not read as failure.
        if (!source.hasAccess()) return Result.success()

        val perUid = try {
            source.todayPerUid()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry()
        }
        if (perUid.isEmpty()) return Result.success()

        val catalog = AppCatalog(applicationContext)
        val day = UsageRollupLogic.dayUsage(
            dayEpoch = UsageHistorySource.startOfToday(),
            perUid = perUid,
            label = { uid -> catalog.label(uid) },
        )

        return try {
            UsageRepository(DuckDatabase.build(applicationContext).usage()).replaceDay(day)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** The rollup's shape, with no Android and no database in it. */
object UsageRollupLogic {

    /**
     * One day's record built from `NetworkStatsManager`'s per-UID totals.
     *
     * These are cumulative day totals, not deltas: every run of the day reports
     * the same bytes again. The result is therefore written with
     * [UsageRepository.replaceDay], never merged — see that method for why the
     * additive `mergeToday` path stays as it is.
     */
    fun dayUsage(
        dayEpoch: Long,
        perUid: Map<Int, Pair<Long, Long>>,
        label: (Int) -> String,
    ): DailyUsage {
        var rx = 0L
        var tx = 0L
        val apps = LinkedHashMap<String, Long>()
        for ((uid, bytes) in perUid) {
            val uidRx = bytes.first.coerceAtLeast(0L)
            val uidTx = bytes.second.coerceAtLeast(0L)
            rx += uidRx
            tx += uidTx
            val total = uidRx + uidTx
            if (total == 0L) continue
            val name = nameFor(uid, label)
            apps[name] = (apps[name] ?: 0L) + total
        }
        return DailyUsage(
            dayEpoch = dayEpoch,
            rx = rx,
            tx = tx,
            apps = UsageRollup.mergeCounts(emptyMap(), apps, UsageRollup.TOP_N),
            // NetworkStatsManager buckets carry no remote-host attribution, so
            // there is nothing honest to put in the hosts map from this source.
            hosts = emptyMap(),
        )
    }

    private fun nameFor(uid: Int, label: (Int) -> String): String {
        val resolved = try {
            label(uid)
        } catch (e: Exception) {
            ""
        }
        return resolved.ifBlank { "uid $uid" }
    }
}
