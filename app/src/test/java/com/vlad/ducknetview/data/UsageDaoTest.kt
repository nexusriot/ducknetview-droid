package com.vlad.ducknetview.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.data.db.DailyUsageEntity
import com.vlad.ducknetview.data.db.DuckDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsageDaoTest {

    private lateinit var db: DuckDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DuckDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun day(dayEpoch: Long, rx: Long = 1L) =
        DailyUsageEntity(dayEpoch = dayEpoch, rx = rx, tx = 2L, appsJson = "", hostsJson = "")

    @Test
    fun `upsert inserts a new day`() = runTest {
        db.usage().upsert(day(20_000))
        assertEquals(1L, db.usage().byDay(20_000)?.rx)
    }

    @Test
    fun `upsert replaces the row for a day already present`() = runTest {
        db.usage().upsert(day(20_000, rx = 1L))
        db.usage().upsert(day(20_000, rx = 99L))
        assertEquals(1, db.usage().all().first().size)
        assertEquals(99L, db.usage().byDay(20_000)?.rx)
    }

    @Test
    fun `byDay returns null for a day with no row`() = runTest {
        assertNull(db.usage().byDay(1))
    }

    @Test
    fun `all returns newest day first`() = runTest {
        listOf(20_003L, 20_001L, 20_002L).forEach { db.usage().upsert(day(it)) }
        assertEquals(listOf(20_003L, 20_002L, 20_001L), db.usage().all().first().map { it.dayEpoch })
    }

    @Test
    fun `lastDays returns the newest n days`() = runTest {
        (1L..10L).forEach { db.usage().upsert(day(20_000 + it)) }
        assertEquals(listOf(20_010L, 20_009L, 20_008L), db.usage().lastDays(3).map { it.dayEpoch })
    }

    @Test
    fun `prune keeps the newest days and drops the rest`() = runTest {
        (1L..50L).forEach { db.usage().upsert(day(20_000 + it)) }
        db.usage().prune(40)
        val remaining = db.usage().all().first().map { it.dayEpoch }
        assertEquals(40, remaining.size)
        assertEquals(20_050L, remaining.first())
        assertEquals(20_011L, remaining.last())
    }

    @Test
    fun `prune is a no-op when fewer days are stored than kept`() = runTest {
        (1L..5L).forEach { db.usage().upsert(day(20_000 + it)) }
        db.usage().prune(40)
        assertEquals(5, db.usage().all().first().size)
    }

    @Test
    fun `map columns survive as encoded strings`() = runTest {
        db.usage().upsert(day(1).copy(appsJson = "a=1;", hostsJson = "h=2;"))
        val row = db.usage().byDay(1)
        assertEquals("a=1;", row?.appsJson)
        assertEquals("h=2;", row?.hostsJson)
    }
}
