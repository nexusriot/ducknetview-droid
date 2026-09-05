package com.vlad.ducknetview.engine.api

import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyProberTest {

    @Test
    fun `a completed handshake is a valid timing`() {
        val s = LatencyProber.classify("1.2.3.4:80", "gw", null, 12L)
        assertTrue(s.ok)
        assertEquals(12, s.millis)
        assertNull(s.note)
    }

    @Test
    fun `a refused connection counts as a valid timing`() {
        val s = LatencyProber.classify(
            "192.168.1.1:80", "gateway", ConnectException("Connection refused"), 3L,
        )
        assertTrue(s.ok)
        assertEquals(3, s.millis)
        assertEquals("refused", s.note)
    }

    @Test
    fun `a reset connection also counts as a valid timing`() {
        val s = LatencyProber.classify(
            "192.168.1.1:80", "gateway", ConnectException("Connection reset by peer"), 4L,
        )
        assertTrue(s.ok)
        assertEquals(4, s.millis)
    }

    @Test
    fun `ECONNREFUSED spelt out by the kernel is still refused`() {
        val s = LatencyProber.classify(
            "10.0.0.1:80", "gw", ConnectException("failed: ECONNREFUSED (Connection refused)"), 2L,
        )
        assertTrue(s.ok)
    }

    @Test
    fun `a timeout is not a measurement`() {
        val s = LatencyProber.classify("1.1.1.1:443", "dns", SocketTimeoutException(), 2000L)
        assertFalse(s.ok)
        assertEquals(-1, s.millis)
        assertEquals("timeout", s.note)
    }

    @Test
    fun `no route is not a measurement`() {
        val s = LatencyProber.classify("10.0.0.1:80", "gw", NoRouteToHostException(), 5L)
        assertFalse(s.ok)
        assertEquals("no route", s.note)
    }

    @Test
    fun `an unresolvable host is not a measurement`() {
        val s = LatencyProber.classify("nope.invalid:80", "nope", UnknownHostException(), 5L)
        assertFalse(s.ok)
        assertEquals("unknown host", s.note)
    }

    @Test
    fun `an unreachable network is not a measurement`() {
        val s = LatencyProber.classify(
            "10.0.0.1:80", "gw", ConnectException("Network is unreachable"), 1L,
        )
        assertFalse(s.ok)
        assertEquals("unreachable", s.note)
    }

    @Test
    fun `parseTarget splits host and port`() {
        assertEquals(Pair("1.2.3.4", 53), LatencyProber.parseTarget("1.2.3.4:53"))
        assertEquals(Pair("example.com", 443), LatencyProber.parseTarget(" example.com:443 "))
    }

    @Test
    fun `parseTarget handles a bracketed IPv6 literal`() {
        assertEquals(Pair("::1", 80), LatencyProber.parseTarget("[::1]:80"))
        assertEquals(Pair("fe80::1", 443), LatencyProber.parseTarget("[fe80::1]:443"))
    }

    @Test
    fun `parseTarget rejects a target with no port`() {
        assertNull(LatencyProber.parseTarget("1.2.3.4"))
        assertNull(LatencyProber.parseTarget("example.com"))
        assertNull(LatencyProber.parseTarget(""))
    }

    @Test
    fun `parseTarget rejects a bare IPv6 literal that only looks like host colon port`() {
        assertNull(LatencyProber.parseTarget("fe80::1"))
        assertNull(LatencyProber.parseTarget("2001:db8::dead:beef"))
    }

    @Test
    fun `parseTarget rejects an out-of-range or non-numeric port`() {
        assertNull(LatencyProber.parseTarget("1.2.3.4:0"))
        assertNull(LatencyProber.parseTarget("1.2.3.4:70000"))
        assertNull(LatencyProber.parseTarget("1.2.3.4:http"))
        assertNull(LatencyProber.parseTarget("1.2.3.4:"))
    }

    @Test
    fun `joinHostPort brackets an IPv6 literal`() {
        assertEquals("192.168.1.1:80", LatencyProber.joinHostPort("192.168.1.1", 80))
        assertEquals("[fe80::1]:80", LatencyProber.joinHostPort("fe80::1", 80))
    }

    @Test
    fun `a target with no port is reported as unusable, not probed`() = runBlocking {
        val sample = LatencyProber().probe("192.168.1.1")
        assertFalse(sample.ok)
        assertEquals("no port", sample.note)
        assertEquals(-1, sample.millis)
    }

    @Test
    fun `a real loopback listener is timed successfully`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val sample = LatencyProber().probe("127.0.0.1:${server.localPort}", 1000)
            assertTrue("expected a timing, got ${sample.note}", sample.ok)
            assertTrue(sample.millis >= 0)
            assertEquals("127.0.0.1", sample.label)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a closed loopback port is refused and therefore still timed`() = runBlocking {
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()
        val sample = LatencyProber().probe("127.0.0.1:$port", 1000)
        // Loopback refuses immediately; anything else here would be a timeout.
        assertTrue("expected refused-as-valid, got ${sample.note}", sample.ok)
    }

    @Test
    fun `history is kept per target and bounded`() = runBlocking {
        val prober = LatencyProber(historySize = 3)
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val target = "127.0.0.1:${server.localPort}"
            repeat(5) { prober.probe(target, 1000) }
            assertEquals(3, prober.history(target).size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `probeAll probes the gateway first and labels it`() = runBlocking {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val out = LatencyProber().probeAll(
                targets = listOf("127.0.0.1:${server.localPort}"),
                gateway = "127.0.0.1",
                timeoutMs = 1000,
            )
            assertEquals(2, out.size)
            assertEquals(LatencyProber.GATEWAY_LABEL, out[0].label)
            assertEquals("127.0.0.1:${LatencyProber.GATEWAY_PORT}", out[0].target)
        } finally {
            server.close()
        }
    }

    @Test
    fun `probeAll with no gateway probes only the configured targets`() = runBlocking {
        val out = LatencyProber().probeAll(listOf("192.168.255.255"), null, 200)
        assertEquals(1, out.size)
        assertEquals("no port", out[0].note)
    }

    @Test
    fun `probeAll skips blank targets`() = runBlocking {
        val out = LatencyProber().probeAll(listOf("", "   "), null, 200)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `retain drops history for targets that are gone`() = runBlocking {
        val prober = LatencyProber()
        prober.probe("192.168.1.1")
        assertEquals(1, prober.history("192.168.1.1").size)
        prober.retain(emptySet())
        assertTrue(prober.history("192.168.1.1").isEmpty())
    }

    @Test
    fun `an unclassified IO failure is not credited with a timing`() {
        val s = LatencyProber.classify("1.2.3.4:80", "x", IOException("broken pipe"), 7L)
        assertFalse(s.ok)
        assertEquals("io error", s.note)
        assertEquals(-1, s.millis)
    }

    @Test
    fun `labelFor uses the host, falling back to the raw target`() {
        assertEquals("1.1.1.1", LatencyProber.labelFor("1.1.1.1:53"))
        assertEquals("::1", LatencyProber.labelFor("[::1]:53"))
        assertEquals("1.1.1.1", LatencyProber.labelFor("1.1.1.1"))
    }
}
