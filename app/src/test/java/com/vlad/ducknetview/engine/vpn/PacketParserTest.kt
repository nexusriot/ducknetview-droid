package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.AddrTextCache
import com.vlad.ducknetview.engine.vpn.packet.IpProto
import com.vlad.ducknetview.engine.vpn.packet.PacketParser
import com.vlad.ducknetview.engine.vpn.packet.Packets
import com.vlad.ducknetview.engine.vpn.packet.TcpFlag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The read loop parses into reused headers and reuses address strings. These
 * tests hold it to the same answers the allocating parser gives.
 */
class PacketParserTest {

    private fun tcpV4(payload: ByteArray?, srcLast: Int = 1, dstLast: Int = 2): ByteArray =
        Packets.buildTcp(
            ipVersion = 4,
            srcRaw = byteArrayOf(10, 0, 0, srcLast.toByte()),
            dstRaw = byteArrayOf(10, 0, 0, dstLast.toByte()),
            srcPort = 1111,
            dstPort = 2222,
            seq = 5,
            ack = 6,
            flags = TcpFlag.PSH or TcpFlag.ACK,
            window = 4096,
            payload = payload,
        )

    @Test
    fun theParserAgreesWithTheAllocatingParserForIpv4Tcp() {
        val pkt = tcpV4("hello".toByteArray())
        val parser = PacketParser()
        val a = Packets.parseIp(pkt, pkt.size)!!
        val b = parser.parseIp(pkt, pkt.size)!!
        assertEquals(a.version, b.version)
        assertEquals(a.protocol, b.protocol)
        assertEquals(a.srcIp, b.srcIp)
        assertEquals(a.dstIp, b.dstIp)
        assertEquals(a.headerLength, b.headerLength)
        assertEquals(a.payloadOffset, b.payloadOffset)
        assertEquals(a.payloadLength, b.payloadLength)
        assertEquals(a, b)

        val ta = Packets.parseTcp(pkt, a)!!
        val tb = parser.parseTcp(pkt, b)!!
        assertEquals(ta.srcPort, tb.srcPort)
        assertEquals(ta.dstPort, tb.dstPort)
        assertEquals(ta.seq, tb.seq)
        assertEquals(ta.ack, tb.ack)
        assertEquals(ta.flags, tb.flags)
        assertEquals(ta.payloadOffset, tb.payloadOffset)
        assertEquals(ta.payloadLength, tb.payloadLength)
    }

