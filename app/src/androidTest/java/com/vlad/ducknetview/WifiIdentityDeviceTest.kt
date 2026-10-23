package com.vlad.ducknetview

import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.net.NetworkTrust
import com.vlad.ducknetview.engine.api.WifiSource
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The name of the Wi-Fi network this device is on, read the way the app reads
 * it.
 *
 * This cannot be a JVM test. Redaction is the platform deciding what a *caller*
 * may see, and Robolectric's shadow returns whatever the test put in it — so
 * the suite could (and did) pass while the shipped app could not name a single
 * network. The fault it guards: `WifiSource` chained its two sources with `?:`,
 * which falls through on a missing source and not on a redacted one, and the
 * source it asked first is the one the platform always redacts.
 *
 * It is scoped tightly on purpose. Nothing here asserts *which* network this
 * is — only that a device which is on a named Wi-Fi link, with the permissions
 * that entitle the app to the name, is told the name.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WifiIdentityDeviceTest {

    // The SSID is disclosed to a foreground app, so an Activity has to be up:
    // without one this class would skip on its own assumption and be reported
    // exactly like a pass (DESIGN §8b).
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun entitledToTheName(): Boolean {
        val locationOn = context.getSystemService(LocationManager::class.java)?.isLocationEnabled
        return granted(android.Manifest.permission.ACCESS_FINE_LOCATION) && locationOn == true
    }

    @Test
    fun t01_aWifiLinkIsNamed() {
        rule.waitForIdle()
        val wifi = WifiSource(context).current()
        assumeTrue("this device is not on Wi-Fi", wifi != null)
        assumeTrue(
            "the app is not entitled to the name on this device",
            entitledToTheName(),
        )

        assertNotNull(
            "the app is on a Wi-Fi link and holds the permission for its name, and " +
                "still could not read it — the platform redacts the source asked first, " +
                "so a name has to be merged across sources rather than taken from one",
            wifi!!.ssid,
        )
    }

    /**
     * Network trust is keyed on the SSID, so a nameless link is not a cosmetic
     * gap: it silently removes the whole feature. `keyOf` returns null, the
     * Links screen renders no trust buttons, and nothing the user marks can be
     * remembered — with no message anywhere saying so.
     */
    @Test
    fun t02_theWifiLinkCarriesATrustKey() {
        rule.waitForIdle()
        val app = context.applicationContext as DuckApp
        rule.waitUntil(20_000) {
            app.deps.engine.snapshot.value.networks.any { it.transport == Transport.WIFI }
        }
        val row = app.deps.engine.snapshot.value.networks
            .first { it.transport == Transport.WIFI }
        assumeTrue(
            "the app is not entitled to the name on this device",
            entitledToTheName(),
        )

        assertNotNull(
            "a Wi-Fi link with no trust key cannot be marked trusted or untrusted at all",
            NetworkTrust.keyOf(row),
        )
    }

    /** A redacted placeholder must never reach the UI as if it were a name. */
    @Test
    fun t03_thePlaceholderNameIsNeverShown() {
        rule.waitForIdle()
        val wifi = WifiSource(context).current()
        assumeTrue("this device is not on Wi-Fi", wifi != null)

        assertNull(
            "the placeholder SSID reached the UI as a name",
            wifi!!.ssid?.takeIf { it == WifiSource.UNKNOWN_SSID },
        )
        assertNull(
            "the anonymised BSSID reached the UI as an address",
            wifi.bssid?.takeIf { it == WifiSource.ANONYMISED_BSSID },
        )
    }
}
