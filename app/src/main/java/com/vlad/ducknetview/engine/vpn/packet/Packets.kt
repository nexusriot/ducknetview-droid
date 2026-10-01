package com.vlad.ducknetview.engine.vpn.packet

/**
 * Minimal IPv4/IPv6 + TCP/UDP codec for the TUN loop.
 *
 * Everything here is pure byte manipulation with no Android dependency, so the
 * whole wire format is exercised by plain JVM tests against hex fixtures.
 *
 * The header types are mutable and reusable on purpose: the TUN read loop parses
 * thousands of packets a second and allocating a header per packet is pure
 * garbage. A reused header is only ever valid for the buffer and the call that
 * filled it — see [PacketParser], which owns one set of scratch headers for the
 * single thread that drives the read loop.
 */
object IpProto {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
    const val ICMPV6 = 58
}

object TcpFlag {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
    const val URG = 0x20
}

class IpHeader {
    var version: Int = 0
    var protocol: Int = 0
    var srcIp: String = ""
    var dstIp: String = ""
    var headerLength: Int = 0
    var payloadOffset: Int = 0
    var payloadLength: Int = 0

    /** Where the addresses sit in the buffer this header was parsed from. */
    var srcOffset: Int = 0
    var dstOffset: Int = 0
    var addrLength: Int = 0

    /**
     * A private copy of the source address. Only call this while [buf] still
     * holds the packet this header was parsed from — the read loop reuses that
     * buffer for the next packet as soon as dispatch returns.
     */
    fun srcRaw(buf: ByteArray): ByteArray = buf.copyOfRange(srcOffset, srcOffset + addrLength)

    fun dstRaw(buf: ByteArray): ByteArray = buf.copyOfRange(dstOffset, dstOffset + addrLength)

    override fun equals(other: Any?): Boolean =
        other is IpHeader && version == other.version && protocol == other.protocol &&
            srcIp == other.srcIp && dstIp == other.dstIp &&
            headerLength == other.headerLength && payloadLength == other.payloadLength

    override fun hashCode(): Int =
        (((version * 31 + protocol) * 31 + srcIp.hashCode()) * 31 + dstIp.hashCode()) * 31 +
            payloadLength
}

class TcpHeader {
    var srcPort: Int = 0
    var dstPort: Int = 0
    var seq: Long = 0
    var ack: Long = 0
    var dataOffset: Int = 0
    var flags: Int = 0
    var window: Int = 0
    var mss: Int = 0
    var payloadOffset: Int = 0
    var payloadLength: Int = 0

    val isSyn get() = flags and TcpFlag.SYN != 0
    val isAck get() = flags and TcpFlag.ACK != 0
    val isFin get() = flags and TcpFlag.FIN != 0
    val isRst get() = flags and TcpFlag.RST != 0
}

class UdpHeader {
    var srcPort: Int = 0
    var dstPort: Int = 0
    var length: Int = 0
    var payloadOffset: Int = 0
    var payloadLength: Int = 0
}

/**
 * Direct-mapped cache of address bytes to their text form.
 *
 * Rendering an address allocates a String on every packet otherwise, and the
 * cached instances make the [com.vlad.ducknetview.engine.vpn.FlowKey] hash
 * cheap too, because String caches its own hashCode. Not thread-safe: one
 * instance belongs to one parsing thread.
 */
class AddrTextCache(slots: Int = 64) {
    private val mask = Integer.highestOneBit(slots.coerceIn(1, 1024)) - 1
    private val keys = arrayOfNulls<ByteArray>(mask + 1)
    private val values = arrayOfNulls<String>(mask + 1)

    fun text(buf: ByteArray, offset: Int, length: Int): String {
        val slot = hash(buf, offset, length) and mask
        val key = keys[slot]
        if (key != null && key.size == length && matches(key, buf, offset)) return values[slot]!!
        val raw = buf.copyOfRange(offset, offset + length)
        val text = if (length == 4) Packets.ipv4ToString(raw) else Packets.ipv6ToString(raw)
        keys[slot] = raw
        values[slot] = text
        return text
    }

    private fun hash(buf: ByteArray, offset: Int, length: Int): Int {
        var h = 0
        for (i in 0 until length) h = h * 31 + (buf[offset + i].toInt() and 0xFF)
        return h xor (h ushr 16) and 0x7FFFFFFF
    }

