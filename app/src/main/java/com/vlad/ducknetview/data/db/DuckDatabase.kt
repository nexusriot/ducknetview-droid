package com.vlad.ducknetview.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId

@Database(
    entities = [
        EventEntity::class,
        DailyUsageEntity::class,
        ClosedConnEntity::class,
        HostSeenEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class DuckDatabase : RoomDatabase() {

    abstract fun events(): EventDao
    abstract fun usage(): UsageDao
    abstract fun closedConns(): ClosedConnDao
    abstract fun hostsSeen(): HostSeenDao

    companion object {
        const val NAME = "ducknetview.db"

        /**
         * `daily_usage.dayEpoch` is a day number, but the rollup used to store
         * the local midnight in *milliseconds* under it, which made the usage
         * screen throw `Invalid value for EpochDay` on the first row it
         * formatted. The column type never changed, only its meaning, so this
         * migration rewrites the values, not the schema.
         *
         * The conversion runs in Kotlin rather than SQL because the stored
         * value is a *local* midnight: dividing it by 86_400_000 lands on the
         * previous day in every zone east of UTC. Values already small enough
         * to be day numbers are left alone, so the migration is idempotent.
         * `OR REPLACE` covers the collision that one writer keyed on the day
         * cannot actually produce.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val zone = ZoneId.systemDefault()
                val legacy = ArrayList<Long>()
                db.query("SELECT dayEpoch FROM daily_usage WHERE dayEpoch >= $MILLIS_FLOOR")
                    .use { c -> while (c.moveToNext()) legacy += c.getLong(0) }
                for (millis in legacy) {
                    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
                    db.execSQL(
                        "UPDATE OR REPLACE daily_usage SET dayEpoch = ? WHERE dayEpoch = ?",
                        arrayOf<Any>(day, millis),
                    )
                }
            }
        }

        /**
         * No real day number reaches this, and every millisecond timestamp
         * since 1973 exceeds it, so it separates the two unambiguously.
         */
        private const val MILLIS_FLOOR = 100_000_000_000L

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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
