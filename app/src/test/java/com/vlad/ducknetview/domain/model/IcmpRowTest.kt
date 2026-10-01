package com.vlad.ducknetview.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ICMP echo addresses a host, not a service. Its flow key borrows the port
 * fields to carry the echo identifier, which is an implementation detail the
 * table must not leak as a port number nothing is listening on.
 */
class IcmpRowTest {

    private fun row(proto: Proto, localPort: Int = 1234, remotePort: Int = 443) = ConnRow(
        key = "k",
        proto = proto,
        localAddr = "10.0.0.2",
        localPort = localPort,
        remoteAddr = "1.1.1.1",
        remotePort = remotePort,
        state = ConnState.ACTIVE,
        uid = -1,
        resolvedHost = "one.one.one.one",
    )

    @Test
    fun `a TCP row still prints its ports`() {
        val r = row(Proto.TCP)
        assertEquals("10.0.0.2:1234", r.local)
        assertEquals("1.1.1.1:443", r.remote)
        assertEquals("one.one.one.one:443", r.remoteDisplay(revDns = true))
        assertEquals(-1, r.echoId)
    }

    @Test
    fun `an ICMP row prints bare addresses`() {
        val r = row(Proto.ICMP, localPort = 4242, remotePort = 0)
        assertEquals("10.0.0.2", r.local)
        assertEquals("1.1.1.1", r.remote)
        assertEquals("one.one.one.one", r.remoteDisplay(revDns = true))
    }

    @Test
    fun `the echo identifier is reachable without pretending to be a port`() {
        assertEquals(4242, row(Proto.ICMP, localPort = 4242, remotePort = 0).echoId)
    }

    @Test
    fun `reverse DNS off shows the address in both protocols`() {
        assertEquals("1.1.1.1:443", row(Proto.TCP).remoteDisplay(revDns = false))
        assertEquals("1.1.1.1", row(Proto.ICMP, remotePort = 0).remoteDisplay(revDns = false))
    }

    @Test
    fun `an IPv6 TCP remote is still bracketed`() {
        val r = row(Proto.TCP).copy(remoteAddr = "2001:db8::1", resolvedHost = null)
        assertEquals("[2001:db8::1]:443", r.remote)
    }

    @Test
    fun `an IPv6 ICMP remote is not bracketed, because there is no port to separate`() {
        val r = row(Proto.ICMP, remotePort = 0).copy(remoteAddr = "2001:db8::1", resolvedHost = null)
        assertEquals("2001:db8::1", r.remote)
    }
}
