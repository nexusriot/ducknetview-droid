package com.vlad.ducknetview.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.model.WifiState
import com.vlad.ducknetview.ui.screens.InterfacesScreen
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The location grant is what the platform gates Wi-Fi radio detail behind.
 * Without this note an ungranted device shows "not measured" forever and looks
 * broken, and the action to fix it had no caller at all until now.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class WifiPermissionNoteTest {

    @get:Rule
    val rule = createComposeRule()

    private fun wifiNetwork(rssi: Int) = NetworkRow(
        id = "wifi-1",
        ifaceName = "wlan0",
        transport = Transport.WIFI,
        up = true,
        isDefault = true,
        addresses = listOf("192.168.1.20"),
        wifi = WifiState(
            ssid = if (rssi == WifiState.UNKNOWN_RSSI) null else "home",
            bssid = null,
            rssiDbm = rssi,
            linkSpeedMbps = 300,
        ),
    )

    private fun show(state: UiState, actions: FakeActions) {
        rule.setContent { DuckTheme { InterfacesScreen(state, actions) } }
    }

    private fun stateWith(rssi: Int, granted: Boolean) = Fx.state().copy(
        snapshot = Fx.state().snapshot.copy(
            networks = listOf(wifiNetwork(rssi)),
            selectedNetworkId = "wifi-1",
        ),
        wifiPermissionGranted = granted,
    )

    @Test
    fun theNoteAppearsWhenSignalIsUnmeasuredAndPermissionIsMissing() {
        show(stateWith(WifiState.UNKNOWN_RSSI, granted = false), FakeActions())
        rule.onNodeWithTag("wifi:permission-note", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun grantingRaisesTheRequestAction() {
        val actions = FakeActions()
        show(stateWith(WifiState.UNKNOWN_RSSI, granted = false), actions)
        rule.onNodeWithTag("wifi:permission-grant", useUnmergedTree = true)
            .performScrollTo().performClick()
        assertEquals(1, actions.calls.count { it.first == "requestWifiPermission" })
    }

    @Test
    fun noNoteOnceThePermissionIsGranted() {
        show(stateWith(WifiState.UNKNOWN_RSSI, granted = true), FakeActions())
        rule.onNodeWithTag("wifi:permission-note", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun noNoteWhenTheRadioIsActuallyReporting() {
        show(stateWith(-55, granted = false), FakeActions())
        rule.onNodeWithTag("wifi:permission-note", useUnmergedTree = true).assertDoesNotExist()
    }
}
