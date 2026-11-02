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
 * A refused connection must fail at once, not time out.
 *
 * The RST that refuses a SYN has to acknowledge it, or the app's kernel drops
 * it as unacceptable and keeps retransmitting the SYN. Found on the M10: a
 * connection to a blocked host hung for the whole connect timeout instead of
 * reporting "connection refused".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpRejectTest {

    private val appRaw = byteArrayOf(10, 215.toByte(), 173.toByte(), 2)
    private val remoteRaw = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)

    private class Recording : TunWriter {
        private val pool = ByteArrayPool()
        val sent = ArrayList<ByteArray>()
        override fun acquire(size: Int): PacketBuf = pool.acquire(size)
        override fun submit(buf: PacketBuf) {
            sent += buf.array.copyOf(buf.length)
            pool.release(buf)
        }
        override fun release(buf: PacketBuf) = pool.release(buf)
    }

    @Test
    fun `the refusing reset acknowledges the syn`() = runTest {
        val tun = Recording()
        val flow = Flow(FlowKey(Proto.TCP, "10.215.173.2", 50000, "93.184.216.34", 443), 4, 1000L)
        val conn = TcpConnection(
            flow = flow,
            ipVersion = 4,
            appRaw = appRaw,
            remoteRaw = remoteRaw,
            tun = tun,
            protect = { true },
            scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
            mtu = 1500,
            onClosed = {},
        )
        val synSeq = 123_456_789L
        val syn = Packets.buildTcp(
            ipVersion = 4,
            srcRaw = appRaw,
            dstRaw = remoteRaw,
            srcPort = 50000,
            dstPort = 443,
            seq = synSeq,
            ack = 0,
            flags = TcpFlag.SYN,
            window = 65535,
        )
        val ip = Packets.parseIp(syn, syn.size)!!
        conn.reject(Packets.parseTcp(syn, ip)!!)

        val rst = tun.sent.single()
        val rIp = Packets.parseIp(rst, rst.size)!!
        val rTcp = Packets.parseTcp(rst, rIp)!!
        assertTrue("not a reset", rTcp.isRst)
        assertTrue("a reset answering a SYN must carry ACK", rTcp.isAck)
        assertEquals(synSeq + 1, rTcp.ack)
        assertTrue(flow.blocked)
    }
}
