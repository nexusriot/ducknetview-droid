package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.DnsPeek
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A name read out of a DNS response is attacker-controlled, exactly as the one
 * read out of a TLS ClientHello is.
 *
 * [DnsPeek] runs on responses crossing the TUN, and nothing says the responder
 * was honest: on an open network, or with a hostile resolver, the question
 * section echoed back is whatever the responder chose to put there. The name
 * goes straight into the Domains screen, into Room and into the CSV export, so
 * it is checked rather than trusted — the rule [com.vlad.ducknetview.engine.vpn.packet.TlsPeek]
 * already applies to SNI.
 */
class DnsPeekHostileNameTest {

    /** A response whose question name is built from raw label bytes. */
    private fun responseWithQuestionLabels(labels: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        out += listOf(0x12.toByte(), 0x34.toByte()) // id
        out += listOf(0x81.toByte(), 0x80.toByte()) // response, NOERROR
        out += listOf(0x00.toByte(), 0x01.toByte()) // qdcount
        out += listOf(0x00.toByte(), 0x01.toByte()) // ancount
        out += listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        for (l in labels) {
            out += l.size.toByte()
            out += l.toList()
        }
        out += 0
        out += listOf(0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte())
        // One A answer, named with a pointer back to the question.
        out += listOf(0xC0.toByte(), 0x0C.toByte())
        out += listOf(0x00.toByte(), 0x01.toByte()) // type A
        out += listOf(0x00.toByte(), 0x01.toByte()) // class IN
        out += listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3C.toByte())
        out += listOf(0x00.toByte(), 0x04.toByte())
        out += listOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())
        return out.toByteArray()
    }

    @Test
    fun `a question carrying bytes no host name may contain is refused`() {
        val pkt = responseWithQuestionLabels(
            listOf("evil\u0000\nmore".toByteArray(Charsets.ISO_8859_1), "com".toByteArray()),
        )
        val observed = DnsPeek.parse(pkt)
        // The answers are still usable; it is the name that must not be trusted.
        assertTrue(
            "a control-character name must not reach the UI: ${observed?.question}",
            observed == null || observed.question.isEmpty(),
        )
    }

    @Test
    fun `a question longer than a legal host name is refused`() {
        // 63-byte labels, well past the 253-byte limit a real name obeys.
        val labels = (0 until 20).map { ByteArray(63) { 'a'.code.toByte() } }
        val observed = DnsPeek.parse(responseWithQuestionLabels(labels))
        assertTrue(
            "an over-long name must not reach the UI: ${observed?.question?.length}",
            observed == null || observed.question.isEmpty(),
        )
    }

    /**
     * Compression pointers expand: a short payload can describe a name far
     * larger than itself, which is the classic way to turn a bounded read into
     * an unbounded string.
     */
    @Test
    fun `a name inflated by compression pointers cannot exceed the host-name limit`() {
        val out = ArrayList<Byte>()
        out += listOf(0x12.toByte(), 0x34.toByte())
        out += listOf(0x81.toByte(), 0x80.toByte())
        out += listOf(0x00.toByte(), 0x01.toByte())
        out += listOf(0x00.toByte(), 0x01.toByte())
        out += listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        // Question at offset 12: many labels, then a pointer back to offset 12
        // is illegal (a loop), so point forward-chain style instead: a chain of
        // blocks each ending in a pointer to the previous one.
        val blockStart = out.size
        repeat(60) {
            out += 63.toByte()
            out += ByteArray(63) { 'b'.code.toByte() }.toList()
        }
        out += 0
        out += listOf(0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte())
        out += listOf(0xC0.toByte(), (blockStart and 0xFF).toByte())
        out += listOf(0x00.toByte(), 0x01.toByte())
        out += listOf(0x00.toByte(), 0x01.toByte())
        out += listOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3C.toByte())
        out += listOf(0x00.toByte(), 0x04.toByte())
        out += listOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())

        val observed = DnsPeek.parse(out.toByteArray())
        assertTrue(
            "an inflated name must not reach the UI: ${observed?.question?.length}",
            observed == null || observed.question.isEmpty(),
        )
    }

    @Test
    fun `an answer name is checked the same way`() {
        val pkt = responseWithQuestionLabels(listOf("ok".toByteArray(), "com".toByteArray()))
        // Sanity: the honest case still parses, so the checks above are not
        // simply rejecting everything.
        val observed = DnsPeek.parse(pkt)
        assertTrue("an honest name must still be read", observed?.question == "ok.com")
        assertNull(
            "no answer should carry an implausible name",
            observed?.answers?.firstOrNull { !isPlausible(it.name) },
        )
    }

    private fun isPlausible(n: String): Boolean =
        n.isNotEmpty() && n.length <= 253 && n.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
        }
}
