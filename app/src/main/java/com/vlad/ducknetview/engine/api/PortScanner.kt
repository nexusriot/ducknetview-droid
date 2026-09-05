package com.vlad.ducknetview.engine.api

import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.net.IpScope
import com.vlad.ducknetview.domain.net.ServiceNames
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * The Android replacement for the TUI's Ports tab.
 *
 * `/proc/net/tcp` has been unreadable since Android 10, so the only way left to
 * answer "what is this device exposing" is to knock on its own doors: connect
 * to 127.0.0.1 for the loopback-bound listeners, and to each LAN address for
 * the ones that are reachable from the network. A listener bound only to
 * loopback refuses the LAN address, which is exactly what makes the two passes
 * distinguish LOCAL from LAN/EXPOSED.
 *
 * UDP is deliberately not scanned. A UDP "connect" sends nothing, and an open
 * UDP port is silent while a closed one may or may not answer ICMP
 * port-unreachable (which an unprivileged app cannot read anyway), so any UDP
 * result would be a guess dressed up as a finding.
 */
class PortScanner {

    internal data class Hit(val addr: String, val port: Int)

    suspend fun scan(
        localAddrs: List<String>,
        full: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServiceRow> = scanAt(localAddrs, full, System.currentTimeMillis(), onProgress)

    suspend fun scanAt(
        localAddrs: List<String>,
        full: Boolean,
        now: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServiceRow> = scanPorts(
        addrs = targetAddrs(localAddrs),
        ports = ports(full),
        timeoutMs = if (full) FULL_TIMEOUT_MS else FAST_TIMEOUT_MS,
        now = now,
        onProgress = onProgress,
    )

    internal suspend fun scanPorts(
        addrs: List<String>,
        ports: List<Int>,
        timeoutMs: Int,
        now: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<ServiceRow> {
        val total = addrs.size * ports.size
        if (total == 0) {
            onProgress(0, 0)
            return emptyList()
        }
        val gate = Semaphore(CONCURRENCY)
        val hits = ConcurrentLinkedQueue<Hit>()
        val done = AtomicInteger()
        val step = (total / 100).coerceAtLeast(1)

        // coroutineScope, not a bare launch loop: the merge must not run until
        // every probe has finished, and cancelling the caller must cancel them.
        coroutineScope {
            outer@ for (addr in addrs) {
                for (port in ports) {
                    if (!isActive) break@outer
                    gate.acquire()
                    launch(Dispatchers.IO) {
                        try {
                            if (isActive && connects(addr, port, timeoutMs)) {
                                hits.add(Hit(addr, port))
                            }
                        } finally {
                            gate.release()
                            val n = done.incrementAndGet()
                            if (n % step == 0 || n == total) onProgress(n, total)
                        }
                    }
                }
            }
        }
        return mergeHits(hits, now)
    }

    private fun connects(addr: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName(addr), port), timeoutMs)
            true
        }
    } catch (e: Exception) {
        false
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONCURRENCY = 256
        const val FAST_TIMEOUT_MS = 300
        const val FULL_TIMEOUT_MS = 120
        const val MAX_PORT = 65535

        /**
         * Loopback is always scanned, even when the caller passes no addresses:
         * a device with no LAN address still has local listeners worth showing.
         */
        internal fun targetAddrs(localAddrs: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            out.add(LOOPBACK)
            for (raw in localAddrs) {
                val a = normalizeAddr(raw) ?: continue
                out.add(a)
            }
            return out.toList()
        }

        /** Accepts `10.0.0.5/24` and `fe80::1%wlan0` shapes from LinkProperties. */
        internal fun normalizeAddr(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            var s = raw.trim()
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(0, slash)
            val pct = s.indexOf('%')
            if (pct >= 0) s = s.substring(0, pct)
            s = s.removePrefix("[").removeSuffix("]")
            if (s.isBlank()) return null
            return s
        }

        internal fun ports(full: Boolean): List<Int> =
            if (full) (1..MAX_PORT).toList() else COMMON_PORTS

        internal fun widest(a: Exposure, b: Exposure): Exposure =
            if (rank(b) > rank(a)) b else a

        internal fun rank(e: Exposure): Int = when (e) {
            Exposure.LOCAL -> 0
            Exposure.LAN -> 1
            Exposure.EXPOSED -> 2
        }

        /**
         * One row per port. When a port answers on both loopback and a LAN
         * address the LAN answer wins: the widest reachability is the one that
         * matters for "am I exposed", and reporting the loopback bind would
         * under-state the risk.
         *
         * The owning UID is left unset — Android gives no way to attribute a
         * listening socket to another app.
         */
        internal fun mergeHits(hits: Collection<Hit>, now: Long): List<ServiceRow> {
            val best = HashMap<Int, Pair<String, Exposure>>()
            for (hit in hits) {
                val exposure = IpScope.exposureOf(hit.addr)
                val prev = best[hit.port]
                if (prev == null || rank(exposure) > rank(prev.second)) {
                    best[hit.port] = Pair(hit.addr, exposure)
                }
            }
            return best.entries
                .sortedBy { it.key }
                .map { (port, v) ->
                    ServiceRow(
                        proto = Proto.TCP,
                        bindAddr = v.first,
                        port = port,
                        service = ServiceNames.of(port, Proto.TCP),
                        exposure = v.second,
                        firstSeen = now,
                        lastSeen = now,
                    )
                }
        }

        /**
         * The fast pass. Weighted towards what actually listens on a phone
         * (adb, dev servers, media/cast, tethering helpers) on top of the
         * classic service ports, so a one-second scan still catches the things
         * a user would want to know about.
         */
        internal val COMMON_PORTS: List<Int> = listOf(
            7, 9, 13, 19, 20, 21, 22, 23, 25, 37, 42, 43, 49, 53, 67, 69, 70, 79,
            80, 81, 82, 83, 84, 88, 89, 110, 111, 113, 119, 123, 135, 137, 138,
            139, 143, 161, 162, 179, 199, 264, 389, 427, 443, 444, 445, 464, 465,
            497, 500, 512, 513, 514, 515, 520, 523, 540, 543, 544, 548, 554, 563,
            587, 593, 623, 626, 631, 636, 646, 666, 749, 771, 783, 800, 801, 808,
            843, 873, 880, 888, 898, 900, 901, 902, 989, 990, 992, 993, 995, 999,
            1000, 1024, 1025, 1026, 1027, 1080, 1099, 1110, 1194, 1234, 1241,
            1311, 1337, 1352, 1433, 1434, 1443, 1494, 1521, 1583, 1604, 1701,
            1720, 1723, 1755, 1812, 1813, 1883, 1900, 1935, 2000, 2001, 2049,
            2082, 2083, 2086, 2087, 2095, 2096, 2100, 2121, 2181, 2222, 2375,
            2376, 2379, 2380, 2401, 2404, 2483, 2484, 2601, 2604, 2638, 2869,
            3000, 3001, 3128, 3260, 3268, 3269, 3283, 3306, 3333, 3389, 3478,
            3479, 3689, 3690, 3702, 3784, 3790, 3888, 4000, 4001, 4040, 4045,
            4100, 4200, 4222, 4243, 4369, 4433, 4443, 4444, 4500, 4567, 4664,
            4711, 4786, 4840, 4848, 4899, 5000, 5001, 5002, 5004, 5005, 5009,
            5037, 5050, 5060, 5061, 5100, 5190, 5222, 5228, 5269, 5280, 5298,
            5353, 5355, 5357, 5432, 5433, 5555, 5556, 5601, 5631, 5666, 5672,
            5678, 5683, 5800, 5900, 5901, 5938, 5984, 5985, 5986, 6000, 6001,
            6002, 6003, 6379, 6443, 6543, 6566, 6588, 6646, 6667, 6697, 6881,
            6969, 7000, 7001, 7070, 7100, 7199, 7443, 7474, 7547, 7777, 8000,
            8001, 8002, 8008, 8009, 8010, 8020, 8025, 8030, 8042, 8060, 8080,
            8081, 8082, 8083, 8085, 8086, 8087, 8088, 8090, 8091, 8096, 8098,
            8123, 8125, 8139, 8161, 8180, 8181, 8200, 8222, 8266, 8291, 8333,
            8384, 8388, 8443, 8500, 8501, 8530, 8531, 8546, 8600, 8686, 8765,
            8787, 8800, 8834, 8843, 8880, 8883, 8888, 8983, 9000, 9001, 9002,
            9009, 9042, 9050, 9051, 9080, 9090, 9091, 9092, 9100, 9101, 9110,
            9160, 9187, 9200, 9300, 9418, 9443, 9500, 9595, 9600, 9800, 9876,
            9898, 9900, 9981, 9999, 10000, 10001, 10250, 10255, 11211, 11434,
            12345, 15672, 16992, 16993, 17500, 18080, 19132, 20000, 20547,
            22105, 23023, 24800, 25565, 27015, 27017, 27018, 28017, 30000,
            31337, 32768, 32769, 32770, 33060, 34567, 37777, 44818, 47001,
            47808, 49152, 49153, 49154, 50000, 50070, 51820, 54321, 55553,
            61616, 62078, 64738,
        ).distinct().sorted()
    }
}
