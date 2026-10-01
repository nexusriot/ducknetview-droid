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
 * The Domains store arrives in a database that already holds the event log and
 * forty days of usage history, so the migration is hand-written rather than
 * destructive: dropping a month of history to add an unrelated table would do
 * more damage than the feature is worth.
 *
 * Room validates the resulting schema against the exported one when it opens
 * the migrated database, so a statement that does not match [DomainEntity]
 * fails here rather than on a user's device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DomainMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DuckDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val name = "migration-domains.db"

    @Test
    fun `the domains table is added and the rest of the database survives`() {
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                "INSERT INTO events (at, level, kind, subject, detail) " +
                    "VALUES (1700000000000, 'ALERT', 'service_up', 'tcp 0.0.0.0:22', 'ssh')"
            )
            db.execSQL(
                "INSERT INTO daily_usage (dayEpoch, rx, tx, appsJson, hostsJson) " +
                    "VALUES (20000, 111, 222, '', '')"
            )
        }

        val migrated = helper.runMigrationsAndValidate(
            name, 3, true, DuckDatabase.MIGRATION_2_3,
        )

        migrated.query("SELECT subject FROM events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("tcp 0.0.0.0:22", c.getString(0))
        }
        migrated.query("SELECT rx, tx FROM daily_usage").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(111L, c.getLong(0))
            assertEquals(222L, c.getLong(1))
        }
        migrated.query("SELECT COUNT(*) FROM domains").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `the unique index is what makes one name per app a single row`() {
        helper.createDatabase(name, 2).close()
        val migrated = helper.runMigrationsAndValidate(
            name, 3, true, DuckDatabase.MIGRATION_2_3,
        )

        val insert = "INSERT INTO domains (name, uid, source, lookups, firstSeen, lastSeen, addresses) " +
            "VALUES ('example.com', 10100, 'DNS', 1, 1, 1, '')"
        migrated.execSQL(insert)
        // Without the index the repository's find-then-upsert would quietly
        // accumulate duplicate rows for the same name.
        val duplicate = runCatching { migrated.execSQL(insert) }
        assertTrue(duplicate.isFailure)

        migrated.execSQL(insert.replace("10100", "10200"))
        migrated.query("SELECT COUNT(*) FROM domains").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }
}
