package com.vlad.ducknetview.domain.model

import com.vlad.ducknetview.domain.net.Trust
import com.vlad.ducknetview.domain.usage.QuotaStatus

/**
 * The immutable snapshot every screen renders from. One is produced per poll
 * tick by the SnapshotAssembler; screens never reach into the engines directly.
 * This mirrors ducknetview's single-enumeration design: one pass feeds the
 * connections, apps and throughput views alike.
 */
data class NetSnapshot(
    val atMillis: Long = 0L,
    val engine: EngineMode = EngineMode.API,
    val paused: Boolean = false,
    val intervalSeconds: Int = 2,
    val deviceName: String = "",
    val uptimeMillis: Long = 0L,
    val networks: List<NetworkRow> = emptyList(),
    val selectedNetworkId: String? = null,
    val conns: List<ConnRow> = emptyList(),
    val closedConns: List<ClosedConn> = emptyList(),
    val apps: List<AppRow> = emptyList(),
    val services: List<ServiceRow> = emptyList(),
    val serviceScanAt: Long = 0L,
    val serviceScanRunning: Boolean = false,
    val neighbours: List<NeighbourRow> = emptyList(),
    val neighbourScanAt: Long = 0L,
    val neighbourScanRunning: Boolean = false,
    /** The prefix the last sweep covered, for the screen to name. */
    val neighbourSubnet: String = "",
    val total: Throughput = Throughput(),
    val totalPeakRx: Long = 0L,
    val totalPeakTx: Long = 0L,
    val sessionRx: Long = 0L,
    val sessionTx: Long = 0L,
    val rxHistory: List<Float> = emptyList(),
    val txHistory: List<Float> = emptyList(),
    val churnHistory: List<Float> = emptyList(),
    val newConnCount: Int = 0,
    val closedConnCount: Int = 0,
    val topApps: List<Talker> = emptyList(),
    val topHosts: List<Talker> = emptyList(),
    val latency: List<LatencySample> = emptyList(),
    val externalIp: String? = null,
    val externalIpAt: Long = 0L,
    val security: SecurityCounts = SecurityCounts(),
    /**
     * The billing cycle against its cap. Always present; [QuotaStatus.configured]
     * is false until the user sets one, and every screen keys off that rather
     * than off a null.
     */
    val quota: QuotaStatus = QuotaStatus(),
    /**
     * Whether the default link is one the automatic responses should fire on.
     *
     * Computed once, by the assembler, because it is the policy outcome and
     * not the raw verdict: it folds in "treat a network I have never seen as
     * untrusted". Both the event producer and the automatic actions read this
     * one field, so the log can never explain an action that did not happen
     * or stay silent about one that did.
     */
    val untrustedDefault: Boolean = false,
    val frozen: Boolean = false,
    val frozenLabel: String? = null,
    val error: String? = null,
)

enum class EngineMode { API, VPN }

/**
 * Whether a byte counter or column has a real source in the current mode.
 * The TUI hides columns it cannot fill rather than showing them empty; the
 * app follows the same rule, driven by this.
 */
data class Capabilities(
    val hasConnections: Boolean = false,
    val hasPerConnBytes: Boolean = false,
    val hasRtt: Boolean = false,
    val hasPerAppLive: Boolean = false,
    // There is deliberately no `hasRetrans`. It existed, it was `false` in
    // every mode and nothing read it: a capability flag with no true case is
    // the capability layer's version of the empty column this class exists to
    // prevent. Android exposes no unprivileged source for retransmit counts,
    // so the column is absent rather than gated — see DESIGN §3.
) {
    companion object {
        val API = Capabilities(hasPerAppLive = false)
        val VPN = Capabilities(
            hasConnections = true,
            hasPerConnBytes = true,
            hasRtt = true,
            hasPerAppLive = true,
        )
        fun of(mode: EngineMode) = if (mode == EngineMode.VPN) VPN else API
    }
}

data class Throughput(val rxBps: Long = 0L, val txBps: Long = 0L)

data class Talker(
    val key: String,
    val label: String,
    val rx: Long,
    val tx: Long,
) {
    val total: Long get() = rx + tx
}

data class SecurityCounts(
    val exposedServices: Int = 0,
    val publicConns: Int = 0,
    val watchlistHits: Int = 0,
    val offBaseline: Int = 0,
)

// ---------------- networks / interfaces ----------------

enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, BLUETOOTH, LOOPBACK, OTHER }

