package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.engine.vpn.packet.Packets
import com.vlad.ducknetview.engine.vpn.packet.QuicPeek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * The seam the flows write finished IP packets through, and the owner of the
 * buffers they are built in.
 *
 * Buffer ownership: [acquire] lends a buffer to the caller. [submit] hands it
 * to the TUN writer, which returns it to the pool once written — after submit
 * the caller must not read or write it again. [release] returns a buffer that
 * was never submitted.
 */
interface TunWriter {
    fun acquire(size: Int): PacketBuf

    fun submit(buf: PacketBuf)

    fun release(buf: PacketBuf)
}

/**
 * One proxied UDP flow. UDP has no teardown, so these live on an idle timer
 * driven by [FlowTable.expire].
 */
class UdpConnection(
    private val flow: Flow,
    private val ipVersion: Int,
    private val appRaw: ByteArray,
    private val remoteRaw: ByteArray,
    private val tun: TunWriter,
    private val protect: (DatagramSocket) -> Boolean,
    private val scope: CoroutineScope,
    private val onDnsPayload: (ByteArray) -> Unit,
    private val onSni: (String) -> Unit,
    private val onClosed: (FlowKey) -> Unit,
) {
    private val key = flow.key
    private var channel: DatagramChannel? = null
    private var readJob: Job? = null

    /**
     * Collects a ClientHello out of this flow's Initial packets, or null once
     * there is nothing left to collect.
     *
     * A modern ClientHello carries a post-quantum key share and does not fit
     * one Initial, so a single-datagram peek found a name for none of the
     * QUIC flows on the test tablet. The assembler is created only if the
     * flow's first datagram looks like an Initial and is dropped the moment
     * it is finished, so a flow that is not QUIC pays five bytes of
     * comparison once and nothing after that.
     */
    private var quic: QuicPeek.Assembler? = null
    private var quicChecked = false

    @Volatile private var closed = false

    fun start(now: Long): Boolean {
        val ch = try {
            DatagramChannel.open()
        } catch (e: Exception) {
            return false
        }
        return try {
            ch.configureBlocking(true)
            if (!protect(ch.socket())) throw IllegalStateException("protect failed")
            ch.connect(InetSocketAddress(key.dstIp, key.dstPort))
            channel = ch
            flow.state = ConnState.ACTIVE
            flow.touch(now)
            readJob = scope.launch(Dispatchers.IO) { pumpReads(ch) }
            true
        } catch (e: Exception) {
            runCatching { ch.close() }
            channel = null
            false
        }
    }

    /**
     * Relay one datagram straight out of the TUN read buffer. The write
     * completes before this returns, so the caller may reuse [buf] afterwards.
     */
    fun send(buf: ByteArray, offset: Int, length: Int, now: Long) {
        val ch = channel ?: return
        flow.touch(now)
        try {
            flow.tx.addAndGet(length.toLong())
            ch.write(ByteBuffer.wrap(buf, offset, length))
            // The DNS peek keeps the payload past this call, so it gets a copy.
            if (key.dstPort == DNS_PORT) onDnsPayload(buf.copyOfRange(offset, offset + length))
            // QUIC is gated on the packet's own shape rather than on port
            // 443, because the protocol is not bound to a port and the gate
            // is five bytes of comparison either way.
            peekQuic(buf, offset, length)
        } catch (e: Exception) {
            close()
        }
    }

    /**
     * Offer one outbound datagram to the QUIC handshake reader.
     *
     * The assembler is started only by a first datagram that looks like an
     * Initial: a flow that opens with anything else is never QUIC's
     * handshake and is marked off for good.
     */
    private fun peekQuic(buf: ByteArray, offset: Int, length: Int) {
        val assembler = quic ?: run {
            if (quicChecked) return
            quicChecked = true
            if (!QuicPeek.looksLikeInitial(buf, offset, length)) return
            QuicPeek.Assembler().also { quic = it }
        }
        val name = assembler.offer(buf, offset, length)
        if (name != null) onSni(name)
        if (assembler.done) quic = null
    }

    private suspend fun pumpReads(ch: DatagramChannel) {
        // One scratch datagram for the life of the flow: it is consumed into a
        // TUN buffer before the next read overwrites it.
        val scratch = ByteArray(MAX_DATAGRAM)
        val bb = ByteBuffer.wrap(scratch)
        try {
            while (scope.isActive && !closed) {
                bb.clear()
                val n = ch.read(bb)
                if (n <= 0) continue
                flow.rx.addAndGet(n.toLong())
                flow.touch(System.currentTimeMillis())
                if (key.dstPort == DNS_PORT) onDnsPayload(scratch.copyOf(n))
                val out = tun.acquire(Packets.udpPacketLength(ipVersion, n))
                out.length = Packets.buildUdpInto(
                    out = out.array,
                    offset = 0,
                    ipVersion = ipVersion,
                    srcRaw = remoteRaw,
                    dstRaw = appRaw,
                    srcPort = key.dstPort,
                    dstPort = key.srcPort,
                    payload = scratch,
                    payloadOffset = 0,
                    payloadLength = n,
                )
                tun.submit(out)
            }
        } catch (e: Exception) {
            if (!closed) close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { channel?.close() }
        channel = null
        readJob?.cancel()
        onClosed(key)
    }

    companion object {
        const val DNS_PORT = 53
        private const val MAX_DATAGRAM = 65535
    }
}
