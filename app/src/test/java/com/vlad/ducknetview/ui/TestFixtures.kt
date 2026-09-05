package com.vlad.ducknetview.ui

import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.CellularState
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.EventLevelFilter
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.RouteRow
import com.vlad.ducknetview.domain.model.SecurityCounts
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.model.WifiState

/** Shared UiState fixtures for the pure-composable screen tests. */
object Fx {

    const val NOW = 1_700_000_000_000L

    fun wifi(): NetworkRow = NetworkRow(
        id = "net-wifi",
        ifaceName = "wlan0",
        transport = Transport.WIFI,
        up = true,
        addresses = listOf("192.168.1.42/24", "fe80::1/64"),
        mtu = 1500,
        metered = false,
        validated = true,
        isDefault = true,
        gateway = "192.168.1.1",
        dnsServers = listOf("8.8.8.8", "1.1.1.1"),
        privateDns = "dns.duckpond.net",
        routes = listOf(
            RouteRow("0.0.0.0/0", "192.168.1.1", "wlan0", true),
            RouteRow("192.168.1.0/24", null, "wlan0", false),
        ),
        rxBytes = 4_500_000L,
        txBytes = 900_000L,
        rxBps = 120_000L,
        txBps = 30_000L,
        peakRxBps = 500_000L,
        peakTxBps = 80_000L,
        rxHistory = listOf(1f, 4f, 2f, 8f),
        txHistory = listOf(1f, 1f, 3f, 2f),
        wifi = WifiState(
            ssid = "duckpond",
            bssid = "aa:bb:cc:dd:ee:ff",
            rssiDbm = -55,
            linkSpeedMbps = 433,
            txLinkSpeedMbps = 433,
            rxLinkSpeedMbps = 390,
            frequencyMhz = 5180,
            standard = "Wi-Fi 6",
        ),
    )

    fun cellular(): NetworkRow = NetworkRow(
        id = "net-cell",
        ifaceName = "rmnet0",
        transport = Transport.CELLULAR,
        up = true,
        addresses = listOf("10.44.1.2/30"),
        mtu = 1400,
        metered = true,
        validated = true,
        gateway = "10.44.1.1",
        dnsServers = listOf("2001:4860:4860::8888"),
        routes = listOf(RouteRow("0.0.0.0/0", "10.44.1.1", "rmnet0", true)),
        rxBps = 4_000L,
        txBps = 1_000L,
        cellular = CellularState(networkType = "LTE", operator = "DuckMobile", signalLevel = 3),
    )

    fun loopback(): NetworkRow = NetworkRow(
        id = "net-lo",
        ifaceName = "lo",
        transport = Transport.LOOPBACK,
        up = true,
        addresses = listOf("127.0.0.1/8"),
        mtu = 65536,
        routes = listOf(RouteRow("127.0.0.0/8", null, "lo", false)),
    )

    fun latency(): List<LatencySample> = listOf(
        LatencySample(
            target = "192.168.1.1",
            label = "gateway",
            millis = 7,
            ok = true,
            history = listOf(6f, 8f, 7f),
        ),
        LatencySample(
            target = "1.1.1.1",
            label = "cloudflare",
            millis = -1,
            ok = false,
            history = emptyList(),
            note = "no route to host",
        ),
    )

    fun talkerApps(): List<Talker> = listOf(
        Talker(key = "uid:10120", label = "Chrome", rx = 3_000_000L, tx = 500_000L),
        Talker(key = "uid:10044", label = "Maps", rx = 400_000L, tx = 90_000L),
    )

    fun talkerHosts(): List<Talker> = listOf(
        Talker(key = "203.0.113.20", label = "cdn.example.com", rx = 900_000L, tx = 100_000L),
    )

    fun snapshot(
        engine: EngineMode = EngineMode.API,
        paused: Boolean = false,
        networks: List<NetworkRow> = listOf(wifi(), loopback()),
        topApps: List<Talker> = emptyList(),
        topHosts: List<Talker> = emptyList(),
        latencySamples: List<LatencySample> = latency(),
        externalIp: String? = "203.0.113.9",
        security: SecurityCounts = SecurityCounts(
            exposedServices = 2,
            publicConns = 3,
            watchlistHits = 1,
            offBaseline = 4,
        ),
        selectedNetworkId: String? = null,
    ): NetSnapshot = NetSnapshot(
        atMillis = NOW,
        engine = engine,
        paused = paused,
        intervalSeconds = 2,
        deviceName = "Pixel Duck",
        uptimeMillis = 3 * 3_600_000L + 25 * 60_000L,
        networks = networks,
        selectedNetworkId = selectedNetworkId,
        total = Throughput(rxBps = 120_000L, txBps = 30_000L),
        totalPeakRx = 500_000L,
        totalPeakTx = 80_000L,
        sessionRx = 4_500_000L,
        sessionTx = 900_000L,
        rxHistory = listOf(1f, 4f, 2f, 8f),
        txHistory = listOf(1f, 1f, 3f, 2f),
        churnHistory = listOf(0f, 3f, 1f, 5f),
        newConnCount = 4,
        closedConnCount = 2,
        topApps = topApps,
        topHosts = topHosts,
        latency = latencySamples,
        externalIp = externalIp,
        externalIpAt = NOW - 12_000L,
        security = security,
    )

