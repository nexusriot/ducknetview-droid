package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.DnsPeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question, not just the answers.
 *
 * A CNAME chain answers under the provider's name — ask for `www.example.com`
 * and the A record comes back under `example.cdn.net`. The connection table
 * wants the latter to label an address; the Domains screen wants the former,
 * because it is what the app asked for and what a person recognises.
 */
class DnsPeekQuestionTest {

    private fun name(label: String): List<Byte> {
        val out = ArrayList<Byte>()
        for (part in label.split('.')) {
            out += part.length.toByte()
            out += part.toByteArray(Charsets.US_ASCII).toList()
        }
        out += 0
        return out
    }

    private fun response(question: String, answers: List<Pair<String, ByteArray>>): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(0x12.toByte(), 0x34.toByte())
        out += listOf(0x81.toByte(), 0x80.toByte())
        out += listOf(0x00.toByte(), 0x01.toByte())
        out += listOf(((answers.size shr 8) and 0xFF).toByte(), (answers.size and 0xFF).toByte())
        out += listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        out += name(question)
        out += listOf(0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte())
        for ((answerName, ip) in answers) {
            out += name(answerName)
            val type = if (ip.size == 4) 1 else 28
            out += listOf(0x00.toByte(), type.toByte())
            out += listOf(0x00.toByte(), 0x01.toByte())
            out += listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3C.toByte())
            out += listOf(0x00.toByte(), ip.size.toByte())
            out += ip.toList()
        }
        return out.toByteArray()
    }

    @Test
    fun `the question survives a CNAME chain that answers under another name`() {
        val pkt = response(
            "www.example.com",
            listOf("example.cdn.net" to byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)),
        )
        val observed = DnsPeek.parse(pkt)!!

        assertEquals("www.example.com", observed.question)
        // The answer keeps its own name, because that is what labels the address.
        assertEquals("example.cdn.net", observed.answers.single().name)
        assertEquals(listOf("93.184.216.34"), observed.addresses)
    }

    @Test
    fun `several addresses for one name are all collected`() {
        val pkt = response(
            "cdn.example.org",
            listOf(
                "cdn.example.org" to byteArrayOf(1, 2, 3, 4),
                "cdn.example.org" to byteArrayOf(5, 6, 7, 8),
            ),
        )
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), DnsPeek.parse(pkt)!!.addresses)
    }

    @Test
    fun `an AAAA answer is read as well`() {
        val v6 = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 1 }
        val pkt = response("v6.example.com", listOf("v6.example.com" to v6))
        assertEquals(listOf("2001::1"), DnsPeek.parse(pkt)!!.addresses)
    }

    @Test
    fun `a query is not an observation`() {
        val pkt = response("example.com", emptyList()).copyOf()
        pkt[2] = 0x01 // clear the response bit
        pkt[3] = 0x00
        assertNull(DnsPeek.parse(pkt))
    }

    @Test
    fun `an answerless response records nothing`() {
        // NXDOMAIN and empty answers say the device asked, but there is no
        // address to attribute and no name that resolved.
        assertNull(DnsPeek.parse(response("nope.example.com", emptyList())))
    }

    @Test
    fun `parseAnswers still behaves as it did`() {
        val pkt = response("example.com", listOf("example.com" to byteArrayOf(10, 0, 0, 1)))
        val answers = DnsPeek.parseAnswers(pkt)
        assertEquals(1, answers.size)
        assertEquals("10.0.0.1", answers.single().ip)
        assertTrue(DnsPeek.parseAnswers(ByteArray(4)).isEmpty())
    }

    @Test
    fun `every truncation returns rather than throwing`() {
        val pkt = response("example.com", listOf("example.com" to byteArrayOf(10, 0, 0, 1)))
        for (n in 0..pkt.size) {
            assertNull(runCatching { DnsPeek.parse(pkt.copyOf(n)) }.exceptionOrNull())
        }
    }
}