    private fun matches(key: ByteArray, buf: ByteArray, offset: Int): Boolean {
        for (i in key.indices) if (key[i] != buf[offset + i]) return false
        return true
    }
}

/**
 * Per-thread parsing scratch: one header of each kind plus the address text
 * cache. The returned headers are the parser's own and stay valid only until
 * the next parse call on the same parser.
 */
class PacketParser {
    private val ip = IpHeader()
    private val tcp = TcpHeader()
    private val udp = UdpHeader()
    private val text = AddrTextCache()

    fun parseIp(buf: ByteArray, length: Int): IpHeader? =
        if (Packets.parseIpInto(buf, length, ip, text)) ip else null

    fun parseTcp(buf: ByteArray, header: IpHeader): TcpHeader? =
        if (Packets.parseTcpInto(buf, header, tcp)) tcp else null

    fun parseUdp(buf: ByteArray, header: IpHeader): UdpHeader? =
        if (Packets.parseUdpInto(buf, header, udp)) udp else null
}

object Packets {

    fun parseIp(buf: ByteArray, length: Int): IpHeader? {
        val out = IpHeader()
        return if (parseIpInto(buf, length, out, null)) out else null
    }

    fun parseIpInto(buf: ByteArray, length: Int, out: IpHeader, text: AddrTextCache?): Boolean {
        if (length < 20) return false
        return when ((buf[0].toInt() ushr 4) and 0x0F) {
            4 -> parseIpv4(buf, length, out, text)
            6 -> parseIpv6(buf, length, out, text)
            else -> false
        }
    }

    private fun addrText(buf: ByteArray, offset: Int, length: Int, text: AddrTextCache?): String =
        text?.text(buf, offset, length)
            ?: if (length == 4) {
                ipv4ToString(buf, offset)
            } else {
                ipv6ToString(buf, offset)
            }

    private fun parseIpv4(
        buf: ByteArray,
        length: Int,
        out: IpHeader,
        text: AddrTextCache?,
    ): Boolean {
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return false
        val total = u16(buf, 2)
        // A truncated read must not be trusted to describe more than we hold.
        val effective = if (total in ihl..length) total else length
        out.version = 4
        out.protocol = buf[9].toInt() and 0xFF
        out.srcIp = addrText(buf, 12, 4, text)
        out.dstIp = addrText(buf, 16, 4, text)
        out.srcOffset = 12
        out.dstOffset = 16
        out.addrLength = 4
        out.headerLength = ihl
        out.payloadOffset = ihl
        out.payloadLength = effective - ihl
        return true
    }

    private fun parseIpv6(
        buf: ByteArray,
        length: Int,
        out: IpHeader,
        text: AddrTextCache?,
    ): Boolean {
        if (length < 40) return false
        val payloadLen = u16(buf, 4)
        var next = buf[6].toInt() and 0xFF
        var offset = 40
        // Walk the extension-header chain to the transport header. Anything we
        // do not understand stops the walk and is reported as-is.
        var guard = 0
        while (guard++ < 8) {
            when (next) {
                0, 43, 60 -> {
                    if (length < offset + 8) return false
                    val hdrLen = ((buf[offset + 1].toInt() and 0xFF) + 1) * 8
                    next = buf[offset].toInt() and 0xFF
                    offset += hdrLen
                }
                else -> break
            }
        }
        if (offset > length) return false
        val effectiveEnd = minOf(length, 40 + payloadLen)
        out.version = 6
        out.protocol = next
        out.srcIp = addrText(buf, 8, 16, text)
        out.dstIp = addrText(buf, 24, 16, text)
        out.srcOffset = 8
        out.dstOffset = 24
        out.addrLength = 16
        out.headerLength = 40
        out.payloadOffset = offset
        out.payloadLength = (effectiveEnd - offset).coerceAtLeast(0)
        return true
    }

    fun parseTcp(buf: ByteArray, ip: IpHeader): TcpHeader? {
        val out = TcpHeader()
        return if (parseTcpInto(buf, ip, out)) out else null
    }

