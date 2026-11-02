package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.TlsServerHelloPeek
import com.vlad.ducknetview.engine.vpn.packet.TlsServerInfo
import com.vlad.ducknetview.engine.vpn.packet.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsServerHelloPeekTest {

    private fun u16(v: Int) = listOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u24(v: Int) = listOf(((v ushr 16) and 0xFF).toByte()) + u16(v)

    private val hrrRandom = intArrayOf(
        0xCF, 0x21, 0xAD, 0x74, 0xE5, 0x9A, 0x61, 0x11, 0xBE, 0x1D, 0x8C, 0x02, 0x1E, 0x65, 0xB8, 0x91,
        0xC2, 0xA2, 0x11, 0x16, 0x7A, 0xBB, 0x8C, 0x5E, 0x07, 0x9E, 0x09, 0xE2, 0xC8, 0xA8, 0x33, 0x9C,
    ).map { it.toByte() }

    private fun ext(type: Int, body: List<Byte>) = u16(type) + u16(body.size) + body

    private fun supportedVersions(code: Int) = ext(43, u16(code))

    /**
     * A ServerHello handshake message, then [trailing] bytes in the same record
     * (a Certificate message, say), all wrapped in one handshake record.
     * [extensions] null omits the extensions block entirely, as TLS 1.2 allows.
     */
    private fun serverHello(
        legacyVersion: Int = 0x0303,
        cipherSuite: Int = 0xC02F,
        extensions: List<Byte>? = null,
        random: List<Byte> = List(32) { (it + 1).toByte() },
        sessionId: List<Byte> = List(32) { 0x55 },
        handshakeType: Int = 0x02,
        recordType: Int = 0x16,
        trailing: List<Byte> = emptyList(),
    ): ByteArray {
        val body = ArrayList<Byte>()
        body += u16(legacyVersion)
        body += random
        body += sessionId.size.toByte()
        body += sessionId
        body += u16(cipherSuite)
        body += 0x00.toByte() // null compression
        if (extensions != null) {
            body += u16(extensions.size)
            body += extensions
        }
        val handshake = listOf(handshakeType.toByte()) + u24(body.size) + body + trailing
        val record = listOf(recordType.toByte(), 0x03, 0x03) + u16(handshake.size) + handshake
        return record.toByteArray()
    }

    private fun parse(b: ByteArray) = TlsServerHelloPeek.parse(b, 0, b.size)

    @Test
    fun `a TLS 1_2 hello without extensions`() {
        assertEquals(TlsServerInfo(TlsVersion.TLS1_2, 0xC02F), parse(serverHello()))
    }

    @Test
    fun `a TLS 1_2 hello with unrelated extensions`() {
        val exts = ext(0xFF01, listOf(0)) + ext(0x000B, listOf(1, 0)) + ext(0x0017, emptyList())
        assertEquals(TlsServerInfo(TlsVersion.TLS1_2, 0xC030), parse(serverHello(cipherSuite = 0xC030, extensions = exts)))
    }

    @Test
    fun `TLS 1_3 is read from supported_versions, not the legacy field`() {
        val exts = ext(51, List(36) { 7 }) + supportedVersions(0x0304)
        val info = parse(serverHello(legacyVersion = 0x0303, cipherSuite = 0x1301, extensions = exts))
        assertEquals(TlsServerInfo(TlsVersion.TLS1_3, 0x1301), info)
        assertFalse(info!!.version.deprecated)
    }

    @Test
    fun `a TLS 1_0 server is reported as deprecated`() {
        val info = parse(serverHello(legacyVersion = 0x0301, cipherSuite = 0x002F))
        assertEquals(TlsServerInfo(TlsVersion.TLS1_0, 0x002F), info)
        assertTrue(info!!.version.deprecated)
    }

    @Test
    fun `TLS 1_1 and SSL 3 are recognised`() {
        assertEquals(TlsVersion.TLS1_1, parse(serverHello(legacyVersion = 0x0302))?.version)
        assertEquals(TlsVersion.SSL3, parse(serverHello(legacyVersion = 0x0300))?.version)
    }

    @Test
    fun `an unknown version code is UNKNOWN, not a failure`() {
        val info = parse(serverHello(extensions = supportedVersions(0x7F1C))) // a TLS 1.3 draft
        assertEquals(TlsVersion.UNKNOWN, info?.version)
        assertEquals(0xC02F, info?.cipherSuite)
    }

    @Test
    fun `a HelloRetryRequest is not the server's final answer`() {
        val hrr = serverHello(
            cipherSuite = 0x1302,
            random = hrrRandom,
            extensions = supportedVersions(0x0304) + ext(51, u16(0x001D)),
        )
        assertNull(parse(hrr))
    }

    @Test
    fun `a random that only starts like the HRR value is an ordinary hello`() {
        val nearly = hrrRandom.toMutableList().also { it[31] = 0 }
        assertEquals(TlsVersion.TLS1_3, parse(serverHello(random = nearly, extensions = supportedVersions(0x0304)))?.version)
    }

    @Test
    fun `an empty session id is handled`() {
        assertEquals(TlsServerInfo(TlsVersion.TLS1_2, 0x009C), parse(serverHello(cipherSuite = 0x009C, sessionId = emptyList())))
    }

    @Test
    fun `the hello is enough even when the rest of its record has not arrived`() {
        // TLS 1.2 commonly packs the certificate chain into the same record;
        // only the first segment of it is in hand.
        val full = serverHello(trailing = List(4000) { 0x0B })
        val firstSegment = full.copyOf(full.size - 3000)
        assertEquals(TlsVersion.TLS1_2, parse(firstSegment)?.version)
    }

    @Test
    fun `every truncation of a hello is null, never an exception`() {
        val full = serverHello(extensions = supportedVersions(0x0304))
        assertEquals(TlsVersion.TLS1_3, parse(full)?.version)
        for (n in 0 until full.size) {
            assertNull("first $n bytes", TlsServerHelloPeek.parse(full, 0, n))
            assertNull("first $n bytes in a short array", parse(full.copyOf(n)))
        }
    }

    @Test
    fun `an alert record is not a hello`() {
        val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28)
        assertNull(parse(alert))
        assertNull(parse(serverHello(recordType = 0x15)))
        assertNull(parse(serverHello(recordType = 0x17)))
    }

    @Test
    fun `a handshake that is not a ServerHello is null`() {
        assertNull(parse(serverHello(handshakeType = 0x01)))
        assertNull(parse(serverHello(handshakeType = 0x0B)))
    }

    @Test
    fun `plaintext that is not TLS is null`() {
        assertNull(parse("HTTP/1.1 200 OK\r\n\r\n".toByteArray()))
        assertNull(parse(ByteArray(0)))
    }

    @Test
    fun `a malformed supported_versions extension is rejected`() {
        assertNull(parse(serverHello(extensions = ext(43, u16(0x0304) + u16(0x0303)))))
        assertNull(parse(serverHello(extensions = ext(43, listOf(0x03)))))
    }

    @Test
    fun `an extension running past the hello is rejected`() {
        val lying = u16(43) + u16(200) + u16(0x0304)
        assertNull(parse(serverHello(extensions = lying)))
    }

    @Test
    fun `a hello claiming to be longer than its record is rejected`() {
        val b = serverHello()
        b[4] = (b[4] - 10).toByte() // shrink the record length under the hello
        assertNull(parse(b))
    }

    @Test
    fun `the hello is read at an offset and bounded by length`() {
        val hello = serverHello(extensions = supportedVersions(0x0304))
        val buf = ByteArray(7) { 0x7F } + hello + ByteArray(5) { 0x16 }
        assertEquals(TlsVersion.TLS1_3, TlsServerHelloPeek.parse(buf, 7, hello.size)?.version)
        assertNull(TlsServerHelloPeek.parse(buf, 7, hello.size - 1))
        assertNull(TlsServerHelloPeek.parse(buf, buf.size, 10))
        assertNull(TlsServerHelloPeek.parse(buf, -1, 10))
        // A length larger than the array is clamped, not trusted.
        assertEquals(TlsVersion.TLS1_3, TlsServerHelloPeek.parse(hello, 0, hello.size + 100)?.version)
    }

    @Test
    fun `random garbage never throws`() {
        val rnd = java.util.Random(42)
        repeat(2000) {
            val b = ByteArray(rnd.nextInt(120))
            rnd.nextBytes(b)
            if (b.isNotEmpty() && rnd.nextBoolean()) b[0] = 0x16
            if (b.size > 5 && rnd.nextBoolean()) b[5] = 0x02
            TlsServerHelloPeek.parse(b, 0, b.size)
        }
    }

    @Test
    fun `version metadata`() {
        assertEquals(TlsVersion.TLS1_3, TlsVersion.of(0x0304))
        assertEquals(TlsVersion.UNKNOWN, TlsVersion.of(0x0305))
        assertEquals("TLS 1.2", TlsVersion.TLS1_2.label)
        assertEquals(
            listOf(TlsVersion.SSL3, TlsVersion.TLS1_0, TlsVersion.TLS1_1),
            TlsVersion.entries.filter { it.deprecated },
        )
    }
}