    @Test
    fun theParserAgreesWithTheAllocatingParserForIpv6Udp() {
        val src = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 9 }
        val dst = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 8 }
        val pkt = Packets.buildUdp(
            ipVersion = 6,
            srcRaw = src,
            dstRaw = dst,
            srcPort = 5353,
            dstPort = 53,
            payload = byteArrayOf(1, 2, 3, 4, 5),
        )
        val parser = PacketParser()
        val a = Packets.parseIp(pkt, pkt.size)!!
        val b = parser.parseIp(pkt, pkt.size)!!
        assertEquals(a.srcIp, b.srcIp)
        assertEquals(a.dstIp, b.dstIp)
        assertEquals(6, b.version)
        assertEquals(IpProto.UDP, b.protocol)
        val ua = Packets.parseUdp(pkt, a)!!
        val ub = parser.parseUdp(pkt, b)!!
        assertEquals(ua.srcPort, ub.srcPort)
        assertEquals(ua.dstPort, ub.dstPort)
        assertEquals(ua.length, ub.length)
        assertEquals(ua.payloadOffset, ub.payloadOffset)
        assertEquals(ua.payloadLength, ub.payloadLength)
    }

    @Test
    fun theScratchHeaderAlwaysDescribesTheMostRecentPacket() {
        val parser = PacketParser()
        val first = tcpV4(ByteArray(10), srcLast = 5, dstLast = 6)
        val second = tcpV4(ByteArray(100), srcLast = 7, dstLast = 8)
        val a = parser.parseIp(first, first.size)!!
        assertEquals("10.0.0.5", a.srcIp)
        val b = parser.parseIp(second, second.size)!!
        assertSame(a, b)
        assertEquals("10.0.0.7", b.srcIp)
        assertEquals("10.0.0.8", b.dstIp)
        assertEquals(120, b.payloadLength)
    }

    @Test
    fun rawAddressesAreReadBackFromTheParsedBuffer() {
        val parser = PacketParser()
        val pkt = tcpV4(null, srcLast = 44, dstLast = 55)
        val ip = parser.parseIp(pkt, pkt.size)!!
        assertArrayEquals(byteArrayOf(10, 0, 0, 44), ip.srcRaw(pkt))
        assertArrayEquals(byteArrayOf(10, 0, 0, 55), ip.dstRaw(pkt))
        assertEquals(4, ip.addrLength)
    }

    @Test
    fun rawIpv6AddressesAreReadBackFromTheParsedBuffer() {
        val src = ByteArray(16) { (it + 1).toByte() }
        val dst = ByteArray(16) { (100 - it).toByte() }
        val pkt = Packets.buildTcp(
            ipVersion = 6,
            srcRaw = src,
            dstRaw = dst,
            srcPort = 1,
            dstPort = 2,
            seq = 0,
            ack = 0,
            flags = TcpFlag.ACK,
            window = 1,
        )
        val ip = PacketParser().parseIp(pkt, pkt.size)!!
        assertArrayEquals(src, ip.srcRaw(pkt))
        assertArrayEquals(dst, ip.dstRaw(pkt))
        assertEquals(16, ip.addrLength)
    }

    @Test
    fun repeatedAddressesReuseTheSameStringInstance() {
        val parser = PacketParser()
        val pkt = tcpV4(null, srcLast = 3, dstLast = 4)
        val firstSrc = parser.parseIp(pkt, pkt.size)!!.srcIp
        val secondSrc = parser.parseIp(pkt, pkt.size)!!.srcIp
        assertSame(firstSrc, secondSrc)
    }

    @Test
    fun theParserRejectsTheSameGarbageTheAllocatingParserRejects() {
        val parser = PacketParser()
        assertNull(parser.parseIp(ByteArray(0), 0))
        assertNull(parser.parseIp(ByteArray(60) { 0xFF.toByte() }, 60))
        val short = ByteArray(21).also { it[0] = 0x45 }
        val ip = parser.parseIp(short, 21)
        if (ip != null) assertNull(parser.parseTcp(short, ip))
    }

    @Test
    fun aParserSurvivesManyDistinctAddressesWithoutMixingThemUp() {
        val parser = PacketParser()
        for (i in 0..255) {
            val pkt = Packets.buildTcp(
                ipVersion = 4,
                srcRaw = byteArrayOf(172.toByte(), 16, (i / 256).toByte(), i.toByte()),
                dstRaw = byteArrayOf(8, 8, (i and 1).toByte(), 8),
                srcPort = 1,
                dstPort = 2,
                seq = 0,
                ack = 0,
                flags = TcpFlag.ACK,
                window = 1,
            )
            val ip = parser.parseIp(pkt, pkt.size)!!
            assertEquals("172.16.0.$i", ip.srcIp)
            assertEquals("8.8.${i and 1}.8", ip.dstIp)
        }
    }

    @Test
    fun theAddressCacheRendersTheSameTextAsTheDirectFormatters() {
        val cache = AddrTextCache(slots = 4)
        val rnd = Random(1234)
        repeat(200) {
            val raw = rnd.nextBytes(4)
            val buf = ByteArray(8).also { b -> raw.copyInto(b, 2) }
            assertEquals(Packets.ipv4ToString(raw), cache.text(buf, 2, 4))
        }
        repeat(200) {
            val raw = rnd.nextBytes(16)
            val buf = ByteArray(20).also { b -> raw.copyInto(b, 3) }
            assertEquals(Packets.ipv6ToString(raw), cache.text(buf, 3, 16))
        }
    }

    @Test
    fun aTinyCacheStillReturnsCorrectTextWhenItEvicts() {
        val cache = AddrTextCache(slots = 1)
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(5, 6, 7, 8)
        repeat(10) {
            assertEquals("1.2.3.4", cache.text(a, 0, 4))
            assertEquals("5.6.7.8", cache.text(b, 0, 4))
        }
    }

    @Test
    fun parseIpIntoWithoutACacheMatchesParseIp() {
        val pkt = tcpV4(ByteArray(20), srcLast = 21, dstLast = 22)
        val out = com.vlad.ducknetview.engine.vpn.packet.IpHeader()
        assertTrue(Packets.parseIpInto(pkt, pkt.size, out, null))
        val ref = Packets.parseIp(pkt, pkt.size)!!
        assertEquals(ref.srcIp, out.srcIp)
        assertEquals(ref.dstIp, out.dstIp)
        assertEquals(ref, out)
        assertNotNull(Packets.parseTcp(pkt, out))
    }
}