    fun parseTcpInto(buf: ByteArray, ip: IpHeader, out: TcpHeader): Boolean {
        val off = ip.payloadOffset
        if (ip.payloadLength < 20 || buf.size < off + 20) return false
        val dataOffset = ((buf[off + 12].toInt() and 0xF0) ushr 4) * 4
        if (dataOffset < 20 || dataOffset > ip.payloadLength) return false
        var mss = 0
        var i = off + 20
        val optEnd = off + dataOffset
        while (i < optEnd && i < buf.size) {
            when (buf[i].toInt() and 0xFF) {
                0 -> break
                1 -> i++
                else -> {
                    if (i + 1 >= buf.size) break
                    val kind = buf[i].toInt() and 0xFF
                    val len = buf[i + 1].toInt() and 0xFF
                    if (len < 2 || i + len > optEnd) break
                    if (kind == 2 && len == 4) mss = u16(buf, i + 2)
                    i += len
                }
            }
        }
        out.srcPort = u16(buf, off)
        out.dstPort = u16(buf, off + 2)
        out.seq = u32(buf, off + 4)
        out.ack = u32(buf, off + 8)
        out.dataOffset = dataOffset
        out.flags = buf[off + 13].toInt() and 0x3F
        out.window = u16(buf, off + 14)
        out.mss = mss
        out.payloadOffset = off + dataOffset
        out.payloadLength = ip.payloadLength - dataOffset
        return true
    }

    fun parseUdp(buf: ByteArray, ip: IpHeader): UdpHeader? {
        val out = UdpHeader()
        return if (parseUdpInto(buf, ip, out)) out else null
    }

    fun parseUdpInto(buf: ByteArray, ip: IpHeader, out: UdpHeader): Boolean {
        val off = ip.payloadOffset
        if (ip.payloadLength < 8 || buf.size < off + 8) return false
        val len = u16(buf, off + 4)
        out.srcPort = u16(buf, off)
        out.dstPort = u16(buf, off + 2)
        out.length = len
        out.payloadOffset = off + 8
        out.payloadLength = (len - 8).coerceIn(0, ip.payloadLength - 8)
        return true
    }

    /** Bytes [buildTcpInto] will write for these parameters. */
    fun tcpPacketLength(ipVersion: Int, payloadLength: Int, mss: Int = 0): Int =
        (if (ipVersion == 4) 20 else 40) + 20 + (if (mss > 0) 4 else 0) + payloadLength

    /** Bytes [buildUdpInto] will write for these parameters. */
    fun udpPacketLength(ipVersion: Int, payloadLength: Int): Int =
        (if (ipVersion == 4) 20 else 40) + 8 + payloadLength

    /** Bytes [buildIcmpInto] will write for an ICMP message of this length. */
    fun icmpPacketLength(ipVersion: Int, messageLength: Int): Int =
        (if (ipVersion == 4) 20 else 40) + messageLength

    /**
     * Build a TCP segment. Addresses are the raw bytes as they will appear on
     * the wire, so the caller decides the direction; [ipVersion] must match
     * their length.
     */
    fun buildTcp(
        ipVersion: Int,
        srcRaw: ByteArray,
        dstRaw: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = payload?.size ?: 0,
        mss: Int = 0,
    ): ByteArray {
        val out = ByteArray(tcpPacketLength(ipVersion, payloadLength, mss))
        buildTcpInto(
            out, 0, ipVersion, srcRaw, dstRaw, srcPort, dstPort, seq, ack, flags, window,
            payload, payloadOffset, payloadLength, mss,
        )
        return out
    }

