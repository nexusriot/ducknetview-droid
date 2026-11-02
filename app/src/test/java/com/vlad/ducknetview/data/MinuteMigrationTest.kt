package com.vlad.ducknetview.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.data.db.DuckDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v3 → v4 adds the 24-hour throughput table by hand. Room validates the result
 * against the exported v4 schema, and the user's existing history must come
 * through untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MinuteMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DuckDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val name = "migration-minutes.db"

    @Test
    fun `the minute table is added and the rest of the database survives`() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO domains (name, uid, source, lookups, firstSeen, lastSeen, addresses) " +
                    "VALUES ('example.com', 10001, 'DNS', 3, 1, 2, '')"
            )
        }
        val migrated = helper.runMigrationsAndValidate(name, 4, true, DuckDatabase.MIGRATION_3_4)
        migrated.query("SELECT name, lookups FROM domains").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("example.com", c.getString(0))
            assertEquals(3, c.getInt(1))
        }
        migrated.execSQL(
            "INSERT INTO minute_history (minute, rxPeakBps, txPeakBps, rxBytes, txBytes) VALUES (5, 1, 2, 3, 4)"
        )
        migrated.query("SELECT COUNT(*) FROM minute_history").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }
}
