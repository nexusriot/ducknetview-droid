package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextTest {

    @Test
    fun `the tcp cleartext ports are labelled`() {
        val expected = mapOf(
            21 to "ftp", 23 to "telnet", 25 to "smtp", 80 to "http", 110 to "pop3",
            143 to "imap", 1883 to "mqtt", 5222 to "xmpp", 8080 to "http-alt",
        )
        for ((port, label) in expected) assertEquals(label, Cleartext.protocolOf(Proto.TCP, port))
    }

    @Test
    fun `the udp cleartext ports are labelled`() {
        assertEquals("tftp", Cleartext.protocolOf(Proto.UDP, 69))
        assertEquals("snmp", Cleartext.protocolOf(Proto.UDP, 161))
        assertEquals("syslog", Cleartext.protocolOf(Proto.UDP, 514))
    }

    @Test
    fun `encrypted and unknown ports are not flagged`() {
        for (port in listOf(22, 443, 465, 853, 993, 995, 8883, 0, 65535, -1)) {
            assertNull("tcp/$port", Cleartext.protocolOf(Proto.TCP, port))
        }
        assertNull(Cleartext.protocolOf(Proto.UDP, 443))
        assertNull(Cleartext.protocolOf(Proto.UDP, 53))
    }

    @Test
    fun `the protocol matters, not just the port`() {
        // tcp/514 is rsh and udp/80 is nothing; neither should borrow a label.
        assertNull(Cleartext.protocolOf(Proto.UDP, 80))
        assertNull(Cleartext.protocolOf(Proto.TCP, 69))
        assertNull(Cleartext.protocolOf(Proto.TCP, 161))
    }

    @Test
    fun `icmp and other protocols have no application layer to label`() {
        assertNull(Cleartext.protocolOf(Proto.ICMP, 80))
        assertNull(Cleartext.protocolOf(Proto.OTHER, 80))
    }

    @Test
    fun `protocols with STARTTLS may upgrade`() {
        for (label in listOf("smtp", "imap", "pop3", "ftp", "xmpp")) assertTrue(label, Cleartext.mayUpgrade(label))
    }

    @Test
    fun `protocols with no in-band upgrade do not`() {
        for (label in listOf("http", "http-alt", "telnet", "mqtt", "tftp", "snmp", "syslog", "", "nope")) {
            assertFalse(label, Cleartext.mayUpgrade(label))
        }
    }
}
