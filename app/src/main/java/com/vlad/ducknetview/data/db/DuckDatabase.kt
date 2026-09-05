package com.vlad.ducknetview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        EventEntity::class,
        DailyUsageEntity::class,
        ClosedConnEntity::class,
        HostSeenEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DuckDatabase : RoomDatabase() {

    abstract fun events(): EventDao
    abstract fun usage(): UsageDao
    abstract fun closedConns(): ClosedConnDao
    abstract fun hostsSeen(): HostSeenDao

    companion object {
        const val NAME = "ducknetview.db"

        @Volatile
        private var instance: DuckDatabase? = null

        fun build(context: Context): DuckDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DuckDatabase::class.java,
                    NAME,
                )
                    // Everything here is derived monitoring history, so a
                    // downgrade may throw it away rather than refuse to open.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { instance = it }
            }
    }
}