    /**
     * Write a TCP segment into [out] starting at [offset] and return how many
     * bytes were written. Every byte of the packet is written, so a recycled
     * buffer holding old data is safe here.
     */
    fun buildTcpInto(
        out: ByteArray,
        offset: Int,
        ipVersion: Int,
        srcRaw: ByteArray,
        dstRaw: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray? = null,
        payloadOffset: Int = 0,
        payloadLength: Int = payload?.size ?: 0,
        mss: Int = 0,
    ): Int {
        val options = if (mss > 0) 4 else 0
        val tcpLen = 20 + options + payloadLength
        val ipHdrLen = if (ipVersion == 4) 20 else 40

        writeIpHeader(out, offset, ipVersion, srcRaw, dstRaw, IpProto.TCP, tcpLen)

        val t = offset + ipHdrLen
        put16(out, t, srcPort)
        put16(out, t + 2, dstPort)
        put32(out, t + 4, seq)
        put32(out, t + 8, ack)
        out[t + 12] = (((20 + options) / 4) shl 4).toByte()
        out[t + 13] = flags.toByte()
        put16(out, t + 14, window)
        // Checksum and urgent pointer must read as zero while the checksum is
        // computed over them; a pooled buffer arrives holding the last packet.
        put16(out, t + 16, 0)
        put16(out, t + 18, 0)
        if (options > 0) {
            out[t + 20] = 2
            out[t + 21] = 4
            put16(out, t + 22, mss)
        }
        if (payload != null && payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, out, t + 20 + options, payloadLength)
        }
        put16(out, t + 16, transportChecksum(out, offset, ipVersion, t, tcpLen, IpProto.TCP))
        return ipHdrLen + tcpLen
    }

    fun buildUdp(
        ipVersion: Int,
        srcRaw: ByteArray,
        dstRaw: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ): ByteArray {
        val out = ByteArray(udpPacketLength(ipVersion, payloadLength))
        buildUdpInto(
            out, 0, ipVersion, srcRaw, dstRaw, srcPort, dstPort,
            payload, payloadOffset, payloadLength,
        )
        return out
    }

    /** As [buildUdp], but into [out] at [offset]; returns the bytes written. */
    fun buildUdpInto(
        out: ByteArray,
        offset: Int,
        ipVersion: Int,
        srcRaw: ByteArray,
        dstRaw: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ): Int {
        val udpLen = 8 + payloadLength
        val ipHdrLen = if (ipVersion == 4) 20 else 40
        writeIpHeader(out, offset, ipVersion, srcRaw, dstRaw, IpProto.UDP, udpLen)
        val u = offset + ipHdrLen
        put16(out, u, srcPort)
        put16(out, u + 2, dstPort)
        put16(out, u + 4, udpLen)
        put16(out, u + 6, 0)
        if (payloadLength > 0) {
            System.arraycopy(payload, payloadOffset, out, u + 8, payloadLength)
        }
        var ck = transportChecksum(out, offset, ipVersion, u, udpLen, IpProto.UDP)
        // UDP uses 0 to mean "no checksum", so a computed zero is sent inverted.
        if (ck == 0) ck = 0xFFFF
        put16(out, u + 6, ck)
        return ipHdrLen + udpLen
    }

    /**
     * Wrap a complete ICMP message in an IP header and write it into [out] at
     * [offset], returning the bytes written.
     *
     * The message is copied verbatim apart from its checksum, which is always
     * recomputed: a reply coming back from a ping socket carries the kernel's
     * checksum over the identifier the kernel chose, and the relay rewrites
     * that identifier to the guest's. ICMPv4 checksums the message alone;
     * ICMPv6 includes the IPv6 pseudo-header, which is why this has to happen
     * after the addresses are in place.
     */
    fun buildIcmpInto(
        out: ByteArray,
        offset: Int,
        ipVersion: Int,
        srcRaw: ByteArray,
        dstRaw: ByteArray,
        message: ByteArray,
        messageOffset: Int = 0,
        messageLength: Int = message.size,
    ): Int {
        val ipHdrLen = if (ipVersion == 4) 20 else 40
        val protocol = if (ipVersion == 4) IpProto.ICMP else IpProto.ICMPV6
        writeIpHeader(out, offset, ipVersion, srcRaw, dstRaw, protocol, messageLength)
        val m = offset + ipHdrLen
        System.arraycopy(message, messageOffset, out, m, messageLength)
        put16(out, m + 2, 0)
        val ck = if (ipVersion == 4) {
            checksum(out, m, messageLength)
        } else {
            transportChecksum(out, offset, 6, m, messageLength, IpProto.ICMPV6)
        }
        put16(out, m + 2, ck)
        return ipHdrLen + messageLength
    }

    private fun writeIpHeader(
        out: ByteArray,
        base: Int,
        version: Int,
        srcRaw: ByteArray,
        dstRaw: ByteArray,
        protocol: Int,
        payloadLength: Int,
    ) {
        if (version == 4) {
            out[base] = 0x45
            out[base + 1] = 0
            put16(out, base + 2, 20 + payloadLength)
            put16(out, base + 4, 0)
            put16(out, base + 6, 0x4000) // Don't Fragment: we never emit fragments.
            out[base + 8] = 64
            out[base + 9] = protocol.toByte()
            put16(out, base + 10, 0)
            System.arraycopy(srcRaw, 0, out, base + 12, 4)
            System.arraycopy(dstRaw, 0, out, base + 16, 4)
            put16(out, base + 10, checksum(out, base, 20))
        } else {
            out[base] = 0x60
            // Traffic class and flow label are always zero for us, and must be
            // written explicitly because the buffer may be recycled.
            out[base + 1] = 0
            put16(out, base + 2, 0)
            put16(out, base + 4, payloadLength)
            out[base + 6] = protocol.toByte()
            out[base + 7] = 64
            System.arraycopy(srcRaw, 0, out, base + 8, 16)
            System.arraycopy(dstRaw, 0, out, base + 24, 16)
        }
    }

    private fun transportChecksum(
        buf: ByteArray,
        base: Int,
        ipVersion: Int,
        transportOffset: Int,
        transportLength: Int,
        protocol: Int,
    ): Int {
        var sum = 0L
        val addrOffset = base + if (ipVersion == 4) 12 else 8
        val addrLen = if (ipVersion == 4) 4 else 16
        var i = addrOffset
        while (i < addrOffset + addrLen * 2) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += protocol.toLong()
        sum += transportLength.toLong()
        sum += rawSum(buf, transportOffset, transportLength)
        return fold(sum)
    }

    fun checksum(buf: ByteArray, offset: Int, length: Int): Int = fold(rawSum(buf, offset, length))

    /**
     * One's-complement sum, accumulated 32 bits at a time.
     *
     * Folding is associative, so summing big-endian 32-bit words and folding
     * once at the end gives the same answer as summing 16-bit words while
     * touching the array half as often — this loop runs over every payload byte
     * the proxy relays, so its cost is the packet rate times the MTU.
     */
    private fun rawSum(buf: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        val end8 = offset + (length and 7.inv())
        while (i < end8) {
            sum += word32(buf, i)
            sum += word32(buf, i + 4)
            i += 8
        }
        while (i + 3 < end) {
            sum += word32(buf, i)
            i += 4
        }
        if (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun word32(buf: ByteArray, i: Int): Long =
        (((buf[i].toInt() and 0xFF) shl 24) or ((buf[i + 1].toInt() and 0xFF) shl 16) or
            ((buf[i + 2].toInt() and 0xFF) shl 8) or (buf[i + 3].toInt() and 0xFF))
            .toLong() and 0xFFFFFFFFL

    private fun fold(value: Long): Int {
        var sum = value
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun u16(buf: ByteArray, i: Int): Int =
        ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)

    fun u32(buf: ByteArray, i: Int): Long =
        ((buf[i].toLong() and 0xFF) shl 24) or ((buf[i + 1].toLong() and 0xFF) shl 16) or
            ((buf[i + 2].toLong() and 0xFF) shl 8) or (buf[i + 3].toLong() and 0xFF)

    fun put16(buf: ByteArray, i: Int, v: Int) {
        buf[i] = ((v ushr 8) and 0xFF).toByte()
        buf[i + 1] = (v and 0xFF).toByte()
    }

    fun put32(buf: ByteArray, i: Int, v: Long) {
        buf[i] = ((v ushr 24) and 0xFF).toByte()
        buf[i + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[i + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[i + 3] = (v and 0xFF).toByte()
    }

    fun ipv4ToString(b: ByteArray, offset: Int = 0): String =
        "${b[offset].toInt() and 0xFF}.${b[offset + 1].toInt() and 0xFF}." +
            "${b[offset + 2].toInt() and 0xFF}.${b[offset + 3].toInt() and 0xFF}"

    /** RFC 5952 form: lowercase hex, longest run of zero groups collapsed once. */
    fun ipv6ToString(b: ByteArray, offset: Int = 0): String {
        val groups = IntArray(8) { u16(b, offset + it * 2) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (groups[i] == 0) {
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) {
                    bestLen = j - i
                    bestStart = i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        var k = 0
        while (k < 8) {
            if (k == bestStart) {
                sb.append("::")
                k += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(Integer.toHexString(groups[k]))
            k++
        }
        return if (sb.isEmpty()) "::" else sb.toString()
    }

    /** Sequence-space comparison, correct across the 32-bit wrap. */
    fun seqLte(a: Long, b: Long): Boolean = ((b - a) and 0xFFFFFFFFL) < 0x80000000L

    fun seqAdd(a: Long, n: Long): Long = (a + n) and 0xFFFFFFFFL
}
