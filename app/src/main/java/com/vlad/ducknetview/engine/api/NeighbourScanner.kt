package com.vlad.ducknetview.engine.api

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.model.NeighbourSource
import com.vlad.ducknetview.domain.net.Subnet
import com.vlad.ducknetview.engine.vpn.EchoSocket
import com.vlad.ducknetview.engine.vpn.PingSockets
import com.vlad.ducknetview.engine.vpn.packet.Icmp
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android replacement for the TUI's ARP / neighbour table.
 *
 * `/proc/net/arp` has been unreadable outside the system UID since Android 10
 * and there is no public API for the neighbour cache, so the only way left to
 * answer "what else is on this network" is the same one the Services screen
 * uses for listeners: ask. Two sources, and a row says which.
 *
 * **ICMP echo** across the link's own IPv4 prefix, through the unprivileged
 * ping socket this app already uses for the gateway probe — no root, and the
 * kernel restricts the socket to echo, so nothing else can be sent from it.
 *
 * **mDNS** through [NsdManager], which gives the part a sweep cannot: names.
 * It runs in the system's own daemon rather than on a socket of ours, so it
 * needs no multicast lock, no extra permission, and — importantly while
 * capture is on — it never traverses our TUN.
 *
 * ## Deliberate limits
 *
 * This is **active**, unlike every other source in the app, so it runs only
 * when asked, exactly like the service scan, and never on the poll tick.
 *
 * It sweeps the device's own on-link prefix and nothing else: no
 * user-supplied range, no routed networks, no prefix wider than
 * [Subnet.MIN_PREFIX]. A host that answers neither a ping nor mDNS is simply
 * absent from the result — this is a list of what answered, not a census, and
 * the screen says so rather than implying the network is empty.
 */
