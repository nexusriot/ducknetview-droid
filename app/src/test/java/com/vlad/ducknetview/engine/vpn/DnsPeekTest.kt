package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.engine.vpn.packet.DnsPeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPeekTest {

    private fun query(name: String, id: Int = 0x1234): ByteArray {
        val out = ArrayList<Byte>()
        out += byteArrayOf((id shr 8).toByte(), id.toByte()).toList()
        out += byteArrayOf(0x01, 0x00).toList()
        out += byteArrayOf(0x00, 0x01).toList()
        out += byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00).toList()
        for (label in name.split('.')) {
            out += label.length.toByte()
            out += label.toByteArray().toList()
        }
        out += 0
        out += byteArrayOf(0x00, 0x01, 0x00, 0x01).toList()
        return out.toByteArray()
    }

    private fun responseWithA(name: String, ip: ByteArray): ByteArray {
        val q = query(name).toMutableList()
        q[2] = 0x81.toByte()
        q[3] = 0x80.toByte()
        q[7] = 0x01
        q += byteArrayOf(0xC0.toByte(), 0x0C).toList()
        q += byteArrayOf(0x00, 0x01, 0x00, 0x01).toList()
        q += byteArrayOf(0x00, 0x00, 0x00, 0x3C).toList()
        q += byteArrayOf(0x00, 0x04).toList()
        q += ip.toList()
        return q.toByteArray()
    }

    @Test
    fun readsAnARecordThroughACompressionPointer() {
        val pkt = responseWithA("example.com", byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
        val answers = DnsPeek.parseAnswers(pkt)
        assertEquals(1, answers.size)
        assertEquals("example.com", answers[0].name)
        assertEquals("93.184.216.34", answers[0].ip)
    }

    @Test
    fun ignoresQuestionsSoWeOnlyLearnFromAnswers() {
        assertTrue(DnsPeek.parseAnswers(query("example.com")).isEmpty())
    }

    @Test
    fun ignoresErrorResponses() {
        val pkt = responseWithA("bad.example", byteArrayOf(1, 2, 3, 4)).copyOf()
        pkt[3] = 0x83.toByte() // NXDOMAIN
        assertTrue(DnsPeek.parseAnswers(pkt).isEmpty())
    }

    @Test
    fun malformedInputNeverThrows() {
        DnsPeek.parseAnswers(ByteArray(0))
        DnsPeek.parseAnswers(ByteArray(11))
        DnsPeek.parseAnswers(ByteArray(64) { 0xFF.toByte() })
        val truncated = responseWithA("example.com", byteArrayOf(1, 2, 3, 4))
        for (n in 12 until truncated.size) {
            DnsPeek.parseAnswers(truncated.copyOf(n))
        }
    }

    @Test
    fun compressionPointerLoopIsBounded() {
        val pkt = ByteArray(32)
        pkt[2] = 0x81.toByte(); pkt[3] = 0x80.toByte()
        pkt[5] = 0x00 // no questions
        pkt[7] = 0x01 // one answer
        pkt[12] = 0xC0.toByte(); pkt[13] = 0x0C // points at itself
        DnsPeek.parseAnswers(pkt)
    }
}