data class NetworkRow(
    val id: String,
    val ifaceName: String,
    val transport: Transport,
    val up: Boolean,
    val addresses: List<String> = emptyList(),
    val mtu: Int = 0,
    val metered: Boolean = false,
    val validated: Boolean = false,
    /**
     * The platform has detected a captive portal on this link: traffic is
     * being intercepted until a sign-in page is satisfied. It is reported
     * separately from [validated] because the two answer different questions —
     * an unvalidated link may simply have no internet, while this one has a
     * middlebox in front of it, and every reading the app takes through it
     * (latency, external IP, reverse DNS, a webhook) is measuring the portal.
     */
    val captivePortal: Boolean = false,
    val isDefault: Boolean = false,
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
    val domains: String? = null,
    val privateDns: String? = null,
    val routes: List<RouteRow> = emptyList(),
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBps: Long = 0L,
    val txBps: Long = 0L,
    val peakRxBps: Long = 0L,
    val peakTxBps: Long = 0L,
    val rxHistory: List<Float> = emptyList(),
    val txHistory: List<Float> = emptyList(),
    val wifi: WifiState? = null,
    val cellular: CellularState? = null,
    /** What the user has said about this network, or that they have not. */
    val trust: Trust = Trust.UNKNOWN,
) {
    /** Loopback, down links and emulator/dummy plumbing the hide-noise filter drops. */
    val isNoise: Boolean
        get() = !up || transport == Transport.LOOPBACK ||
            ifaceName.startsWith("dummy") || ifaceName == "lo"
}

data class RouteRow(
    val destination: String,
    val gateway: String?,
    val iface: String,
    val isDefault: Boolean,
)

data class WifiState(
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int,
    val linkSpeedMbps: Int,
    val txLinkSpeedMbps: Int = -1,
    val rxLinkSpeedMbps: Int = -1,
    val frequencyMhz: Int = 0,
    val standard: String? = null,
) {
    /** dBm rating, matching the TUI's plain-language wording. */
    val rating: String
        get() = when {
            rssiDbm == UNKNOWN_RSSI -> "not measured"
            rssiDbm >= -50 -> "excellent"
            rssiDbm >= -60 -> "good"
            rssiDbm >= -70 -> "fair"
            rssiDbm >= -80 -> "weak"
            else -> "very weak"
        }

    /** Linear 0-100 quality, clamped, from the usual -100..-50 dBm window. */
    val qualityPercent: Int
        get() = when {
            rssiDbm == UNKNOWN_RSSI -> -1
            else -> ((rssiDbm + 100).coerceIn(0, 50) * 2)
        }

    val band: String
        get() = when {
            frequencyMhz == 0 -> ""
            frequencyMhz < 3000 -> "2.4 GHz"
            frequencyMhz < 5900 -> "5 GHz"
            else -> "6 GHz"
        }

    companion object {
        /** Sentinel for "the driver did not report", mirroring the TUI's -256. */
        const val UNKNOWN_RSSI = -256
    }
}

data class CellularState(
    val networkType: String,
    val operator: String?,
    val signalLevel: Int,
)

// ---------------- connections ----------------

enum class Proto { TCP, UDP, ICMP, OTHER;
    override fun toString() = name.lowercase()
}

enum class Scope { LOOPBACK, PRIVATE, PUBLIC, MULTICAST;
    override fun toString() = name.lowercase()
}

enum class ConnState { NEW, SYN_SENT, ESTABLISHED, CLOSING, CLOSED, ACTIVE;
    override fun toString() = name.lowercase()
}

data class ConnRow(
    val key: String,
    val proto: Proto,
    val localAddr: String,
    val localPort: Int,
    val remoteAddr: String,
    val remotePort: Int,
    val state: ConnState,
    val uid: Int,
    val appLabel: String = "",
    val packageName: String = "",
    val service: String = "",
    val scope: Scope = Scope.PUBLIC,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxBps: Long = 0L,
    val txBps: Long = 0L,
    val rttMillis: Int = -1,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val network: String = "",
    val resolvedHost: String? = null,
    val isNew: Boolean = false,
    val watchlisted: Boolean = false,
    val blocked: Boolean = false,
) {
    /**
     * ICMP echo addresses a host, not a service: its ports are the echo
     * identifier and a zero placeholder, so printing them would read as a
     * port number that nothing is listening on.
     */
    private val hasPorts: Boolean get() = proto != Proto.ICMP

    val local: String
        get() = if (hasPorts) fmtAddr(localAddr, localPort) else localAddr

    val remote: String
        get() = if (hasPorts) fmtAddr(remoteAddr, remotePort) else remoteAddr

    /** The echo identifier this flow's requests carry, or -1 when not ICMP. */
    val echoId: Int get() = if (proto == Proto.ICMP) localPort else -1

    /** Resolved name replaces the IP when reverse DNS is on, as in the TUI. */
    fun remoteDisplay(revDns: Boolean): String {
        val host = resolvedHost?.takeIf { revDns && it.isNotEmpty() }
        return when {
            host == null -> remote
            hasPorts -> fmtAddr(host, remotePort)
            else -> host
        }
    }

    fun ageMillis(now: Long): Long = (now - firstSeen).coerceAtLeast(0L)
}

/** IPv6 literals need brackets before a :port suffix or the result is ambiguous. */
fun fmtAddr(addr: String, port: Int): String =
    if (addr.contains(':') && !addr.startsWith("[")) "[$addr]:$port" else "$addr:$port"

