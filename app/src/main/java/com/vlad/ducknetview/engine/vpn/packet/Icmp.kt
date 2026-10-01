package com.vlad.ducknetview.engine.vpn.packet

/**
 * Echo request/reply accessors for the ICMP messages the proxy relays.
 *
 * Only echo is handled. An unprivileged app can open a Linux "ping" socket
 * (`SOCK_DGRAM`/`IPPROTO_ICMP`), which carries echo and nothing else: errors
 * such as Time Exceeded arrive on the socket error queue, and the public API
 * has no `MSG_ERRQUEUE`. So ping works through the TUN and traceroute does
 * not, which the Conns info sheet says rather than leaving it to be guessed.
 *
 * Pure byte manipulation, so the whole wire format is covered by JVM tests.
 */
object Icmp {

    const val V4_ECHO_REPLY = 0
    const val V4_ECHO_REQUEST = 8
    const val V6_ECHO_REQUEST = 128
    const val V6_ECHO_REPLY = 129

    /** type, code, checksum, identifier, sequence. */
    const val HEADER_LENGTH = 8

    fun type(buf: ByteArray, offset: Int): Int = buf[offset].toInt() and 0xFF

    fun code(buf: ByteArray, offset: Int): Int = buf[offset + 1].toInt() and 0xFF

    fun id(buf: ByteArray, offset: Int): Int = Packets.u16(buf, offset + 4)

    fun seq(buf: ByteArray, offset: Int): Int = Packets.u16(buf, offset + 6)

    fun putId(buf: ByteArray, offset: Int, value: Int) = Packets.put16(buf, offset + 4, value)

    fun putType(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = value.toByte()
    }

    fun echoRequestType(ipVersion: Int): Int =
        if (ipVersion == 4) V4_ECHO_REQUEST else V6_ECHO_REQUEST

    fun echoReplyType(ipVersion: Int): Int =
        if (ipVersion == 4) V4_ECHO_REPLY else V6_ECHO_REPLY

    fun isEchoRequest(buf: ByteArray, offset: Int, length: Int, ipVersion: Int): Boolean =
        isEcho(buf, offset, length, echoRequestType(ipVersion))

    fun isEchoReply(buf: ByteArray, offset: Int, length: Int, ipVersion: Int): Boolean =
        isEcho(buf, offset, length, echoReplyType(ipVersion))

    private fun isEcho(buf: ByteArray, offset: Int, length: Int, wanted: Int): Boolean {
        if (length < HEADER_LENGTH || offset + HEADER_LENGTH > buf.size) return false
        return type(buf, offset) == wanted && code(buf, offset) == 0
    }

    /**
     * Build an echo request to hand to a ping socket.
     *
     * The identifier is left at zero: the kernel replaces it with the socket's
     * own port and rewrites the checksum, so a value written here would be
     * discarded. The guest's identifier is restored on the reply instead — see
     * [com.vlad.ducknetview.engine.vpn.IcmpConnection].
     */
    fun buildEchoRequest(ipVersion: Int, seq: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LENGTH + payload.size)
        out[0] = echoRequestType(ipVersion).toByte()
        out[1] = 0
        Packets.put16(out, 2, 0)
        Packets.put16(out, 4, 0)
        Packets.put16(out, 6, seq)
        System.arraycopy(payload, 0, out, HEADER_LENGTH, payload.size)
        if (ipVersion == 4) Packets.put16(out, 2, Packets.checksum(out, 0, out.size))
        return out
    }
}
