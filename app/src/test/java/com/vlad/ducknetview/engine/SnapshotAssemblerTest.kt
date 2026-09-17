package com.vlad.ducknetview.engine

import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.rates.RateTracker
import com.vlad.ducknetview.domain.rdns.RdnsCache
import com.vlad.ducknetview.domain.totals.SessionTotals
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.engine.api.ApiSample
import com.vlad.ducknetview.engine.api.AppCatalog
import com.vlad.ducknetview.engine.api.TrafficSampler
import com.vlad.ducknetview.engine.vpn.FlowKey
import com.vlad.ducknetview.engine.vpn.FlowTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The assembler sits between the two engines and the UI, and nothing used to
 * test it. Both defects below were invisible to the layer tests on either side
 * of it — [TrafficSampler] and [SessionTotals] each behaved correctly in
 * isolation — and only showed up on a device, as a throughput card reading zero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SnapshotAssemblerTest {

    private class FakeCounters : TrafficSampler.Counters {
        var totalRx = 0L
        var totalTx = 0L
        override fun totalRx(): Long = totalRx
        override fun totalTx(): Long = totalTx
        override fun uidRx(uid: Int): Long = TrafficSampler.UNSUPPORTED
        override fun uidTx(uid: Int): Long = TrafficSampler.UNSUPPORTED
    }

    private lateinit var rates: RateTracker
    private lateinit var sampler: TrafficSampler
    private lateinit var counters: FakeCounters
    private lateinit var totals: SessionTotals
    private lateinit var assembler: SnapshotAssembler

    @Before
    fun setUp() {
        rates = RateTracker()
        counters = FakeCounters()
        sampler = TrafficSampler(rates, counters)
        totals = SessionTotals()
        assembler = SnapshotAssembler(
            catalog = AppCatalog(ApplicationProvider.getApplicationContext()),
            rates = rates,
            totals = totals,
            rdns = RdnsCache(),
        )
        assembler.configure("test-device", 0L)
    }

    /** One poll: sample the device counters, then assemble, as the loop does. */
    private fun tick(now: Long, flows: FlowTable?): com.vlad.ducknetview.domain.model.NetSnapshot {
        val total = sampler.sampleDevice(now)
        return assembler.assemble(
            now = now,
            api = ApiSample(networks = emptyList(), total = total, perUid = emptyMap()),
            flows = flows,
            settings = AppSettings(),
            baseline = Baseline(emptySet(), 0L),
            watchlist = Watchlist(emptyList()),
            externalIp = null,
            externalIpAt = 0L,
            latency = emptyList(),
            scanRunning = false,
        )
    }

    private fun flowTableCarrying(bytes: Long, at: Long): FlowTable {
        val table = FlowTable()
        val key = FlowKey(Proto.TCP, "10.215.173.2", 40000, "93.184.216.34", 443)
        val flow = table.open(key, 4, at)
        flow.rx.set(bytes)
        flow.tx.set(bytes / 2)
        return table
    }

    /**
     * The regression that made capture mode useless for throughput. The flow
     * side prunes dead flows with `retain`, which drops every key it is not
     * given; while it shared a tracker with the API engine that call deleted
     * `device.rx` once per poll, so the next sample re-baselined and every
     * throughput reading on the device was a flat zero.
     */
    @Test
    fun `device rates survive a capture-mode assemble`() {
        counters.totalRx = 1_000
        counters.totalTx = 500
        tick(1_000L, flowTableCarrying(400, 1_000L))

        counters.totalRx = 3_000
        counters.totalTx = 1_500
        val snap = tick(2_000L, flowTableCarrying(900, 2_000L))

        assertEquals(EngineMode.VPN, snap.engine)
        assertEquals("device rx rate was wiped between polls", 2_000L, snap.total.rxBps)
        assertEquals("device tx rate was wiped between polls", 1_000L, snap.total.txBps)
    }

    @Test
    fun `per-interface and per-uid series are not pruned by the flow table`() {
        rates.update("net.wifi-1.rx", 10_000, 1_000L)
        rates.update("uid.10123.rx", 4_000, 1_000L)
        tick(1_000L, flowTableCarrying(400, 1_000L))

        // A second point on each series must still read as a rate, which it can
        // only do if the first point survived the assemble in between.
        assertEquals(1_000L, rates.update("net.wifi-1.rx", 11_000, 2_000L))
        assertEquals(2_000L, rates.update("uid.10123.rx", 6_000, 2_000L))
    }

    /**
     * API mode is the default and has no flows at all, so a session total fed
     * from the flow table read 0 B forever — on the card whose other two rows
     * were live the whole time.
     */
    @Test
    fun `session totals accumulate in api mode`() {
        counters.totalRx = 1_000
        counters.totalTx = 500
        tick(1_000L, null)

        counters.totalRx = 3_500
        counters.totalTx = 900
        val snap = tick(2_000L, null)

        assertEquals(EngineMode.API, snap.engine)
        assertEquals(2_500L, snap.sessionRx)
        assertEquals(400L, snap.sessionTx)
    }

    @Test
    fun `the first poll books nothing because a delta needs two samples`() {
        counters.totalRx = 900_000
        counters.totalTx = 400_000
        val snap = tick(1_000L, null)

        assertEquals("the counter's pre-session history is not this session", 0L, snap.sessionRx)
        assertEquals(0L, snap.sessionTx)
    }

    /**
     * Capture mode books the same bytes through the flow path as well, so the
     * grand total has to come from one source only.
     */
    @Test
    fun `capture mode does not double-count the session total`() {
        counters.totalRx = 1_000
        counters.totalTx = 500
        tick(1_000L, flowTableCarrying(400, 1_000L))

        counters.totalRx = 3_000
        counters.totalTx = 1_500
        val snap = tick(2_000L, flowTableCarrying(1_400, 2_000L))

        assertEquals(2_000L, snap.sessionRx)
        assertEquals(1_000L, snap.sessionTx)
        // The per-app talkers still come from the flows, which is their only source.
        assertTrue("flows should still feed the talker tables", snap.topApps.isNotEmpty())
    }

    @Test
    fun `a counter reset contributes nothing rather than a wrapped spike`() {
        counters.totalRx = 5_000
        counters.totalTx = 5_000
        tick(1_000L, null)

        counters.totalRx = 10
        counters.totalTx = 10
        val snap = tick(2_000L, null)

        assertEquals(0L, snap.sessionRx)
        assertEquals(0L, snap.sessionTx)
    }

    @Test
    fun `api mode reports no connections and capture mode reports them`() {
        counters.totalRx = 1_000
        val api = tick(1_000L, null)
        assertTrue(api.conns.isEmpty())

        val vpn = tick(2_000L, flowTableCarrying(400, 2_000L))
        assertEquals(1, vpn.conns.size)
    }

    /**
     * The Apps screen renders a "today" figure, sorts on it and exports it, and
     * for a while nothing ever set it: the engine queried NetworkStatsManager on
     * its slow cadence and threw the answer away, so every row read 0 B.
     */
    @Test
    fun `today's per-uid totals reach the app rows`() {
        val uid = android.os.Process.myUid()
        assembler.setTodayUsage(mapOf(uid to (12_345L to 6_789L)))

        counters.totalRx = 1_000
        val snap = tick(1_000L, flowTableCarrying(400, 1_000L).also { table ->
            table.live().first().uid = uid
        })

        val row = snap.apps.first { it.uid == uid }
        assertEquals(12_345L, row.todayRx)
        assertEquals(6_789L, row.todayTx)
    }

    @Test
    fun `an app with no usage record reports zero rather than another app's bytes`() {
        assembler.setTodayUsage(mapOf(999_999 to (12_345L to 6_789L)))

        counters.totalRx = 1_000
        val snap = tick(1_000L, flowTableCarrying(400, 1_000L))

        val row = snap.apps.first()
        assertEquals(0L, row.todayRx)
        assertEquals(0L, row.todayTx)
    }

    /** Guards the assumption the session-total wiring rests on. */
    @Test
    fun `the assembler reads the same device keys the sampler writes`() {
        counters.totalRx = 1_000
        counters.totalTx = 500
        sampler.sampleDevice(1_000L)
        counters.totalRx = 1_700
        counters.totalTx = 800
        sampler.sampleDevice(2_000L)

        assertEquals(700L, rates.deltaOf(TrafficSampler.KEY_DEVICE_RX))
        assertEquals(300L, rates.deltaOf(TrafficSampler.KEY_DEVICE_TX))
    }
}
