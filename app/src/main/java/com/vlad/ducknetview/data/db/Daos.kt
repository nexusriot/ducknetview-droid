package com.vlad.ducknetview.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(list: List<EventEntity>)

    /**
     * `id` breaks ties because several events of one poll tick share a
     * millisecond; without it the newest-first order is not deterministic.
     */
    @Query("SELECT * FROM events ORDER BY at DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE at > :at AND level IN (:levels)")
    fun countSince(at: Long, levels: List<String>): Flow<Int>

    @Query("DELETE FROM events")
    suspend fun clear()

    @Query("DELETE FROM events WHERE at < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("SELECT * FROM events ORDER BY at ASC, id ASC")
    suspend fun allForExport(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}

@Dao
interface UsageDao {

    @Upsert
    suspend fun upsert(e: DailyUsageEntity)

    @Query("SELECT * FROM daily_usage ORDER BY dayEpoch DESC")
    fun all(): Flow<List<DailyUsageEntity>>

    @Query("SELECT * FROM daily_usage ORDER BY dayEpoch DESC LIMIT :n")
    suspend fun lastDays(n: Int): List<DailyUsageEntity>

    @Query("SELECT * FROM daily_usage WHERE dayEpoch = :dayEpoch")
    suspend fun byDay(dayEpoch: Long): DailyUsageEntity?

    /** Deletes every row outside the newest `keepDays` days. */
    @Query(
        "DELETE FROM daily_usage WHERE dayEpoch NOT IN " +
            "(SELECT dayEpoch FROM daily_usage ORDER BY dayEpoch DESC LIMIT :keepDays)"
    )
    suspend fun prune(keepDays: Int)
}

@Dao
interface ClosedConnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(list: List<ClosedConnEntity>)

    @Query("SELECT * FROM closed_conns ORDER BY closedAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<ClosedConnEntity>>

    /**
     * Keeps the newest `limit` rows and drops the rest. The subquery selects the
     * survivors by the same ordering `recent` renders with, so trimming can never
     * delete a row that is still on screen.
     */
    @Query(
        "DELETE FROM closed_conns WHERE id NOT IN " +
            "(SELECT id FROM closed_conns ORDER BY closedAt DESC, id DESC LIMIT :limit)"
    )
    suspend fun trimTo(limit: Int)

    @Query("DELETE FROM closed_conns")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM closed_conns")
    suspend fun count(): Int
}

@Dao
interface HostSeenDao {

    @Query("SELECT * FROM host_seen WHERE host = :host")
    suspend fun seen(host: String): HostSeenEntity?

    @Upsert
    suspend fun upsert(e: HostSeenEntity)

    @Query("SELECT host FROM host_seen ORDER BY lastSeen DESC")
    suspend fun allHosts(): List<String>

    /** Evicts least-recently-seen hosts so the dedup table stays bounded. */
    @Query(
        "DELETE FROM host_seen WHERE host NOT IN " +
            "(SELECT host FROM host_seen ORDER BY lastSeen DESC LIMIT :max)"
    )
    suspend fun prune(max: Int)

    @Query("SELECT COUNT(*) FROM host_seen")
    suspend fun count(): Int
}
