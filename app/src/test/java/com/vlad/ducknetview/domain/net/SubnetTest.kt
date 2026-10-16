package com.vlad.ducknetview.domain.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the neighbour sweep is allowed to knock on.
 *
 * This is the only arithmetic in the app that decides which addresses receive
 * a packet, so the bounds matter more than the convenience: a prefix wider
 * than the cap must produce no list at all rather than a truncated one that
 * looks like a complete answer.
 */
class SubnetTest {

    @Test
    fun `a slash 24 yields its 254 hosts`() {
        val hosts = Subnet.hostsOf("192.168.1.5/24")
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun `the network and broadcast addresses are left out`() {
        val hosts = Subnet.hostsOf("192.168.1.5/24")
        assertTrue("the network address is swept", "192.168.1.0" !in hosts)
        // The one address on the list that would reach hosts the user did not
        // ask about.
        assertTrue("the broadcast address is swept", "192.168.1.255" !in hosts)
    }

    @Test
    fun `the device's own address stays in the list`() {
        // It is a real host on the link, and dropping it would make the count
        // disagree with the prefix. The scanner marks it instead.
        assertTrue("192.168.1.5" in Subnet.hostsOf("192.168.1.5/24"))
    }

    @Test
    fun `the prefix is honoured, not the address`() {
        val hosts = Subnet.hostsOf("10.0.3.77/23")
        assertEquals(510, hosts.size)
        assertEquals("10.0.2.1", hosts.first())
        assertEquals("10.0.3.254", hosts.last())
    }

    @Test
    fun `a prefix wider than the cap is refused outright`() {
        // Not truncated. A partial sweep presented as a finished one is the
        // kind of silent shortfall this project treats as a defect.
        assertTrue(Subnet.hostsOf("10.0.0.1/8").isEmpty())
        assertTrue(Subnet.hostsOf("172.16.0.1/16").isEmpty())
        assertTrue(Subnet.hostsOf("10.1.0.1/21").isEmpty())
    }

    @Test
    fun `the narrowest permitted prefix is swept`() {
        assertEquals(1022, Subnet.hostsOf("10.0.0.1/${Subnet.MIN_PREFIX}").size)
    }

    @Test
    fun `a point to point link has two hosts and no broadcast`() {
        val hosts = Subnet.hostsOf("10.0.0.4/31")
        assertEquals(listOf("10.0.0.4", "10.0.0.5"), hosts)
    }

    @Test
    fun `a single address prefix is just itself`() {
        assertEquals(listOf("10.0.0.7"), Subnet.hostsOf("10.0.0.7/32"))
    }

    @Test
    fun `an explicit limit caps the list`() {
        assertEquals(10, Subnet.hostsOf("192.168.1.5/24", limit = 10).size)
    }

    @Test
    fun `ipv6 is refused rather than attempted`() {
        // A /64 is 2^64 addresses. Returning an empty list is the honest
        // answer and the screen explains it; truncating to the first 1022
        // would be a sweep of nothing useful dressed up as a result.
        assertTrue(Subnet.hostsOf("fd00::1/64").isEmpty())
        assertTrue(Subnet.hostsOf("2001:db8::5/64").isEmpty())
        assertTrue(Subnet.hostsOf("fe80::1/10").isEmpty())
    }

    @Test
    fun `malformed input yields nothing`() {
        for (bad in listOf("", "/", "192.168.1.5", "192.168.1.5/", "/24", "nope/24",
                           "192.168.1.5/abc", "192.168.1.5/33", "192.168.1.5/-1")) {
            assertTrue("'$bad' produced hosts", Subnet.hostsOf(bad).isEmpty())
        }
    }

    @Test
    fun `the first ipv4 prefix is picked out of a dual stack link`() {
        val addresses = listOf("fe80::1234/64", "2001:db8::5/64", "192.168.1.5/24")
        assertEquals("192.168.1.5/24", Subnet.firstV4(addresses))
    }

    @Test
    fun `an ipv6 only link offers no prefix`() {
        assertNull(Subnet.firstV4(listOf("fe80::1234/64", "2001:db8::5/64")))
        assertNull(Subnet.firstV4(emptyList()))
    }

    @Test
    fun `the count agrees with the list`() {
        for (cidr in listOf("192.168.1.5/24", "10.0.3.77/23", "10.0.0.4/31", "fd00::1/64")) {
            assertEquals(cidr, Subnet.hostsOf(cidr).size, Subnet.hostCount(cidr))
        }
    }
}
