package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.net.EncryptedDns.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedDnsTest {

    @Test
    fun `port 853 is DoT over tcp and DoQ over udp, to any address`() {
        assertEquals(Kind.DOT, EncryptedDns.kindOf(Proto.TCP, "1.1.1.1", 853))
        assertEquals(Kind.DOT, EncryptedDns.kindOf(Proto.TCP, "203.0.113.9", 853))
        assertEquals(Kind.DOQ, EncryptedDns.kindOf(Proto.UDP, "94.140.14.14", 853))
        assertEquals(Kind.DOQ, EncryptedDns.kindOf(Proto.UDP, "2001:db8::1", 853))
    }

    @Test
    fun `https to a known resolver is DoH, over tcp or http3`() {
        assertEquals(Kind.DOH, EncryptedDns.kindOf(Proto.TCP, "8.8.8.8", 443))
        assertEquals(Kind.DOH, EncryptedDns.kindOf(Proto.UDP, "1.1.1.1", 443))
        assertEquals(Kind.DOH, EncryptedDns.kindOf(Proto.TCP, "2620:fe::fe", 443))
    }

    @Test
    fun `https to anything else is just https`() {
        assertNull(EncryptedDns.kindOf(Proto.TCP, "93.184.216.34", 443))
        assertNull(EncryptedDns.kindOf(Proto.UDP, "142.250.74.78", 443))
        // A neighbour of a resolver is not the resolver.
        assertNull(EncryptedDns.kindOf(Proto.TCP, "8.8.8.9", 443))
    }

    @Test
    fun `a resolver on another port is not encrypted dns`() {
        assertNull(EncryptedDns.kindOf(Proto.UDP, "8.8.8.8", 53))
        assertNull(EncryptedDns.kindOf(Proto.TCP, "8.8.8.8", 53))
        assertNull(EncryptedDns.kindOf(Proto.TCP, "1.1.1.1", 80))
    }

    @Test
    fun `icmp is never dns`() {
        assertNull(EncryptedDns.kindOf(Proto.ICMP, "1.1.1.1", 853))
        assertNull(EncryptedDns.kindOf(Proto.ICMP, "1.1.1.1", 443))
        assertNull(EncryptedDns.kindOf(Proto.OTHER, "1.1.1.1", 443))
    }

    @Test
    fun `every listed resolver is recognised`() {
        val all = listOf(
            "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844",
            "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001",
            "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9",
            "94.140.14.14", "94.140.15.15", "208.67.222.222", "208.67.220.220",
        )
        for (a in all) assertTrue(a, EncryptedDns.isKnownResolver(a))
    }

    @Test
    fun `ipv6 spellings are compared as addresses, not text`() {
        assertTrue(EncryptedDns.isKnownResolver("2001:4860:4860:0:0:0:0:8888"))
        assertTrue(EncryptedDns.isKnownResolver("2001:4860:4860:0000:0000:0000:0000:8888"))
        assertTrue(EncryptedDns.isKnownResolver("2606:4700:4700::1111".uppercase()))
        assertTrue(EncryptedDns.isKnownResolver("[2620:fe::fe]"))
        assertTrue(EncryptedDns.isKnownResolver("2620:fe::fe%wlan0"))
        assertTrue(EncryptedDns.isKnownResolver(" 1.1.1.1 "))
    }

    @Test
    fun `an ipv4-mapped literal matches its ipv4 resolver`() {
        assertTrue(EncryptedDns.isKnownResolver("::ffff:1.1.1.1"))
        assertEquals(Kind.DOH, EncryptedDns.kindOf(Proto.TCP, "::ffff:9.9.9.9", 443))
    }

    @Test
    fun `names and garbage are not resolved, only rejected`() {
        assertFalse(EncryptedDns.isKnownResolver("dns.google"))
        assertFalse(EncryptedDns.isKnownResolver(""))
        assertFalse(EncryptedDns.isKnownResolver("1.1.1"))
        assertFalse(EncryptedDns.isKnownResolver("01.1.1.1"))
        assertNull(EncryptedDns.kindOf(Proto.TCP, "one.one.one.one", 443))
    }

    @Test
    fun `kinds carry readable labels`() {
        assertEquals("DNS-over-TLS", Kind.DOT.label)
        assertEquals("DNS-over-QUIC", Kind.DOQ.label)
        assertEquals("DNS-over-HTTPS", Kind.DOH.label)
    }
}
