package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.CellularState
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.model.WifiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrustTest {

    private fun wifi(ssid: String?, iface: String = "wlan0") = NetworkRow(
        id = "n1",
        ifaceName = iface,
        transport = Transport.WIFI,
        up = true,
        wifi = ssid?.let {
            WifiState(ssid = it, bssid = null, rssiDbm = -50, linkSpeedMbps = 300)
        },
    )

    // ------------------------------------------------------------------ keys

    @Test
    fun `wifi is keyed on the ssid, not the interface`() {
        // wlan0 is wlan0 on every Wi-Fi network there has ever been, so
        // keying on it would mark every network at once.
        assertEquals("wifi:Home", NetworkTrust.keyOf(wifi("Home")))
        assertEquals("wifi:Cafe", NetworkTrust.keyOf(wifi("Cafe")))
    }

    @Test
    fun `a wifi link with no ssid yet has no identity to remember`() {
        // Better no buttons than buttons that remember the wrong network.
        assertNull(NetworkTrust.keyOf(wifi(null)))
        assertNull(NetworkTrust.keyOf(wifi("")))
    }

    @Test
    fun `cellular is one decision per operator`() {
        val row = NetworkRow(
            id = "n2",
            ifaceName = "rmnet0",
            transport = Transport.CELLULAR,
            up = true,
            cellular = CellularState(networkType = "LTE", operator = "Telco", signalLevel = 3),
        )
        assertEquals("cellular:Telco", NetworkTrust.keyOf(row))
    }

    @Test
    fun `cellular with no operator still has one identity`() {
        val row = NetworkRow(id = "n2", ifaceName = "rmnet0", transport = Transport.CELLULAR, up = true)
        assertEquals("cellular:any", NetworkTrust.keyOf(row))
    }

    @Test
    fun `ethernet falls back to the interface`() {
        val row = NetworkRow(id = "n3", ifaceName = "eth0", transport = Transport.ETHERNET, up = true)
        assertEquals("ethernet:eth0", NetworkTrust.keyOf(row))
    }

    @Test
    fun `a vpn tunnel is not a network to judge`() {
        // Somebody else's tunnel, and while this app is capturing the default
        // route is its own TUN — marking that trusted would mean nothing.
        val row = NetworkRow(id = "n4", ifaceName = "tun0", transport = Transport.VPN, up = true)
        assertNull(NetworkTrust.keyOf(row))
        assertNull(NetworkTrust.keyOf(NetworkRow(id = "lo", ifaceName = "lo", transport = Transport.LOOPBACK, up = true)))
    }

    @Test
    fun `the label is what the user would recognise`() {
        assertEquals("Home", NetworkTrust.labelOf(wifi("Home")))
        assertEquals("wlan0", NetworkTrust.labelOf(wifi(null)))
    }

    // --------------------------------------------------------------- verdict

    @Test
    fun `an unmarked network is unknown`() {
        assertEquals(Trust.UNKNOWN, NetworkTrust.of(wifi("Home"), AppSettings()))
    }

    @Test
    fun `a marked network reports what it was marked`() {
        val s = AppSettings(trustedNetworks = setOf("wifi:Home"), untrustedNetworks = setOf("wifi:Cafe"))
        assertEquals(Trust.TRUSTED, NetworkTrust.of(wifi("Home"), s))
        assertEquals(Trust.UNTRUSTED, NetworkTrust.of(wifi("Cafe"), s))
    }

    @Test
    fun `a network with no key is never judged`() {
        val s = AppSettings(untrustedNetworks = setOf("wifi:Cafe"))
        assertEquals(Trust.UNKNOWN, NetworkTrust.of(wifi(null), s))
    }

    // ---------------------------------------------------------------- policy

    @Test
    fun `a trusted network never triggers the automatic responses`() {
        assertFalse(NetworkTrust.shouldAct(Trust.TRUSTED, AppSettings()))
        assertFalse(
            NetworkTrust.shouldAct(
                Trust.TRUSTED,
                AppSettings(treatUnknownNetworksAsUntrusted = true),
            ),
        )
    }

    @Test
    fun `an untrusted network always does`() {
        assertTrue(NetworkTrust.shouldAct(Trust.UNTRUSTED, AppSettings()))
    }

    @Test
    fun `an unseen network only counts when the user asked for that`() {
        // The setting that makes the feature useful without priming: the
        // network you have never been on is the one worth reacting to.
        assertFalse(NetworkTrust.shouldAct(Trust.UNKNOWN, AppSettings()))
        assertTrue(
            NetworkTrust.shouldAct(
                Trust.UNKNOWN,
                AppSettings(treatUnknownNetworksAsUntrusted = true),
            ),
        )
    }

    // ----------------------------------------------------------------- apply

    @Test
    fun `marking a network moves it between the sets rather than adding to both`() {
        var s = AppSettings()
        s = NetworkTrust.apply(s, "wifi:Home", Trust.UNTRUSTED)
        assertEquals(setOf("wifi:Home"), s.untrustedNetworks)
        assertTrue(s.trustedNetworks.isEmpty())

        s = NetworkTrust.apply(s, "wifi:Home", Trust.TRUSTED)
        assertEquals(setOf("wifi:Home"), s.trustedNetworks)
        assertTrue("it stayed in both, so the verdict depends on test order", s.untrustedNetworks.isEmpty())
    }

    @Test
    fun `clearing a mark removes it from both sets`() {
        var s = AppSettings(
            trustedNetworks = setOf("wifi:Home"),
            untrustedNetworks = setOf("wifi:Home"),
        )
        s = NetworkTrust.apply(s, "wifi:Home", Trust.UNKNOWN)
        assertTrue(s.trustedNetworks.isEmpty())
        assertTrue(s.untrustedNetworks.isEmpty())
    }

    @Test
    fun `other networks are left alone`() {
        val s = NetworkTrust.apply(
            AppSettings(trustedNetworks = setOf("wifi:Other")),
            "wifi:Home",
            Trust.TRUSTED,
        )
        assertEquals(setOf("wifi:Other", "wifi:Home"), s.trustedNetworks)
    }
}
