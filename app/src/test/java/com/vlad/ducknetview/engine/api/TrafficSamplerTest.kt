package com.vlad.ducknetview.engine.api

import com.vlad.ducknetview.domain.rates.RateTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSamplerTest {

    private class FakeCounters : TrafficSampler.Counters {
        var totalRx = 0L
        var totalTx = 0L
        val uidRx = HashMap<Int, Long>()
        val uidTx = HashMap<Int, Long>()
        var uidCalls = 0

        override fun totalRx(): Long = totalRx
        override fun totalTx(): Long = totalTx
        override fun uidRx(uid: Int): Long {
            uidCalls++
            return uidRx[uid] ?: TrafficSampler.UNSUPPORTED
        }

        override fun uidTx(uid: Int): Long {
            uidCalls++
            return uidTx[uid] ?: TrafficSampler.UNSUPPORTED
        }
    }

    @Test
    fun `first device sample yields zero because a rate needs two points`() {
        val c = FakeCounters().apply { totalRx = 1000; totalTx = 500 }
        val s = TrafficSampler(RateTracker(), c)
        val t = s.sampleDevice(1_000L)
        assertEquals(0L, t.rxBps)
        assertEquals(0L, t.txBps)
    }

    @Test
    fun `device rate is the per-second delta of two samples`() {
        val c = FakeCounters().apply { totalRx = 1000; totalTx = 500 }
        val s = TrafficSampler(RateTracker(), c)
        s.sampleDevice(1_000L)
        c.totalRx = 3000
        c.totalTx = 1500
        val t = s.sampleDevice(3_000L)
        assertEquals(1000L, t.rxBps)
        assertEquals(500L, t.txBps)
    }

    @Test
    fun `a counter that goes backwards reports zero, not a wrap-around spike`() {
        val c = FakeCounters().apply { totalRx = 5000; totalTx = 5000 }
        val s = TrafficSampler(RateTracker(), c)
        s.sampleDevice(1_000L)
        c.totalRx = 10
        c.totalTx = 10
        val t = s.sampleDevice(2_000L)
        assertEquals(0L, t.rxBps)
        assertEquals(0L, t.txBps)
    }

    @Test
    fun `unsupported device totals latch deviceSupported false`() {
        val c = FakeCounters().apply {
            totalRx = TrafficSampler.UNSUPPORTED
            totalTx = TrafficSampler.UNSUPPORTED
        }
        val s = TrafficSampler(RateTracker(), c)
        val t = s.sampleDevice(1_000L)
        assertEquals(0L, t.rxBps)
        assertFalse(s.deviceSupported)
    }

    @Test
    fun `device history grows one point per sample`() {
        val c = FakeCounters()
        val s = TrafficSampler(RateTracker(), c)
        s.sampleDevice(1_000L)
        s.sampleDevice(2_000L)
        assertEquals(2, s.rxHistory().size)
        assertEquals(2, s.txHistory().size)
    }

    @Test
    fun `per-uid rates come from consecutive samples`() {
        val c = FakeCounters()
        c.uidRx[10123] = 0L
        c.uidTx[10123] = 0L
        val s = TrafficSampler(RateTracker(), c)
        s.sampleUids(listOf(10123), 1_000L)
        c.uidRx[10123] = 4000L
        c.uidTx[10123] = 2000L
        val out = s.sampleUids(listOf(10123), 3_000L)
        assertEquals(2000L, out[10123]?.rxBps)
        assertEquals(1000L, out[10123]?.txBps)
    }

    @Test
    fun `all-unsupported uids latch perUidSupported false instead of reporting zeros`() {
        val c = FakeCounters()
        val s = TrafficSampler(RateTracker(), c)
        assertTrue(s.perUidSupported)
        val out = s.sampleUids(listOf(10001, 10002), 1_000L)
        assertTrue(out.isEmpty())
        assertFalse(s.perUidSupported)
    }

    @Test
    fun `once the per-uid latch is off no further counters are read`() {
        val c = FakeCounters()
        val s = TrafficSampler(RateTracker(), c)
        s.sampleUids(listOf(10001), 1_000L)
        val callsAfterFirst = c.uidCalls
        s.sampleUids(listOf(10001), 2_000L)
        assertEquals(callsAfterFirst, c.uidCalls)
    }

    @Test
    fun `a measurable uid keeps the latch on and unmeasurable ones are dropped`() {
        val c = FakeCounters()
        c.uidRx[10123] = 100L
        c.uidTx[10123] = 100L
        val s = TrafficSampler(RateTracker(), c)
        val out = s.sampleUids(listOf(10123, 10999), 1_000L)
        assertTrue(s.perUidSupported)
        assertTrue(out.containsKey(10123))
        assertFalse(out.containsKey(10999))
    }

    @Test
    fun `an unmeasurable uid is negatively cached and not retried`() {
        val c = FakeCounters()
        c.uidRx[10123] = 100L
        c.uidTx[10123] = 100L
        val s = TrafficSampler(RateTracker(), c)
        s.sampleUids(listOf(10123, 10999), 1_000L)
        val calls = c.uidCalls
        s.sampleUids(listOf(10123, 10999), 2_000L)
        // Only the measurable uid is queried on the second pass: 2 more calls.
        assertEquals(calls + 2, c.uidCalls)
    }

    @Test
    fun `an empty uid set is not evidence of anything`() {
        val s = TrafficSampler(RateTracker(), FakeCounters())
        assertTrue(s.sampleUids(emptyList(), 1_000L).isEmpty())
        assertTrue(s.perUidSupported)
    }

    @Test
    fun `uidTotals returns null for an unmeasurable uid`() {
        val c = FakeCounters()
        c.uidRx[10123] = 7L
        c.uidTx[10123] = 9L
        val s = TrafficSampler(RateTracker(), c)
        assertEquals(Pair(7L, 9L), s.uidTotals(10123))
        assertEquals(null, s.uidTotals(10999))
        assertEquals(null, s.uidTotals(-1))
    }

    @Test
    fun `reset clears the latches`() {
        val c = FakeCounters()
        val s = TrafficSampler(RateTracker(), c)
        s.sampleUids(listOf(10001), 1_000L)
        assertFalse(s.perUidSupported)
        s.reset()
        assertTrue(s.perUidSupported)
    }
}
