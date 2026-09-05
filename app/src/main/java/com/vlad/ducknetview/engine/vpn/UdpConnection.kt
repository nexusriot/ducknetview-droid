package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.engine.vpn.packet.Packets
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
    private val onClosed: (FlowKey) -> Unit,
) {
    private val key = flow.key
    private var channel: DatagramChannel? = null
    private var readJob: Job? = null

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
        } catch (e: Exception) {
            close()
        }
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
