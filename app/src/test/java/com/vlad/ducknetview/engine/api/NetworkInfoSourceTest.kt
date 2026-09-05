package com.vlad.ducknetview.engine.api

import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.model.RouteRow
import com.vlad.ducknetview.domain.model.Transport
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NetworkInfoSourceTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun has(vararg transports: Int): (Int) -> Boolean =
        { t -> transports.contains(t) }

    @Test
    fun `a VPN riding on Wi-Fi is reported as a VPN, not as Wi-Fi`() {
        val transport = NetworkInfoSource.transportOf(
            has(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI),
        )
        assertEquals(Transport.VPN, transport)
    }

    @Test
    fun `each single transport maps to its own enum value`() {
        assertEquals(
            Transport.WIFI,
            NetworkInfoSource.transportOf(has(NetworkCapabilities.TRANSPORT_WIFI)),
        )
        assertEquals(
            Transport.CELLULAR,
            NetworkInfoSource.transportOf(has(NetworkCapabilities.TRANSPORT_CELLULAR)),
        )
        assertEquals(
            Transport.ETHERNET,
            NetworkInfoSource.transportOf(has(NetworkCapabilities.TRANSPORT_ETHERNET)),
        )
        assertEquals(
            Transport.BLUETOOTH,
            NetworkInfoSource.transportOf(has(NetworkCapabilities.TRANSPORT_BLUETOOTH)),
        )
    }

    @Test
    fun `no known transport is OTHER rather than a guess`() {
        assertEquals(Transport.OTHER, NetworkInfoSource.transportOf(has()))
    }

    @Test
    fun `the gateway is the default route's next hop`() {
        val routes = listOf(
            RouteRow("192.168.1.0/24", null, "wlan0", false),
            RouteRow("0.0.0.0/0", "192.168.1.1", "wlan0", true),
        )
        assertEquals("192.168.1.1", NetworkInfoSource.gatewayOf(routes))
    }

    @Test
    fun `a default route with no next hop yields no gateway`() {
        val routes = listOf(RouteRow("0.0.0.0/0", null, "rmnet0", true))
        assertNull(NetworkInfoSource.gatewayOf(routes))
    }

    @Test
    fun `a route table with no default yields no gateway`() {
        val routes = listOf(RouteRow("10.0.0.0/8", "10.0.0.1", "wlan0", false))
        assertNull(NetworkInfoSource.gatewayOf(routes))
    }

    @Test
    fun `an unflagged all-destinations route still counts as the default`() {
        val routes = listOf(RouteRow("::/0", "fe80::1", "wlan0", false))
        assertEquals("fe80::1", NetworkInfoSource.gatewayOf(routes))
    }

    @Test
    fun `an on-link route's wildcard next hop is not a gateway`() {
        assertNull(NetworkInfoSource.gatewayText(InetAddress.getByName("0.0.0.0")))
        assertNull(NetworkInfoSource.gatewayText(InetAddress.getByName("::")))
        assertNull(NetworkInfoSource.gatewayText(null))
        assertEquals("10.0.0.1", NetworkInfoSource.gatewayText(InetAddress.getByName("10.0.0.1")))
    }

    @Test
    fun `addresses are rendered with their prefix length`() {
        assertEquals(
            "192.168.1.5/24",
            NetworkInfoSource.addrText(InetAddress.getByName("192.168.1.5"), 24),
        )
    }

    @Test
    fun `an out-of-range prefix falls back to the family width`() {
        assertEquals(
            "192.168.1.5/32",
            NetworkInfoSource.addrText(InetAddress.getByName("192.168.1.5"), -1),
        )
        val v6 = NetworkInfoSource.addrText(InetAddress.getByName("2001:db8::1"), 999)
        assertNotNull(v6)
        assertTrue(v6!!.endsWith("/128"))
    }

    @Test
    fun `hostText carries no scope suffix and never sees a null through`() {
        // Textual IPv6 form differs between runtimes, so only the scope rule is asserted.
        val text = NetworkInfoSource.hostText(InetAddress.getByName("2001:db8::1"))
        assertNotNull(text)
        assertFalse(text!!.contains('%'))
        assertNull(NetworkInfoSource.hostText(null))
    }

    @Test
    fun `start then stop leaves no registered callbacks behind`() {
        val source = NetworkInfoSource(context)
        source.start()
        assertNotNull(source.networks.value)
        source.stop()
        source.stop()
        assertEquals(emptyList<Any>(), source.networks.value)
    }

    @Test
    fun `starting twice is harmless`() {
        val source = NetworkInfoSource(context)
        source.start()
        source.start()
        source.stop()
    }

    @Test
    fun `stopping without starting is harmless`() {
        NetworkInfoSource(context).stop()
    }
}
