package com.vlad.ducknetview.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a latency reading says it probed.
 *
 * The gateway probe prefers an ICMP echo and keys its history on a synthesised
 * `host:80`, so the identity and the thing actually contacted are not the same
 * string. Reporting the identity sent a scrape `target="192.168.88.1:80"` for a
 * figure that never touched a port — the exporter was the only surface that
 * showed it, since the screens render the label.
 */
class ProbedEndpointTest {

    private fun sample(target: String, method: LatencyMethod) =
        LatencySample(target = target, label = "gateway", millis = 3, ok = true, method = method)

    @Test
    fun `an echo reports the host, without the port it never used`() {
        assertEquals("192.168.88.1", sample("192.168.88.1:80", LatencyMethod.ICMP).probedEndpoint)
    }

    @Test
    fun `a handshake reports the port it asked about`() {
        assertEquals("1.1.1.1:443", sample("1.1.1.1:443", LatencyMethod.TCP).probedEndpoint)
    }

    @Test
    fun `a bracketed IPv6 target keeps its address and loses the port`() {
        assertEquals("2001:db8::1", sample("[2001:db8::1]:80", LatencyMethod.ICMP).probedEndpoint)
        assertEquals("[2001:db8::1]:443", sample("[2001:db8::1]:443", LatencyMethod.TCP).probedEndpoint)
    }

    /**
     * `parseTarget` requires a port, so this shape should not reach here — but
     * stripping "everything after the last colon" from a bare IPv6 literal would
     * silently truncate an address, which is worse than leaving it alone.
     */
    @Test
    fun `a target with no port is left as it is`() {
        assertEquals("192.168.88.1", sample("192.168.88.1", LatencyMethod.ICMP).probedEndpoint)
    }

    @Test
    fun `surrounding whitespace does not become part of the endpoint`() {
        assertEquals("192.168.88.1", sample("  192.168.88.1:80  ", LatencyMethod.ICMP).probedEndpoint)
    }
}
