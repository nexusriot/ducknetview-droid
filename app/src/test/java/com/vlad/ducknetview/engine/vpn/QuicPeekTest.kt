package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.QuicPeek
import com.vlad.ducknetview.testing.QuicPackets
import com.vlad.ducknetview.testing.QuicPackets.clientHello
import com.vlad.ducknetview.testing.QuicPackets.hex
import com.vlad.ducknetview.testing.QuicPackets.initial
import com.vlad.ducknetview.testing.QuicPackets.initialCarrying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QUIC Initial parsing, in two layers.
 *
 * The key schedule is pinned against the worked example in **RFC 9001
 * Appendix A** — the salt, the HKDF-Extract, and the three HKDF-Expand-Label
 * outputs for a known connection id. That is a normative vector, so it fixes
 * the one part of this that cannot be checked by round-tripping: an
 * implementation that derived keys its own consistent way would encrypt and
 * decrypt its own packets perfectly and read nothing a browser ever sent.
 *
 * The packet layer is then round-tripped: these tests build a real Initial
 * with the same primitives in the other direction and assert the peek reads
 * the name back out. With the schedule pinned above, that covers the framing
 * — varints, header protection, the AAD, the nonce and the CRYPTO frames —
 * which is where the bugs actually live.
 */
class QuicPeekTest {

    // ---------------------------------------------------------------- vectors

    /** RFC 9001 A.1: the client's Destination Connection ID in the example. */
    private val exampleCid = hex("8394c8f03e515708")

    @Test
    fun `the initial secret matches RFC 9001 appendix A`() {
        assertEquals(
            "7db5df06e7a69e432496adedb00851923595221596ae2ae9fb8115c1e9ed0a44",
            hex(QuicPeek.hkdfExtract(QuicPeek.SALT_V1, exampleCid)),
        )
    }

    @Test
    fun `the client initial secret matches RFC 9001 appendix A`() {
        val initial = QuicPeek.hkdfExtract(QuicPeek.SALT_V1, exampleCid)
        assertEquals(
            "c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea",
            hex(QuicPeek.hkdfExpandLabel(initial, "client in", 32)),
        )
    }

    @Test
    fun `the client initial keys match RFC 9001 appendix A`() {
        val keys = QuicPeek.initialKeys(QuicPeek.VERSION_1, exampleCid)!!
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", hex(keys.key))
        assertEquals("fa044b2f42a3fd3b46fb255c", hex(keys.iv))
        assertEquals("9f50449e04a0e810283a1e9933adedd2", hex(keys.hp))
    }

    // ------------------------------------------------------------------ gate

    @Test
    fun `the gate accepts a v1 client initial`() {
        val p = initial(sni = "example.com")
        assertTrue(QuicPeek.looksLikeInitial(p, 0, p.size))
    }

    @Test
    fun `the gate rejects what is not a long header`() {
        val p = initial(sni = "example.com")
        p[0] = (p[0].toInt() and 0x7F).toByte()
        assertFalse(QuicPeek.looksLikeInitial(p, 0, p.size))
    }

    @Test
    fun `the gate rejects a cleared fixed bit`() {
        val p = initial(sni = "example.com")
        p[0] = (p[0].toInt() and 0xBF).toByte()
        assertFalse(QuicPeek.looksLikeInitial(p, 0, p.size))
    }

    @Test
    fun `the gate rejects a handshake packet`() {
        // Long header, right version, wrong type: type 0b10 is Handshake, and
        // the keys for it are not derivable from anything on the wire.
        val p = initial(sni = "example.com")
        p[0] = ((p[0].toInt() and 0xCF) or 0x20).toByte()
        assertFalse(QuicPeek.looksLikeInitial(p, 0, p.size))
    }

