package com.vlad.ducknetview.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "events", indices = [Index("at")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val at: Long,
    val level: String,
    val kind: String,
    val subject: String,
    val detail: String,
)

@Entity(tableName = "daily_usage")
data class DailyUsageEntity(
    @PrimaryKey val dayEpoch: Long,
    val rx: Long,
    val tx: Long,
    val appsJson: String,
    val hostsJson: String,
)

@Entity(tableName = "closed_conns", indices = [Index("closedAt")])
data class ClosedConnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val closedAt: Long,
    val proto: String,
    val localAddr: String,
    val localPort: Int,
    val remoteAddr: String,
    val remotePort: Int,
    val uid: Int,
    val appLabel: String,
    val service: String,
    val scope: String,
    val finalRx: Long,
    val finalTx: Long,
    val lifetimeMillis: Long,
    val network: String,
    val resolvedHost: String?,
)

/**
 * Backs the "first contact with a new public host" detection. It has to live in
 * the database rather than in a process-lifetime set: the engine runs in a
 * foreground service that the system may kill and restart, and a restart must
 * not re-announce every host the user has already been told about.
 */
@Entity(tableName = "host_seen")
data class HostSeenEntity(
    @PrimaryKey val host: String,
    val firstSeen: Long,
    val lastSeen: Long,
)
