package com.vlad.ducknetview.data

import com.vlad.ducknetview.data.db.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

    @Test
    fun `empty list encodes to empty string`() {
        assertEquals("", Codec.encodeList(emptyList()))
        assertEquals(emptyList<String>(), Codec.decodeList(""))
    }

    @Test
    fun `a single empty element is not the same as an empty list`() {
        val encoded = Codec.encodeList(listOf(""))
        assertTrue(encoded.isNotEmpty())
        assertEquals(listOf(""), Codec.decodeList(encoded))
    }

    @Test
    fun `plain list round-trips`() {
        val v = listOf("1.1.1.1", "8.8.8.8", "example.org")
        assertEquals(v, Codec.decodeList(Codec.encodeList(v)))
    }

    @Test
    fun `elements containing the entry separator round-trip`() {
        val v = listOf("a;b", ";", ";;;", "trailing;")
        assertEquals(v, Codec.decodeList(Codec.encodeList(v)))
    }

    @Test
    fun `elements containing the key-value separator round-trip`() {
        val v = listOf("a=b", "=", "k=v=w")
        assertEquals(v, Codec.decodeList(Codec.encodeList(v)))
    }

    @Test
    fun `elements containing backslashes round-trip`() {
        val v = listOf("""a\b""", """\""", """\;\=""", """c:\path\to""")
        assertEquals(v, Codec.decodeList(Codec.encodeList(v)))
    }

    @Test
    fun `unicode elements round-trip`() {
        val v = listOf("日本語", "приве;т", "emoji 🦆=🦆", "naïve\u0000nul")
        assertEquals(v, Codec.decodeList(Codec.encodeList(v)))
    }

    @Test
    fun `a truncated encoding still yields its readable elements`() {
        assertEquals(listOf("a", "b"), Codec.decodeList("a;b"))
    }

    @Test
    fun `a dangling escape is dropped rather than throwing`() {
        assertEquals(listOf("a"), Codec.decodeList("""a\"""))
    }

    @Test
    fun `empty map round-trips`() {
        assertEquals("", Codec.encodeMap(emptyMap()))
        assertEquals(emptyMap<String, Long>(), Codec.decodeMap(""))
    }

    @Test
    fun `plain map round-trips`() {
        val m = mapOf("com.example.app" to 1234L, "chrome" to 0L, "neg" to -5L)
        assertEquals(m, Codec.decodeMap(Codec.encodeMap(m)))
    }

    @Test
    fun `map keys containing both separators round-trip`() {
        val m = mapOf("a;b" to 1L, "c=d" to 2L, """e\f""" to 3L, "日本;語=x" to 4L)
        assertEquals(m, Codec.decodeMap(Codec.encodeMap(m)))
    }

    @Test
    fun `map decoding skips unreadable entries instead of throwing`() {
        val decoded = Codec.decodeMap("good=1;noequals;bad=notanumber;also=2;")
        assertEquals(mapOf("good" to 1L, "also" to 2L), decoded)
    }

    @Test
    fun `int set round-trips`() {
        val s = setOf(0, 1000, 10123, -1)
        assertEquals(s, Codec.decodeIntSet(Codec.encodeIntSet(s)))
    }

    @Test
    fun `empty int set round-trips`() {
        assertEquals(emptySet<Int>(), Codec.decodeIntSet(Codec.encodeIntSet(emptySet())))
    }

    @Test
    fun `int set decoding reports failure on a non-numeric token`() {
        assertNull(Codec.decodeIntSet("1;oops;3;"))
        assertNull(Codec.decodeIntSet("99999999999999;"))
    }
}
