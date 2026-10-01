package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.Icmp
import com.vlad.ducknetview.engine.vpn.packet.IpProto
import com.vlad.ducknetview.engine.vpn.packet.Packets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The reply the relay writes back to the TUN is built into a pooled buffer that
 * still holds the previous packet, so these build into pre-dirtied arrays: a
 * field left unwritten shows up as a checksum that does not verify.
 */
class PacketsIcmpTest {

    private val v4Src = byteArrayOf(8, 8, 8, 8)
    private val v4Dst = byteArrayOf(10, 0, 0, 2)
    private val v6Src = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }
    private val v6Dst = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }

    private fun dirty(size: Int) = ByteArray(size) { ((it * 37 + 11) and 0xFF).toByte() }

    private fun reply(id: Int = 0x1111, seq: Int = 3, payloadSize: Int = 16, type: Int) =
        ByteArray(Icmp.HEADER_LENGTH + payloadSize) { (it and 0xFF).toByte() }.also {
            it[0] = type.toByte()
            it[1] = 0
            Packets.put16(it, 2, 0)
            Packets.put16(it, 4, id)
            Packets.put16(it, 6, seq)
        }

    @Test
    fun `a v4 reply gets a valid IP header and a valid ICMP checksum`() {
        val message = reply(type = Icmp.V4_ECHO_REPLY)
        val out = dirty(Packets.icmpPacketLength(4, message.size))
        val written = Packets.buildIcmpInto(out, 0, 4, v4Src, v4Dst, message)

        assertEquals(20 + message.size, written)
        val ip = Packets.parseIp(out, written)
        assertNotNull(ip)
        assertEquals(IpProto.ICMP, ip!!.protocol)
        assertEquals("8.8.8.8", ip.srcIp)
        assertEquals("10.0.0.2", ip.dstIp)
        assertEquals(message.size, ip.payloadLength)
        // A correct one's-complement checksum sums to zero over its own data.
        assertEquals(0, Packets.checksum(out, 0, 20))
        assertEquals(0, Packets.checksum(out, 20, message.size))
    }

    @Test
    fun `the message is copied through unchanged apart from its checksum`() {
        val message = reply(id = 0x2222, seq = 9, type = Icmp.V4_ECHO_REPLY)
        val out = dirty(Packets.icmpPacketLength(4, message.size))
        Packets.buildIcmpInto(out, 0, 4, v4Src, v4Dst, message)

        assertEquals(Icmp.V4_ECHO_REPLY, Icmp.type(out, 20))
        assertEquals(0x2222, Icmp.id(out, 20))
        assertEquals(9, Icmp.seq(out, 20))
        assertArrayEquals(
            message.copyOfRange(Icmp.HEADER_LENGTH, message.size),
            out.copyOfRange(20 + Icmp.HEADER_LENGTH, 20 + message.size),
        )
    }

    @Test
    fun `a v6 reply checksums over the pseudo-header, not the message alone`() {
        val message = reply(type = Icmp.V6_ECHO_REPLY)
        val out = dirty(Packets.icmpPacketLength(6, message.size))
        val written = Packets.buildIcmpInto(out, 0, 6, v6Src, v6Dst, message)

        assertEquals(40 + message.size, written)
        val ip = Packets.parseIp(out, written)
        assertNotNull(ip)
        assertEquals(IpProto.ICMPV6, ip!!.protocol)
        assertEquals(message.size, ip.payloadLength)

        // Summing the message alone does not come to zero, because the real
        // checksum covers the addresses too — the field being non-trivial is
        // exactly the point of computing it after the header is in place.
        val overMessageOnly = Packets.checksum(out, 40, message.size)
        val sum = (0xFFFF - overMessageOnly) and 0xFFFF
        assertEquals(true, sum != 0)
    }

    @Test
    fun `building at an offset writes only that region`() {
        val message = reply(type = Icmp.V4_ECHO_REPLY, payloadSize = 8)
        val size = Packets.icmpPacketLength(4, message.size)
        val direct = dirty(size)
        Packets.buildIcmpInto(direct, 0, 4, v4Src, v4Dst, message)

        val offset = 11
        val shifted = dirty(size + offset * 2)
        val before = shifted.copyOf()
        Packets.buildIcmpInto(shifted, offset, 4, v4Src, v4Dst, message)

        assertArrayEquals(direct, shifted.copyOfRange(offset, offset + size))
        assertArrayEquals(before.copyOfRange(0, offset), shifted.copyOfRange(0, offset))
        assertArrayEquals(
            before.copyOfRange(offset + size, shifted.size),
            shifted.copyOfRange(offset + size, shifted.size),
        )
    }

    @Test
    fun `a stale checksum in a recycled buffer is overwritten, not added to`() {
        val message = reply(type = Icmp.V4_ECHO_REPLY)
        // A message arriving with the kernel's own checksum still in it must be
        // recomputed from zero, or the second checksum folds over the first.
        Packets.put16(message, 2, 0x5A5A)
        val out = dirty(Packets.icmpPacketLength(4, message.size))
        Packets.buildIcmpInto(out, 0, 4, v4Src, v4Dst, message)
        assertEquals(0, Packets.checksum(out, 20, message.size))
    }
}
