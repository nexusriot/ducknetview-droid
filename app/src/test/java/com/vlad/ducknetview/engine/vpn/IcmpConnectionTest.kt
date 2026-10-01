package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.engine.vpn.packet.Icmp
import com.vlad.ducknetview.engine.vpn.packet.IpProto
import com.vlad.ducknetview.engine.vpn.packet.Packets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The relay that makes ping work while capture is on.
 *
 * Before this existed the packet dispatcher knew only TCP and UDP, so with a
 * default route through the TUN every echo request a user's app sent was
 * dropped on the floor with nothing said about it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IcmpConnectionTest {

    private val appIp = "10.215.173.2"
    private val remoteIp = "1.1.1.1"
    private val appRaw = byteArrayOf(10, 215.toByte(), 173.toByte(), 2)
    private val remoteRaw = byteArrayOf(1, 1, 1, 1)
    private val guestId = 0x4D2

    /** Records what the flow sent upstream and feeds replies back to it. */
    private class FakeEchoSocket : EchoSocket {
        val sent = ArrayList<ByteArray>()
        val destinations = ArrayList<String>()
        private val inbound = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        @Volatile var failSend = false

        override fun send(message: ByteArray, offset: Int, length: Int, to: InetAddress): Boolean {
            if (failSend) return false
            sent += message.copyOfRange(offset, offset + length)
            destinations += to.hostAddress.orEmpty()
            return true
        }

        override fun receive(into: ByteArray): Int {
            // Mirrors the real socket: a timeout is how a blocked read gives the
            // loop a chance to notice cancellation.
            val next = inbound.poll(20, TimeUnit.MILLISECONDS) ?: return EchoSocket.TIMED_OUT
            if (next.isEmpty()) return -1
            System.arraycopy(next, 0, into, 0, next.size)
            return next.size
        }

        override fun close() {
            closed = true
            inbound.put(ByteArray(0))
        }

        fun deliver(message: ByteArray) = inbound.put(message)
    }

    private class CapturingWriter : TunWriter {
        val pool = ByteArrayPool()
        val written = ArrayList<ByteArray>()

        override fun acquire(size: Int): PacketBuf = pool.acquire(size)

        override fun submit(buf: PacketBuf) {
            synchronized(written) { written += buf.array.copyOf(buf.length) }
            pool.release(buf)
        }

        override fun release(buf: PacketBuf) = pool.release(buf)
    }

    private fun flow(): Flow =
        Flow(FlowKey(Proto.ICMP, appIp, guestId, remoteIp, 0), ipVersion = 4, firstSeen = 1000L)

    private fun echoRequest(seq: Int, payload: Int = 16): ByteArray =
        ByteArray(Icmp.HEADER_LENGTH + payload) { (it and 0xFF).toByte() }.also {
            it[0] = Icmp.V4_ECHO_REQUEST.toByte()
            it[1] = 0
            Packets.put16(it, 4, guestId)
            Packets.put16(it, 6, seq)
        }

    private fun echoReply(seq: Int, kernelId: Int, payload: Int = 16): ByteArray =
        ByteArray(Icmp.HEADER_LENGTH + payload) { (it and 0xFF).toByte() }.also {
            it[0] = Icmp.V4_ECHO_REPLY.toByte()
            it[1] = 0
            Packets.put16(it, 4, kernelId)
            Packets.put16(it, 6, seq)
        }

    private fun connection(
        socket: EchoSocket?,
        f: Flow,
        writer: TunWriter,
        scope: TestScope,
        onClosed: (FlowKey) -> Unit = {},
    ) = IcmpConnection(
        flow = f,
        ipVersion = 4,
        appRaw = appRaw,
        remoteRaw = remoteRaw,
        tun = writer,
        openSocket = { socket },
        scope = scope,
        mtu = 1500,
        onClosed = onClosed,
    )

    private fun awaitWritten(writer: CapturingWriter, count: Int) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (synchronized(writer.written) { writer.written.size } >= count) return
            Thread.sleep(5)
        }
    }

    @Test
    fun `an echo request is relayed with the identifier cleared for the kernel`() = runTest {
        val socket = FakeEchoSocket()
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(socket, f, writer, scope)

        assertTrue(conn.start(1000L))
        assertEquals(ConnState.ACTIVE, f.state)

        conn.send(echoRequest(seq = 5), 0, Icmp.HEADER_LENGTH + 16, 1001L)

        assertEquals(1, socket.sent.size)
        val sent = socket.sent[0]
        // A ping socket rewrites the identifier to its own port, so writing the
        // guest's would be discarded and its checksum invalidated.
        assertEquals(0, Icmp.id(sent, 0))
        assertEquals(0, Packets.u16(sent, 2))
        assertEquals(5, Icmp.seq(sent, 0))
        assertEquals(remoteIp, socket.destinations[0])
        assertEquals((Icmp.HEADER_LENGTH + 16).toLong(), f.tx.get())
        conn.close()
    }

    @Test
    fun `a reply is given the guest's identifier back and wrapped for the TUN`() = runTest {
        val socket = FakeEchoSocket()
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(socket, f, writer, scope)
        conn.start(1000L)
        conn.send(echoRequest(seq = 5), 0, Icmp.HEADER_LENGTH + 16, 1001L)

        // The kernel answers under the identifier it chose, not the guest's.
        socket.deliver(echoReply(seq = 5, kernelId = 0x7777))
        awaitWritten(writer, 1)

        val packet = synchronized(writer.written) { writer.written.single() }
        val ip = Packets.parseIp(packet, packet.size)
        assertNotNull(ip)
        assertEquals(IpProto.ICMP, ip!!.protocol)
        // The reply has to come from the host that was pinged, addressed to the
        // app, or the local stack will not match it to the request.
        assertEquals(remoteIp, ip.srcIp)
        assertEquals(appIp, ip.dstIp)
        assertEquals(guestId, Icmp.id(packet, ip.payloadOffset))
        assertEquals(5, Icmp.seq(packet, ip.payloadOffset))
        assertEquals(0, Packets.checksum(packet, ip.payloadOffset, ip.payloadLength))
        conn.close()
    }

    @Test
    fun `the round trip is measured and lands on the flow`() = runTest {
        val socket = FakeEchoSocket()
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(socket, f, writer, scope)
        conn.start(1000L)

        assertEquals(-1, f.rttMillis)
        conn.send(echoRequest(seq = 1), 0, Icmp.HEADER_LENGTH + 16, 1001L)
        socket.deliver(echoReply(seq = 1, kernelId = 1))
        awaitWritten(writer, 1)

        // This is a real measurement of a real round trip, unlike a TCP
        // handshake time, which also pays for the peer's accept path.
        assertTrue("rtt was ${f.rttMillis}", f.rttMillis >= 0)
        assertEquals((Icmp.HEADER_LENGTH + 16).toLong(), f.rx.get())
        conn.close()
    }

    @Test
    fun `a reply whose sequence was never sent leaves the rtt alone`() = runTest {
        val socket = FakeEchoSocket()
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(socket, f, writer, scope)
        conn.start(1000L)

        socket.deliver(echoReply(seq = 999, kernelId = 1))
        awaitWritten(writer, 1)

        // It is still relayed — the kernel delivered it to this socket, so it
        // belongs to this flow — but there is no request to time it against.
        assertEquals(1, synchronized(writer.written) { writer.written.size })
        assertEquals(-1, f.rttMillis)
        conn.close()
    }

    @Test
    fun `anything that is not an echo reply is not written to the TUN`() = runTest {
        val socket = FakeEchoSocket()
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(socket, f, writer, scope)
        conn.start(1000L)

        val timeExceeded = ByteArray(Icmp.HEADER_LENGTH + 8).also { it[0] = 11 }
        socket.deliver(timeExceeded)
        socket.deliver(echoReply(seq = 2, kernelId = 1))
        awaitWritten(writer, 1)

        assertEquals(1, synchronized(writer.written) { writer.written.size })
        conn.close()
    }

    @Test
    fun `a socket the kernel refuses to open fails the flow rather than throwing`() = runTest {
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        // ping_group_range is a kernel setting, not a guarantee, so the whole
        // feature has to degrade rather than break the engine.
        val conn = connection(null, f, writer, scope)
        assertFalse(conn.start(1000L))
        assertTrue(synchronized(writer.written) { writer.written.isEmpty() })
    }

    @Test
    fun `a failed send closes the flow, and closing twice reports once`() = runTest {
        val socket = FakeEchoSocket()
        val writer = CapturingWriter()
        val f = flow()
        val closed = ArrayList<FlowKey>()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(socket, f, writer, scope) { closed += it }
        conn.start(1000L)

        socket.failSend = true
        conn.send(echoRequest(seq = 1), 0, Icmp.HEADER_LENGTH + 16, 1001L)
        conn.close()

        assertTrue(socket.closed)
        assertEquals(listOf(f.key), closed)
    }

    @Test
    fun `sending before the socket is open does nothing`() = runTest {
        val writer = CapturingWriter()
        val f = flow()
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val conn = connection(FakeEchoSocket(), f, writer, scope)
        conn.send(echoRequest(seq = 1), 0, Icmp.HEADER_LENGTH + 16, 1001L)
        assertEquals(0L, f.tx.get())
    }
}
