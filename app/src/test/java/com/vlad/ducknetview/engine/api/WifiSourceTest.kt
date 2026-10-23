package com.vlad.ducknetview.engine.api

import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.model.WifiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WifiSourceTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * The regression that cost this app the SSID on every modern device.
     *
     * The two sources were chained with `?:`, which only falls through on a
     * *missing* source — and on API 31+ the authoritative one
     * (`NetworkCapabilities.transportInfo` read synchronously) is never
     * missing, it is *redacted*: measured on the M10 it reported
     * `<unknown ssid>` / `02:00:00:00:00:00` with every permission granted,
     * while `getConnectionInfo()` beside it named the network correctly. So
     * the name has to be merged across the sources, not picked from the first
     * one that answered.
     */
    @Test
    fun `a redacted first source does not cost the name the second one has`() {
        val redacted = WifiState(
            ssid = null,
            bssid = null,
            rssiDbm = -55,
            linkSpeedMbps = 150,
            frequencyMhz = 2437,
            standard = "Wi-Fi 4",
        )
        val named = WifiState(
            ssid = "Hystax",
            bssid = "18:fd:74:15:3c:fe",
            rssiDbm = -49,
            linkSpeedMbps = 150,
        )

        val merged = WifiSource.mergeStates(listOf(redacted, named))!!

        assertEquals("Hystax", merged.ssid)
        assertEquals("18:fd:74:15:3c:fe", merged.bssid)
        // The radio numbers still come from the authoritative source.
        assertEquals(-55, merged.rssiDbm)
        assertEquals("Wi-Fi 4", merged.standard)
    }

    @Test
    fun `the first source keeps the name when it has one`() {
        val named = WifiState(ssid = "Primary", bssid = "aa:bb:cc:dd:ee:ff", rssiDbm = -55, linkSpeedMbps = 150)
        val other = WifiState(ssid = "Stale", bssid = "11:22:33:44:55:66", rssiDbm = -40, linkSpeedMbps = 10)

        val merged = WifiSource.mergeStates(listOf(named, other))!!

        assertEquals("Primary", merged.ssid)
        assertEquals("aa:bb:cc:dd:ee:ff", merged.bssid)
    }

    @Test
    fun `no source at all is still no Wi-Fi state, not an empty one`() {
        assertNull(WifiSource.mergeStates(emptyList()))
    }

    @Test
    fun `every source redacted stays null rather than inventing a name`() {
        val redacted = WifiState(ssid = null, bssid = null, rssiDbm = -55, linkSpeedMbps = 150)

        val merged = WifiSource.mergeStates(listOf(redacted, redacted))!!

        assertNull(merged.ssid)
        assertNull(merged.bssid)
    }

    @Test
    fun `cleanSsid strips the quotes the platform wraps around it`() {
        assertEquals("HomeNet", WifiSource.cleanSsid("\"HomeNet\""))
        assertEquals("HomeNet", WifiSource.cleanSsid("HomeNet"))
    }

    @Test
    fun `the missing-permission placeholder SSID becomes null, not a fake name`() {
        assertNull(WifiSource.cleanSsid(WifiSource.UNKNOWN_SSID))
        assertNull(WifiSource.cleanSsid("\"${WifiSource.UNKNOWN_SSID}\""))
    }

    @Test
    fun `an empty or absent SSID is null`() {
        assertNull(WifiSource.cleanSsid(null))
        assertNull(WifiSource.cleanSsid(""))
        assertNull(WifiSource.cleanSsid("   "))
        assertNull(WifiSource.cleanSsid("\"\""))
    }

    @Test
    fun `the anonymised BSSID becomes null`() {
        assertNull(WifiSource.cleanBssid(WifiSource.ANONYMISED_BSSID))
        assertNull(WifiSource.cleanBssid("00:00:00:00:00:00"))
        assertNull(WifiSource.cleanBssid(null))
        assertEquals("aa:bb:cc:dd:ee:ff", WifiSource.cleanBssid(" aa:bb:cc:dd:ee:ff "))
    }

    @Test
    fun `an out-of-range RSSI becomes the unknown sentinel`() {
        assertEquals(WifiState.UNKNOWN_RSSI, WifiSource.sanitizeRssi(0))
        assertEquals(WifiState.UNKNOWN_RSSI, WifiSource.sanitizeRssi(-127))
        assertEquals(WifiState.UNKNOWN_RSSI, WifiSource.sanitizeRssi(-200))
        assertEquals(-55, WifiSource.sanitizeRssi(-55))
    }

    @Test
    fun `wifi standards map to the marketing generation names`() {
        assertEquals("Wi-Fi 4", WifiSource.standardName(4, 2412))
        assertEquals("Wi-Fi 5", WifiSource.standardName(5, 5180))
        assertEquals("Wi-Fi 6", WifiSource.standardName(6, 5180))
        assertEquals("Wi-Fi 7", WifiSource.standardName(8, 5180))
        assertEquals("Legacy", WifiSource.standardName(1, 2412))
    }

    @Test
    fun `11ax in the 6 GHz band is Wi-Fi 6E`() {
        assertEquals("Wi-Fi 6E", WifiSource.standardName(6, 6115))
        assertEquals("Wi-Fi 6", WifiSource.standardName(6, 2412))
    }

    @Test
    fun `an unknown standard is reported as nothing rather than guessed`() {
        assertNull(WifiSource.standardName(0, 2412))
        assertNull(WifiSource.standardName(99, 2412))
    }

    @Test
    fun `current never throws when nothing is connected`() {
        val source = WifiSource(context)
        source.current()
        source.enabled()
    }

    @Test
    fun `cellular network types map to readable names`() {
        assertEquals("LTE", CellularSource.networkTypeName(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals("5G NR", CellularSource.networkTypeName(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals("EDGE", CellularSource.networkTypeName(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals("HSPA+", CellularSource.networkTypeName(TelephonyManager.NETWORK_TYPE_HSPAP))
    }

    @Test
    fun `an unknown cellular type is not invented`() {
        assertNull(CellularSource.networkTypeName(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        assertNull(CellularSource.networkTypeName(-7))
    }

    @Test
    fun `cellular current never throws without a SIM`() {
        CellularSource(context).current()
    }
}
