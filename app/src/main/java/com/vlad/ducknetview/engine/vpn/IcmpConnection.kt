package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.engine.vpn.packet.Icmp
import com.vlad.ducknetview.engine.vpn.packet.Packets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * One proxied ICMP echo flow — every echo request an app sends to one host
 * under one identifier.
 *
 * Without this the capture engine dropped ICMP on the floor: a default route
 * through the TUN plus a dispatcher that only knew TCP and UDP meant that
 * turning capture on silently broke `ping` for every other app on the device.
 *
 * A ping socket rewrites the identifier to its own port, so the guest's
 * identifier is held here and restored on the way back. Replies are matched to
 * requests by sequence number, which is what makes the RTT column real for
 * ICMP rather than borrowed from a TCP handshake.
 */
class IcmpConnection(
    private val flow: Flow,
    private val ipVersion: Int,
    private val appRaw: ByteArray,
    private val remoteRaw: ByteArray,
    private val tun: TunWriter,
    private val openSocket: (Int) -> EchoSocket?,
    private val scope: CoroutineScope,
    private val mtu: Int,
    private val onClosed: (FlowKey) -> Unit,
) {
    private val key = flow.key
    private val remoteAddress: InetAddress? = runCatching {
        InetAddress.getByAddress(remoteRaw)
    }.getOrNull()

    private var socket: EchoSocket? = null
    private var readJob: Job? = null

    /** Sequence number to the nanos it was sent at, bounded so a flood cannot grow it. */
    private val pending = LinkedHashMap<Int, Long>()
    private val pendingLock = Any()

    @Volatile private var closed = false

    private val maxMessage: Int
        get() = (mtu - if (ipVersion == 4) 20 else 40).coerceAtLeast(Icmp.HEADER_LENGTH)

    fun start(now: Long): Boolean {
        if (remoteAddress == null) return false
        val s = openSocket(ipVersion) ?: return false
        socket = s
        flow.state = ConnState.ACTIVE
        flow.touch(now)
        readJob = scope.launch(Dispatchers.IO) { pumpReads(s) }
        return true
    }

    /**
     * Relay one echo request. The identifier and checksum are zeroed: the
     * kernel owns both on a ping socket and would overwrite anything written
     * here, so computing them would be work whose result is discarded.
     */
    fun send(buf: ByteArray, offset: Int, length: Int, now: Long) {
        val s = socket ?: return
        val to = remoteAddress ?: return
        if (length < Icmp.HEADER_LENGTH) return
        flow.touch(now)
        val message = buf.copyOfRange(offset, offset + minOf(length, maxMessage))
        Icmp.putId(message, 0, 0)
        Packets.put16(message, 2, 0)
        notePending(Icmp.seq(message, 0), System.nanoTime())
        flow.tx.addAndGet(message.size.toLong())
        if (!s.send(message, 0, message.size, to)) close()
    }

    private suspend fun pumpReads(s: EchoSocket) {
        // One scratch message for the life of the flow: it is copied into a TUN
        // buffer before the next receive can overwrite it.
        val scratch = ByteArray(maxMessage)
        while (scope.isActive && !closed) {
            val n = s.receive(scratch)
            // A timeout is the socket giving the loop a chance to see
            // cancellation, not an error.
            if (n == EchoSocket.TIMED_OUT) continue
            if (n < 0) break
            if (n < Icmp.HEADER_LENGTH) continue
            if (!Icmp.isEchoReply(scratch, 0, n, ipVersion)) continue

            val now = System.currentTimeMillis()
            flow.rx.addAndGet(n.toLong())
            flow.touch(now)
            completeRtt(Icmp.seq(scratch, 0))
            Icmp.putId(scratch, 0, key.srcPort)

            val out = tun.acquire(Packets.icmpPacketLength(ipVersion, n))
            out.length = Packets.buildIcmpInto(
                out = out.array,
                offset = 0,
                ipVersion = ipVersion,
                srcRaw = remoteRaw,
                dstRaw = appRaw,
                message = scratch,
                messageOffset = 0,
                messageLength = n,
            )
            tun.submit(out)
        }
        if (!closed) close()
    }

    private fun notePending(seq: Int, atNanos: Long) {
        synchronized(pendingLock) {
            pending[seq] = atNanos
            while (pending.size > MAX_PENDING) {
                val oldest = pending.keys.firstOrNull() ?: break
                pending.remove(oldest)
            }
        }
    }

    private fun completeRtt(seq: Int) {
        val sentAt = synchronized(pendingLock) { pending.remove(seq) } ?: return
        flow.rttMillis = ((System.nanoTime() - sentAt) / 1_000_000L).toInt().coerceAtLeast(0)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        socket = null
        // Report before cancelling: the read pump is usually the caller here,
        // exactly as in TcpConnection.
        onClosed(key)
        readJob?.cancel()
        readJob = null
    }

    companion object {
        /** Enough outstanding echoes for any ping utility; a flood is dropped. */
        private const val MAX_PENDING = 64
    }
}
