package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.Icmp
import com.vlad.ducknetview.engine.vpn.packet.Packets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IcmpTest {

    private fun echo(type: Int, code: Int = 0, id: Int = 0x1234, seq: Int = 7, payload: Int = 4) =
        ByteArray(Icmp.HEADER_LENGTH + payload).also {
            it[0] = type.toByte()
            it[1] = code.toByte()
            Packets.put16(it, 4, id)
            Packets.put16(it, 6, seq)
        }

    @Test
    fun `reads the identifier and sequence an echo carries`() {
        val m = echo(Icmp.V4_ECHO_REQUEST, id = 0xBEEF, seq = 513)
        assertEquals(0xBEEF, Icmp.id(m, 0))
        assertEquals(513, Icmp.seq(m, 0))
        assertEquals(Icmp.V4_ECHO_REQUEST, Icmp.type(m, 0))
    }

    @Test
    fun `the identifier can be rewritten in place, which is what the relay does`() {
        val m = echo(Icmp.V4_ECHO_REPLY, id = 1)
        Icmp.putId(m, 0, 0xABCD)
        assertEquals(0xABCD, Icmp.id(m, 0))
        // Nothing else moved: a relay that disturbed the sequence would break
        // the round-trip matching the RTT depends on.
        assertEquals(7, Icmp.seq(m, 0))
    }

    @Test
    fun `echo request and reply are told apart per address family`() {
        assertTrue(Icmp.isEchoRequest(echo(Icmp.V4_ECHO_REQUEST), 0, 12, 4))
        assertFalse(Icmp.isEchoReply(echo(Icmp.V4_ECHO_REQUEST), 0, 12, 4))
        assertTrue(Icmp.isEchoReply(echo(Icmp.V4_ECHO_REPLY), 0, 12, 4))

        assertTrue(Icmp.isEchoRequest(echo(Icmp.V6_ECHO_REQUEST), 0, 12, 6))
        assertTrue(Icmp.isEchoReply(echo(Icmp.V6_ECHO_REPLY), 0, 12, 6))
        // The v4 and v6 type numbers are different, so a v4 reply must not be
        // accepted as a v6 one; the two share a socket path but not a wire.
        assertFalse(Icmp.isEchoReply(echo(Icmp.V4_ECHO_REPLY), 0, 12, 6))
    }

    @Test
    fun `a non-zero code is not an echo`() {
        // Type 0 code 1 is not an echo reply; accepting it would relay another
        // message type as if it were one.
        assertFalse(Icmp.isEchoReply(echo(Icmp.V4_ECHO_REPLY, code = 1), 0, 12, 4))
    }

    @Test
    fun `a message shorter than the header is rejected rather than read past`() {
        val short = ByteArray(4)
        assertFalse(Icmp.isEchoRequest(short, 0, 4, 4))
        assertFalse(Icmp.isEchoReply(short, 0, 4, 4))
        // A length claiming more than the buffer holds must not be believed.
        assertFalse(Icmp.isEchoRequest(short, 0, 64, 4))
    }

    @Test
    fun `a built v4 echo request checksums to zero over itself`() {
        val m = Icmp.buildEchoRequest(4, seq = 9, payload = ByteArray(32) { it.toByte() })
        assertEquals(Icmp.V4_ECHO_REQUEST, Icmp.type(m, 0))
        assertEquals(9, Icmp.seq(m, 0))
        // The identifier is left for the kernel to fill in; a value here would
        // be overwritten and its checksum invalidated.
        assertEquals(0, Icmp.id(m, 0))
        assertEquals(Icmp.HEADER_LENGTH + 32, m.size)
        assertEquals(0, Packets.checksum(m, 0, m.size))
    }

    @Test
    fun `a built v6 echo request leaves the checksum to the kernel`() {
        val m = Icmp.buildEchoRequest(6, seq = 1, payload = ByteArray(8))
        assertEquals(Icmp.V6_ECHO_REQUEST, Icmp.type(m, 0))
        // ICMPv6 checksums cover the IPv6 pseudo-header, whose addresses are
        // only known once the kernel has picked a source, so it is left zero.
        assertEquals(0, Packets.u16(m, 2))
    }

    @Test
    fun `a truncated echo reply is never read past its end`() {
        val full = echo(Icmp.V4_ECHO_REPLY, payload = 40)
        for (n in 0..full.size) {
            assertNull(runCatching { Icmp.isEchoReply(full, 0, n, 4) }.exceptionOrNull())
        }
    }
}