data class ClosedConn(
    val row: ConnRow,
    val closedAt: Long,
    val lifetimeMillis: Long,
    /** Final counters, which for a closed socket are its true lifetime totals. */
    val finalRx: Long,
    val finalTx: Long,
)

// ---------------- apps ----------------

data class AppRow(
    val uid: Int,
    val packageName: String,
    val label: String,
    val isSystem: Boolean = false,
    val connCount: Int = 0,
    val rxBps: Long = 0L,
    val txBps: Long = 0L,
    val sessionRx: Long = 0L,
    val sessionTx: Long = 0L,
    val todayRx: Long = 0L,
    val todayTx: Long = 0L,
    val blocked: Boolean = false,
    val excludedFromVpn: Boolean = false,
    val remoteHosts: Int = 0,
    /**
     * Connections refused on this app's behalf since capture started.
     *
     * The firewall is the app's one enforcement action and it used to leave no
     * trace at all: a blocked app simply has no flows, which looks identical
     * to an app that was not trying. This is the evidence that the switch did
     * something.
     */
    val blockedAttempts: Long = 0L,
)

/**
 * One connection refused because its app is blocked.
 *
 * Observed on the capture engine's packet path and buffered for the poll tick
 * rather than acted on where it is seen — the same rule a DNS answer follows,
 * and for the same reason: a blocked app retries hard, and a database write
 * behind every refusal would put the cost of the block on the packet path.
 *
 * [appLabel] is filled when the batch is drained, not here. The packet path
 * knows only a uid, and an event reading "a connection was refused" without
 * saying whose is most of the value missing.
 */
data class BlockedAttempt(
    val uid: Int,
    val proto: Proto,
    val remoteAddr: String,
    val remotePort: Int,
    val at: Long,
    val appLabel: String = "",
) {
    val remote: String
        get() = if (proto == Proto.ICMP) remoteAddr else fmtAddr(remoteAddr, remotePort)
}

// ---------------- services (self port scan) ----------------

enum class Exposure { LOCAL, LAN, EXPOSED }

data class ServiceRow(
    val proto: Proto,
    val bindAddr: String,
    val port: Int,
    val service: String = "",
    val exposure: Exposure = Exposure.LOCAL,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val isNew: Boolean = false,
    val offBaseline: Boolean = false,
    val uid: Int = -1,
    val appLabel: String = "",
) {
    /** Stable identity used by the baseline and by move-correlation. */
    val key: String get() = "$proto|$bindAddr:$port"
    val baselineKey: String get() = "$proto|$bindAddr:$port"
}

// ---------------- lan neighbours ----------------

/**
 * How a neighbour was found. A row says which, as the Domains rows do.
 *
 * [BOTH] exists because most hosts on a real network answer both ways, and
 * the first version of this reported whichever source happened to arrive
 * first: a printer named `Canon MF270 Series` from an mDNS record was
 * labelled "via ping" on the test tablet, which is a claim the ping could not
 * possibly have supported. The value is now derived from the evidence on the
 * row rather than from arrival order, so it cannot contradict the name beside
 * it.
 */
enum class NeighbourSource(val label: String) {
    PING("ping"),
    MDNS("mDNS"),
    BOTH("ping + mDNS"),
}

/**
 * One host answering on the same link as this device.
 *
 * The Routes screen used to stop at "the ARP table is not available on
 * Android 10 and later", which is true and was the end of it. The listeners
 * question had the same shape and was answered by knocking on the device's own
 * doors; this answers the neighbours question the same way, by asking the link
 * rather than by reading a table the platform will not show.
 *
 * Unlike a real neighbour table this cannot see a host that answers nothing —
 * a device with ICMP filtered and no mDNS is silent here and present on the
 * network. The screen says so.
 */
data class NeighbourRow(
    val address: String,
    val hostname: String = "",
    val source: NeighbourSource = NeighbourSource.PING,
    val rttMillis: Int = -1,
    val services: List<String> = emptyList(),
    val isSelf: Boolean = false,
    val isGateway: Boolean = false,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
) {
    val key: String get() = address

    /** True when mDNS contributed: a resolve always records its service type. */
    val fromMdns: Boolean get() = services.isNotEmpty()

    /** True when an echo came back; -1 is "no reply", not "zero latency". */
    val answeredPing: Boolean get() = rttMillis >= 0

    /** What the user reads first: a name when one was learnt, else the address. */
    val display: String get() = hostname.ifEmpty { address }
}

// ---------------- latency ----------------

/**
 * How a latency figure was obtained. The two are not interchangeable: a TCP
 * handshake includes the peer's accept path, an ICMP echo does not, so a
 * reading is labelled with the method that produced it rather than presented
 * as a single abstract "latency".
 */
enum class LatencyMethod(val label: String) {
    TCP("TCP handshake"),
    ICMP("ICMP echo"),
}

data class LatencySample(
    val target: String,
    val label: String,
    val millis: Int,
    val ok: Boolean,
    val history: List<Float> = emptyList(),
    val note: String? = null,
    val method: LatencyMethod = LatencyMethod.TCP,
)
