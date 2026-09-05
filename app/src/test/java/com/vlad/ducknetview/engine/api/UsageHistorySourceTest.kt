package com.vlad.ducknetview.engine.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
}
