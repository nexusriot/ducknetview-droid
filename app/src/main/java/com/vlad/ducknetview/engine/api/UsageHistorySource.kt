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
                dayEpoch = epochDayOf(starts[i]),
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
            DailyUsage(
                dayEpoch = epochDayOf(start),
                rx = rx,
                tx = tx,
                apps = emptyMap(),
                hosts = emptyMap(),
            )
        }
    }

    /**
     * Bytes used between [startMillis] and now, for the budget card.
     *
     * Queried live rather than summed out of the daily rollup, for two
     * reasons. `NetworkStatsManager` reports cumulative totals over any range
     * in one call, so this is the authoritative figure rather than a
     * reconstruction; and the rollup stores one total per day across every
     * transport, which cannot answer a cap written against mobile data —
     * which is what essentially every cap is written against.
     *
     * Returns null when the permission is missing, which the card renders as
     * "needs Usage Access" rather than as a budget of zero.
     */
    suspend fun cycleTotal(
        startMillis: Long,
        mobileOnly: Boolean,
    ): Long? = withContext(Dispatchers.IO) {
        val manager = nsm ?: return@withContext null
        val now = System.currentTimeMillis()
        if (startMillis >= now) return@withContext 0L
        @Suppress("DEPRECATION")
        val types = if (mobileOnly) {
            intArrayOf(ConnectivityManager.TYPE_MOBILE)
        } else {
            NETWORK_TYPES
        }
        var total = 0L
        var answered = false
        for (type in types) {
            val bucket = deviceSummary(manager, type, startMillis, now) ?: continue
            answered = true
            total += bucket.rxBytes + bucket.txBytes
        }
        if (answered) total else null
    }

    /** Local midnight of the day number [day], for a cycle-start query. */
    /**
     * Today's bytes per uid moved while the app was *not* in the foreground.
     *
     * `querySummary` keeps the per-state split (`STATE_FOREGROUND` vs
     * `STATE_DEFAULT`), which is what answers "which app used data while I
     * wasn't using it" without capture. A uid absent from the answer moved
     * nothing in the background, which is different from the platform giving
     * no split at all — that case returns an empty map and the screen hides
     * the column.
     */
    suspend fun todayBackgroundPerUid(): Map<Int, Long> = withContext(Dispatchers.IO) {
        val manager = nsm ?: return@withContext emptyMap()
        if (!hasAccess()) return@withContext emptyMap()
        val start = startOfToday()
        val end = System.currentTimeMillis()
        val out = HashMap<Int, Long>()
        var sawForeground = false
        for (type in NETWORK_TYPES) {
            val stats = summary(manager, type, start, end) ?: continue
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    out.putIfAbsent(bucket.uid, 0L)
                    when (bucket.state) {
                        NetworkStats.Bucket.STATE_FOREGROUND -> sawForeground = true
                        NetworkStats.Bucket.STATE_DEFAULT ->
                            out[bucket.uid] = out.getValue(bucket.uid) + bucket.rxBytes + bucket.txBytes
                    }
                }
            } catch (e: Exception) {
                // partial data beats none
            } finally {
                closeQuietly(stats)
            }
        }
        // Every bucket in STATE_ALL means the platform did not split by state,
        // and calling all of it "background" would be a fabrication.
        if (!sawForeground && out.values.all { it == 0L }) emptyMap() else out
    }

    /** Bytes per uid since [startMillis], for per-app budgets. */
    suspend fun perUidSince(startMillis: Long, mobileOnly: Boolean): Map<Int, Long> =
        withContext(Dispatchers.IO) {
            val manager = nsm ?: return@withContext emptyMap()
            if (!hasAccess()) return@withContext emptyMap()
            val now = System.currentTimeMillis()
            if (startMillis >= now) return@withContext emptyMap()
            @Suppress("DEPRECATION")
            val types = if (mobileOnly) intArrayOf(ConnectivityManager.TYPE_MOBILE) else NETWORK_TYPES
            val out = HashMap<Int, Long>()
            for (type in types) {
                val stats = summary(manager, type, startMillis, now) ?: continue
                try {
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        if (!stats.getNextBucket(bucket)) break
                        out[bucket.uid] = (out[bucket.uid] ?: 0L) + bucket.rxBytes + bucket.txBytes
                    }
                } catch (e: Exception) {
                    // partial data beats none
                } finally {
                    closeQuietly(stats)
                }
            }
            out
        }

    fun startOfDay(day: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

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

        /**
         * The day number a local-midnight timestamp names.
         *
         * NetworkStatsManager is queried in milliseconds, but [DailyUsage]
         * records a *day number*, so every millisecond boundary crossing into a
         * stored row has to come through here. Passing the query timestamp
         * straight through instead is what made the usage screen throw
         * `Invalid value for EpochDay` on the first row it tried to format.
         */
        internal fun epochDayOf(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate().toEpochDay()

        /** Today's day number, the key every rollup writes under. */
        internal fun todayEpochDay(zone: ZoneId = ZoneId.systemDefault()): Long =
            LocalDate.now(zone).toEpochDay()

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
