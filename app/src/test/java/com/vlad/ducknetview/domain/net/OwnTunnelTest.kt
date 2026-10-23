package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling this app's own capture tunnel apart from somebody else's.
 *
 * The distinction decides whether a link going away is an outage worth a
 * notification or just the capture switch being released — see [OwnTunnel].
 */
class OwnTunnelTest {

    private fun vpn(addresses: List<String>) = Fixtures.network(
        ifaceName = "tun0",
        transport = Transport.VPN,
        addresses = addresses,
        gateway = null,
    )

    @Test
    fun `the service and the event layer agree on the addresses`() {
        // Two copies of this would drift until one of them stopped recognising
        // the tunnel the other configures, and the symptom would be a phantom
        // outage nobody could reproduce.
        assertEquals(OwnTunnel.V4, DuckVpnService.TUN_V4)
        assertEquals(OwnTunnel.V6, DuckVpnService.TUN_V6)
    }

    @Test
    fun `our tunnel is recognised from either address family`() {
        assertTrue(OwnTunnel.isOurs(vpn(listOf("${OwnTunnel.V4}/30"))))
        assertTrue(OwnTunnel.isOurs(vpn(listOf("${OwnTunnel.V6}/126"))))
        assertTrue(OwnTunnel.isOurs(vpn(listOf("${OwnTunnel.V6}/126", "${OwnTunnel.V4}/30"))))
    }

    @Test
    fun `the prefix and the scope suffix the platform adds do not hide it`() {
        assertTrue(OwnTunnel.isOurs(vpn(listOf(OwnTunnel.V4))))
        assertTrue(OwnTunnel.isOurs(vpn(listOf("${OwnTunnel.V6}%tun0/126"))))
        assertTrue(OwnTunnel.isOurs(vpn(listOf("[${OwnTunnel.V6}]"))))
        assertTrue(OwnTunnel.isOurs(vpn(listOf(" ${OwnTunnel.V4}/30 "))))
    }

    /**
     * The whole point of the distinction: another app's VPN dropping is the one
     * externally caused failure that ends this app's capture, so it must keep
     * its outage event.
     */
    @Test
    fun `somebody else's tunnel is not ours`() {
        assertFalse(OwnTunnel.isOurs(vpn(listOf("10.8.0.2/24"))))
        assertFalse(OwnTunnel.isOurs(vpn(listOf("100.64.0.7/32"))))
        assertFalse(OwnTunnel.isOurs(vpn(emptyList())))
    }

    /** `tun0` is `tun0` for every VPN, so the name is not the test. */
    @Test
    fun `a non-VPN link carrying the same address is not ours`() {
        assertFalse(
            OwnTunnel.isOurs(
                Fixtures.network(
                    ifaceName = "wlan0",
                    transport = Transport.WIFI,
                    addresses = listOf("${OwnTunnel.V4}/30"),
                )
            )
        )
    }
}
