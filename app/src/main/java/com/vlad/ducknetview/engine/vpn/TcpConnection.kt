package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.engine.vpn.packet.Packets
import com.vlad.ducknetview.engine.vpn.packet.TcpFlag
import com.vlad.ducknetview.engine.vpn.packet.TcpHeader
import com.vlad.ducknetview.engine.vpn.packet.TlsPeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * One proxied TCP flow.
 *
 * There is no retransmission timer here on purpose: the downstream side is a
 * TUN file descriptor handed straight to the local kernel, which does not lose
 * segments. Flow control, however, is real — the app's advertised window has
 * to be respected or a fast download overruns its receive buffer and the
 * kernel starts dropping what we can no longer resend.
 */
class TcpConnection(
    private val flow: Flow,
    private val ipVersion: Int,
    private val appRaw: ByteArray,
    private val remoteRaw: ByteArray,
    private val tun: TunWriter,
    private val protect: (Socket) -> Boolean,
    private val scope: CoroutineScope,
    private val mtu: Int,
    private val onSni: (String) -> Unit = {},
    private val onClosed: (FlowKey) -> Unit,
) {
    private val key = flow.key

    private var mySeq: Long = Random.nextLong(0, 0xFFFFFFFFL)
    private var theirSeq: Long = 0
    private var theirAck: Long = mySeq
    private var theirWindow: Int = 65535
    private var peerMss: Int = 0

    private val ackSignal = Channel<Unit>(Channel.CONFLATED)
    // Carries pooled buffers: a buffer accepted by this channel belongs to
    // pumpUpstreamWrites, which releases it once the bytes are on the socket.
    private val upstreamOut = Channel<PacketBuf>(256)

    private var channel: SocketChannel? = null
    // Appended from the dispatcher thread and from connect(), and read by
    // close() on whichever coroutine tears the flow down: a plain ArrayList
    // here throws ConcurrentModificationException under real traffic.
    private val jobs = CopyOnWriteArrayList<Job>()

    /** The SNI peek looks at the first in-order payload segment and no other. */
    private var sniChecked = false

    @Volatile private var finSent = false
    @Volatile private var finReceived = false
    @Volatile private var aborted = false
    @Volatile private var established = false
    @Volatile private var closed = false

    private val maxSegment: Int
        get() {
            val ipOverhead = if (ipVersion == 4) 40 else 60
            val local = (mtu - ipOverhead).coerceAtLeast(536)
            return if (peerMss in 1 until local) peerMss else local
        }

    fun onSyn(tcp: TcpHeader, now: Long) {
        if (established || channel != null) {
            return // A retransmitted SYN before we finished connecting.
        }
        theirSeq = Packets.seqAdd(tcp.seq, 1)
        theirWindow = tcp.window
        peerMss = tcp.mss
        flow.state = ConnState.SYN_SENT
        flow.touch(now)
        jobs += scope.launch(Dispatchers.IO) { connect() }
    }

    private suspend fun connect() {
        val startedAt = System.nanoTime()
        val ch = try {
            SocketChannel.open()
        } catch (e: IOException) {
            sendRst()
            return
        }
        channel = ch
        try {
            ch.configureBlocking(true)
            if (!protect(ch.socket())) {
                // Without protect() the upstream socket would be routed back
                // into our own TUN and loop forever.
                throw IOException("protect failed")
            }
            ch.socket().tcpNoDelay = true
            ch.socket().connect(InetSocketAddress(key.dstIp, key.dstPort), CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            runCatching { ch.close() }
            channel = null
            sendRst()
            onClosed(key)
            return
        }

        flow.rttMillis = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
        flow.state = ConnState.ESTABLISHED
        established = true

        sendControl(TcpFlag.SYN or TcpFlag.ACK, mss = maxSegment)
        mySeq = Packets.seqAdd(mySeq, 1)

        jobs += scope.launch(Dispatchers.IO) { pumpUpstreamWrites(ch) }
        jobs += scope.launch(Dispatchers.IO) { pumpUpstreamReads(ch) }
    }

    fun onPacket(tcp: TcpHeader, buf: ByteArray, now: Long) {
        flow.touch(now)

        if (tcp.isRst) {
            abort()
            return
        }
        if (tcp.isAck) {
            theirAck = tcp.ack
            theirWindow = tcp.window
            ackSignal.trySend(Unit)
        }

        if (tcp.payloadLength > 0) {
            when {
                tcp.seq == theirSeq -> {
                    peekSni(buf, tcp)
                    // The read buffer is reused for the next packet, so the
                    // payload is copied into a pooled buffer the upstream pump
                    // owns from the moment the send succeeds.
                    val data = tun.acquire(tcp.payloadLength)
                    System.arraycopy(buf, tcp.payloadOffset, data.array, 0, tcp.payloadLength)
                    data.length = tcp.payloadLength
                    if (upstreamOut.trySend(data).isSuccess) {
                        flow.tx.addAndGet(tcp.payloadLength.toLong())
                        theirSeq = Packets.seqAdd(theirSeq, tcp.payloadLength.toLong())
                        sendControl(TcpFlag.ACK)
                    } else {
                        tun.release(data)
                    }
                    // A full queue means we deliberately do not ACK, so the
                    // local stack retransmits instead of us buffering forever.
                }
                Packets.seqLte(tcp.seq, theirSeq) -> sendControl(TcpFlag.ACK) // duplicate
                else -> sendControl(TcpFlag.ACK) // gap; re-ack what we have
            }
        }

        if (tcp.isFin) {
            finReceived = true
            theirSeq = Packets.seqAdd(theirSeq, 1)
            flow.state = ConnState.CLOSING
            sendControl(TcpFlag.ACK)
            upstreamOut.close()
            if (finSent) finish()
        }
    }

    /**
     * Read the server name out of a TLS ClientHello on its way past.
     *
     * Only the first in-order data segment is looked at, and only when its
     * first bytes are a TLS handshake record, so the cost on a flow that is not
     * TLS is one byte comparison. The bytes are relayed unchanged either way —
     * nothing here decrypts, rewrites or delays anything.
     */
    private fun peekSni(buf: ByteArray, tcp: TcpHeader) {
        if (sniChecked) return
        sniChecked = true
        if (!TlsPeek.looksLikeHandshake(buf, tcp.payloadOffset, tcp.payloadLength)) return
        val name = TlsPeek.serverName(buf, tcp.payloadOffset, tcp.payloadLength) ?: return
        runCatching { onSni(name) }
    }

    private suspend fun pumpUpstreamWrites(ch: SocketChannel) {
        try {
            for (data in upstreamOut) {
                try {
                    val bb = ByteBuffer.wrap(data.array, 0, data.length)
                    while (bb.hasRemaining() && !aborted) ch.write(bb)
                } finally {
                    tun.release(data)
                }
            }
            runCatching { ch.socket().shutdownOutput() }
        } catch (e: Exception) {
            abort()
        }
    }

    private suspend fun pumpUpstreamReads(ch: SocketChannel) {
        // One scratch array for the life of the pump. sendDownstream copies out
        // of it into the TUN buffers before returning, and nothing else reads
        // it, so the next read may overwrite it freely.
        val scratch = ByteArray(READ_CHUNK.coerceAtLeast(maxSegment))
        val bb = ByteBuffer.wrap(scratch)
        try {
            while (scope.isActive && !aborted) {
                bb.clear()
                val n = ch.read(bb)
                if (n < 0) break
                if (n == 0) continue
                flow.rx.addAndGet(n.toLong())
                if (!sendDownstream(scratch, n)) break
            }
            if (!aborted) {
                sendControl(TcpFlag.FIN or TcpFlag.ACK)
                mySeq = Packets.seqAdd(mySeq, 1)
                finSent = true
                flow.state = ConnState.CLOSING
                if (finReceived) finish()
            }
        } catch (e: Exception) {
            if (!aborted) abort()
        }
    }

    /** Segments [length] bytes of [data] to the peer MSS, blocking while the window is full. */
    private suspend fun sendDownstream(data: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val len = minOf(maxSegment, length - offset)
            if (!awaitWindow(len)) return false
            emit(
                flags = TcpFlag.PSH or TcpFlag.ACK,
                window = OUR_WINDOW,
                payload = data,
                payloadOffset = offset,
                payloadLength = len,
            )
            mySeq = Packets.seqAdd(mySeq, len.toLong())
            offset += len
        }
        return true
    }

    /**
     * Build one segment straight into a pooled TUN buffer. Ownership of the
     * buffer passes to the writer at submit().
     */
    private fun emit(
        flags: Int,
        window: Int,
        mss: Int = 0,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = 0,
    ) {
        val buf = tun.acquire(Packets.tcpPacketLength(ipVersion, payloadLength, mss))
        buf.length = Packets.buildTcpInto(
            out = buf.array,
            offset = 0,
            ipVersion = ipVersion,
            srcRaw = remoteRaw,
            dstRaw = appRaw,
            srcPort = key.dstPort,
            dstPort = key.srcPort,
            seq = mySeq,
            ack = theirSeq,
            flags = flags,
            window = window,
            payload = payload,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            mss = mss,
        )
        tun.submit(buf)
    }

    private suspend fun awaitWindow(len: Int): Boolean {
        var waited = 0L
        while (!aborted) {
            val inFlight = (mySeq - theirAck) and 0xFFFFFFFFL
            val window = theirWindow.coerceAtLeast(1)
            if (inFlight + len <= window) return true
            val got = withTimeoutOrNull(WINDOW_WAIT_MS) { ackSignal.receive() }
            if (got == null) {
                waited += WINDOW_WAIT_MS
                if (waited >= WINDOW_STALL_MS) {
                    abort()
                    return false
                }
            }
        }
        return false
    }

    private fun sendControl(flags: Int, mss: Int = 0) {
        emit(flags, OUR_WINDOW, mss = mss)
    }

    private fun sendRst() {
        emit(TcpFlag.RST or TcpFlag.ACK, window = 0)
    }

    /** Refuse the flow outright — how a blocked app is enforced. */
    fun reject() {
        flow.blocked = true
        sendRst()
        close()
    }

    fun abort() {
        if (aborted) return
        aborted = true
        sendRst()
        close()
    }

    private fun finish() {
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        aborted = true
        upstreamOut.close()
        ackSignal.close()
        runCatching { channel?.close() }
        channel = null
        // Report the close before cancelling: one of these jobs is usually the
        // caller, and cancelling it first would cut the notification short.
        onClosed(key)
        val running = jobs.toList()
        jobs.clear()
        running.forEach { it.cancel() }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8000
        const val OUR_WINDOW = 65535
        private const val WINDOW_WAIT_MS = 2000L
        private const val WINDOW_STALL_MS = 60_000L

        /**
         * Upstream reads are taken in chunks far larger than one segment and
         * then cut to the MSS, which costs a read syscall per chunk instead of
         * one per segment. It does not change what goes on the wire.
         */
        private const val READ_CHUNK = 16384
    }
}
