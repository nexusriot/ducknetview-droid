package com.vlad.ducknetview.data.work

import com.vlad.ducknetview.engine.api.PortScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressesTest {

    @Test
    fun `loopback is present even when nothing was reported`() {
        assertEquals(listOf(PortScanner.LOOPBACK), LocalAddresses.usable(emptyList()))
    }

    @Test
    fun `a prefix length is stripped off`() {
        assertTrue(LocalAddresses.usable(listOf("10.0.0.5/24")).contains("10.0.0.5"))
    }

    @Test
    fun `a scope id is stripped off`() {
        assertTrue(LocalAddresses.usable(listOf("2001:db8::1%wlan0")).contains("2001:db8::1"))
    }

    @Test
    fun `link-local IPv6 is dropped because its scope id cannot survive`() {
        assertFalse(LocalAddresses.usable(listOf("fe80::1%wlan0")).contains("fe80::1"))
    }

    @Test
    fun `IPv4 link-local is dropped`() {
        assertFalse(LocalAddresses.usable(listOf("169.254.10.1")).any { it.startsWith("169.254") })
    }

    @Test
    fun `a second loopback report does not duplicate the entry`() {
        val out = LocalAddresses.usable(listOf("127.0.0.1", "::1"))
        assertEquals(listOf(PortScanner.LOOPBACK), out)
    }

    @Test
    fun `duplicate LAN addresses collapse to one`() {
        val out = LocalAddresses.usable(listOf("10.0.0.5", "10.0.0.5/24"))
        assertEquals(listOf(PortScanner.LOOPBACK, "10.0.0.5"), out)
    }

    @Test
    fun `blank entries are ignored`() {
        assertEquals(listOf(PortScanner.LOOPBACK), LocalAddresses.usable(listOf("", "   ")))
    }

    @Test
    fun `ordering puts loopback first and keeps the reported order after it`() {
        val out = LocalAddresses.usable(listOf("10.0.0.5", "192.168.1.9"))
        assertEquals(listOf(PortScanner.LOOPBACK, "10.0.0.5", "192.168.1.9"), out)
    }
}
