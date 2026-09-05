package com.vlad.ducknetview.engine.api

import com.vlad.ducknetview.domain.model.LatencySample
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * TCP-handshake round-trip timing, the Android port of `probe.MeasureLatency`.
 *
 * ICMP ping needs a raw socket, so the only portable "how far away is the
 * network" measurement an unprivileged app can make is the time to complete (or
 * be refused) a TCP connect.
 */
class LatencyProber(private val historySize: Int = HISTORY) {

    private val history = LinkedHashMap<String, ArrayDeque<Float>>()

    suspend fun probe(target: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): LatencySample =
        probeLabeled(target, labelFor(target), timeoutMs)

    suspend fun probeLabeled(
        target: String,
        label: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): LatencySample {
        val hostPort = parseTarget(target)
            ?: return withHistory(
                LatencySample(
                    target = target,
                    label = label,
                    millis = -1,
                    ok = false,
                    note = "no port",
                )
            )
        val (host, port) = hostPort
        return withContext(Dispatchers.IO) {
            var failure: Exception? = null
            val start = System.nanoTime()
            var elapsedNanos = 0L
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
                }
                elapsedNanos = System.nanoTime() - start
            } catch (e: Exception) {
                elapsedNanos = System.nanoTime() - start
                failure = e
            }
            withHistory(classify(target, label, failure, elapsedNanos / 1_000_000L))
        }
    }

    /**
     * The gateway probe always leads: it is the only hop that can be measured
     * without any traffic leaving the LAN, so it separates "my Wi-Fi is bad"
     * from "the internet is bad".
     */
    suspend fun probeAll(
        targets: List<String>,
        gateway: String?,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): List<LatencySample> = coroutineScope {
        val jobs = ArrayList<Deferred<LatencySample>>()
        val gw = gateway?.trim()?.takeIf { it.isNotEmpty() }
        if (gw != null) {
            val addr = joinHostPort(gw, GATEWAY_PORT)
            jobs += async { probeLabeled(addr, GATEWAY_LABEL, timeoutMs) }
        }
        for (t in targets) {
            val trimmed = t.trim()
            if (trimmed.isEmpty()) continue
            jobs += async { probeLabeled(trimmed, labelFor(trimmed), timeoutMs) }
        }
        val results = jobs.awaitAll()
        retain(results.map { it.target }.toSet())
        results
    }

    private fun withHistory(sample: LatencySample): LatencySample {
        val q = synchronized(history) {
            val deque = history.getOrPut(sample.target) { ArrayDeque() }
            // A failed probe records 0 rather than being skipped: a gap that
            // silently shortens the sparkline reads as "fine", an outage does not.
            deque.addLast(if (sample.ok) sample.millis.toFloat() else 0f)
            while (deque.size > historySize) deque.removeFirst()
            deque.toList()
        }
        return sample.copy(history = q)
    }

    fun history(target: String): List<Float> =
        synchronized(history) { history[target]?.toList() ?: emptyList() }

    fun retain(targets: Set<String>) {
        synchronized(history) { history.keys.retainAll(targets) }
    }

    fun clear() {
        synchronized(history) { history.clear() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2000
        const val HISTORY = 40
        const val GATEWAY_LABEL = "gateway"
        const val GATEWAY_PORT = 80

        /**
         * A refused connection is a *valid* timing: the RST travelled exactly
         * the path a SYN-ACK would have, so the round trip is real even though
         * nothing is listening. Only a timeout (or a routing failure, where no
         * packet came back at all) is a missing measurement.
         */
        fun classify(
            target: String,
            label: String,
            ex: Exception?,
            elapsedMs: Long,
        ): LatencySample {
            val millis = elapsedMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            if (ex == null) {
                return LatencySample(target = target, label = label, millis = millis, ok = true)
            }
            if (ex is SocketTimeoutException) {
                return LatencySample(
                    target = target, label = label, millis = -1, ok = false, note = "timeout",
                )
            }
            val text = (ex.message ?: "").lowercase()
            if (text.contains("refused") || text.contains("reset") ||
                text.contains("econnrefused") || text.contains("econnreset")
            ) {
                return LatencySample(
                    target = target, label = label, millis = millis, ok = true, note = "refused",
                )
            }
            val note = when {
                ex is UnknownHostException -> "unknown host"
                ex is NoRouteToHostException -> "no route"
                text.contains("unreachable") -> "unreachable"
                ex is ConnectException -> "connect failed"
                ex is IOException -> "io error"
                else -> ex.javaClass.simpleName
            }
            return LatencySample(
                target = target, label = label, millis = -1, ok = false, note = note,
            )
        }

        /**
         * Splits `host:port`. A bare host is rejected rather than defaulted:
         * guessing a port would silently measure a different path than the user
         * asked for.
         */
        internal fun parseTarget(target: String): Pair<String, Int>? {
            val t = target.trim()
            if (t.isEmpty()) return null
            if (t.startsWith("[")) {
                val close = t.indexOf(']')
                if (close < 0 || close + 1 >= t.length || t[close + 1] != ':') return null
                val host = t.substring(1, close)
                val port = t.substring(close + 2).toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return Pair(host, port)
            }
            val colon = t.lastIndexOf(':')
            if (colon <= 0 || colon == t.length - 1) return null
            // More than one colon with no brackets is a bare IPv6 literal, not
            // an address with a port.
            if (t.indexOf(':') != colon) return null
            val host = t.substring(0, colon)
            val port = t.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) return null
            return Pair(host, port)
        }

        internal fun labelFor(target: String): String {
            val parsed = parseTarget(target) ?: return target.trim()
            return parsed.first
        }

        internal fun joinHostPort(host: String, port: Int): String =
            if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
    }
}
