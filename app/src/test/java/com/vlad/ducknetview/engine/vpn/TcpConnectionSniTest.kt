package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.engine.vpn.packet.Packets
import com.vlad.ducknetview.engine.vpn.packet.TcpFlag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring between the ClientHello parser and the flow.
 *
 * The parsing itself is covered by TlsPeekTest; what matters here is that the
 * peek happens once, on the first in-order data segment, and that a flow which
 * is not TLS pays almost nothing for the attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpConnectionSniTest {

    private val appRaw = byteArrayOf(10, 215.toByte(), 173.toByte(), 2)
    private val remoteRaw = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)

    private class CapturingWriter : TunWriter {
        private val pool = ByteArrayPool()
        override fun acquire(size: Int): PacketBuf = pool.acquire(size)
        override fun submit(buf: PacketBuf) = pool.release(buf)
        override fun release(buf: PacketBuf) = pool.release(buf)
    }

    private fun u16(v: Int) = listOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun clientHello(name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII).toList()
        val entry = listOf(0x00.toByte()) + u16(nameBytes.size) + nameBytes
        val list = u16(entry.size) + entry
        val extensions = u16(0x0000) + u16(list.size) + list

        val body = ArrayList<Byte>()
        body += u16(0x0303)
        body += ByteArray(32).toList()
        body += 0x00
        body += u16(2) + listOf(0x13.toByte(), 0x01.toByte())
        body += listOf(0x01.toByte(), 0x00.toByte())
        body += u16(extensions.size)
        body += extensions

        val handshake = ArrayList<Byte>()
        handshake += 0x01
        handshake += listOf(0, ((body.size ushr 8) and 0xFF).toByte(), (body.size and 0xFF).toByte())
        handshake += body

        return (listOf(0x16.toByte()) + u16(0x0301) + u16(handshake.size) + handshake).toByteArray()
    }

    /** Feeds one data segment into the connection the way the dispatcher does. */
    private fun deliver(conn: TcpConnection, payload: ByteArray, seq: Long) {
        val packet = Packets.buildTcp(
            ipVersion = 4,
            srcRaw = appRaw,
            dstRaw = remoteRaw,
            srcPort = 50000,
            dstPort = 443,
            seq = seq,
            ack = 1,
            flags = TcpFlag.PSH or TcpFlag.ACK,
            window = 65535,
            payload = payload,
        )
        val ip = Packets.parseIp(packet, packet.size)!!
        val tcp = Packets.parseTcp(packet, ip)!!
        conn.onPacket(tcp, packet, now = 1000L)
    }

    private fun connection(scope: TestScope, names: MutableList<String>): TcpConnection =
        TcpConnection(
            flow = Flow(
                FlowKey(Proto.TCP, "10.215.173.2", 50000, "93.184.216.34", 443),
                ipVersion = 4,
                firstSeen = 1000L,
            ),
            ipVersion = 4,
            appRaw = appRaw,
            remoteRaw = remoteRaw,
            tun = CapturingWriter(),
            protect = { true },
            scope = scope,
            mtu = 1500,
            onSni = { names += it },
            onClosed = {},
        )

    @Test
    fun `the server name is read off the first data segment`() = runTest {
        val names = ArrayList<String>()
        val conn = connection(TestScope(UnconfinedTestDispatcher(testScheduler)), names)

        deliver(conn, clientHello("example.com"), seq = 0)

        assertEquals(listOf("example.com"), names)
        conn.close()
    }

    @Test
    fun `later segments are not re-examined`() = runTest {
        val names = ArrayList<String>()
        val conn = connection(TestScope(UnconfinedTestDispatcher(testScheduler)), names)

        val hello = clientHello("example.com")
        deliver(conn, hello, seq = 0)
        // Scanning every segment of every flow for a handshake would be a cost
        // paid on all traffic forever for an answer that only arrives first.
        deliver(conn, clientHello("later.example.com"), seq = hello.size.toLong())

        assertEquals(listOf("example.com"), names)
        conn.close()
    }

    @Test
    fun `a flow that does not start with a handshake is never peeked at again`() = runTest {
        val names = ArrayList<String>()
        val conn = connection(TestScope(UnconfinedTestDispatcher(testScheduler)), names)

        val request = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        deliver(conn, request, seq = 0)
        deliver(conn, clientHello("example.com"), seq = request.size.toLong())

        assertTrue(names.isEmpty())
        conn.close()
    }

    @Test
    fun `a TLS flow carrying no server name reports nothing`() = runTest {
        val names = ArrayList<String>()
        val conn = connection(TestScope(UnconfinedTestDispatcher(testScheduler)), names)

        // A handshake record whose body is not a readable ClientHello: the peek
        // has to be silent rather than guess.
        deliver(conn, byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00), seq = 0)

        assertTrue(names.isEmpty())
        conn.close()
    }

    @Test
    fun `a callback that throws does not disturb the relay`() = runTest {
        // The name is cosmetic; the bytes are the user's traffic. Nothing about
        // labelling may take a flow down.
        val conn = TcpConnection(
            flow = Flow(
                FlowKey(Proto.TCP, "10.215.173.2", 50000, "93.184.216.34", 443),
                ipVersion = 4,
                firstSeen = 1000L,
            ),
            ipVersion = 4,
            appRaw = appRaw,
            remoteRaw = remoteRaw,
            tun = CapturingWriter(),
            protect = { true },
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            mtu = 1500,
            onSni = { throw IllegalStateException("sink exploded") },
            onClosed = {},
        )

        deliver(conn, clientHello("example.com"), seq = 0)
        conn.close()
    }
}
