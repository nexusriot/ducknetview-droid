package com.vlad.ducknetview.engine.api

import com.vlad.ducknetview.engine.vpn.EchoSocket
import com.vlad.ducknetview.engine.vpn.PingSockets
import com.vlad.ducknetview.engine.vpn.packet.Icmp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A round trip measured by ICMP echo, where the kernel allows it.
 *
 * Returns null for "no answer, or this device does not permit echo sockets",
 * which is the caller's signal to fall back to timing a TCP handshake rather
 * than to report a failure.
 */
interface EchoProbe {

    suspend fun rttMillis(host: String, timeoutMs: Int): Int?

    /** The stand-in for a device with no echo socket: everything falls back. */
    object Unavailable : EchoProbe {
        override suspend fun rttMillis(host: String, timeoutMs: Int): Int? = null
    }
}

/**
 * ICMP echo over an unprivileged ping socket.
 *
 * This is a true network round trip, unlike the TCP handshake the app falls
 * back to, which also pays for the peer's accept path. The two are reported
 * under different labels rather than averaged into one number.
 */
class IcmpEchoProbe(
    private val open: (Int, Int) -> EchoSocket? = { version, timeout ->
        PingSockets.open(version, timeoutMs = timeout)
    },
) : EchoProbe {

    /** Identifies our own replies; the kernel owns the identifier, not this. */
    private val sequence = AtomicInteger(1)

    override suspend fun rttMillis(host: String, timeoutMs: Int): Int? =
        withContext(Dispatchers.IO) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
                ?: return@withContext null
            val version = if (address is Inet4Address) 4 else 6
            // The socket timeout bounds one receive; the deadline below bounds
            // the whole probe, so a stream of other replies cannot extend it.
            val socket = open(version, timeoutMs.coerceAtLeast(1)) ?: return@withContext null
            try {
                val seq = sequence.getAndIncrement() and 0xFFFF
                val message = Icmp.buildEchoRequest(version, seq, PAYLOAD)
                val startedAt = System.nanoTime()
                if (!socket.send(message, 0, message.size, address)) return@withContext null

                val scratch = ByteArray(MAX_REPLY)
                val deadline = startedAt + timeoutMs * 1_000_000L
                while (System.nanoTime() < deadline) {
                    val n = socket.receive(scratch)
                    if (n == EchoSocket.TIMED_OUT) continue
                    if (n < Icmp.HEADER_LENGTH) return@withContext null
                    if (!Icmp.isEchoReply(scratch, 0, n, version)) continue
                    if (Icmp.seq(scratch, 0) != seq) continue
                    return@withContext ((System.nanoTime() - startedAt) / 1_000_000L)
                        .toInt().coerceAtLeast(0)
                }
                null
            } finally {
                socket.close()
            }
        }

    companion object {
        /** The conventional 32-byte ping payload; its content is irrelevant. */
        private val PAYLOAD = ByteArray(32) { (it and 0xFF).toByte() }
        private const val MAX_REPLY = 1500
    }
}
