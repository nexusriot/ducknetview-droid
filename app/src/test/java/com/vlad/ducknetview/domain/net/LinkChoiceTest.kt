package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Both of these answers were previously "whatever the platform listed first",
 * and on a real tablet that was the wrong one in both cases.
 */
class LinkChoiceTest {

    // ---------------- underlyingLabel ----------------

    @Test
    fun `skips our own tun and names the link underneath it`() {
        // What capture actually looks like: the TUN owns the default route, so
        // "the default network" is the tunnel and the Wi-Fi link is not default.
        val networks = listOf(
            Fixtures.network(
                id = "tun",
                ifaceName = "tun0",
                transport = Transport.VPN,
                isDefault = true,
            ),
            Fixtures.network(id = "wifi", ifaceName = "wlan0", isDefault = false),
        )
        assertEquals("wlan0", LinkChoice.underlyingLabel(networks))
    }

    @Test
    fun `prefers the default network when it is not a vpn`() {
        val networks = listOf(
            Fixtures.network(
                id = "cell",
                ifaceName = "rmnet0",
                transport = Transport.CELLULAR,
                isDefault = false,
            ),
            Fixtures.network(id = "wifi", ifaceName = "wlan0", isDefault = true),
        )
        assertEquals("wlan0", LinkChoice.underlyingLabel(networks))
    }

    @Test
    fun `falls back to a validated link when none is default`() {
        val networks = listOf(
            Fixtures.network(
                id = "a",
                ifaceName = "rmnet0",
                isDefault = false,
                validated = false,
            ),
            Fixtures.network(id = "b", ifaceName = "wlan0", isDefault = false, validated = true),
        )
        assertEquals("wlan0", LinkChoice.underlyingLabel(networks))
    }

    @Test
    fun `empty when the only network is our tunnel`() {
        val networks = listOf(
            Fixtures.network(ifaceName = "tun0", transport = Transport.VPN, isDefault = true),
        )
        assertEquals("", LinkChoice.underlyingLabel(networks))
    }

    @Test
    fun `empty for no networks at all`() {
        assertEquals("", LinkChoice.underlyingLabel(emptyList()))
    }

    @Test
    fun `ignores a network the platform named with an empty interface`() {
        val networks = listOf(
            Fixtures.network(id = "a", ifaceName = "", isDefault = true),
            Fixtures.network(id = "b", ifaceName = "wlan0", isDefault = false),
        )
        assertEquals("wlan0", LinkChoice.underlyingLabel(networks))
    }

    // ---------------- displayAddress ----------------

    @Test
    fun `prefers the routable v4 address over the link-local v6 the platform lists first`() {
        // Exactly what wlan0 reported on the test tablet, in that order.
        val addresses = listOf("fe80::bc10:d0ff:fe80:9e3/64", "192.168.88.34/24")
        assertEquals("192.168.88.34/24", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `prefers a global v6 address over a unique-local one`() {
        val addresses = listOf("fd00:6475:636b::1/126", "2001:db8::5/64")
        assertEquals("2001:db8::5/64", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `prefers a unique-local v6 address over a link-local one`() {
        val addresses = listOf("fe80::1/64", "fd00:6475:636b::1/126")
        assertEquals("fd00:6475:636b::1/126", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `falls back to link-local when that is all there is`() {
        assertEquals("fe80::1/64", LinkChoice.displayAddress(listOf("fe80::1/64")))
    }

    @Test
    fun `ranks loopback below link-local`() {
        val addresses = listOf("127.0.0.1/8", "fe80::1/64")
        assertEquals("fe80::1/64", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `treats the v4 link-local range as link-local too`() {
        val addresses = listOf("169.254.3.4/16", "10.0.0.7/8")
        assertEquals("10.0.0.7/8", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `null for a link with no address`() {
        assertNull(LinkChoice.displayAddress(emptyList()))
    }

    @Test
    fun `keeps list order between addresses of equal rank`() {
        val addresses = listOf("10.0.0.7/8", "192.168.1.5/24")
        assertEquals("10.0.0.7/8", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `an unparseable literal never outranks a real address`() {
        val addresses = listOf("not-an-address", "192.168.1.5/24")
        assertEquals("192.168.1.5/24", LinkChoice.displayAddress(addresses))
    }

    @Test
    fun `a bare address without a prefix width still ranks`() {
        // NetworkInfoSource always appends a width, but a snapshot file read
        // back from another build need not have.
        val addresses = listOf("fe80::1", "192.168.1.5")
        assertEquals("192.168.1.5", LinkChoice.displayAddress(addresses))
    }

    // ---------------- underlying(), for the address to advertise ----------------

    /**
     * The Prometheus scrape URL is built from this. It used to come from "the
     * default network", which while capture is running is the app's own TUN —
     * so the URL shown on the Settings screen was http://10.215.173.1:9187/
     * metrics, the one address on the device that no other machine can reach.
     * Scraping it from the laptop got nothing; the Wi-Fi address answered 200.
     */
    @Test
    fun `underlying gives the reachable link while the tunnel holds the default route`() {
        val networks = listOf(
            Fixtures.network(
                id = "787",
                ifaceName = "tun0",
                transport = Transport.VPN,
                isDefault = true,
                addresses = listOf("10.215.173.1/30", "fd00:6475:636b::1/126"),
            ),
            Fixtures.network(
                id = "786",
                ifaceName = "wlan0",
                isDefault = false,
                addresses = listOf("192.168.88.34/24"),
            ),
        )
        val chosen = LinkChoice.underlying(networks)
        assertEquals("wlan0", chosen?.ifaceName)
        assertEquals(listOf("192.168.88.34/24"), chosen?.addresses)
    }

    @Test
    fun `underlying is null when only a tunnel is up`() {
        val networks = listOf(
            Fixtures.network(ifaceName = "tun0", transport = Transport.VPN, isDefault = true),
        )
        assertNull(LinkChoice.underlying(networks))
    }

    @Test
    fun `underlying and underlyingLabel agree`() {
        val networks = listOf(
            Fixtures.network(id = "a", ifaceName = "tun0", transport = Transport.VPN, isDefault = true),
            Fixtures.network(id = "b", ifaceName = "wlan0", isDefault = false),
        )
        assertEquals(LinkChoice.underlying(networks)?.ifaceName, LinkChoice.underlyingLabel(networks))
    }
}
