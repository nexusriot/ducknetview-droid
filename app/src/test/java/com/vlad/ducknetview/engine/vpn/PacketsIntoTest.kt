package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.Packets
import com.vlad.ducknetview.engine.vpn.packet.TcpFlag
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The pooled write path builds packets into buffers that already hold the
 * previous packet's bytes. Every test here therefore builds into a buffer
 * pre-filled with junk and demands output byte-identical to the allocating
 * builder — a field left unwritten shows up immediately.
 */
class PacketsIntoTest {

    private val v4Src = byteArrayOf(10, 0, 0, 1)
    private val v4Dst = byteArrayOf(192.toByte(), 168.toByte(), 1, 55)
    private val v6Src = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
    private val v6Dst = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 2 }

    private fun dirty(size: Int, seed: Int = 7): ByteArray =
        ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun src(version: Int) = if (version == 4) v4Src else v6Src

    private fun dst(version: Int) = if (version == 4) v4Dst else v6Dst

    private fun buildBoth(
        version: Int,
        payload: ByteArray?,
        payloadOffset: Int = 0,
        payloadLength: Int = payload?.size ?: 0,
        mss: Int = 0,
        flags: Int = TcpFlag.PSH or TcpFlag.ACK,
        window: Int = 65535,
        offset: Int = 0,
    ): Pair<ByteArray, ByteArray> {
        val expected = Packets.buildTcp(
            ipVersion = version,
            srcRaw = src(version),
            dstRaw = dst(version),
            srcPort = 443,
            dstPort = 51000,
            seq = 0xFFFFFF00L,
            ack = 12345,
            flags = flags,
            window = window,
            payload = payload,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            mss = mss,
        )
        val out = dirty(offset + expected.size + 16)
        val written = Packets.buildTcpInto(
            out = out,
            offset = offset,
            ipVersion = version,
            srcRaw = src(version),
            dstRaw = dst(version),
            srcPort = 443,
            dstPort = 51000,
            seq = 0xFFFFFF00L,
            ack = 12345,
            flags = flags,
            window = window,
            payload = payload,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength,
            mss = mss,
        )
        assertEquals(expected.size, written)
        return expected to out.copyOfRange(offset, offset + written)
    }

    @Test
    fun tcpIntoMatchesTheAllocatingBuilderAcrossPayloadSizesV4() {
        for (size in intArrayOf(0, 1, 2, 3, 7, 64, 535, 536, 1459, 1460)) {
            val payload = Random(size).nextBytes(size)
            val (expected, actual) = buildBoth(4, payload.takeIf { size > 0 })
            assertArrayEquals("payload size $size", expected, actual)
        }
    }

    @Test
    fun tcpIntoMatchesTheAllocatingBuilderAcrossPayloadSizesV6() {
        for (size in intArrayOf(0, 1, 2, 3, 7, 64, 1440)) {
            val payload = Random(size).nextBytes(size)
            val (expected, actual) = buildBoth(6, payload.takeIf { size > 0 })
            assertArrayEquals("payload size $size", expected, actual)
        }
    }

    @Test
    fun tcpIntoMatchesWithTheMssOption() {
        for (version in intArrayOf(4, 6)) {
            val (expected, actual) = buildBoth(
                version,
                payload = null,
                mss = 1460,
                flags = TcpFlag.SYN or TcpFlag.ACK,
            )
            assertArrayEquals("v$version", expected, actual)
        }
    }

    @Test
    fun tcpIntoMatchesWithTheMssOptionAndAPayload() {
        val payload = Random(3).nextBytes(200)
        val (expected, actual) = buildBoth(4, payload, mss = 536)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun tcpIntoMatchesForAZeroWindowReset() {
        val (expected, actual) = buildBoth(
            4,
            payload = null,
            flags = TcpFlag.RST or TcpFlag.ACK,
            window = 0,
        )
        assertArrayEquals(expected, actual)
    }

    @Test
    fun tcpIntoMatchesWhenWritingAtANonZeroOffset() {
        val payload = Random(11).nextBytes(300)
        for (offset in intArrayOf(1, 4, 13, 64)) {
            val (expected, actual) = buildBoth(4, payload, offset = offset)
            assertArrayEquals("offset $offset", expected, actual)
        }
    }

    @Test
    fun tcpIntoMatchesForASliceOfALargerPayload() {
        val big = Random(5).nextBytes(4096)
        val (expected, actual) = buildBoth(4, big, payloadOffset = 1000, payloadLength = 1460)
        assertArrayEquals(expected, actual)
        // and the bytes really are the requested slice
        val ip = Packets.parseIp(actual, actual.size)!!
        val tcp = Packets.parseTcp(actual, ip)!!
        assertArrayEquals(
            big.copyOfRange(1000, 2460),
            actual.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength),
        )
    }

    @Test
    fun tcpIntoLeavesTheSurroundingBufferUntouched() {
        val out = dirty(2048)
        val before = out.copyOf()
        val written = Packets.buildTcpInto(
            out = out,
            offset = 100,
            ipVersion = 4,
            srcRaw = v4Src,
            dstRaw = v4Dst,
            srcPort = 1,
            dstPort = 2,
            seq = 3,
            ack = 4,
            flags = TcpFlag.ACK,
            window = 100,
            payload = ByteArray(50) { 9 },
        )
        assertArrayEquals(before.copyOfRange(0, 100), out.copyOfRange(0, 100))
        assertArrayEquals(
            before.copyOfRange(100 + written, 2048),
            out.copyOfRange(100 + written, 2048),
        )
    }

    @Test
    fun tcpPacketLengthAgreesWithWhatIsBuilt() {
        for (version in intArrayOf(4, 6)) {
            for (mss in intArrayOf(0, 1460)) {
                for (size in intArrayOf(0, 1, 999)) {
                    val built = Packets.buildTcp(
                        ipVersion = version,
                        srcRaw = src(version),
                        dstRaw = dst(version),
                        srcPort = 1,
                        dstPort = 2,
                        seq = 0,
                        ack = 0,
                        flags = TcpFlag.ACK,
                        window = 1,
                        payload = ByteArray(size),
                        mss = mss,
                    )
                    assertEquals(built.size, Packets.tcpPacketLength(version, size, mss))
                }
            }
        }
    }

    @Test
    fun udpIntoMatchesTheAllocatingBuilder() {
        for (version in intArrayOf(4, 6)) {
            for (size in intArrayOf(0, 1, 2, 5, 512, 1472)) {
                val payload = Random(size + version).nextBytes(size)
                val expected = Packets.buildUdp(
                    ipVersion = version,
                    srcRaw = src(version),
                    dstRaw = dst(version),
                    srcPort = 53,
                    dstPort = 40000,
                    payload = payload,
                )
                val out = dirty(expected.size + 32, seed = 200)
                val written = Packets.buildUdpInto(
                    out = out,
                    offset = 0,
                    ipVersion = version,
                    srcRaw = src(version),
                    dstRaw = dst(version),
                    srcPort = 53,
                    dstPort = 40000,
                    payload = payload,
                )
                assertEquals(expected.size, written)
                assertArrayEquals("v$version size $size", expected, out.copyOfRange(0, written))
            }
        }
    }

    @Test
    fun udpIntoMatchesAtANonZeroOffsetAndForASlice() {
        val big = Random(77).nextBytes(2000)
        val expected = Packets.buildUdp(
            ipVersion = 4,
            srcRaw = v4Src,
            dstRaw = v4Dst,
            srcPort = 5353,
            dstPort = 53,
            payload = big,
            payloadOffset = 500,
            payloadLength = 400,
        )
        val out = dirty(expected.size + 40, seed = 99)
        val written = Packets.buildUdpInto(
            out = out,
            offset = 17,
            ipVersion = 4,
            srcRaw = v4Src,
            dstRaw = v4Dst,
            srcPort = 5353,
            dstPort = 53,
            payload = big,
            payloadOffset = 500,
            payloadLength = 400,
        )
        assertArrayEquals(expected, out.copyOfRange(17, 17 + written))
    }

    @Test
    fun udpPacketLengthAgreesWithWhatIsBuilt() {
        for (version in intArrayOf(4, 6)) {
            for (size in intArrayOf(0, 1, 1472)) {
                val built = Packets.buildUdp(
                    ipVersion = version,
                    srcRaw = src(version),
                    dstRaw = dst(version),
                    srcPort = 1,
                    dstPort = 2,
                    payload = ByteArray(size),
                )
                assertEquals(built.size, Packets.udpPacketLength(version, size))
            }
        }
    }

    @Test
    fun packetsBuiltIntoADirtyBufferStillCarryValidChecksums() {
        val out = dirty(4096, seed = 123)
        val written = Packets.buildTcpInto(
            out = out,
            offset = 33,
            ipVersion = 4,
            srcRaw = v4Src,
            dstRaw = v4Dst,
            srcPort = 80,
            dstPort = 33000,
            seq = 1,
            ack = 2,
            flags = TcpFlag.PSH or TcpFlag.ACK,
            window = 65535,
            payload = Random(1).nextBytes(1001),
        )
        val pkt = out.copyOfRange(33, 33 + written)
        // A correct IPv4 header checksum makes the header sum to zero.
        assertEquals(0, Packets.checksum(pkt, 0, 20).let { if (it == 0xFFFF) 0 else it })
        val ip = Packets.parseIp(pkt, pkt.size)!!
        val tcp = Packets.parseTcp(pkt, ip)!!
        assertEquals(1001, tcp.payloadLength)
        assertNotEquals(0, Packets.u16(pkt, 20 + 16))
    }

    /** The 32-bit accumulation must agree with the textbook 16-bit sum exactly. */
    @Test
    fun checksumMatchesANaiveSixteenBitReferenceOverRandomData() {
        val rnd = Random(4242)
        for (length in 0..300) {
            val buf = rnd.nextBytes(length + 8)
            for (offset in intArrayOf(0, 1, 3, 8)) {
                if (offset + length > buf.size) continue
                assertEquals(
                    "len=$length off=$offset",
                    naiveChecksum(buf, offset, length),
                    Packets.checksum(buf, offset, length),
                )
            }
        }
    }

    @Test
    fun checksumMatchesTheNaiveReferenceOnALargeBuffer() {
        val buf = Random(9).nextBytes(65535)
        for (length in intArrayOf(1459, 1460, 1461, 65535)) {
            assertEquals(naiveChecksum(buf, 0, length), Packets.checksum(buf, 0, length))
        }
    }

    @Test
    fun checksumOfAnAllOnesBufferFoldsCorrectly() {
        val buf = ByteArray(2048) { 0xFF.toByte() }
        for (length in intArrayOf(0, 1, 2, 3, 1023, 2048)) {
            assertEquals(naiveChecksum(buf, 0, length), Packets.checksum(buf, 0, length))
        }
    }

    @Test
    fun aMaximumSegmentRoundTripsThroughTheIntoBuilder() {
        val payload = Random(31).nextBytes(1460)
        val out = dirty(2048, seed = 55)
        val written = Packets.buildTcpInto(
            out = out,
            offset = 0,
            ipVersion = 4,
            srcRaw = v4Src,
            dstRaw = v4Dst,
            srcPort = 443,
            dstPort = 40404,
            seq = 100,
            ack = 200,
            flags = TcpFlag.PSH or TcpFlag.ACK,
            window = 65535,
            payload = payload,
        )
        assertEquals(1500, written)
        val ip = Packets.parseIp(out, written)!!
        val tcp = Packets.parseTcp(out, ip)!!
        assertArrayEquals(
            payload,
            out.copyOfRange(tcp.payloadOffset, tcp.payloadOffset + tcp.payloadLength),
        )
        assertTrue(tcp.isAck)
    }

    private fun naiveChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
