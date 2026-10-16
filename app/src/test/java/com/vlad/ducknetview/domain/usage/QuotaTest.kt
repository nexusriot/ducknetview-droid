package com.vlad.ducknetview.domain.usage

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the only number in this app that tells someone to
 * change their behaviour, so the edges get written down rather than assumed:
 * a cycle that starts on a day February does not have, a cap set on day one,
 * and a cycle nobody has used anything in.
 */
class QuotaTest {

    private fun day(text: String) = LocalDate.parse(text)

    // ------------------------------------------------------------ boundaries

    @Test
    fun `the cycle containing today starts this month once the day has passed`() {
        assertEquals(day("2026-03-05"), Quota.cycleStart(day("2026-03-20"), 5))
        assertEquals(day("2026-04-01"), Quota.cycleEnd(day("2026-03-20"), 5).withDayOfMonth(1))
        assertEquals(day("2026-04-05"), Quota.cycleEnd(day("2026-03-20"), 5))
    }

    @Test
    fun `before the reset day the cycle is still last month's`() {
        assertEquals(day("2026-02-20"), Quota.cycleStart(day("2026-03-04"), 20))
        assertEquals(day("2026-03-20"), Quota.cycleEnd(day("2026-03-04"), 20))
    }

    @Test
    fun `the reset day itself belongs to the new cycle`() {
        assertEquals(day("2026-03-05"), Quota.cycleStart(day("2026-03-05"), 5))
        assertEquals(1, Quota.status(day("2026-03-05"), 5, 1, 0).daysElapsed)
    }

    @Test
    fun `a reset day February does not have falls on its last day`() {
        // Plans phrased "the 31st" exist. Skipping the month would drop a
        // cycle; rolling into March would move everyone's reset date.
        assertEquals(day("2026-02-28"), Quota.cycleStart(day("2026-03-01"), 31))
        assertEquals(day("2026-03-31"), Quota.cycleEnd(day("2026-03-01"), 31))
    }

    @Test
    fun `a leap February takes the 29th`() {
        assertEquals(day("2028-02-29"), Quota.cycleStart(day("2028-03-01"), 31))
    }

    @Test
    fun `an out of range reset day is clamped rather than throwing`() {
        assertEquals(day("2026-03-01"), Quota.cycleStart(day("2026-03-10"), 0))
        assertEquals(day("2026-03-31"), Quota.cycleStart(day("2026-03-31"), 99))
    }

    @Test
    fun `the cycle spans the month, not a fixed thirty days`() {
        assertEquals(28, Quota.status(day("2026-02-10"), 1, 1, 0).cycleDays)
        assertEquals(31, Quota.status(day("2026-03-10"), 1, 1, 0).cycleDays)
    }

    // --------------------------------------------------------------- status

    private fun status(used: Long, cap: Long = 10_000L, on: String = "2026-03-11") =
        Quota.status(day(on), startDay = 1, capBytes = cap, usedBytes = used)

    @Test
    fun `no cap means not configured and no warnings`() {
        val q = status(used = 5_000L, cap = 0L)
        assertFalse(q.configured)
        assertFalse(q.overBudget)
        assertFalse(q.projectedOver)
        assertEquals(0, q.percentUsed)
    }

    @Test
    fun `percent used is reported both clamped and exact`() {
        val q = status(used = 14_000L)
        assertEquals("the bar must not overflow", 100, q.percentUsed)
        assertEquals("the text should say how far over", 140, q.percentUsedExact)
        assertTrue(q.overBudget)
        assertEquals(0L, q.remainingBytes)
    }

    @Test
    fun `day one does not divide by zero`() {
        // The day a cap is most often set. Counting today as elapsed is what
        // makes the projection defined at all here.
        val q = Quota.status(day("2026-03-01"), startDay = 1, capBytes = 10_000L, usedBytes = 300L)
        assertEquals(1, q.daysElapsed)
        assertEquals(300L, q.bytesPerDay)
        assertEquals(300L * 31, q.projectedBytes)
    }

    @Test
    fun `the projection is the rate so far across the whole cycle`() {
        // 11 days in, 1100 bytes: 100/day over 31 days is 3100.
        val q = status(used = 1_100L)
        assertEquals(11, q.daysElapsed)
        assertEquals(100L, q.bytesPerDay)
        assertEquals(3_100L, q.projectedBytes)
        assertFalse(q.projectedOver)
    }

    @Test
    fun `a cycle on course to overrun says so before it does`() {
        // 11 days in and already 5500 of 10000: 500/day projects to 15500.
        val q = status(used = 5_500L)
        assertTrue(q.projectedOver)
        assertFalse("it has not actually overrun yet", q.overBudget)
    }

    @Test
    fun `the safe daily figure spends exactly the remainder`() {
        val q = status(used = 5_500L)
        assertEquals(20, q.daysRemaining)
        assertEquals((10_000L - 5_500L) / 20, q.safeDailyRemaining)
    }

    @Test
    fun `an unused cycle projects nothing and exhausts never`() {
        val q = status(used = 0L)
        assertEquals(0L, q.projectedBytes)
        assertFalse(q.projectedOver)
        assertNull(q.exhaustedOnDay)
    }

    @Test
    fun `the exhaustion date lands inside the cycle when the rate overruns`() {
        val q = status(used = 5_500L)
        val exhausted = q.exhaustedOnDay
        assertTrue("expected a date inside the cycle, got $exhausted", exhausted != null)
        assertTrue(exhausted!! > q.todayDay)
        assertTrue(exhausted < q.cycleEndDay)
    }

    @Test
    fun `a cycle that will finish inside the cap has no exhaustion date`() {
        assertNull(status(used = 1_100L).exhaustedOnDay)
    }

    @Test
    fun `an already exhausted cap reports today`() {
        val q = status(used = 12_000L)
        assertEquals(q.todayDay, q.exhaustedOnDay)
    }

    @Test
    fun `the last day of a cycle has no remaining days to divide by`() {
        val q = Quota.status(day("2026-03-31"), startDay = 1, capBytes = 10_000L, usedBytes = 9_000L)
        assertEquals(0, q.daysRemaining)
        assertEquals("the whole remainder, not a division by zero", 1_000L, q.safeDailyRemaining)
    }

    @Test
    fun `negative inputs are floored rather than propagated`() {
        val q = Quota.status(day("2026-03-11"), startDay = 1, capBytes = -5L, usedBytes = -5L)
        assertFalse(q.configured)
        assertEquals(0L, q.usedBytes)
    }
}