    /** A snapshot before the first poll has produced anything. */
    fun barrenSnapshot(): NetSnapshot = NetSnapshot(atMillis = NOW, deviceName = "Pixel Duck")

    fun state(
        snapshot: NetSnapshot = snapshot(),
        settings: AppSettings = AppSettings(),
        caps: Capabilities = Capabilities.of(snapshot.engine),
        status: String? = null,
        unackedAlerts: Int = 0,
    ): UiState = UiState(
        snapshot = snapshot,
        caps = caps,
        settings = settings,
        status = status,
        unackedAlerts = unackedAlerts,
        vpnRunning = snapshot.engine == EngineMode.VPN,
    )

    fun apiState(): UiState = state(snapshot(engine = EngineMode.API))

    fun vpnState(): UiState = state(
        snapshot(
            engine = EngineMode.VPN,
            topApps = talkerApps(),
            topHosts = talkerHosts(),
        ),
    )
}

/** Records every call so tests can assert on what a screen raised. */
class FakeActions : UiActions {

    val calls = mutableListOf<Pair<String, Any?>>()

    fun names(): List<String> = calls.map { it.first }
    fun count(name: String): Int = calls.count { it.first == name }
    fun argsFor(name: String): List<Any?> = calls.filter { it.first == name }.map { it.second }
    fun lastArg(name: String): Any? = argsFor(name).lastOrNull()

    private fun record(name: String, arg: Any? = null) {
        calls += name to arg
    }

    override fun setTab(tab: Tab) = record("setTab", tab)
    override fun togglePause() = record("togglePause")
    override fun setInterval(seconds: Int) = record("setInterval", seconds)
    override fun refreshNow() = record("refreshNow")
    override fun toggleRateUnit() = record("toggleRateUnit")
    override fun toggleThroughputMode() = record("toggleThroughputMode")
    override fun toggleHideNoise() = record("toggleHideNoise")
    override fun toggleRevDns() = record("toggleRevDns")
    override fun toggleGrouping() = record("toggleGrouping")
    override fun toggleShowClosed() = record("toggleShowClosed")
    override fun selectNetwork(id: String) = record("selectNetwork", id)

    override fun setSearch(query: String) = record("setSearch", query)
    override fun toggleSearchMode() = record("toggleSearchMode")
    override fun clearSearch() = record("clearSearch")

    override fun setSort(table: String, column: String) = record("setSort", table to column)
    override fun setProtoFilter(f: ProtoFilter) = record("setProtoFilter", f)
    override fun setIpVersionFilter(f: IpVersionFilter) = record("setIpVersionFilter", f)
    override fun setStateFilter(f: StateFilter) = record("setStateFilter", f)
    override fun togglePublicOnly() = record("togglePublicOnly")
    override fun toggleUserAppsOnly() = record("toggleUserAppsOnly")
    override fun setNetworkFilter(network: String?) = record("setNetworkFilter", network)
    override fun setUidFilter(uid: Int?) = record("setUidFilter", uid)

    override fun startVpn() = record("startVpn")
    override fun stopVpn() = record("stopVpn")

    override fun scanServices() = record("scanServices")
    override fun setScanFullRange(full: Boolean) = record("setScanFullRange", full)
    override fun saveBaseline() = record("saveBaseline")
    override fun clearBaseline() = record("clearBaseline")
    override fun acceptIntoBaseline(row: ServiceRow) = record("acceptIntoBaseline", row)

    override fun toggleWatchlist(remote: String) = record("toggleWatchlist", remote)
    override fun addWatchlistEntry(entry: String) = record("addWatchlistEntry", entry)
    override fun removeWatchlistEntry(entry: String) = record("removeWatchlistEntry", entry)

    override fun blockApp(uid: Int, blocked: Boolean) = record("blockApp", uid to blocked)
    override fun excludeApp(uid: Int, excluded: Boolean) = record("excludeApp", uid to excluded)
    override fun openAppInfo(packageName: String) = record("openAppInfo", packageName)

    override fun copyText(label: String, text: String) = record("copyText", label to text)
    override fun exportCurrentTable() = record("exportCurrentTable")
    override fun exportSnapshotJson() = record("exportSnapshotJson")
    override fun exportEvents() = record("exportEvents")

    override fun setEventFilter(f: EventLevelFilter) = record("setEventFilter", f)
    override fun ackAlerts() = record("ackAlerts")
    override fun clearEvents() = record("clearEvents")

    override fun refreshExternalIp() = record("refreshExternalIp")
    override fun updateSettings(block: (AppSettings) -> AppSettings) =
        record("updateSettings", block)

    override fun requestUsageAccess() = record("requestUsageAccess")
    override fun requestWifiPermission() = record("requestWifiPermission")
    override fun dismissStatus() = record("dismissStatus")

    var openSnapshotCount = 0
    override fun openSnapshot() { openSnapshotCount++ }
    var closeSnapshotCount = 0
    override fun closeSnapshot() { closeSnapshotCount++ }
    var refreshUsageCount = 0
    override fun refreshUsage() { refreshUsageCount++ }

    var nextMatchCount = 0
    override fun nextMatch() { nextMatchCount++ }
    var prevMatchCount = 0
    override fun prevMatch() { prevMatchCount++ }
}