    @Test
    fun `the gate rejects an unknown version`() {
        // A real v1 packet with its version field rewritten. There are no
        // published salts for a version this build does not know, so the keys
        // cannot be derived and the only honest answer is no name — not a
        // guess made with v1 keys.
        val p = initial(sni = "example.com")
        p[1] = 0x51; p[2] = 0x30; p[3] = 0x35; p[4] = 0x30
        assertFalse(QuicPeek.looksLikeInitial(p, 0, p.size))
        assertNull(QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `the gate rejects a datagram too short to classify`() {
        assertFalse(QuicPeek.looksLikeInitial(ByteArray(3), 0, 3))
        assertFalse(QuicPeek.looksLikeInitial(ByteArray(0), 0, 0))
    }

    @Test
    fun `an ordinary dns datagram is not mistaken for quic`() {
        val dns = hex("1234010000010000000000000377777706676f6f676c6503636f6d0000010001")
        assertFalse(QuicPeek.looksLikeInitial(dns, 0, dns.size))
        assertNull(QuicPeek.serverName(dns, 0, dns.size))
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun `the server name is read out of a v1 initial`() {
        val p = initial(sni = "www.example.com")
        assertEquals("www.example.com", QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `the server name is read out of a v2 initial`() {
        val p = initial(sni = "v2.example.com", version = QuicPeek.VERSION_2)
        assertEquals("v2.example.com", QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `a packet at an offset inside a larger buffer is read`() {
        // The relay hands the peek the TUN read buffer with the UDP payload
        // somewhere inside it, never a tight array.
        val p = initial(sni = "offset.example.com")
        val framed = ByteArray(64) { 0x5A } + p + ByteArray(32) { 0x5A }
        assertEquals("offset.example.com", QuicPeek.serverName(framed, 64, p.size))
    }

    @Test
    fun `a hello split across crypto frames in one packet is reassembled`() {
        val p = initial(sni = "split.example.com", splitCryptoAt = 40)
        assertEquals("split.example.com", QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `padding before the crypto frame is skipped`() {
        val p = initial(sni = "padded.example.com", leadingPadding = 24)
        assertEquals("padded.example.com", QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `a single datagram carrying only part of a hello yields nothing yet`() {
        val p = initial(sni = "late.example.com", cryptoOffset = 200)
        assertNull(QuicPeek.serverName(p, 0, p.size))
    }

    // ------------------------------------------------- cross-packet assembly

    /**
     * The case the M10 found. A modern ClientHello carries a post-quantum key
     * share and does not fit one Initial, so Chrome's hello arrives split —
     * and a single-datagram peek returned a name for none of the dozens of
     * UDP/443 flows on the device while every TCP/TLS flow was labelled fine.
     * TlsPeek's "do not reassemble" reasoning was carried over here and did
     * not transfer: for QUIC a split hello is the normal case, so declining
     * to reassemble was declining the feature.
     */
    @Test
    fun `a hello split across two datagrams is reassembled`() {
        val hello = clientHello("pq.example.com")
        val cut = hello.size / 2
        val a = QuicPeek.Assembler()
        val first = initialCarrying(hello.copyOfRange(0, cut), 0L)
        assertNull("the name cannot be known yet", a.offer(first, 0, first.size))
        assertFalse("giving up after one packet is the bug", a.done)
        val second = initialCarrying(hello.copyOfRange(cut, hello.size), cut.toLong())
        assertEquals("pq.example.com", a.offer(second, 0, second.size))
        assertTrue(a.done)
    }

    @Test
    fun `both initials coalesced into one datagram are read`() {
        val hello = clientHello("coalesced.example.com")
        val cut = hello.size / 2
        val datagram = initialCarrying(hello.copyOfRange(0, cut), 0L, pad = false) +
            initialCarrying(hello.copyOfRange(cut, hello.size), cut.toLong(), pad = false)
        val a = QuicPeek.Assembler()
        assertEquals("coalesced.example.com", a.offer(datagram, 0, datagram.size))
    }

    @Test
    fun `the pieces may arrive out of order`() {
        val hello = clientHello("ooo.example.com")
        val cut = hello.size / 2
        val a = QuicPeek.Assembler()
        val second = initialCarrying(hello.copyOfRange(cut, hello.size), cut.toLong())
        assertNull(a.offer(second, 0, second.size))
        val first = initialCarrying(hello.copyOfRange(0, cut), 0L)
        assertEquals("ooo.example.com", a.offer(first, 0, first.size))
    }

    @Test
    fun `a flow that stops sending initials is abandoned`() {
        val hello = clientHello("gone.example.com")
        val a = QuicPeek.Assembler()
        val first = initialCarrying(hello.copyOfRange(0, hello.size / 2), 0L)
        a.offer(first, 0, first.size)
        // A short-header packet: the handshake has moved on, so the hello is
        // either already in hand or never coming. The buffer must not be held
        // for the life of the flow.
        val shortHeader = ByteArray(64) { 0x41 }
        assertNull(a.offer(shortHeader, 0, shortHeader.size))
        assertTrue(a.done)
    }

    @Test
    fun `a client that keeps sending initials is cut off`() {
        // Each packet carries one byte at a high offset, so the run from zero
        // never completes and the only thing that can stop this is the cap.
        val a = QuicPeek.Assembler()
        repeat(QuicPeek.MAX_INITIALS + 2) { i ->
            val p = initialCarrying(byteArrayOf(0x16), 1000L + i)
            a.offer(p, 0, p.size)
        }
        assertTrue("an endless stream of Initials was never cut off", a.done)
    }

    @Test
    fun `an absurd declared hello length is refused`() {
        // The three-byte handshake length is attacker-supplied; a hello
        // claiming megabytes must be refused on the claim, not on the
        // allocation that would follow it.
        val fake = byteArrayOf(0x01, 0x7F, 0xFF.toByte(), 0xFF.toByte()) + ByteArray(32)
        val p = initialCarrying(fake, 0L)
        val a = QuicPeek.Assembler()
        assertNull(a.offer(p, 0, p.size))
    }

    @Test
    fun `a hello with no server name extension yields nothing`() {
        val p = initial(sni = null)
        assertNull(QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `a corrupted packet fails authentication rather than guessing`() {
        val p = initial(sni = "example.com")
        p[p.size - 1] = (p[p.size - 1].toInt() xor 0xFF).toByte()
        assertNull(QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `a truncated packet is refused`() {
        val p = initial(sni = "example.com")
        for (cut in listOf(8, 20, 64, p.size / 2, p.size - 1)) {
            assertNull("a $cut-byte prefix was parsed", QuicPeek.serverName(p, 0, cut))
        }
    }

    @Test
    fun `a nonsense name is not accepted`() {
        // The bytes come off the wire from whatever the device was talking to
        // and go straight into stored history, so they are checked.
        val p = initial(sni = "not a hostname at all")
        assertNull(QuicPeek.serverName(p, 0, p.size))
    }

    @Test
    fun `random bytes with a plausible header never produce a name`() {
        val rnd = java.util.Random(20261016)
        repeat(300) {
            val junk = ByteArray(1 + rnd.nextInt(1400))
            rnd.nextBytes(junk)
            if (junk.isNotEmpty()) junk[0] = 0xC3.toByte()
            if (junk.size > 4) {
                junk[1] = 0x00; junk[2] = 0x00; junk[3] = 0x00; junk[4] = 0x01
            }
            assertNull(QuicPeek.serverName(junk, 0, junk.size))
        }
    }

    @Test
    fun `an absurd crypto length cannot make it allocate`() {
        // A varint can claim 2^62 bytes. The frame walk has to refuse that on
        // the claim rather than on the allocation.
        val p = initial(sni = "example.com", cryptoLengthOverride = 0x3FFFFFFFFFFFFFFFL)
        assertNull(QuicPeek.serverName(p, 0, p.size))
    }
}
