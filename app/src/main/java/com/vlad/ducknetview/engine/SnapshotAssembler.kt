package com.vlad.ducknetview.engine

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.SecurityCounts
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.rates.RateTracker
import com.vlad.ducknetview.domain.rdns.RdnsCache
import com.vlad.ducknetview.domain.totals.SessionTotals
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.engine.api.ApiSample
import com.vlad.ducknetview.engine.api.AppCatalog
import com.vlad.ducknetview.engine.api.TrafficSampler
import com.vlad.ducknetview.engine.vpn.FlowTable

/**
 * Builds the one immutable snapshot per tick that every screen renders from.
 *
 * Rates come from diffing the previous poll's counters, and session totals are
 * accumulated from those deltas — so they describe what happened while the app
 * was watching, not the whole life of a socket that predates it.
 */
class SnapshotAssembler(
    private val catalog: AppCatalog,
    private val rates: RateTracker,
    private val totals: SessionTotals,
    private val rdns: RdnsCache,
    private val historySize: Int = 60,
) {
    /**
     * Flow rates live in their own tracker, apart from the device, per-interface
     * and per-UID series in [rates].
     *
     * Flows come and go constantly, so the flow side has to `retain()` the keys
     * still alive on every tick. `retain` drops everything it is not given, and
     * while the two shared one tracker that call deleted the API engine's series
     * once per poll: with capture on, device throughput, every link's rate and
     * per-app throughput all read zero, because each poll re-baselined them. Two
     * trackers mean the flow side can prune as aggressively as it needs to
     * without reaching into series it does not own.
     */
    private val flowRates = RateTracker(historySize)

    private var deviceName: String = ""
    private var startedAt: Long = 0L
    private var peakRx = 0L
    private var peakTx = 0L
    private val rxHistory = ArrayDeque<Float>()
    private val txHistory = ArrayDeque<Float>()
    private val churnHistory = ArrayDeque<Float>()
    private var seenConnKeys = HashSet<String>()
    private var services: List<ServiceRow> = emptyList()
    private var serviceScanAt: Long = 0L
    private var todayPerUid: Map<Int, Pair<Long, Long>> = emptyMap()

    fun configure(deviceName: String, startedAt: Long) {
        this.deviceName = deviceName
        this.startedAt = startedAt
    }

    fun setServices(rows: List<ServiceRow>, at: Long) {
        services = rows
        serviceScanAt = at
    }

    /**
     * Today's per-UID totals from NetworkStatsManager, refreshed on the slow
     * cadence rather than every poll because the query is expensive.
     *
     * Without this the Apps screen's "today" column — and the `today` sort, and
     * the CSV and JSON exports — read a flat 0 B for every app: the engine was
     * fetching these numbers on schedule and discarding them.
     */
    fun setTodayUsage(byUid: Map<Int, Pair<Long, Long>>) {
        todayPerUid = byUid
    }

    fun assemble(
        now: Long,
        api: ApiSample,
        flows: FlowTable?,
        settings: AppSettings,
        baseline: Baseline,
        watchlist: Watchlist,
        externalIp: String?,
        externalIpAt: Long,
        latency: List<com.vlad.ducknetview.domain.model.LatencySample>,
        scanRunning: Boolean,
    ): NetSnapshot {
        val mode = if (flows != null) EngineMode.VPN else EngineMode.API

        val conns = if (flows != null) buildConns(flows, now, settings, watchlist) else emptyList()
        val apps = buildApps(conns, api, flows != null)

        // The grand session total integrates the same device counters that feed
        // the "Now" and "Session peak" rows beside it, so it is available in API
        // mode too — where there are no flows to add up at all.
        totals.addDevice(
            rates.deltaOf(TrafficSampler.KEY_DEVICE_RX),
            rates.deltaOf(TrafficSampler.KEY_DEVICE_TX),
        )

        peakRx = maxOf(peakRx, api.total.rxBps)
        peakTx = maxOf(peakTx, api.total.txBps)
        push(rxHistory, api.total.rxBps.toFloat())
        push(txHistory, api.total.txBps.toFloat())

        val (opened, closedCount) = flows?.drainChurn() ?: (0 to 0)
        push(churnHistory, (opened + closedCount).toFloat())

        val markedServices = services.map { row ->
            row.copy(offBaseline = baseline.isOffBaseline(row))
        }

        return NetSnapshot(
            atMillis = now,
            engine = mode,
            paused = settings.paused,
            intervalSeconds = settings.intervalSeconds,
            deviceName = deviceName,
            uptimeMillis = (now - startedAt).coerceAtLeast(0L),
            networks = api.networks,
            selectedNetworkId = api.networks.firstOrNull { it.isDefault }?.id,
            conns = conns,
            closedConns = flows?.closedSnapshot() ?: emptyList(),
            apps = apps,
            services = markedServices,
            serviceScanAt = serviceScanAt,
            serviceScanRunning = scanRunning,
            total = api.total,
            totalPeakRx = peakRx,
            totalPeakTx = peakTx,
            sessionRx = totals.sessionRx,
            sessionTx = totals.sessionTx,
            rxHistory = rxHistory.toList(),
            txHistory = txHistory.toList(),
            churnHistory = churnHistory.toList(),
            newConnCount = opened,
            closedConnCount = closedCount,
            topApps = totals.topApps(5),
            topHosts = totals.topHosts(5),
            latency = latency,
            externalIp = externalIp,
            externalIpAt = externalIpAt,
            security = securityCounts(markedServices, conns),
            frozen = false,
        )
    }

    private fun buildConns(
        flows: FlowTable,
        now: Long,
        settings: AppSettings,
        watchlist: Watchlist,
    ): List<ConnRow> {
        val live = flows.live()
        val keys = HashSet<String>(live.size)
        val out = ArrayList<ConnRow>(live.size)

        for (f in live) {
            val id = f.key.toString()
            keys += id
            val rx = f.rx.get()
            val tx = f.tx.get()
            val rxBps = flowRates.update("$id|rx", rx, now)
            val txBps = flowRates.update("$id|tx", tx, now)

            val (label, pkg) = catalog.row(f.uid).let { it.label to it.packageName }
            val host = rdns.get(f.key.dstIp)
            val row = flows.toRow(
                f = f,
                now = now,
                appLabel = label,
                packageName = pkg,
                rxBps = rxBps,
                txBps = txBps,
                isNew = id !in seenConnKeys,
                resolvedHost = host,
                watchlisted = watchlist.matches(f.key.dstIp, host),
            )
            out += row

            if (rxBps > 0 || txBps > 0) {
                val dRx = flowRates.deltaOf("$id|rx")
                val dTx = flowRates.deltaOf("$id|tx")
                totals.add(f.uid, label, dRx, dTx)
                totals.addHost(host ?: f.key.dstIp, dRx, dTx)
            }
        }

        flowRates.retain(keys.flatMap { listOf("$it|rx", "$it|tx") }.toSet())
        rdns.retain(live.map { it.key.dstIp }.toSet())
        seenConnKeys = keys
        return out
    }

    private fun buildApps(
        conns: List<ConnRow>,
        api: ApiSample,
        vpnMode: Boolean,
    ): List<AppRow> {
        val byUid = HashMap<Int, MutableList<ConnRow>>()
        for (c in conns) byUid.getOrPut(c.uid) { ArrayList() } += c

        val uids = if (vpnMode) byUid.keys.toSet() else api.perUid.keys
        return uids.map { uid ->
            val rows = byUid[uid].orEmpty()
            val base = catalog.row(uid)
            val fromFlows = Throughput(
                rxBps = rows.sumOf { it.rxBps },
                txBps = rows.sumOf { it.txBps },
            )
            val t = if (vpnMode) fromFlows else (api.perUid[uid] ?: Throughput())
            val session = totals.app(uid)
            val today = todayPerUid[uid]
            base.copy(
                connCount = rows.size,
                rxBps = t.rxBps,
                txBps = t.txBps,
                sessionRx = session?.rx ?: 0L,
                sessionTx = session?.tx ?: 0L,
                todayRx = today?.first ?: 0L,
                todayTx = today?.second ?: 0L,
                remoteHosts = rows.map { it.remoteAddr }.distinct().size,
            )
        }
    }

    private fun securityCounts(services: List<ServiceRow>, conns: List<ConnRow>) = SecurityCounts(
        exposedServices = services.count {
            it.exposure == com.vlad.ducknetview.domain.model.Exposure.EXPOSED
        },
        publicConns = conns.count { it.scope == Scope.PUBLIC },
        watchlistHits = conns.count { it.watchlisted },
        offBaseline = services.count { it.offBaseline },
    )

    private fun push(q: ArrayDeque<Float>, v: Float) {
        q.addLast(v)
        while (q.size > historySize) q.removeFirst()
    }

    fun reset() {
        peakRx = 0
        peakTx = 0
        rxHistory.clear()
        txHistory.clear()
        churnHistory.clear()
        seenConnKeys = HashSet()
    }

    companion object {
        val UDP: Proto = Proto.UDP
        val CAPS_VPN = Capabilities.VPN
        val CAPS_API = Capabilities.API
    }
}
