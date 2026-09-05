package com.vlad.ducknetview.engine.api

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import com.vlad.ducknetview.domain.usage.DailyUsage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Historical per-app byte counts from `NetworkStatsManager`.
 *
 * This is the one place where Android beats the TUI outright: the platform has
 * kept a per-UID, per-day ledger since long before the app was installed, where
 * ducknetview only ever knew about the sessions it had itself observed.
 *
 * The price is PACKAGE_USAGE_STATS, a special access the user grants in
 * Settings rather than a runtime prompt, so every entry point checks
 * [hasAccess] and every query degrades to empty instead of throwing.
 */
class UsageHistorySource(context: Context) {

    private val appContext = context.applicationContext

    private val nsm = try {
        appContext.getSystemService(NetworkStatsManager::class.java)
    } catch (e: Exception) {
        null
    }

    private val appOps = try {
        appContext.getSystemService(AppOpsManager::class.java)
    } catch (e: Exception) {
        null
    }

    fun hasAccess(): Boolean {
        val ops = appOps ?: return false
        return try {
            val mode = ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** rx/tx per UID for the current local day. */
    suspend fun todayPerUid(): Map<Int, Pair<Long, Long>> = withContext(Dispatchers.IO) {
        val manager = nsm ?: return@withContext emptyMap()
        if (!hasAccess()) return@withContext emptyMap()
        val start = startOfToday()
        val end = System.currentTimeMillis()
        val out = HashMap<Int, Pair<Long, Long>>()
        for (type in NETWORK_TYPES) {
            val stats = summary(manager, type, start, end) ?: continue
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    val uid = bucket.uid
                    val prev = out[uid] ?: ZERO
                    out[uid] = Pair(prev.first + bucket.rxBytes, prev.second + bucket.txBytes)
                }
            } catch (e: Exception) {
                // a truncated bucket stream is still worth what was read
            } finally {
                closeQuietly(stats)
            }
        }
        out
    }

    /**
     * The last [days] local days for one UID, oldest first and gap-free — days
     * with no recorded traffic come back as zeros so a chart has no holes in it.
     */
    suspend fun dailyForUid(uid: Int, days: Int): List<DailyUsage> = withContext(Dispatchers.IO) {
        if (days <= 0) return@withContext emptyList()
        val manager = nsm ?: return@withContext emptyList()
        if (!hasAccess()) return@withContext emptyList()
        val starts = dayStarts(System.currentTimeMillis(), days)
        val rx = LongArray(starts.size)
        val tx = LongArray(starts.size)
        val rangeStart = starts.first()
        val rangeEnd = System.currentTimeMillis()
        for (type in NETWORK_TYPES) {
            val stats = detailsForUid(manager, type, rangeStart, rangeEnd, uid) ?: continue
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    val idx = indexOfDay(starts, bucket.startTimeStamp)
                    if (idx < 0) continue
                    rx[idx] += bucket.rxBytes
                    tx[idx] += bucket.txBytes
                }
            } catch (e: Exception) {
                // partial data beats none
            } finally {
                closeQuietly(stats)
            }
        }
        starts.indices.map { i ->
            // apps/hosts stay empty: NetworkStatsManager buckets carry neither a
            // package breakdown for a single UID nor any remote-host attribution.
            DailyUsage(
                dayEpoch = starts[i],
                rx = rx[i],
                tx = tx[i],
                apps = emptyMap(),
                hosts = emptyMap(),
            )
        }
    }

    /** rx/tx for the whole device over the last [days] days, oldest first. */
    suspend fun dailyDevice(days: Int): List<DailyUsage> = withContext(Dispatchers.IO) {
        if (days <= 0) return@withContext emptyList()
        val manager = nsm ?: return@withContext emptyList()
        val starts = dayStarts(System.currentTimeMillis(), days)
        val now = System.currentTimeMillis()
        starts.map { start ->
            val end = (start + DAY_MS).coerceAtMost(now)
            var rx = 0L
            var tx = 0L
            if (end > start) {
                for (type in NETWORK_TYPES) {
                    val bucket = deviceSummary(manager, type, start, end) ?: continue
                    rx += bucket.rxBytes
                    tx += bucket.txBytes
                }
            }
            DailyUsage(dayEpoch = start, rx = rx, tx = tx, apps = emptyMap(), hosts = emptyMap())
        }
    }

    private fun summary(
        manager: NetworkStatsManager,
        type: Int,
        start: Long,
        end: Long,
    ): NetworkStats? = try {
        manager.querySummary(type, null, start, end)
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun detailsForUid(
        manager: NetworkStatsManager,
        type: Int,
        start: Long,
        end: Long,
        uid: Int,
    ): NetworkStats? = try {
        manager.queryDetailsForUid(type, null, start, end, uid)
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun deviceSummary(
        manager: NetworkStatsManager,
        type: Int,
        start: Long,
        end: Long,
    ): NetworkStats.Bucket? = try {
        manager.querySummaryForDevice(type, null, start, end)
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun closeQuietly(stats: NetworkStats) {
        try {
            stats.close()
        } catch (e: Exception) {
            // nothing useful to do with a failing close
        }
    }

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L

        private val ZERO = Pair(0L, 0L)

        /**
         * The template-based query API is hidden; the legacy `TYPE_*` constants
         * are the only public way to name Wi-Fi and mobile stats, deprecated or
         * not. A null subscriber id keeps this out of READ_PHONE_STATE territory.
         */
        @Suppress("DEPRECATION")
        private val NETWORK_TYPES = intArrayOf(
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_MOBILE,
            ConnectivityManager.TYPE_ETHERNET,
        )

        internal fun startOfToday(zone: ZoneId = ZoneId.systemDefault()): Long =
            LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

        /** Local midnights for the last [days] days, oldest first, today last. */
        internal fun dayStarts(
            now: Long,
            days: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<Long> {
            if (days <= 0) return emptyList()
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return (days - 1 downTo 0).map { back ->
                today.minusDays(back.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }

        /** Index of the day bucket a timestamp falls in, or -1 when out of range. */
        internal fun indexOfDay(starts: List<Long>, atMillis: Long): Int {
            if (starts.isEmpty()) return -1
            var idx = -1
            for (i in starts.indices) {
                if (atMillis >= starts[i]) idx = i else break
            }
            return idx
        }
    }
}
