package com.vlad.ducknetview.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinuteHistoryTest {

    private val m = MinuteAccumulator.MINUTE_MS

    @Test
    fun aMinuteIsEmittedOnlyOnceTheNextOneStarts() {
        val acc = MinuteAccumulator()
        assertNull(acc.add(10 * m, 100, 10))
        assertNull(acc.add(10 * m + 2_000, 300, 20))
        val closed = acc.add(11 * m, 0, 0)!!
        assertEquals(10L, closed.minute)
        assertEquals(300L, closed.rxPeakBps)
        assertEquals(20L, closed.txPeakBps)
        // 2 s at 300 B/s, the first reading having no gap behind it.
        assertEquals(600L, closed.rxBytes)
    }

    @Test
    fun aLongSleepIsNotChargedToTheMinuteItWokeIn() {
        // A reading an hour after the last is charged the capped 5 s, not an hour.
        val next = MinuteAccumulator(maxGapMillis = 5_000)
        next.add(0, 1_000, 0)
        next.add(3_600_000, 1_000, 0)
        assertEquals(5_000L, next.add(3_600_000 + m, 0, 0)!!.rxBytes)
    }

    @Test
    fun theSeriesSpansADayWithGapsLeftAsGaps() {
        val now = 2_000 * m
        val samples = listOf(
            MinuteSample(minute = 2_000, rxPeakBps = 50, txPeakBps = 50, rxBytes = 0, txBytes = 0),
            MinuteSample(minute = 2_000 - 1439, rxPeakBps = 7, txPeakBps = 0, rxBytes = 0, txBytes = 0),
            MinuteSample(minute = 2_000 - 1440, rxPeakBps = 999, txPeakBps = 0, rxBytes = 0, txBytes = 0),
        )
        val s = MinuteAccumulator.series(samples, now, buckets = 96)
        assertEquals(96, s.size)
        assertEquals(7f, s.first())
        assertEquals(100f, s.last())
        assertEquals(0f, s[48])
    }
}
