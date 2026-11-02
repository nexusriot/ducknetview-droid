package com.vlad.ducknetview.domain.asn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AsnTableTest {

    private val v4Tsv = """
        1.0.0.0	1.0.0.255	13335	US	CLOUDFLARENET
        1.0.1.0	1.0.3.255	0	None	Not routed
        8.8.4.0	8.8.4.255	15169	US	GOOGLE
        8.8.8.0	8.8.8.255	15169	US	GOOGLE
        255.255.255.0	255.255.255.255	64512	ZZ	TOP-OF-RANGE
    """.trimIndent()

    private val v6Tsv = """
        2001:4860::	2001:4860:ffff:ffff:ffff:ffff:ffff:ffff	15169	US	GOOGLE
        2606:4700::	2606:4700:ffff:ffff:ffff:ffff:ffff:ffff	13335	US	CLOUDFLARENET
        ffff::	ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff	64513	ZZ	TOP-V6
    """.trimIndent()

    private fun table(text: String) = AsnTable.parse(ByteArrayInputStream(text.toByteArray()))

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    @Test
    fun `ipv4 addresses resolve to their range`() {
        val t = table(v4Tsv)
        assertEquals(AsnInfo(13335, "US", "CLOUDFLARENET"), t.lookup("1.0.0.1"))
        assertEquals(15169L, t.lookup("8.8.8.8")?.number)
        assertEquals(15169L, t.lookup("8.8.4.4")?.number)
    }

    @Test
    fun `range boundaries are inclusive`() {
        val t = table(v4Tsv)
        assertEquals(13335L, t.lookup("1.0.0.0")?.number)
        assertEquals(13335L, t.lookup("1.0.0.255")?.number)
        assertEquals(15169L, t.lookup("8.8.8.0")?.number)
        assertEquals(15169L, t.lookup("8.8.8.255")?.number)
        assertNull(t.lookup("8.8.9.0"))
        assertNull(t.lookup("8.8.7.255"))
    }

    @Test
    fun `addresses above 2^31 are compared unsigned`() {
        val t = table(v4Tsv)
        assertEquals(64512L, t.lookup("255.255.255.255")?.number)
        assertEquals(64512L, t.lookup("255.255.255.0")?.number)
        assertNull(t.lookup("200.1.1.1"))
    }

    @Test
    fun `lookups that miss return null`() {
        val t = table(v4Tsv)
        assertNull(t.lookup("0.0.0.1")) // before the first range
        assertNull(t.lookup("9.9.9.9")) // in a gap
        assertNull(t.lookup("not-an-ip"))
        assertNull(t.lookup(""))
        assertNull(t.lookup("2606:4700::1")) // no v6 rows loaded
    }

    @Test
    fun `not-routed rows are dropped without counting as garbage`() {
        val t = table(v4Tsv)
        assertNull(t.lookup("1.0.2.0"))
        assertEquals(4, t.size)
        assertEquals(0, t.skipped)
    }

    @Test
    fun `ipv6 ranges are searched as 128-bit values`() {
        val t = table(v6Tsv)
        assertEquals(15169L, t.lookup("2001:4860:4860::8888")?.number)
        assertEquals("AS13335 CLOUDFLARENET", t.lookup("2606:4700:4700::1111")?.label)
        assertEquals(13335L, t.lookup("[2606:4700::]")?.number)
        assertEquals(64513L, t.lookup("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")?.number)
        assertNull(t.lookup("2001:db8::1"))
        assertNull(t.lookup("::1"))
        assertNull(t.lookup("8.8.8.8"))
    }

    @Test
    fun `a combined file serves both families`() {
        val t = table(v4Tsv + "\n" + v6Tsv)
        assertEquals(7, t.size)
        assertEquals(15169L, t.lookup("8.8.8.8")?.number)
        assertEquals(15169L, t.lookup("2001:4860::1")?.number)
    }

    @Test
    fun `an ipv4-mapped query is looked up as ipv4`() {
        val t = table(v4Tsv)
        assertEquals(15169L, t.lookup("::ffff:8.8.8.8")?.number)
        assertEquals(15169L, t.lookup(byteArrayOf(8, 8, 8, 8))?.number)
    }

    @Test
    fun `gzipped input is detected by its magic`() {
        val t = AsnTable.parse(ByteArrayInputStream(gzip(v4Tsv + "\n" + v6Tsv)))
        assertEquals(7, t.size)
        assertEquals(13335L, t.lookup("1.0.0.7")?.number)
        assertEquals(13335L, t.lookup("2606:4700::7")?.number)
    }

    @Test
    fun `garbage lines are skipped and counted`() {
        val text = listOf(
            "range_start\trange_end\tAS_number\tcountry_code\tAS_description", // a header
            "1.0.0.0\t1.0.0.255\t13335\tUS\tCLOUDFLARENET",
            "this is not a row",
            "1.2.3.4\t1.2.3.5\tAS13335\tUS\tbad number",
            "1.2.3.999\t1.2.4.0\t1\tUS\tbad address",
            "10.0.0.9\t10.0.0.1\t2\tUS\treversed",
            "10.0.0.1\t2001:db8::1\t3\tUS\tmixed families",
            "10.0.0.1\t10.0.0.2\t-5\tUS\tnegative",
            "10.0.0.1\t10.0.0.2\t4294967296\tUS\ttoo big",
            "",
            "   ",
            "8.8.8.0\t8.8.8.255\t15169\tUS\tGOOGLE",
        ).joinToString("\n")
        val t = table(text)
        assertEquals(2, t.size)
        assertEquals(8, t.skipped)
        assertEquals(15169L, t.lookup("8.8.8.8")?.number)
    }

    @Test
    fun `unsorted input is sorted before searching`() {
        val text = v4Tsv.lines().reversed().joinToString("\n") + "\n" +
            v6Tsv.lines().reversed().joinToString("\n")
        val t = table(text)
        assertEquals(13335L, t.lookup("1.0.0.1")?.number)
        assertEquals(15169L, t.lookup("8.8.4.4")?.number)
        assertEquals(64512L, t.lookup("255.255.255.1")?.number)
        assertEquals(15169L, t.lookup("2001:4860::1")?.number)
        assertEquals(13335L, t.lookup("2606:4700::1")?.number)
        assertNull(t.lookup("9.9.9.9"))
    }

    @Test
    fun `identical AS records share one instance`() {
        val t = table(v4Tsv)
        assertSame(t.lookup("8.8.8.8"), t.lookup("8.8.4.4"))
    }

    @Test
    fun `the largest 32-bit AS number survives`() {
        val t = table("5.5.5.0\t5.5.5.255\t4294967295\tZZ\tMAX")
        assertEquals(4294967295L, t.lookup("5.5.5.5")?.number)
    }

    @Test
    fun `a description with a tab or crlf ending is kept intact`() {
        val t = table("5.5.5.0\t5.5.5.255\t7\tDE\tACME\tGmbH\r\n")
        assertEquals("ACME\tGmbH", t.lookup("5.5.5.5")?.name)
        assertEquals("DE", t.lookup("5.5.5.5")?.country)
    }

    @Test
    fun `a nameless AS is labelled by number alone`() {
        assertEquals("AS64500", AsnInfo(64500, "", "").label)
        assertEquals("AS13335 CLOUDFLARENET", AsnInfo(13335, "US", "CLOUDFLARENET").label)
    }

    @Test
    fun `the empty table answers nothing`() {
        assertEquals(0, AsnTable.EMPTY.size)
        assertEquals(0, AsnTable.EMPTY.skipped)
        assertNull(AsnTable.EMPTY.lookup("8.8.8.8"))
        assertNull(AsnTable.EMPTY.lookup("2001:4860::1"))
        assertEquals(0, table("").size)
    }

    @Test
    fun `a large table still finds every range`() {
        // Enough rows to force several array growths and a deep search.
        val sb = StringBuilder()
        for (i in 0 until 5000) {
            val a = i / 256
            val b = i % 256
            sb.append("20.$a.$b.0\t20.$a.$b.127\t${i + 1}\tUS\tAS-${i % 50}\n")
        }
        val t = table(sb.toString())
        assertEquals(5000, t.size)
        for (i in listOf(0, 1, 255, 256, 2500, 4999)) {
            val ip = "20.${i / 256}.${i % 256}.64"
            assertEquals(ip, (i + 1).toLong(), t.lookup(ip)?.number)
            assertNull(t.lookup("20.${i / 256}.${i % 256}.200"))
        }
    }
}
