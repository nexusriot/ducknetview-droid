package com.vlad.ducknetview.engine.vpn

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.engine.vpn.packet.DnsPeek
import com.vlad.ducknetview.engine.vpn.packet.IpHeader
import com.vlad.ducknetview.engine.vpn.packet.IpProto
import com.vlad.ducknetview.engine.vpn.packet.PacketParser
import com.vlad.ducknetview.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress

/**
 * The local capture engine. All device traffic is routed into a TUN device we
 * own, parsed here, and relayed onward through protect()ed sockets — which is
 * what makes this app the accountant for every flow without root.
 *
 * Packet payloads are relayed and counted, never stored: this is a monitor,
 * not a sniffer.
 */
class DuckVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private val pool = ByteArrayPool()
    private val writeQueue = Channel<PacketBuf>(1024)

    private val table = FlowTable()
    private val tcpFlows = HashMap<FlowKey, TcpConnection>()
    private val udpFlows = HashMap<FlowKey, UdpConnection>()
    private val flowLock = Any()

    private lateinit var connectivity: ConnectivityManager

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            stopSelf()
            return START_NOT_STICKY
        }
        startEngine()
        return START_STICKY
    }

    private fun startEngine() {
        if (tunFd != null) return

        val notification = Notifications.engineNotification(this, 0L, 0L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                Notifications.ENGINE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(Notifications.ENGINE_ID, notification)
        }

        val builder = Builder()
            .setSession(SESSION)
            .setMtu(MTU)
            .addAddress(TUN_V4, 30)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUN_V6, 126)
            .addRoute("::", 0)

        // Our own traffic normally stays off the TUN. The proxy's upstream
        // sockets are protect()ed either way; excluding the package keeps our
        // probes and fetches out of the table as noise. Instrumented tests
        // flip this so they can observe their own requests being captured.
        if (!VpnBridge.captureOwnTraffic) {
            runCatching { builder.addDisallowedApplication(packageName) }
        }

        for (pkg in VpnBridge.excludedPackages) {
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        val fd = try {
            builder.establish()
        } catch (e: Exception) {
            VpnBridge.reportError("could not establish the TUN device: ${e.message}")
            stopSelf()
            return
        }
        if (fd == null) {
            VpnBridge.reportError("VPN permission was not granted")
            stopSelf()
            return
        }

        tunFd = fd
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        VpnBridge.attach(table)

        s.launch { readLoop(FileInputStream(fd.fileDescriptor)) }
        s.launch { writeLoop(FileOutputStream(fd.fileDescriptor)) }
        s.launch { expiryLoop() }
    }

    /**
     * The writer side of the engine. Buffers come from one pool: a producer
     * borrows one, fills it, and submits it; the write loop returns it once the
     * bytes have reached the TUN. A producer must never touch a buffer after
     * submitting it.
     */
    private inner class QueueWriter : TunWriter {
        override fun acquire(size: Int): PacketBuf = pool.acquire(size)

        override fun submit(buf: PacketBuf) {
            // A full queue means the TUN cannot keep up; the packet is dropped
            // (the peer will retransmit) and its buffer goes straight back.
            if (!writeQueue.trySend(buf).isSuccess) pool.release(buf)
        }

        override fun release(buf: PacketBuf) = pool.release(buf)
    }

    private suspend fun readLoop(input: FileInputStream) {
        val buffer = ByteArray(MTU + 80)
        val writer = QueueWriter()
        // The parser's scratch headers belong to this loop alone, and stay
        // valid only for the dispatch call they were filled for.
        val parser = PacketParser()
        while (scope?.isActive == true) {
            val n = try {
                input.read(buffer)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) continue
            try {
                dispatch(buffer, n, writer, parser)
            } catch (e: Exception) {
                // One malformed packet must never take the engine down.
            }
        }
    }

    private fun dispatch(buf: ByteArray, length: Int, writer: TunWriter, parser: PacketParser) {
        val ip = parser.parseIp(buf, length) ?: return
        val now = System.currentTimeMillis()
        when (ip.protocol) {
            IpProto.TCP -> dispatchTcp(buf, ip, writer, parser, now)
            IpProto.UDP -> dispatchUdp(buf, ip, writer, parser, now)
        }
    }

    private fun dispatchTcp(
        buf: ByteArray,
        ip: IpHeader,
        writer: TunWriter,
        parser: PacketParser,
        now: Long,
    ) {
        val tcp = parser.parseTcp(buf, ip) ?: return
        val key = FlowKey(Proto.TCP, ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort)

        val conn: TcpConnection? = synchronized(flowLock) {
            var c = tcpFlows[key]
            if (c == null && tcp.isSyn && !tcp.isAck) {
                val flow = table.open(key, ip.version, now)
                flow.uid = lookupUid(IpProto.TCP, ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort)
                flow.network = VpnBridge.currentNetworkLabel
                c = TcpConnection(
                    flow = flow,
                    ipVersion = ip.version,
                    appRaw = ip.srcRaw(buf),
                    remoteRaw = ip.dstRaw(buf),
                    tun = writer,
                    protect = { sock -> protect(sock) },
                    scope = scope ?: return@synchronized null,
                    mtu = MTU,
                    onClosed = { k -> closeTcp(k) },
                )
                tcpFlows[key] = c
                if (VpnBridge.isBlocked(flow.uid)) {
                    c.reject()
                    return@synchronized null
                }
                c.onSyn(tcp, now)
                return@synchronized null
            }
            c
        }
        conn?.onPacket(tcp, buf, now)
    }

    private fun dispatchUdp(
        buf: ByteArray,
        ip: IpHeader,
        writer: TunWriter,
        parser: PacketParser,
        now: Long,
    ) {
        val udp = parser.parseUdp(buf, ip) ?: return
        val key = FlowKey(Proto.UDP, ip.srcIp, udp.srcPort, ip.dstIp, udp.dstPort)
        val payloadOffset = udp.payloadOffset
        val payloadLength = udp.payloadLength

        val conn = synchronized(flowLock) {
            var c = udpFlows[key]
            if (c == null) {
                val flow = table.open(key, ip.version, now)
                flow.uid = lookupUid(IpProto.UDP, ip.srcIp, udp.srcPort, ip.dstIp, udp.dstPort)
                flow.network = VpnBridge.currentNetworkLabel
                if (VpnBridge.isBlocked(flow.uid)) {
                    table.close(key, now, "", "")
                    return@synchronized null
                }
                c = UdpConnection(
                    flow = flow,
                    ipVersion = ip.version,
                    appRaw = ip.srcRaw(buf),
                    remoteRaw = ip.dstRaw(buf),
                    tun = writer,
                    protect = { sock -> protect(sock) },
                    scope = scope ?: return@synchronized null,
                    onDnsPayload = { data -> notePassiveDns(data) },
                    onClosed = { k -> closeUdp(k) },
                )
                if (!c.start(now)) {
                    table.close(key, now, "", "")
                    return@synchronized null
                }
                udpFlows[key] = c
            }
            c
        }
        conn?.send(buf, payloadOffset, payloadLength, now)
    }

    private fun notePassiveDns(payload: ByteArray) {
        for (a in DnsPeek.parseAnswers(payload)) {
            VpnBridge.noteDnsAnswer(a.ip, a.name)
        }
    }

    /**
     * The active VPN may ask the framework who owns a socket. INVALID_UID is a
     * normal answer for very short-lived flows, so the row renders as an
     * unknown app rather than being dropped.
     */
    private fun lookupUid(protocol: Int, src: String, sport: Int, dst: String, dport: Int): Int =
        try {
            connectivity.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(src, sport),
                InetSocketAddress(dst, dport),
            )
        } catch (e: Exception) {
            Process.INVALID_UID
        }

    private fun closeTcp(key: FlowKey) {
        synchronized(flowLock) { tcpFlows.remove(key) }
        val label = VpnBridge.labelFor(table.get(key)?.uid ?: -1)
        table.close(key, System.currentTimeMillis(), label.first, label.second)
    }

    private fun closeUdp(key: FlowKey) {
        synchronized(flowLock) { udpFlows.remove(key) }
        val label = VpnBridge.labelFor(table.get(key)?.uid ?: -1)
        table.close(key, System.currentTimeMillis(), label.first, label.second)
    }

    /**
     * Drains the queue rather than suspending per packet: under load a burst
     * costs one coroutine resume instead of one per segment. Each buffer is
     * released the moment its bytes are on the fd, and never touched after.
     */
    private suspend fun writeLoop(output: FileOutputStream) {
        for (first in writeQueue) {
            var packet: PacketBuf? = first
            while (packet != null) {
                try {
                    output.write(packet.array, 0, packet.length)
                } catch (e: Exception) {
                    pool.release(packet)
                    return
                }
                pool.release(packet)
                packet = writeQueue.tryReceive().getOrNull()
            }
        }
    }

    private suspend fun expiryLoop() {
        while (scope?.isActive == true) {
            kotlinx.coroutines.delay(EXPIRY_TICK_MS)
            val now = System.currentTimeMillis()
            val doomed = ArrayList<FlowKey>()
            table.expire(now, UDP_IDLE_MS, TCP_IDLE_MS) { doomed += it.key }
            for (k in doomed) {
                synchronized(flowLock) {
                    udpFlows.remove(k)?.close()
                    tcpFlows.remove(k)?.abort()
                }
                table.close(k, now, "", "")
            }
            VpnBridge.updateNotification(this)
        }
    }

    override fun onRevoke() {
        VpnBridge.reportRevoked()
        stopEngine()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    private fun stopEngine() {
        scope?.cancel()
        scope = null
        // Take a copy and empty the maps before closing anything: close()
        // calls back into closeTcp/closeUdp, which remove from these same maps
        // under a reentrant lock, so closing while iterating throws
        // ConcurrentModificationException and takes the process down.
        val tcp: List<TcpConnection>
        val udp: List<UdpConnection>
        synchronized(flowLock) {
            tcp = tcpFlows.values.toList()
            udp = udpFlows.values.toList()
            tcpFlows.clear()
            udpFlows.clear()
        }
        tcp.forEach { runCatching { it.close() } }
        udp.forEach { runCatching { it.close() } }
        runCatching { tunFd?.close() }
        tunFd = null
        pool.clear()
        table.clear()
        VpnBridge.detach()
        ServiceCompat.stopForeground(this, Service.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_STOP = "com.vlad.ducknetview.STOP_ENGINE"
        const val SESSION = "ducknetview"
        const val MTU = 1500
        const val TUN_V4 = "10.215.173.1"
        const val TUN_V6 = "fd00:6475:636b::1"
        private const val EXPIRY_TICK_MS = 5000L
        private const val UDP_IDLE_MS = 60_000L
        private const val TCP_IDLE_MS = 600_000L

        /**
         * The service calls startForeground(), so it must be launched with
         * startForegroundService() on O+. Some OEM builds (seen on Allwinner's
         * "awbms" background manager) additionally drop service starts from
         * apps the user has not allowed to run in the background; that shows up
         * as the engine never attaching, so the caller is told rather than left
         * with a silently dead toggle.
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DuckVpnService::class.java),
            )
            true
        }.getOrElse {
            VpnBridge.reportError("the system refused to start the capture service: ${it.message}")
            false
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, DuckVpnService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}

/**
 * The seam between the service process components and the rest of the app.
 * The service publishes its flow table here; the ViewModel reads it.
 */
object VpnBridge {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors.asStateFlow()

    private val _revoked = MutableStateFlow(false)
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    @Volatile var table: FlowTable? = null
        private set

    @Volatile var currentNetworkLabel: String = ""

    @Volatile var excludedPackages: Set<String> = emptySet()

    /** Instrumented tests set this to route the test process's own traffic
     *  through the TUN, which is the only way to assert capture end to end. */
    @Volatile var captureOwnTraffic: Boolean = false

    @Volatile private var blockedUids: Set<Int> = emptySet()

    @Volatile var labelResolver: (Int) -> Pair<String, String> = { "" to "" }

    @Volatile var dnsSink: (String, String) -> Unit = { _, _ -> }

    @Volatile var notificationUpdater: (Context) -> Unit = { }

    fun attach(t: FlowTable) {
        table = t
        _running.value = true
        _revoked.value = false
    }

    fun detach() {
        table = null
        _running.value = false
    }

    fun setBlocked(uids: Set<Int>) {
        blockedUids = uids
    }

    fun isBlocked(uid: Int): Boolean = uid in blockedUids

    fun labelFor(uid: Int): Pair<String, String> = labelResolver(uid)

    fun noteDnsAnswer(ip: String, name: String) = dnsSink(ip, name)

    fun reportError(message: String) {
        _errors.value = message
    }

    fun clearError() {
        _errors.value = null
    }

    fun reportRevoked() {
        _revoked.value = true
        detach()
    }

    fun updateNotification(context: Context) = notificationUpdater(context)
}
