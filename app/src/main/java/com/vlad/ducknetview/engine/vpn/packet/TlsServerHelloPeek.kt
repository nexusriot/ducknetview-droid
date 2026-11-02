package com.vlad.ducknetview.engine.vpn.packet

/** A TLS protocol version as it appears on the wire. */
enum class TlsVersion(val label: String, val code: Int, val deprecated: Boolean) {
    SSL3("SSL 3.0", 0x0300, true),
    TLS1_0("TLS 1.0", 0x0301, true),
    TLS1_1("TLS 1.1", 0x0302, true),
    TLS1_2("TLS 1.2", 0x0303, false),
    TLS1_3("TLS 1.3", 0x0304, false),

    /** A code this table does not know, such as a pre-standard TLS 1.3 draft. */
    UNKNOWN("unknown", -1, false);

    companion object {
        fun of(code: Int): TlsVersion = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** What a server chose: the protocol version and the IANA cipher suite number. */
data class TlsServerInfo(val version: TlsVersion, val cipherSuite: Int)

/**
 * Reads the negotiated TLS version and cipher suite out of a ServerHello.
 *
 * Why: a ClientHello only says what an app *offered*. Whether a connection
 * actually runs on TLS 1.0 — deprecated by RFC 8996 — is decided by the
 * server, and the ServerHello that announces it is sent in the clear in every
 * TLS version including 1.3. As with [TlsPeek], nothing is decrypted: the two
 * fields are read off the first server-to-client bytes and the stream is
 * relayed untouched.
 *
 * TLS 1.3 hides behind a `legacy_version` of 1.2 for middlebox compatibility;
 * the real choice is in the `supported_versions` extension, which overrides
 * the legacy field whenever it is present.
 *
 * Only the ServerHello message itself has to be present. In TLS 1.2 it often
 * shares a record with the certificate chain, a record that runs to several
 * kilobytes over many segments; requiring the whole record would miss most
 * TLS 1.2 handshakes, so the walk stops at the end of the hello. Anything that
 * does not fit, or is not a ServerHello, is null — never an exception.
 */
object TlsServerHelloPeek {

    private const val RECORD_HANDSHAKE = 0x16
    private const val HANDSHAKE_SERVER_HELLO = 0x02
    private const val EXT_SUPPORTED_VERSIONS = 43
    private const val RANDOM_LENGTH = 32

    /**
     * RFC 8446 §4.1.3: a HelloRetryRequest is a ServerHello whose random is
     * SHA-256("HelloRetryRequest"). It asks the client to try again, so the
     * cipher suite in it is not the one the connection will use.
     */
    private val HELLO_RETRY_RANDOM = intArrayOf(
        0xCF, 0x21, 0xAD, 0x74, 0xE5, 0x9A, 0x61, 0x11, 0xBE, 0x1D, 0x8C, 0x02, 0x1E, 0x65, 0xB8, 0x91,
        0xC2, 0xA2, 0x11, 0x16, 0x7A, 0xBB, 0x8C, 0x5E, 0x07, 0x9E, 0x09, 0xE2, 0xC8, 0xA8, 0x33, 0x9C,
    )

    /**
     * The server's choice, or null for a truncated hello, a non-handshake record
     * (an alert, application data), a handshake that is not a ServerHello, or a
     * HelloRetryRequest — the real ServerHello follows that one.
     */
    fun parse(data: ByteArray, offset: Int, length: Int): TlsServerInfo? {
        if (offset < 0 || length <= 0 || offset >= data.size) return null
        val end = offset + minOf(length, data.size - offset)
        val r = Reader(data, offset, end)

        if (r.u8() != RECORD_HANDSHAKE) return null
        if (r.u8() != 3) return null // record version major
        if (r.u8() < 0) return null // record version minor
        val recordLength = r.u16() ?: return null
        val limit = minOf(r.position + recordLength, end)

        if (r.u8() != HANDSHAKE_SERVER_HELLO) return null
        val helloLength = r.u24() ?: return null
        val helloEnd = r.position + helloLength
        if (helloEnd > limit) return null

        val r2 = Reader(data, r.position, helloEnd)
        val legacyVersion = r2.u16() ?: return null
        if (r2.position + RANDOM_LENGTH > helloEnd) return null
        if (isHelloRetry(data, r2.position)) return null
        r2.position += RANDOM_LENGTH
        if (!r2.skipVector1()) return null // legacy_session_id_echo
        val cipherSuite = r2.u16() ?: return null
        if (r2.u8() < 0) return null // legacy_compression_method

        var versionCode = legacyVersion
        // Extensions are optional before TLS 1.3; a hello that ends here is complete.
        if (r2.position < helloEnd) {
            val extLength = r2.u16() ?: return null
            val extEnd = r2.position + extLength
            if (extEnd > helloEnd) return null
            while (r2.position + 4 <= extEnd) {
                val type = r2.u16() ?: return null
                val size = r2.u16() ?: return null
                val next = r2.position + size
                if (next > extEnd) return null
                if (type == EXT_SUPPORTED_VERSIONS) {
                    // A server selects exactly one version: two bytes, not a list.
                    if (size != 2) return null
                    versionCode = r2.u16() ?: return null
                }
                r2.position = next
            }
        }
        return TlsServerInfo(TlsVersion.of(versionCode), cipherSuite)
    }

    private fun isHelloRetry(buf: ByteArray, at: Int): Boolean {
        for (i in HELLO_RETRY_RANDOM.indices) {
            if ((buf[at + i].toInt() and 0xFF) != HELLO_RETRY_RANDOM[i]) return false
        }
        return true
    }

    /**
     * A private copy of the bounds-checked reader [TlsPeek] keeps to itself;
     * that one is private and this file must not change it.
     */
    private class Reader(val buf: ByteArray, var position: Int, val end: Int) {

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

        fun skipVector1(): Boolean {
            val size = u8()
            if (size < 0 || position + size > end) return false
            position += size
            return true
        }
    }
}
