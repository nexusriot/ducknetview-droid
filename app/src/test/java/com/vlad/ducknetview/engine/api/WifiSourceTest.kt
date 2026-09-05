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
