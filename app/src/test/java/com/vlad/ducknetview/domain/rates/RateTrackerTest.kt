package com.vlad.ducknetview.domain.rates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateTrackerTest {

    @Test
    fun firstSampleYieldsZeroBecauseARateNeedsTwoPoints() {
        val t = RateTracker()
        assertEquals(0L, t.update("a", 1_000_000, 1_000L))
        assertEquals(0L, t.deltaOf("a"))
    }

    @Test
    fun rateIsThePerSecondDeltaOfCumulativeCounters() {
        val t = RateTracker()
        t.update("a", 1_000, 1_000L)
        assertEquals(2_000L, t.update("a", 3_000, 2_000L))
        assertEquals(2_000L, t.deltaOf("a"))
    }

    @Test
    fun subSecondIntervalsAreScaledUp() {
        val t = RateTracker()
        t.update("a", 0, 0L)
        assertEquals(4_000L, t.update("a", 1_000, 250L))
    }

    @Test
    fun aCounterGoingBackwardsReportsZeroInsteadOfAWrappedSpike() {
        val t = RateTracker()
        t.update("iface", 5_000_000, 1_000L)
        // An interface counter reset: without the guard this becomes a rate of
        // roughly 2^64 bytes per second.
        assertEquals(0L, t.update("iface", 12, 2_000L))
        assertEquals(0L, t.deltaOf("iface"))
    }

    @Test
    fun zeroOrNegativeElapsedTimeReportsZero() {
        val t = RateTracker()
        t.update("a", 100, 1_000L)
        assertEquals(0L, t.update("a", 500, 1_000L))
        assertEquals(0L, t.update("a", 900, 500L))
    }

    @Test
    fun keysAreTrackedIndependently() {
        val t = RateTracker()
        t.update("rx", 0, 0L)
        t.update("tx", 0, 0L)
        assertEquals(100L, t.update("rx", 100, 1_000L))
        assertEquals(7L, t.update("tx", 7, 1_000L))
    }

    @Test
    fun historyIsBoundedAndKeepsTheNewest() {
        val t = RateTracker(historySize = 3)
        (1..5).forEach { t.pushHistory("h", it.toFloat()) }
        assertEquals(listOf(3f, 4f, 5f), t.history("h"))
    }

    @Test
    fun historyOfAnUnknownKeyIsEmptyRatherThanNull() {
        assertTrue(RateTracker().history("nope").isEmpty())
    }

    @Test
    fun retainDropsBookkeepingForKeysNoLongerPresent() {
        val t = RateTracker()
        t.update("gone", 10, 0L)
        t.update("kept", 10, 0L)
        t.pushHistory("gone", 1f)
        t.retain(setOf("kept"))
        // A dropped key starts over: its next sample is a first sighting again.
        assertEquals(0L, t.update("gone", 20, 1_000L))
        assertEquals(10L, t.update("kept", 20, 1_000L))
        assertTrue(t.history("gone").isEmpty())
    }

    @Test
    fun forgetAndClearResetState() {
        val t = RateTracker()
        t.update("a", 10, 0L)
        t.forget("a")
        assertEquals(0L, t.update("a", 20, 1_000L))
        t.update("b", 10, 0L)
        t.clear()
        assertEquals(0L, t.update("b", 30, 1_000L))
    }

    @Test
    fun deltaSurvivesAcrossPollsForSessionTotals() {
        val t = RateTracker()
        t.update("f", 0, 0L)
        t.update("f", 512, 1_000L)
        assertEquals(512L, t.deltaOf("f"))
        // A slow poll must report the whole delta, not a scaled-down rate.
        t.update("f", 1_536, 5_000L)
        assertEquals(1_024L, t.deltaOf("f"))
        assertEquals(256L, ((1_536L - 512L) * 1000L) / 4_000L)
    }
}
