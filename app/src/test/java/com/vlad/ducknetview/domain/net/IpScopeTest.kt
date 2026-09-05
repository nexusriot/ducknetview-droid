package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpScopeTest {

    @Test
    fun loopbackAndUnspecified() {
        assertEquals(Scope.LOOPBACK, IpScope.of("127.0.0.1"))
        assertEquals(Scope.LOOPBACK, IpScope.of("127.255.255.254"))
        assertEquals(Scope.LOOPBACK, IpScope.of("0.0.0.0"))
        assertEquals(Scope.LOOPBACK, IpScope.of("::1"))
        assertEquals(Scope.LOOPBACK, IpScope.of("::"))
    }

    @Test
    fun rfc1918Boundaries() {
        assertEquals(Scope.PRIVATE, IpScope.of("10.0.0.0"))
        assertEquals(Scope.PRIVATE, IpScope.of("10.255.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("9.255.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("11.0.0.0"))
        assertEquals(Scope.PUBLIC, IpScope.of("172.15.255.255"))
        assertEquals(Scope.PRIVATE, IpScope.of("172.16.0.0"))
        assertEquals(Scope.PRIVATE, IpScope.of("172.31.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("172.32.0.0"))
        assertEquals(Scope.PRIVATE, IpScope.of("192.168.0.0"))
        assertEquals(Scope.PRIVATE, IpScope.of("192.168.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("192.169.0.0"))
    }

    @Test
    fun cgnatBoundaries() {
        assertEquals(Scope.PUBLIC, IpScope.of("100.63.255.255"))
        assertEquals(Scope.PRIVATE, IpScope.of("100.64.0.0"))
        assertEquals(Scope.PRIVATE, IpScope.of("100.127.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("100.128.0.0"))
    }

    @Test
    fun linkLocalV4() {
        assertEquals(Scope.PRIVATE, IpScope.of("169.254.0.1"))
        assertEquals(Scope.PRIVATE, IpScope.of("169.254.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("169.253.255.255"))
        assertEquals(Scope.PUBLIC, IpScope.of("169.255.0.1"))
    }

    @Test
    fun multicastV4Boundaries() {
        assertEquals(Scope.PUBLIC, IpScope.of("223.255.255.255"))
        assertEquals(Scope.MULTICAST, IpScope.of("224.0.0.0"))
        assertEquals(Scope.MULTICAST, IpScope.of("224.0.0.251"))
        assertEquals(Scope.MULTICAST, IpScope.of("239.255.255.250"))
        assertEquals(Scope.PUBLIC, IpScope.of("240.0.0.1"))
        assertEquals(Scope.MULTICAST, IpScope.of("255.255.255.255"))
    }

    @Test
    fun uniqueLocalV6Boundaries() {
        assertEquals(Scope.PRIVATE, IpScope.of("fc00::1"))
        assertEquals(Scope.PRIVATE, IpScope.of("fd12:3456::1"))
        assertEquals(Scope.PRIVATE, IpScope.of("fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        assertEquals(Scope.PUBLIC, IpScope.of("fb00::1"))
    }

    @Test
    fun linkLocalV6Boundaries() {
        assertEquals(Scope.PRIVATE, IpScope.of("fe80::1"))
        assertEquals(Scope.PRIVATE, IpScope.of("febf::1"))
        assertEquals(Scope.PUBLIC, IpScope.of("fec0::1"))
        assertEquals(Scope.PUBLIC, IpScope.of("fe7f::1"))
    }

    @Test
    fun multicastV6() {
        assertEquals(Scope.MULTICAST, IpScope.of("ff00::1"))
        assertEquals(Scope.MULTICAST, IpScope.of("ff02::fb"))
        assertEquals(Scope.PUBLIC, IpScope.of("2001:4860:4860::8888"))
    }

    @Test
    fun ipv4MappedIsClassifiedAsItsIpv4() {
        assertEquals(Scope.PRIVATE, IpScope.of("::ffff:10.0.0.1"))
        assertEquals(Scope.PUBLIC, IpScope.of("::ffff:8.8.8.8"))
        assertEquals(Scope.LOOPBACK, IpScope.of("::ffff:127.0.0.1"))
    }

    @Test
    fun unparseableCountsAsPublic() {
        assertEquals(Scope.PUBLIC, IpScope.of("not-an-ip"))
        assertEquals(Scope.PUBLIC, IpScope.of(""))
        assertEquals(Scope.PUBLIC, IpScope.of("example.com"))
    }

    @Test
    fun ipVersionDetection() {
        assertTrue(IpScope.isIpv6("::1"))
        assertTrue(IpScope.isIpv6("2001:db8::1"))
        assertTrue(IpScope.isIpv6("[2001:db8::1]"))
        assertTrue(IpScope.isIpv6("fe80::1%wlan0"))
        assertFalse(IpScope.isIpv6("1.2.3.4"))
        assertFalse(IpScope.isIpv6("garbage"))
    }

    @Test
    fun ipv4MappedCountsAsIpv4Traffic() {
        assertFalse(IpScope.isIpv6("::ffff:1.2.3.4"))
        assertTrue(IpScope.isIpv4("::ffff:1.2.3.4"))
        assertTrue(IpScope.isIpv4("1.2.3.4"))
        assertFalse(IpScope.isIpv4("::1"))
    }

    @Test
    fun exposureOfBindAddresses() {
        assertEquals(Exposure.EXPOSED, IpScope.exposureOf("0.0.0.0"))
        assertEquals(Exposure.EXPOSED, IpScope.exposureOf("::"))
        assertEquals(Exposure.LOCAL, IpScope.exposureOf("127.0.0.1"))
        assertEquals(Exposure.LOCAL, IpScope.exposureOf("::1"))
        assertEquals(Exposure.LAN, IpScope.exposureOf("192.168.1.5"))
        assertEquals(Exposure.LAN, IpScope.exposureOf("10.1.2.3"))
        assertEquals(Exposure.LAN, IpScope.exposureOf("fe80::1"))
        assertEquals(Exposure.LAN, IpScope.exposureOf("100.100.1.1"))
        assertEquals(Exposure.EXPOSED, IpScope.exposureOf("93.184.216.34"))
    }

    @Test
    fun unparseableBindFailsLoud() {
        assertEquals(Exposure.EXPOSED, IpScope.exposureOf(""))
        assertEquals(Exposure.EXPOSED, IpScope.exposureOf("*"))
    }

    @Test
    fun exposureRankOrdersWidening() {
        assertTrue(IpScope.exposureRank(Exposure.LOCAL) < IpScope.exposureRank(Exposure.LAN))
        assertTrue(IpScope.exposureRank(Exposure.LAN) < IpScope.exposureRank(Exposure.EXPOSED))
    }

    @Test
    fun ipv4ParsingRejectsMalformed() {
        assertNull(IpBytes.parseV4("1.2.3"))
        assertNull(IpBytes.parseV4("1.2.3.4.5"))
        assertNull(IpBytes.parseV4("256.1.1.1"))
        assertNull(IpBytes.parseV4("1.2.3."))
        assertNull(IpBytes.parseV4(".1.2.3"))
        assertNull(IpBytes.parseV4("1.2.3.-4"))
        assertNull(IpBytes.parseV4("a.b.c.d"))
    }

    @Test
    fun ipv4ParsingRejectsLeadingZeros() {
        assertNull(IpBytes.parseV4("010.0.0.1"))
        assertNull(IpBytes.parseV4("1.2.3.04"))
        assertEquals(4, IpBytes.parseV4("0.0.0.0")?.size)
    }

    @Test
    fun ipv4ParsingKeepsByteOrder() {
        val b = IpBytes.parseV4("192.168.1.255")!!
        assertEquals(192, b[0].toInt() and 0xFF)
        assertEquals(168, b[1].toInt() and 0xFF)
        assertEquals(1, b[2].toInt() and 0xFF)
        assertEquals(255, b[3].toInt() and 0xFF)
    }

    @Test
    fun ipv6ParsingHandlesCompression() {
        assertTrue(IpBytes.parseV6("::")!!.all { it.toInt() == 0 })
        assertEquals(16, IpBytes.parseV6("1:2:3:4:5:6:7:8")?.size)
        val b = IpBytes.parseV6("2001:db8::1")!!
        assertEquals(0x20, b[0].toInt() and 0xFF)
        assertEquals(0x01, b[1].toInt() and 0xFF)
        assertEquals(0x0d, b[2].toInt() and 0xFF)
        assertEquals(0xb8, b[3].toInt() and 0xFF)
        assertEquals(1, b[15].toInt())
    }

    @Test
    fun ipv6ParsingRejectsMalformed() {
        assertNull(IpBytes.parseV6("1::2::3"))
        assertNull(IpBytes.parseV6("12345::"))
        assertNull(IpBytes.parseV6("1:2:3:4:5:6:7:8:9"))
        assertNull(IpBytes.parseV6("1:2:3:4:5:6:7"))
        assertNull(IpBytes.parseV6("g::1"))
        assertNull(IpBytes.parse(":1.2.3.4"))
    }

    @Test
    fun ipv6WithEmbeddedIpv4() {
        val mapped = IpBytes.parse("::ffff:192.0.2.1")!!
        assertEquals(16, mapped.size)
        val normalized = IpBytes.normalize(mapped)
        assertEquals(4, normalized.size)
        assertEquals(192, normalized[0].toInt() and 0xFF)
        assertEquals(1, normalized[3].toInt() and 0xFF)

        val compat = IpBytes.parse("::1.2.3.4")!!
        assertEquals(16, compat.size)
        // ::a.b.c.d is not the v4-mapped prefix, so it stays 16 bytes: ::1
        // would otherwise be folded into 0.0.0.1 and stop being loopback.
        assertEquals(16, IpBytes.normalize(compat).size)
    }

    @Test
    fun bracketsAndZoneAreStripped() {
        assertEquals(16, IpBytes.parse("[2001:db8::1]")?.size)
        assertEquals(16, IpBytes.parse("fe80::1%wlan0")?.size)
        assertEquals(4, IpBytes.parse("  10.0.0.1  ")?.size)
    }
}
