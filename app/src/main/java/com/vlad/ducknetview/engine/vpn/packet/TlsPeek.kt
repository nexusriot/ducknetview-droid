package com.vlad.ducknetview.engine.vpn.packet

/**
 * Reads the `server_name` out of a TLS ClientHello.
 *
 * Why this exists: the connection table labels a remote address with the name
 * the device looked up, taken from DNS answers crossing the TUN. On a device
 * with Private DNS switched on — the default on most modern Android — those
 * answers are encrypted and [DnsPeek] sees nothing at all, so every row falls
 * back to a bare IP. SNI is the one name still in the clear, and reading it is
 * what keeps the table legible on exactly the devices most likely to run this
 * app.
 *
 * This is not TLS interception. SNI is sent unencrypted by the client before
 * any key exchange, nothing is decrypted, no key is touched, and the name is
 * the only field read — the handshake is relayed byte for byte either way.
 * Encrypted Client Hello will eventually take this away again, and when it
 * does the field is simply absent and the row falls back to its address.
 *
 * Pure byte manipulation, so the wire format is covered by JVM tests.
 */
object TlsPeek {

    private const val RECORD_HANDSHAKE = 0x16
    private const val HANDSHAKE_CLIENT_HELLO = 0x01
    private const val EXT_SERVER_NAME = 0x0000
    private const val NAME_TYPE_HOST = 0x00

    /** The record header alone; a shorter read cannot even be classified. */
    const val MIN_LENGTH = 5

    /**
     * True when these bytes open a TLS handshake record. Cheap enough to run on
     * the first segment of every flow, which is the point: the expensive walk
     * below then runs only for handshakes.
     */
    fun looksLikeHandshake(buf: ByteArray, offset: Int, length: Int): Boolean =
        length >= MIN_LENGTH &&
            offset + MIN_LENGTH <= buf.size &&
            (buf[offset].toInt() and 0xFF) == RECORD_HANDSHAKE &&
            (buf[offset + 1].toInt() and 0xFF) == 3

    /**
     * The SNI host name, or null when there is none to read.
     *
     * Null covers every uninteresting case alike: not a handshake, not a
     * ClientHello, no server_name extension, a hello split across more than one
     * TCP segment, or a field that claims to run past what was handed in. A
     * ClientHello large enough to be fragmented is rare but legal, and
     * reassembling one would mean buffering attacker-controlled bytes for a
     * cosmetic label — so it is deliberately not done.
     */
    fun serverName(buf: ByteArray, offset: Int, length: Int): String? {
        if (!looksLikeHandshake(buf, offset, length)) return null
        val end = offset + minOf(length, buf.size - offset)
        val r = Reader(buf, offset, end)

        r.skip(3) // record type and version
        val recordLength = r.u16() ?: return null
        val recordEnd = r.position + recordLength
        if (recordEnd > end) return null // hello continues in a later segment

        if (r.u8() != HANDSHAKE_CLIENT_HELLO) return null
        val helloLength = r.u24() ?: return null
        if (r.position + helloLength > recordEnd) return null

        r.skip(2) // client version
        r.skip(32) // random
        if (!r.skipVector(1)) return null // session id
        if (!r.skipVector(2)) return null // cipher suites
        if (!r.skipVector(1)) return null // compression methods

        val extensionsLength = r.u16() ?: return null
        val extensionsEnd = r.position + extensionsLength
        if (extensionsEnd > recordEnd) return null

        while (r.position + 4 <= extensionsEnd) {
            val type = r.u16() ?: return null
            val size = r.u16() ?: return null
            val next = r.position + size
            if (next > extensionsEnd) return null
            if (type == EXT_SERVER_NAME) return readServerName(r, next)
            r.position = next
        }
        return null
    }

    private fun readServerName(r: Reader, extensionEnd: Int): String? {
        val listLength = r.u16() ?: return null
        val listEnd = r.position + listLength
        if (listEnd > extensionEnd) return null
        while (r.position + 3 <= listEnd) {
            val nameType = r.u8()
            val nameLength = r.u16() ?: return null
            val nameEnd = r.position + nameLength
            if (nameEnd > listEnd) return null
            if (nameType == NAME_TYPE_HOST) {
                val name = String(r.buf, r.position, nameLength, Charsets.US_ASCII)
                return name.takeIf { isPlausibleHost(it) }
            }
            r.position = nameEnd
        }
        return null
    }

    /**
     * A name is going straight into the UI and into stored history, so it is
     * checked rather than trusted: the bytes come off the wire from whatever
     * the device was talking to. The rule lives in [HostNames] because a DNS
     * answer needs exactly the same treatment.
     */
    internal fun isPlausibleHost(name: String): Boolean = HostNames.isPlausible(name)

    private class Reader(val buf: ByteArray, var position: Int, val end: Int) {

        fun skip(n: Int) {
            position += n
        }

        fun u8(): Int {
            if (position >= end) return -1
            return buf[position++].toInt() and 0xFF
        }

        fun u16(): Int? {
            if (position + 2 > end) return null
            val v = Packets.u16(buf, position)
            position += 2
            return v
        }

        fun u24(): Int? {
            if (position + 3 > end) return null
            val v = ((buf[position].toInt() and 0xFF) shl 16) or
                ((buf[position + 1].toInt() and 0xFF) shl 8) or
                (buf[position + 2].toInt() and 0xFF)
            position += 3
            return v
        }

        /** Steps over a length-prefixed vector whose length field is [sizeBytes] wide. */
        fun skipVector(sizeBytes: Int): Boolean {
            val size = when (sizeBytes) {
                1 -> u8().takeIf { it >= 0 }
                2 -> u16()
                else -> null
            } ?: return false
            if (position + size > end) return false
            position += size
            return true
        }
    }
}