class NeighbourScanner(
    private val context: Context,
    private val openSocket: (Int, Int) -> EchoSocket? = { version, timeout ->
        PingSockets.open(version, timeoutMs = timeout)
    },
) {

    private val sequence = AtomicInteger(1)

    /**
     * Sweep [subnet] and fold in whatever mDNS answers in the same window.
     *
     * @param subnet an address with a prefix, as `LinkProperties` reports it.
     * @param gateway the link's next hop, marked in the result when it answers.
     * @param selfAddresses this device's own addresses, marked likewise.
     */
    suspend fun scan(
        subnet: String,
        gateway: String? = null,
        selfAddresses: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
        ifaceName: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<NeighbourRow> {
        val hosts = Subnet.hostsOf(subnet)
        if (hosts.isEmpty() && ifaceName.isNullOrEmpty()) {
            onProgress(0, 0)
            return emptyList()
        }

        val found = ConcurrentHashMap<String, NeighbourRow>()
        coroutineScope {
            // mDNS runs for the whole sweep rather than after it: the two
            // sources are independent and serialising them would double the
            // wait for no better answer.
            val names = launch { discoverNames(found, now) }
            val v6 = if (!ifaceName.isNullOrEmpty()) launch { allNodes(ifaceName, found, now) } else null
            sweep(hosts, found, now, onProgress)
            v6?.join()
            // Give the responders that are slower than the sweep a moment,
            // then stop asking. An unbounded wait on a daemon callback is how
            // a scan button never un-greys.
            withTimeoutOrNull(MDNS_TAIL_MS) { names.join() }
            names.cancel()
        }

        val gatewayText = gateway?.substringBefore('%')
        return found.values
            .map { row ->
                row.copy(
                    // Derived here rather than written as each source lands:
                    // whichever arrived first used to win, so a host named by
                    // an mDNS record could be labelled "via ping". Reading it
                    // off the row's own evidence means the label and the name
                    // beside it cannot disagree.
                    source = sourceOf(row),
                    isGateway = gatewayText != null && row.address == gatewayText,
                    isSelf = row.address in selfAddresses,
                )
            }
            .sortedWith(compareBy({ sortKey(it.address) }, { it.address }))
    }

    internal fun sourceOf(row: NeighbourRow): NeighbourSource = when {
        row.answeredPing && row.fromMdns -> NeighbourSource.BOTH
        row.fromMdns -> NeighbourSource.MDNS
        else -> NeighbourSource.PING
    }

    private suspend fun sweep(
        hosts: List<String>,
        found: ConcurrentHashMap<String, NeighbourRow>,
        now: Long,
        onProgress: (Int, Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val total = hosts.size
        val done = AtomicInteger(0)
        val gate = Semaphore(CONCURRENCY)
        onProgress(0, total)
        coroutineScope {
            for (host in hosts) {
                launch {
                    gate.acquire()
                    try {
                        if (!isActive) return@launch
                        val rtt = ping(host)
                        if (rtt != null) {
                            found.compute(host) { _, existing ->
                                (existing ?: NeighbourRow(address = host, firstSeen = now)).copy(
                                    rttMillis = rtt,
                                    lastSeen = now,
                                )
                            }
                        }
                    } finally {
                        gate.release()
                        onProgress(done.incrementAndGet(), total)
                    }
                }
            }
        }
    }

    /**
     * One echo request, one socket. A ping socket's identifier belongs to the
     * kernel and is the socket's own port, so two sweeps sharing one socket
     * would have to demultiplex replies by sequence number across hosts — a
     * socket per probe is a few file descriptors at this concurrency and
     * leaves the matching unambiguous.
     */
    private fun ping(host: String): Int? {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        if (address !is Inet4Address) return null
        val socket = openSocket(4, PROBE_TIMEOUT_MS) ?: return null
        return try {
            val seq = sequence.getAndIncrement() and 0xFFFF
            val message = Icmp.buildEchoRequest(4, seq, PAYLOAD)
            val startedAt = System.nanoTime()
            if (!socket.send(message, 0, message.size, address)) return null
            val scratch = ByteArray(MAX_REPLY)
            val deadline = startedAt + PROBE_TIMEOUT_MS * 1_000_000L
            while (System.nanoTime() < deadline) {
                val n = socket.receive(scratch)
                if (n == EchoSocket.TIMED_OUT) continue
                if (n < Icmp.HEADER_LENGTH) return null
                if (!Icmp.isEchoReply(scratch, 0, n, 4)) continue
                if (Icmp.seq(scratch, 0) != seq) continue
                return ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(0)
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            socket.close()
        }
    }

    /**
     * One echo to `ff02::1`, the link's all-nodes group, and every reply
     * that comes back within the window.
     *
     * IPv6 cannot be swept — a /64 has 2^64 addresses — but it does not need
     * to be: every IPv6 host on a link is a member of the all-nodes group and
     * is required to answer an echo sent to it, so one request asks them all.
     * A host that filters ICMPv6 echo stays silent here, as it does to the
     * IPv4 sweep; and a kernel that delivers only one reply per request makes
     * this find one host — the screen reports what answered either way.
     */
    private suspend fun allNodes(
        iface: String,
        found: ConcurrentHashMap<String, NeighbourRow>,
        now: Long,
    ) = withContext(Dispatchers.IO) {
        val group = runCatching { InetAddress.getByName("$ALL_NODES%$iface") }.getOrNull()
            ?: return@withContext
        val socket = openSocket(6, PROBE_TIMEOUT_MS) ?: return@withContext
        try {
            val seq = sequence.getAndIncrement() and 0xFFFF
            val message = Icmp.buildEchoRequest(6, seq, PAYLOAD)
            val startedAt = System.nanoTime()
            if (!socket.send(message, 0, message.size, group)) return@withContext
            val scratch = ByteArray(MAX_REPLY)
            val deadline = startedAt + ALL_NODES_WINDOW_MS * 1_000_000L
            while (isActive && System.nanoTime() < deadline) {
                val (n, from) = socket.receiveFrom(scratch)
                if (n == EchoSocket.TIMED_OUT) continue
                if (n < Icmp.HEADER_LENGTH) break
                if (from == null || !Icmp.isEchoReply(scratch, 0, n, 6)) continue
                if (Icmp.seq(scratch, 0) != seq) continue
                val address = from.hostAddress?.substringBefore('%') ?: continue
                val rtt = ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(0)
                found.compute(address) { _, existing ->
                    (existing ?: NeighbourRow(address = address, firstSeen = now)).copy(
                        rttMillis = if (existing != null && existing.rttMillis >= 0) existing.rttMillis else rtt,
                        lastSeen = now,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a partial answer is still an answer
        } finally {
            socket.close()
        }
    }

    /**
     * mDNS service discovery for a handful of common types.
     *
     * The `_services._dns-sd._udp` meta-query would be the tidy way to ask
     * "what types exist here", and Android's resolver answers it
     * inconsistently across versions, so a fixed list of the types that
     * actually name home-network devices is the reliable one.
     */
    private suspend fun discoverNames(
        found: ConcurrentHashMap<String, NeighbourRow>,
        now: Long,
    ) {
        val nsd = runCatching { context.getSystemService(NsdManager::class.java) }.getOrNull()
            ?: return
        val listeners = ArrayList<Pair<String, NsdManager.DiscoveryListener>>()
        try {
            for (type in SERVICE_TYPES) {
                val listener = discoveryListener(nsd, type, found, now)
                val started = runCatching {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                }.isSuccess
                if (started) listeners += type to listener
            }
            if (listeners.isEmpty()) return
            delay(MDNS_WINDOW_MS)
        } finally {
            for ((_, listener) in listeners) {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }
    }

    private fun discoveryListener(
        nsd: NsdManager,
        type: String,
        found: ConcurrentHashMap<String, NeighbourRow>,
        now: Long,
    ) = object : NsdManager.DiscoveryListener {

        override fun onDiscoveryStarted(serviceType: String?) = Unit

        override fun onDiscoveryStopped(serviceType: String?) = Unit

        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            val info = serviceInfo ?: return
            runCatching { nsd.resolveService(info, resolveListener(type, found, now)) }
        }
    }

    private fun resolveListener(
        type: String,
        found: ConcurrentHashMap<String, NeighbourRow>,
        now: Long,
    ) = object : NsdManager.ResolveListener {

        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

        @Suppress("DEPRECATION")
        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
            val info = serviceInfo ?: return
            val host = runCatching { info.host }.getOrNull() ?: return
            if (host !is Inet4Address) return
            val address = host.hostAddress?.substringBefore('%') ?: return
            val label = info.serviceName?.takeIf { it.isNotBlank() }.orEmpty()
            val pretty = prettyType(type)
            found.compute(address) { _, existing ->
                val base = existing ?: NeighbourRow(address = address, firstSeen = now)
                base.copy(
                    // A name learnt once is kept: a later service on the same
                    // host should add to the row, not rename it.
                    hostname = base.hostname.ifEmpty { label },
                    services = (base.services + pretty).distinct().take(MAX_SERVICES),
                    lastSeen = now,
                )
            }
        }
    }

    /** `_googlecast._tcp.` reads as "googlecast" on a row. */
    private fun prettyType(type: String): String =
        type.trim('.').removePrefix("_").substringBefore("._")

    /** Sorts 192.168.1.9 before 192.168.1.10, which a string sort does not. */
    private fun sortKey(address: String): Long {
        var v = 0L
        for (part in address.split('.')) {
            val n = part.toLongOrNull() ?: return Long.MAX_VALUE
            v = (v shl 8) or (n and 0xFF)
        }
        return v
    }

    companion object {
        /**
         * Sockets in flight. Higher finishes a /24 faster and is also what a
         * consumer access point reads as a flood; this is the service
         * scanner's figure, which a home network tolerates.
         */
        const val CONCURRENCY = 32

        /** One echo, one chance. A LAN that needs longer than this is not one. */
        const val PROBE_TIMEOUT_MS = 500

        /** The link-local all-nodes multicast group (RFC 4291). */
        const val ALL_NODES = "ff02::1"

        /** Replies to one all-nodes echo arrive in a burst; this is generous. */
        const val ALL_NODES_WINDOW_MS = 1_500L

        /** How long mDNS discovery is left running alongside the sweep. */
        const val MDNS_WINDOW_MS = 4_000L

        /** Grace for responders slower than the sweep, after it finishes. */
        const val MDNS_TAIL_MS = 1_500L

        private const val MAX_SERVICES = 6
        private const val MAX_REPLY = 1500
        private val PAYLOAD = ByteArray(32) { (it and 0xFF).toByte() }

        /** The types that actually name devices on a home or office network. */
        internal val SERVICE_TYPES = listOf(
            "_workstation._tcp",
            "_http._tcp",
            "_https._tcp",
            "_ssh._tcp",
            "_smb._tcp",
            "_afpovertcp._tcp",
            "_ipp._tcp",
            "_printer._tcp",
            "_googlecast._tcp",
            "_airplay._tcp",
            "_raop._tcp",
            "_homekit._tcp",
            "_spotify-connect._tcp",
        )
    }
}
