package com.vlad.ducknetview

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.ui.screens.formatDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * `daily_usage.dayEpoch` shipped holding local-midnight *milliseconds* under a
 * column the rest of the app reads as a day number, so the usage screen crashed
 * on the first row it formatted. The fix is at the producer, but every device
 * that already ran the rollup has the bad values on disk — this proves the
 * migration converts them, on real SQLite rather than a JVM stand-in.
 */
@RunWith(AndroidJUnit4::class)
class UsageMigrationDeviceTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DuckDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun midnightMillis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun insertLegacy(dayEpoch: Long, rx: Long, tx: Long) =
        "INSERT INTO daily_usage (dayEpoch, rx, tx, appsJson, hostsJson) " +
            "VALUES ($dayEpoch, $rx, $tx, '{}', '{}')"

    @Test
    fun millisecondDayKeysBecomeDayNumbers() {
        val days = listOf(
            LocalDate.now(zone),
            LocalDate.now(zone).minusDays(1),
            LocalDate.now(zone).minusDays(7),
        )
        helper.createDatabase(DB, 1).use { db ->
            days.forEachIndexed { i, d ->
                db.execSQL(insertLegacy(midnightMillis(d), 100L * (i + 1), 10L * (i + 1)))
            }
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, DuckDatabase.MIGRATION_1_2)

        val stored = HashMap<Long, Pair<Long, Long>>()
        db.query("SELECT dayEpoch, rx, tx FROM daily_usage ORDER BY dayEpoch").use { c ->
            while (c.moveToNext()) stored[c.getLong(0)] = c.getLong(1) to c.getLong(2)
        }

        assertEquals(days.map { it.toEpochDay() }.sorted(), stored.keys.sorted())
        // The byte counters ride along untouched; only the key changes.
        assertEquals(100L to 10L, stored[days[0].toEpochDay()])
        assertEquals(300L to 30L, stored[days[2].toEpochDay()])
    }

    /**
     * Dividing the stored value by 86_400_000 is the obvious conversion and it
     * is wrong: the value is a *local* midnight, so east of UTC it lands on the
     * previous day. This is the assertion that separates the two.
     */
    @Test
    fun theConvertedKeyIsTheLocalDayNotTheUtcQuotient() {
        val date = LocalDate.now(zone).minusDays(3)
        val millis = midnightMillis(date)
        helper.createDatabase(DB, 1).use { db -> db.execSQL(insertLegacy(millis, 1L, 1L)) }

        val db = helper.runMigrationsAndValidate(DB, 2, true, DuckDatabase.MIGRATION_1_2)

        var key = Long.MIN_VALUE
        db.query("SELECT dayEpoch FROM daily_usage").use { c ->
            assertTrue(c.moveToNext())
            key = c.getLong(0)
        }
        assertEquals(date.toEpochDay(), key)
        assertEquals(
            Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay(),
            key,
        )
    }

    /** Every migrated key has to be one the screen can actually render. */
    @Test
    fun migratedKeysAreRenderableByTheUsageScreen() {
        helper.createDatabase(DB, 1).use { db ->
            repeat(5) { i ->
                db.execSQL(insertLegacy(midnightMillis(LocalDate.now(zone).minusDays(i.toLong())), 1L, 1L))
            }
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, DuckDatabase.MIGRATION_1_2)

        db.query("SELECT dayEpoch FROM daily_usage").use { c ->
            var rows = 0
            while (c.moveToNext()) {
                val key = c.getLong(0)
                rows++
                // Throws if a millisecond value survived; formatDay is total, so
                // assert on the rendering instead of relying on it to blow up.
                val label = formatDay(key)
                assertEquals(
                    LocalDate.ofEpochDay(key).format(
                        java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.US),
                    ),
                    label,
                )
            }
            assertEquals(5, rows)
        }
    }

    /** Re-running must not shift a key that is already a day number. */
    @Test
    fun rowsAlreadyHoldingDayNumbersAreLeftAlone() {
        val day = LocalDate.now(zone).toEpochDay()
        helper.createDatabase(DB, 1).use { db -> db.execSQL(insertLegacy(day, 42L, 7L)) }

        val db = helper.runMigrationsAndValidate(DB, 2, true, DuckDatabase.MIGRATION_1_2)

        db.query("SELECT dayEpoch, rx FROM daily_usage").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(day, c.getLong(0))
            assertEquals(42L, c.getLong(1))
        }
    }

    @Test
    fun anEmptyHistoryMigratesCleanly() {
        helper.createDatabase(DB, 1).close()
        val db = helper.runMigrationsAndValidate(DB, 2, true, DuckDatabase.MIGRATION_1_2)
        db.query("SELECT COUNT(*) FROM daily_usage").use { c ->
            assertTrue(c.moveToNext())
            assertEquals(0, c.getInt(0))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
