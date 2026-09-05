package com.vlad.ducknetview.data

import com.vlad.ducknetview.data.db.ClosedConnDao
import com.vlad.ducknetview.data.db.EventDao
import com.vlad.ducknetview.data.db.HostSeenDao
import com.vlad.ducknetview.data.db.HostSeenEntity
import com.vlad.ducknetview.data.db.UsageDao
import com.vlad.ducknetview.data.db.toDomain
import com.vlad.ducknetview.data.db.toEntity
import com.vlad.ducknetview.domain.export.Csv
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.usage.DailyUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The event log. The TUI's 500-entry ring becomes "500 rendered, everything
 * stored, pruned at 30 days" — a phone keeps history across process death, so
 * the cap moves from memory to time.
 */
class EventRepository(
    private val dao: EventDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {

    val recent: Flow<List<Event>> = dao.recent(RECENT_LIMIT).map { rows -> rows.map { it.toDomain() } }

    /** Fire-and-forget: the poll tick must not wait on disk. */
    fun record(events: List<Event>) {
        if (events.isEmpty()) return
        scope.launch {
            dao.insert(events.map { it.toEntity() })
            dao.pruneOlderThan(now() - RETENTION_MILLIS)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun unackedAlertCount(
        ackedAt: Flow<Long>,
        levels: List<String> = ATTENTION_LEVELS,
    ): Flow<Int> = ackedAt.flatMapLatest { dao.countSince(it, levels) }

    suspend fun clear() = dao.clear()

    suspend fun exportCsv(): String = Csv.events(dao.allForExport().map { it.toDomain() })

    companion object {
        const val RECENT_LIMIT = 500
        const val RETENTION_DAYS = 30
        const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L

        /** What the nav badge counts: anything above plain informational. */
        val ATTENTION_LEVELS: List<String> = listOf(EventLevel.WARN.name, EventLevel.ALERT.name)
    }
}

/** Closed-connection history, bounded to the TUI's last-200 window. */
class ClosedConnRepository(
    private val dao: ClosedConnDao,
    private val scope: CoroutineScope,
) {

    val recent: Flow<List<ClosedConn>> = dao.recent(LIMIT).map { rows -> rows.map { it.toDomain() } }

    fun record(list: List<ClosedConn>) {
        if (list.isEmpty()) return
        scope.launch {
            dao.insert(list.map { it.toEntity() })
            dao.trimTo(LIMIT)
        }
    }

    suspend fun clear() = dao.clear()

    companion object {
        const val LIMIT = 200
    }
}

/** Daily usage rollup: 40 days deep, top-50 apps and hosts per day. */
class UsageRepository(private val dao: UsageDao) {

    val all: Flow<List<DailyUsage>> = dao.all().map { rows -> rows.map { it.toDomain() } }

    private val lock = Mutex()

    /**
     * Folds a tick's usage into the stored row for its day. Held under a mutex
     * because read-modify-write from several collectors would otherwise lose
     * increments.
     */
    suspend fun mergeToday(day: DailyUsage) {
        lock.withLock {
            val existing = dao.byDay(day.dayEpoch)?.toDomain()
            dao.upsert(merge(existing, day).toEntity())
            dao.prune(KEEP_DAYS)
        }
    }

    /**
     * Overwrites the stored row for [day] instead of folding into it.
     *
     * [mergeToday] is additive because the live engine hands it one tick's
     * delta. The background rollup reads NetworkStatsManager, which reports the
     * day's *cumulative* totals, so a second run in the same day carries the
     * first run's bytes again — adding them would double-count. Replacing makes
     * the rollup idempotent without changing what mergeToday means.
     */
    suspend fun replaceDay(day: DailyUsage) {
        lock.withLock {
            dao.upsert(capped(day).toEntity())
            dao.prune(KEEP_DAYS)
        }
    }

    suspend fun lastDays(n: Int): List<DailyUsage> = dao.lastDays(n).map { it.toDomain() }

    companion object {
        const val KEEP_DAYS = 40
        const val TOP_N = 50

        /**
         * Mirrors UsageRollup.merge: counters add, per-key maps add per key, and
         * each map is capped at the heaviest [TOP_N] keys so one day's row cannot
         * grow without bound on a device that talks to thousands of hosts.
         */
        fun merge(existing: DailyUsage?, incoming: DailyUsage): DailyUsage {
            if (existing == null) {
                return DailyUsage(
                    dayEpoch = incoming.dayEpoch,
                    rx = incoming.rx,
                    tx = incoming.tx,
                    apps = topN(incoming.apps),
                    hosts = topN(incoming.hosts),
                )
            }
            return DailyUsage(
                dayEpoch = incoming.dayEpoch,
                rx = existing.rx + incoming.rx,
                tx = existing.tx + incoming.tx,
                apps = topN(sum(existing.apps, incoming.apps)),
                hosts = topN(sum(existing.hosts, incoming.hosts)),
            )
        }

        /** The per-day [TOP_N] cap applied on its own, with nothing merged in. */
        fun capped(day: DailyUsage): DailyUsage =
            day.copy(apps = topN(day.apps), hosts = topN(day.hosts))

        private fun sum(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
            val out = LinkedHashMap<String, Long>(a)
            for ((k, v) in b) out[k] = (out[k] ?: 0L) + v
            return out
        }

        private fun topN(m: Map<String, Long>): Map<String, Long> {
            if (m.size <= TOP_N) return m
            return m.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .take(TOP_N)
                .associate { it.key to it.value }
        }
    }
}

/**
 * Remembers which public hosts have already been reported, so "first contact
 * with a new host" survives the foreground service being killed and restarted.
 */
class HostSeenRepository(private val dao: HostSeenDao) {

    private val lock = Mutex()

    /** Records the sighting and reports whether this host had never been seen. */
    suspend fun isNew(host: String, now: Long): Boolean = lock.withLock {
        if (host.isEmpty()) return@withLock false
        val prev = dao.seen(host)
        dao.upsert(HostSeenEntity(host = host, firstSeen = prev?.firstSeen ?: now, lastSeen = now))
        if (prev == null) dao.prune(MAX_HOSTS)
        prev == null
    }

    suspend fun known(): List<String> = dao.allHosts()

    companion object {
        const val MAX_HOSTS = 4096
    }
}
