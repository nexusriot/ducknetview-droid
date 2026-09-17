package com.vlad.ducknetview.engine.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsageHistorySourceTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val utc = ZoneId.of("UTC")

    @Test
    fun `dayStarts returns one local midnight per day, oldest first`() {
        val now = 1_700_000_000_000L
        val starts = UsageHistorySource.dayStarts(now, 5, utc)
        assertEquals(5, starts.size)
        assertEquals(starts.sorted(), starts)
        for (i in 1 until starts.size) {
            assertEquals(UsageHistorySource.DAY_MS, starts[i] - starts[i - 1])
        }
    }

    @Test
    fun `the last dayStart is today`() {
        val now = 1_700_000_000_000L
        val starts = UsageHistorySource.dayStarts(now, 3, utc)
        val today = UsageHistorySource.dayStarts(now, 1, utc).first()
        assertEquals(today, starts.last())
        assertTrue(starts.last() <= now)
        assertTrue(now - starts.last() < UsageHistorySource.DAY_MS)
    }

    @Test
    fun `zero or negative day counts produce nothing`() {
        assertTrue(UsageHistorySource.dayStarts(1L, 0, utc).isEmpty())
        assertTrue(UsageHistorySource.dayStarts(1L, -3, utc).isEmpty())
    }

    @Test
    fun `indexOfDay buckets a timestamp into the day it falls in`() {
        val starts = UsageHistorySource.dayStarts(1_700_000_000_000L, 3, utc)
        assertEquals(0, UsageHistorySource.indexOfDay(starts, starts[0]))
        assertEquals(0, UsageHistorySource.indexOfDay(starts, starts[0] + 1_000L))
        assertEquals(1, UsageHistorySource.indexOfDay(starts, starts[1] + 5_000L))
        assertEquals(2, UsageHistorySource.indexOfDay(starts, starts[2] + 5_000L))
    }

    @Test
    fun `a timestamp before the range belongs to no day`() {
        val starts = UsageHistorySource.dayStarts(1_700_000_000_000L, 3, utc)
        assertEquals(-1, UsageHistorySource.indexOfDay(starts, starts[0] - 1L))
        assertEquals(-1, UsageHistorySource.indexOfDay(emptyList(), 1L))
    }

    @Test
    fun `startOfToday is a midnight at or before now`() {
        val start = UsageHistorySource.startOfToday(utc)
        val now = System.currentTimeMillis()
        assertTrue(start <= now)
        assertTrue(now - start < UsageHistorySource.DAY_MS)
    }

    @Test
    fun `hasAccess answers without throwing when the special access is absent`() {
        UsageHistorySource(context).hasAccess()
    }

    @Test
    fun `todayPerUid degrades to empty without usage access`() = runBlocking {
        val out = UsageHistorySource(context).todayPerUid()
        assertNotNull(out)
    }

    @Test
    fun `dailyForUid degrades to empty without usage access`() = runBlocking {
        val out = UsageHistorySource(context).dailyForUid(10123, 7)
        assertNotNull(out)
    }

    @Test
    fun `dailyForUid asks for nothing when given no days`() = runBlocking {
        assertTrue(UsageHistorySource(context).dailyForUid(10123, 0).isEmpty())
    }

    @Test
    fun `dailyDevice asks for nothing when given no days`() = runBlocking {
        assertTrue(UsageHistorySource(context).dailyDevice(0).isEmpty())
    }

    /**
     * NetworkStatsManager is queried in milliseconds but DailyUsage records a
     * day number, and for a while the millisecond value was written straight
     * into the record. Nothing caught it: both halves were self-consistent and
     * only met on a device, where the usage screen threw
     * `Invalid value for EpochDay` and killed the app. These pin the boundary.
     */
    @Test
    fun `epochDayOf turns a query timestamp into a day number`() {
        val starts = UsageHistorySource.dayStarts(1_700_000_000_000L, 3, utc)
        val days = starts.map { UsageHistorySource.epochDayOf(it, utc) }

        assertEquals(listOf(19_673L, 19_674L, 19_675L), days)
        // Consecutive midnights are consecutive day numbers, not 86_400_000 apart.
        assertEquals(1L, days[1] - days[0])
    }

    /**
     * The tempting one-liner is `millis / 86_400_000`, and it is wrong: the
     * stored value is a *local* midnight, which east of UTC is the previous
     * day's afternoon in UTC. This is the case that separates them.
     */
    @Test
    fun `epochDayOf is the local day, not the UTC quotient`() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati") // UTC+14
        val midnight = UsageHistorySource.dayStarts(1_700_000_000_000L, 1, kiritimati).first()
        val expected = Instant.ofEpochMilli(midnight).atZone(kiritimati).toLocalDate().toEpochDay()

        assertEquals(expected, UsageHistorySource.epochDayOf(midnight, kiritimati))
        assertEquals("the naive conversion should be a day behind here", expected - 1, midnight / 86_400_000L)
    }

    @Test
    fun `todayEpochDay is the day number startOfToday names`() {
        assertEquals(
            UsageHistorySource.epochDayOf(UsageHistorySource.startOfToday(utc), utc),
            UsageHistorySource.todayEpochDay(utc),
        )
    }

    /** The values the rollup stores must be ones the screen can render. */
    @Test
    fun `stored day keys are inside LocalDate's range`() {
        val keys = UsageHistorySource.dayStarts(1_700_000_000_000L, 40, utc)
            .map { UsageHistorySource.epochDayOf(it, utc) } + UsageHistorySource.todayEpochDay(utc)

        for (key in keys) {
            // Throws DateTimeException if the producer ever hands back millis again.
            LocalDate.ofEpochDay(key)
        }
    }
}
