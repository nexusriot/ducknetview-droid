package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNamesTest {

    @Test
    fun commonTcpPorts() {
        assertEquals("https", ServiceNames.of(443, Proto.TCP))
        assertEquals("http", ServiceNames.of(80, Proto.TCP))
        assertEquals("ssh", ServiceNames.of(22, Proto.TCP))
        assertEquals("mysql", ServiceNames.of(3306, Proto.TCP))
        assertEquals("postgresql", ServiceNames.of(5432, Proto.TCP))
        assertEquals("redis", ServiceNames.of(6379, Proto.TCP))
        assertEquals("imaps", ServiceNames.of(993, Proto.TCP))
        assertEquals("smtp", ServiceNames.of(25, Proto.TCP))
    }

    @Test
    fun commonUdpPorts() {
        assertEquals("dns", ServiceNames.of(53, Proto.UDP))
        assertEquals("dhcp-server", ServiceNames.of(67, Proto.UDP))
        assertEquals("dhcp-client", ServiceNames.of(68, Proto.UDP))
        assertEquals("ntp", ServiceNames.of(123, Proto.UDP))
        assertEquals("mdns", ServiceNames.of(5353, Proto.UDP))
        assertEquals("wireguard", ServiceNames.of(51820, Proto.UDP))
    }

    @Test
    fun protocolChangesTheAnswerOnSharedPorts() {
        assertEquals("https", ServiceNames.of(443, Proto.TCP))
        assertEquals("quic", ServiceNames.of(443, Proto.UDP))
        assertEquals("dns", ServiceNames.of(53, Proto.TCP))
        assertEquals("dns", ServiceNames.of(53, Proto.UDP))
        assertEquals("", ServiceNames.of(51820, Proto.TCP))
    }

    @Test
    fun androidSpecificPorts() {
        assertEquals("adb", ServiceNames.of(5555, Proto.TCP))
        assertEquals("android-mtalk", ServiceNames.of(5228, Proto.TCP))
        assertEquals("rtsp", ServiceNames.of(554, Proto.TCP))
        assertEquals("sip", ServiceNames.of(5060, Proto.UDP))
    }

    @Test
    fun unknownAndInvalidPorts() {
        assertEquals("", ServiceNames.of(12345, Proto.TCP))
        assertEquals("", ServiceNames.of(0, Proto.TCP))
        assertEquals("", ServiceNames.of(-1, Proto.TCP))
        assertEquals("", ServiceNames.of(70000, Proto.TCP))
        assertEquals("", ServiceNames.of(443, Proto.ICMP))
        assertEquals("", ServiceNames.of(443, Proto.OTHER))
    }

    @Test
    fun tableIsSubstantial() {
        assertTrue("expected a broad built-in table", ServiceNames.size() >= 120)
    }
}
