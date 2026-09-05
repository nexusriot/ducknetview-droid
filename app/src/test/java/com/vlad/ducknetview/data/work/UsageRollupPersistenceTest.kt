package com.vlad.ducknetview.data.work

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.data.UsageRepository
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.domain.usage.DailyUsage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rollup's end-to-end correctness risk: NetworkStatsManager hands out the
 * day's cumulative totals, so two runs in one day must land on one row with one
 * day's bytes in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsageRollupPersistenceTest {

    private lateinit var db: DuckDatabase
    private lateinit var repo: UsageRepository

    private val day = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DuckDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = UsageRepository(db.usage())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rollup(perUid: Map<Int, Pair<Long, Long>>, dayEpoch: Long = day) =
        UsageRollupLogic.dayUsage(dayEpoch, perUid) { "app$it" }

    @Test
    fun `a first rollup stores the day`() = runTest {
        repo.replaceDay(rollup(mapOf(1 to Pair(100L, 20L))))
        val stored = repo.all.first().single()
        assertEquals(day, stored.dayEpoch)
        assertEquals(100L, stored.rx)
        assertEquals(20L, stored.tx)
    }

    @Test
    fun `running the rollup twice in one day does not double-count`() = runTest {
        val perUid = mapOf(1 to Pair(100L, 20L))
        repo.replaceDay(rollup(perUid))
        repo.replaceDay(rollup(perUid))
        val stored = repo.all.first().single()
        assertEquals(100L, stored.rx)
        assertEquals(20L, stored.tx)
    }

    @Test
    fun `a second run in the same day replaces rather than accumulates the apps map`() = runTest {
        repo.replaceDay(rollup(mapOf(1 to Pair(100L, 0L))))
        repo.replaceDay(rollup(mapOf(1 to Pair(150L, 0L))))
        val stored = repo.all.first().single()
        assertEquals(mapOf("app1" to 150L), stored.apps)
    }

    @Test
    fun `two runs in one day leave exactly one row`() = runTest {
        repo.replaceDay(rollup(mapOf(1 to Pair(1L, 1L))))
        repo.replaceDay(rollup(mapOf(1 to Pair(2L, 2L))))
        assertEquals(1, repo.all.first().size)
    }

    @Test
    fun `an app that stopped talking drops out of the day`() = runTest {
        repo.replaceDay(rollup(mapOf(1 to Pair(5L, 0L), 2 to Pair(5L, 0L))))
        repo.replaceDay(rollup(mapOf(1 to Pair(9L, 0L))))
        assertEquals(setOf("app1"), repo.all.first().single().apps.keys)
    }

    @Test
    fun `a different day is a different row`() = runTest {
        repo.replaceDay(rollup(mapOf(1 to Pair(1L, 1L)), dayEpoch = day))
        repo.replaceDay(rollup(mapOf(1 to Pair(2L, 2L)), dayEpoch = day + 1))
        assertEquals(2, repo.all.first().size)
    }

    @Test
    fun `mergeToday stays additive so the live engine is unaffected`() = runTest {
        repo.mergeToday(DailyUsage(dayEpoch = day, rx = 10, tx = 5))
        repo.mergeToday(DailyUsage(dayEpoch = day, rx = 10, tx = 5))
        val stored = repo.all.first().single()
        assertEquals(20L, stored.rx)
        assertEquals(10L, stored.tx)
    }

    @Test
    fun `history is pruned to forty days`() = runTest {
        (1..45).forEach { repo.replaceDay(rollup(mapOf(1 to Pair(1L, 1L)), dayEpoch = day + it)) }
        val days = repo.all.first().map { it.dayEpoch }
        assertEquals(UsageRepository.KEEP_DAYS, days.size)
        assertEquals(day + 45, days.first())
        assertEquals(day + 6, days.last())
    }

    @Test
    fun `the stored apps map is capped at fifty`() = runTest {
        repo.replaceDay(rollup((1..120).associate { it to Pair(it.toLong(), 0L) }))
        val stored = repo.all.first().single()
        assertEquals(UsageRepository.TOP_N, stored.apps.size)
        assertTrue(stored.apps.containsKey("app120"))
        assertFalse(stored.apps.containsKey("app1"))
    }

    @Test
    fun `replaceDay caps a hosts map handed to it directly`() = runTest {
        val many = (1..120).associate { "h$it" to it.toLong() }
        repo.replaceDay(DailyUsage(dayEpoch = day, rx = 1, tx = 1, hosts = many))
        assertEquals(UsageRepository.TOP_N, repo.all.first().single().hosts.size)
    }

    @Test
    fun `an empty day still writes a row so the chart has no hole`() = runTest {
        repo.replaceDay(rollup(emptyMap()))
        val stored = repo.all.first().single()
        assertEquals(0L, stored.total)
        assertEquals(day, stored.dayEpoch)
    }
}
