package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.IpProto
import com.vlad.ducknetview.engine.vpn.packet.Packets
import com.vlad.ducknetview.engine.vpn.packet.TcpFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketsTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(Regex("[^0-9a-fA-F]"), "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or
                Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    /** A real IPv4/TCP SYN captured from a loopback connect. */
    private val ipv4Syn = hex(
        "45 00 00 3c 1c 46 40 00 40 06 b1 e6 c0 a8 00 68 c0 a8 00 01" +
            "d4 31 00 50 00 00 00 00 00 00 00 00 a0 02 72 10 e6 32 00 00" +
            "02 04 05 b4 04 02 08 0a 00 0a 00 00 00 00 00 00 01 03 03 07"
    )

    @Test
    fun parsesIpv4Header() {
        val ip = Packets.parseIp(ipv4Syn, ipv4Syn.size)
        assertNotNull(ip)
        assertEquals(4, ip!!.version)
        assertEquals(IpProto.TCP, ip.protocol)
        assertEquals("192.168.0.104", ip.srcIp)
        assertEquals("192.168.0.1", ip.dstIp)
        assertEquals(20, ip.headerLength)
        assertEquals(40, ip.payloadLength)
    }

    @Test
    fun parsesTcpSynWithMssOption() {
        val ip = Packets.parseIp(ipv4Syn, ipv4Syn.size)!!
        val tcp = Packets.parseTcp(ipv4Syn, ip)!!
        assertEquals(54321, tcp.srcPort)
        assertEquals(80, tcp.dstPort)
        assertTrue(tcp.isSyn)
        assertTrue(!tcp.isAck)
        assertEquals(1460, tcp.mss)
        assertEquals(40, tcp.dataOffset)
        assertEquals(0, tcp.payloadLength)
    }

    @Test
    fun truncatedBufferIsRejectedRatherThanTrusted() {
        assertNull(Packets.parseIp(ipv4Syn, 10))
        val ip = Packets.parseIp(ipv4Syn, 30)
        // totalLength claims 60 but only 30 bytes are held; payload must not
        // be reported as longer than what we actually have.
        assertTrue(ip == null || ip.payloadLength <= 10)
    }

    @Test
    fun ipv4HeaderChecksumOfBuiltPacketVerifies() {
        val pkt = Packets.buildTcp(
            ipVersion = 4,
            srcRaw = byteArrayOf(10, 0, 0, 1),
            dstRaw = byteArrayOf(10, 0, 0, 2),
            srcPort = 80,
            dstPort = 12345,
            seq = 1000,
            ack = 2000,
            flags = TcpFlag.SYN or TcpFlag.ACK,
            window = 65535,
            mss = 1460,
        )
        // A correct checksum makes the whole header sum to zero.
        assertEquals(0, Packets.checksum(pkt, 0, 20).let { if (it == 0xFFFF) 0 else it })
    }

    @Test
    fun builtTcpRoundTripsThroughTheParser() {
        val payload = "hello duck".toByteArray()
        val pkt = Packets.buildTcp(
            ipVersion = 4,
            srcRaw = byteArrayOf(1, 2, 3, 4),
            dstRaw = byteArrayOf(5, 6, 7, 8),
            srcPort = 443,
            dstPort = 51000,
            seq = 0xFFFFFFF0L,
            ack = 42,
            flags = TcpFlag.PSH or TcpFlag.ACK,
            window = 4096,
            payload = payload,
        )
        val ip = Packets.parseIp(pkt, pkt.size)!!
        val tcp = Packets.parseTcp(pkt, ip)!!
        assertEquals("1.2.3.4", ip.srcIp)
        assertEquals("5.6.7.8", ip.dstIp)
        assertEquals(443, tcp.srcPort)
        assertEquals(51000, tcp.dstPort)
        assertEquals(0xFFFFFFF0L, tcp.seq)
        assertEquals(42L, tcp.ack)
        assertEquals(payload.size, tcp.payloadLength)
        assertEquals(
            "hello duck",
            String(pkt, tcp.payloadOffset, tcp.payloadLength),
        )
    }

    @Test
    fun builtUdpRoundTripsAndNeverSendsAZeroChecksum() {
        val pkt = Packets.buildUdp(
            ipVersion = 4,
            srcRaw = byteArrayOf(9, 9, 9, 9),
            dstRaw = byteArrayOf(8, 8, 8, 8),
            srcPort = 5353,
            dstPort = 53,
            payload = byteArrayOf(0, 1, 2, 3),
        )
        val ip = Packets.parseIp(pkt, pkt.size)!!
        val udp = Packets.parseUdp(pkt, ip)!!
        assertEquals(5353, udp.srcPort)
        assertEquals(53, udp.dstPort)
        assertEquals(4, udp.payloadLength)
        val checksum = Packets.u16(pkt, 20 + 6)
        assertTrue("a zero checksum means 'not computed' in UDP", checksum != 0)
    }

    @Test
    fun ipv6PacketRoundTrips() {
        val src = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
        val dst = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 2 }
        val pkt = Packets.buildTcp(
            ipVersion = 6,
            srcRaw = src,
            dstRaw = dst,
            srcPort = 1234,
            dstPort = 443,
            seq = 7,
            ack = 8,
            flags = TcpFlag.ACK,
            window = 100,
            payload = byteArrayOf(1, 2, 3),
        )
        val ip = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(6, ip.version)
        assertEquals(IpProto.TCP, ip.protocol)
        val tcp = Packets.parseTcp(pkt, ip)!!
        assertEquals(1234, tcp.srcPort)
        assertEquals(3, tcp.payloadLength)
    }

    @Test
    fun ipv6TextFormFollowsRfc5952() {
        assertEquals("::1", Packets.ipv6ToString(ByteArray(16).also { it[15] = 1 }))
        assertEquals("::", Packets.ipv6ToString(ByteArray(16)))
        val addr = ByteArray(16)
        addr[0] = 0x20; addr[1] = 0x01; addr[2] = 0x0d; addr[3] = 0xb8.toByte(); addr[15] = 1
        assertEquals("2001:db8::1", Packets.ipv6ToString(addr))
    }

    @Test
    fun ipv4TextForm() {
        assertEquals(
            "255.128.0.7",
            Packets.ipv4ToString(byteArrayOf(255.toByte(), 128.toByte(), 0, 7)),
        )
    }

    @Test
    fun sequenceComparisonIsCorrectAcrossTheWrap() {
        assertTrue(Packets.seqLte(0xFFFFFFF0L, 0x00000010L))
        assertTrue(!Packets.seqLte(0x00000010L, 0xFFFFFFF0L))
        assertTrue(Packets.seqLte(5, 5))
        assertEquals(5L, Packets.seqAdd(0xFFFFFFFFL, 6))
    }

    @Test
    fun ipv6ExtensionHeaderChainIsWalkedToTheTransportHeader() {
        val pkt = ByteArray(40 + 8 + 20)
        pkt[0] = 0x60
        Packets.put16(pkt, 4, 28)
        pkt[6] = 0 // hop-by-hop
        pkt[7] = 64
        pkt[40] = IpProto.TCP.toByte()
        pkt[41] = 0 // (0+1)*8 = 8 bytes
        val ip = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(IpProto.TCP, ip.protocol)
        assertEquals(48, ip.payloadOffset)
    }

    @Test
    fun garbageInputIsRejectedWithoutThrowing() {
        assertNull(Packets.parseIp(ByteArray(0), 0))
        assertNull(Packets.parseIp(ByteArray(60) { 0xFF.toByte() }, 60))
        val short = ByteArray(21).also { it[0] = 0x45 }
        val ip = Packets.parseIp(short, 21)
        if (ip != null) assertNull(Packets.parseTcp(short, ip))
    }
}
