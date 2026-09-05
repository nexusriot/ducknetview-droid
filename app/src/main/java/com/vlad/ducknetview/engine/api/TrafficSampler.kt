package com.vlad.ducknetview.engine.api

import android.net.TrafficStats
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.rates.RateTracker

/**
 * Device-wide and per-UID cumulative byte counters, turned into rates by the
 * shared [RateTracker].
 *
 * `TrafficStats` reports `UNSUPPORTED` (-1), not 0, when a counter has no
 * source. Since Android N the per-UID counters only answer for the calling UID
 * and return UNSUPPORTED for every other one, so a "0 B/s" row for another app
 * would be a lie: unmeasurable UIDs are dropped from the result and
 * [perUidSupported] latches false once nothing at all could be read.
 */
class TrafficSampler(
    private val rates: RateTracker,
    private val counters: Counters = PlatformCounters,
) {

    /** Indirection so the counter source can be faked in tests. */
    interface Counters {
        fun totalRx(): Long
        fun totalTx(): Long
        fun uidRx(uid: Int): Long
        fun uidTx(uid: Int): Long
    }

    object PlatformCounters : Counters {
        override fun totalRx(): Long = safe { TrafficStats.getTotalRxBytes() }
        override fun totalTx(): Long = safe { TrafficStats.getTotalTxBytes() }
        override fun uidRx(uid: Int): Long = safe { TrafficStats.getUidRxBytes(uid) }
        override fun uidTx(uid: Int): Long = safe { TrafficStats.getUidTxBytes(uid) }

        private inline fun safe(block: () -> Long): Long =
            try {
                block()
            } catch (e: SecurityException) {
                UNSUPPORTED
            } catch (e: Throwable) {
                UNSUPPORTED
            }
    }

    private var deviceSupportedLatch = true
    private var perUidSupportedLatch = true
    private val unsupportedUids = HashSet<Int>()

    /** False once the platform has proven it will not report per-UID bytes. */
    val perUidSupported: Boolean get() = perUidSupportedLatch

    /** False once the platform has proven it will not report device totals. */
    val deviceSupported: Boolean get() = deviceSupportedLatch

    /** Last raw cumulative device counters, or -1 when unmeasurable. */
    var lastTotalRx: Long = UNSUPPORTED
        private set
    var lastTotalTx: Long = UNSUPPORTED
        private set

    fun sampleDevice(now: Long): Throughput {
        val rx = counters.totalRx()
        val tx = counters.totalTx()
        if (rx <= UNSUPPORTED && tx <= UNSUPPORTED) {
            deviceSupportedLatch = false
            return Throughput()
        }
        deviceSupportedLatch = true
        lastTotalRx = rx
        lastTotalTx = tx
        val rxBps = if (rx > UNSUPPORTED) rates.update(KEY_DEVICE_RX, rx, now) else 0L
        val txBps = if (tx > UNSUPPORTED) rates.update(KEY_DEVICE_TX, tx, now) else 0L
        rates.pushHistory(KEY_DEVICE_RX, rxBps.toFloat())
        rates.pushHistory(KEY_DEVICE_TX, txBps.toFloat())
        return Throughput(rxBps, txBps)
    }

    fun rxHistory(): List<Float> = rates.history(KEY_DEVICE_RX)

    fun txHistory(): List<Float> = rates.history(KEY_DEVICE_TX)

    fun sampleUids(uids: Collection<Int>, now: Long): Map<Int, Throughput> {
        if (!perUidSupportedLatch || uids.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, Throughput>()
        var asked = 0
        for (uid in uids) {
            if (uid < 0 || uid in unsupportedUids) continue
            asked++
            val rx = counters.uidRx(uid)
            val tx = counters.uidTx(uid)
            if (rx <= UNSUPPORTED && tx <= UNSUPPORTED) {
                unsupportedUids.add(uid)
                continue
            }
            val rxKey = "$KEY_UID_PREFIX$uid.rx"
            val txKey = "$KEY_UID_PREFIX$uid.tx"
            val rxBps = if (rx > UNSUPPORTED) rates.update(rxKey, rx, now) else 0L
            val txBps = if (tx > UNSUPPORTED) rates.update(txKey, tx, now) else 0L
            out[uid] = Throughput(rxBps, txBps)
        }
        if (out.isEmpty() && asked > 0) perUidSupportedLatch = false
        return out
    }

    /** Cumulative bytes for one UID, or null when that UID is unmeasurable. */
    fun uidTotals(uid: Int): Pair<Long, Long>? {
        if (uid < 0 || uid in unsupportedUids) return null
        val rx = counters.uidRx(uid)
        val tx = counters.uidTx(uid)
        if (rx <= UNSUPPORTED && tx <= UNSUPPORTED) {
            unsupportedUids.add(uid)
            return null
        }
        return Pair(rx.coerceAtLeast(0L), tx.coerceAtLeast(0L))
    }

    fun reset() {
        rates.forget(KEY_DEVICE_RX)
        rates.forget(KEY_DEVICE_TX)
        unsupportedUids.clear()
        perUidSupportedLatch = true
        deviceSupportedLatch = true
        lastTotalRx = UNSUPPORTED
        lastTotalTx = UNSUPPORTED
    }

    companion object {
        const val UNSUPPORTED = -1L
        const val KEY_DEVICE_RX = "device.rx"
        const val KEY_DEVICE_TX = "device.tx"
        const val KEY_UID_PREFIX = "uid."
    }
}
