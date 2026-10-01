package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.TlsPeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsPeekTest {

    private fun u16(v: Int) = listOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** A ClientHello with the extensions given, wrapped in a handshake record. */
    private fun clientHello(
        extensions: List<Byte>,
        recordLengthOverride: Int? = null,
        handshakeType: Int = 0x01,
    ): ByteArray {
        val body = ArrayList<Byte>()
        body += u16(0x0303)
        body += ByteArray(32) { it.toByte() }.toList()
        body += 0x00 // empty session id
        body += u16(2) + listOf(0x13.toByte(), 0x01.toByte()) // one cipher suite
        body += listOf(0x01.toByte(), 0x00.toByte()) // one compression method
        body += u16(extensions.size)
        body += extensions

        val handshake = ArrayList<Byte>()
        handshake += handshakeType.toByte()
        handshake += listOf(
            ((body.size ushr 16) and 0xFF).toByte(),
            ((body.size ushr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        handshake += body

        val record = ArrayList<Byte>()
        record += 0x16
        record += u16(0x0301)
        record += u16(recordLengthOverride ?: handshake.size)
        record += handshake
        return record.toByteArray()
    }

    private fun sniExtension(name: String): List<Byte> {
        val bytes = name.toByteArray(Charsets.US_ASCII).toList()
        val entry = listOf(0x00.toByte()) + u16(bytes.size) + bytes
        val list = u16(entry.size) + entry
        return u16(0x0000) + u16(list.size) + list
    }

    private fun otherExtension(type: Int, size: Int): List<Byte> =
        u16(type) + u16(size) + List(size) { 0x00.toByte() }

    @Test
    fun `reads the server name out of a ClientHello`() {
        val hello = clientHello(sniExtension("example.com"))
        assertEquals("example.com", TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `finds the name after other extensions`() {
        // Real clients put supported_versions, ALPN and key_share around it, so
        // the walk has to step over extensions rather than expect a position.
        val hello = clientHello(
            otherExtension(0x002B, 5) + sniExtension("cdn.example.org") + otherExtension(0x0010, 9)
        )
        assertEquals("cdn.example.org", TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `a hello with no server_name yields nothing`() {
        val hello = clientHello(otherExtension(0x002B, 4))
        assertNull(TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `a hello with no extensions at all yields nothing`() {
        val hello = clientHello(emptyList())
        assertNull(TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `a hello split across segments is skipped rather than guessed at`() {
        // The record header claims more than this segment carries. Reassembling
        // would mean buffering attacker-controlled bytes for a cosmetic label.
        val hello = clientHello(sniExtension("example.com"), recordLengthOverride = 9000)
        assertNull(TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `only a handshake record is examined`() {
        val hello = clientHello(sniExtension("example.com")).copyOf()
        hello[0] = 0x17 // application data
        assertFalse(TlsPeek.looksLikeHandshake(hello, 0, hello.size))
        assertNull(TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `a handshake that is not a ClientHello yields nothing`() {
        val hello = clientHello(sniExtension("example.com"), handshakeType = 0x02)
        assertTrue(TlsPeek.looksLikeHandshake(hello, 0, hello.size))
        assertNull(TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `plain HTTP is rejected by the cheap check alone`() {
        val get = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray()
        assertFalse(TlsPeek.looksLikeHandshake(get, 0, get.size))
        assertNull(TlsPeek.serverName(get, 0, get.size))
    }

    @Test
    fun `a name with bytes no host name may contain is refused`() {
        // The bytes come off the wire and go into the UI and into stored
        // history, so they are checked rather than trusted.
        val hello = clientHello(sniExtension("ex\u0007ample .com"))
        assertNull(TlsPeek.serverName(hello, 0, hello.size))
        assertFalse(TlsPeek.isPlausibleHost(""))
        assertFalse(TlsPeek.isPlausibleHost("a".repeat(254)))
        assertTrue(TlsPeek.isPlausibleHost("sub-domain_1.example.com"))
    }

    @Test
    fun `every truncation of a valid hello returns without throwing`() {
        val hello = clientHello(sniExtension("example.com"))
        for (n in 0..hello.size) {
            // A short read must be a null, never an exception on the packet path.
            assertNull(runCatching { TlsPeek.serverName(hello, 0, n) }.exceptionOrNull())
        }
        // And only the complete one actually answers.
        assertEquals("example.com", TlsPeek.serverName(hello, 0, hello.size))
    }

    @Test
    fun `reading at an offset uses the offset, not the array start`() {
        val hello = clientHello(sniExtension("offset.example"))
        val padded = ByteArray(7) { 0xEE.toByte() } + hello
        assertEquals("offset.example", TlsPeek.serverName(padded, 7, hello.size))
        assertNull(TlsPeek.serverName(padded, 0, padded.size))
    }
}
