package com.vlad.ducknetview.engine.api

import android.content.Context
import android.net.TrafficStats
import android.os.Build
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.rates.RateTracker
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One poll tick's worth of everything the API engine can measure. */
data class ApiSample(
    val networks: List<NetworkRow>,
    val total: Throughput,
    val perUid: Map<Int, Throughput>,
)

/**
 * Engine A: the always-on, permission-light data source.
 *
 * It never fabricates a number it cannot measure — a network whose per-interface
 * counters the platform refuses is left at zero bytes and the UI hides the
 * column, rather than showing the device total on every row and implying the
 * Wi-Fi link carried the cellular traffic.
 */
class ApiEngine(context: Context, private val rates: RateTracker) {

    private val appContext = context.applicationContext

    private val info = NetworkInfoSource(appContext)
    private val traffic = TrafficSampler(rates)
    private val wifiSource = WifiSource(appContext)
    private val cellularSource = CellularSource(appContext)

    val appCatalog = AppCatalog(appContext)
    val usageHistory = UsageHistorySource(appContext)

    private val _networks = MutableStateFlow<List<NetworkRow>>(emptyList())
    val networks: StateFlow<List<NetworkRow>> = _networks.asStateFlow()

    private val counters = ConcurrentHashMap<String, Counters>()
    private var collector: Job? = null

    @Volatile
    private var perIfaceUsable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val perUidSupported: Boolean get() = traffic.perUidSupported

    /** Sparkline history for the device totals, for the Overview card. */
    fun deviceRxHistory(): List<Float> = traffic.rxHistory()

    fun deviceTxHistory(): List<Float> = traffic.txHistory()

    fun start(scope: CoroutineScope) {
        info.start()
        appCatalog.start()
        collector?.cancel()
        collector = scope.launch {
            info.networks.collect { rows -> _networks.value = decorate(rows) }
        }
    }

    fun stop() {
        collector?.cancel()
        collector = null
        info.stop()
        appCatalog.stop()
        counters.clear()
        _networks.value = emptyList()
    }

    suspend fun sample(now: Long): ApiSample {
        val base = info.networks.value
        val total = traffic.sampleDevice(now)
        val defaultId = base.firstOrNull { it.isDefault }?.id
        for (row in base) {
            updateCounters(row, now, total, defaultId)
        }
        val live = base.map { it.id }.toSet()
        val gone = counters.keys - live
        for (id in gone) {
            counters.remove(id)
            rates.forget(rxKey(id))
            rates.forget(txKey(id))
        }
        val rows = decorate(base)
        _networks.value = rows
        val perUid = traffic.sampleUids(appCatalog.allUids(), now)
        return ApiSample(networks = rows, total = total, perUid = perUid)
    }

    private fun updateCounters(row: NetworkRow, now: Long, total: Throughput, defaultId: String?) {
        val iface = row.ifaceName
        var rxBytes = -1L
        var txBytes = -1L
        if (perIfaceUsable && iface.isNotEmpty()) {
            rxBytes = ifaceBytes(iface, rx = true)
            txBytes = ifaceBytes(iface, rx = false)
        }
        val prev = counters[row.id] ?: Counters()
        if (rxBytes >= 0L || txBytes >= 0L) {
            val rxBps = if (rxBytes >= 0L) rates.update(rxKey(row.id), rxBytes, now) else 0L
            val txBps = if (txBytes >= 0L) rates.update(txKey(row.id), txBytes, now) else 0L
            rates.pushHistory(rxKey(row.id), rxBps.toFloat())
            rates.pushHistory(txKey(row.id), txBps.toFloat())
            counters[row.id] = Counters(
                rxBytes = rxBytes.coerceAtLeast(0L),
                txBytes = txBytes.coerceAtLeast(0L),
                rxBps = rxBps,
                txBps = txBps,
                peakRxBps = maxOf(prev.peakRxBps, rxBps),
                peakTxBps = maxOf(prev.peakTxBps, txBps),
                attributed = true,
            )
            return
        }
        // No per-interface source. The device total belongs to exactly one row —
        // the default network — because spreading it over every link would
        // invent traffic on links that carried none.
        if (row.id == defaultId) {
            rates.pushHistory(rxKey(row.id), total.rxBps.toFloat())
            rates.pushHistory(txKey(row.id), total.txBps.toFloat())
            counters[row.id] = Counters(
                rxBytes = traffic.lastTotalRx.coerceAtLeast(0L),
                txBytes = traffic.lastTotalTx.coerceAtLeast(0L),
                rxBps = total.rxBps,
                txBps = total.txBps,
                peakRxBps = maxOf(prev.peakRxBps, total.rxBps),
                peakTxBps = maxOf(prev.peakTxBps, total.txBps),
                attributed = false,
            )
        } else {
            counters[row.id] = Counters(peakRxBps = prev.peakRxBps, peakTxBps = prev.peakTxBps)
        }
    }

    /**
     * The per-interface variants of `TrafficStats` only became public API in
     * API 30, and a handful of vendor images still lack them — a missing method
     * arrives as `NoSuchMethodError`, not an exception, hence the `Throwable`
     * catch and the one-way latch that stops us paying for it every tick.
     */
    private fun ifaceBytes(iface: String, rx: Boolean): Long = try {
        val v = if (rx) TrafficStats.getRxBytes(iface) else TrafficStats.getTxBytes(iface)
        if (v < 0L) -1L else v
    } catch (e: Throwable) {
        perIfaceUsable = false
        -1L
    }

    private fun decorate(rows: List<NetworkRow>): List<NetworkRow> {
        val wifi = if (rows.any { it.transport == Transport.WIFI }) wifiSource.current() else null
        val cell = if (rows.any { it.transport == Transport.CELLULAR }) {
            cellularSource.current()
        } else {
            null
        }
        return rows.map { row ->
            val c = counters[row.id]
            row.copy(
                rxBytes = c?.rxBytes ?: 0L,
                txBytes = c?.txBytes ?: 0L,
                rxBps = c?.rxBps ?: 0L,
                txBps = c?.txBps ?: 0L,
                peakRxBps = c?.peakRxBps ?: 0L,
                peakTxBps = c?.peakTxBps ?: 0L,
                rxHistory = rates.history(rxKey(row.id)),
                txHistory = rates.history(txKey(row.id)),
                wifi = if (row.transport == Transport.WIFI) wifi else null,
                cellular = if (row.transport == Transport.CELLULAR) cell else null,
            )
        }
    }

    private data class Counters(
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L,
        val rxBps: Long = 0L,
        val txBps: Long = 0L,
        val peakRxBps: Long = 0L,
        val peakTxBps: Long = 0L,
        /** True when the numbers came from this interface, not from the device total. */
        val attributed: Boolean = false,
    )

    companion object {
        internal fun rxKey(id: String) = "net.$id.rx"
        internal fun txKey(id: String) = "net.$id.tx"
    }
}
