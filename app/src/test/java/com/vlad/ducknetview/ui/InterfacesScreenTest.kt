package com.vlad.ducknetview.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.ui.screens.InterfacesScreen
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class InterfacesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = FakeActions()

    private fun show(state: UiState, twoPane: Boolean = false) {
        rule.setContent {
            DuckTheme {
                InterfacesScreen(state = state, actions = actions, twoPane = twoPane)
            }
        }
    }

    private fun noisy(hideNoise: Boolean) = Fx.state(
        snapshot = Fx.snapshot(networks = listOf(Fx.wifi(), Fx.loopback())),
        settings = AppSettings(hideNoise = hideNoise),
    )

    @Test
    fun hideNoiseRemovesTheLoopbackRow() {
        show(noisy(hideNoise = true))
        rule.onNodeWithTag("iface:net-wifi").assertExists()
        rule.onNodeWithTag("iface:net-lo").assertDoesNotExist()
    }

    @Test
    fun turningHideNoiseOffShowsTheLoopbackRow() {
        show(noisy(hideNoise = false))
        rule.onNodeWithTag("iface:net-lo").assertExists()
    }

    @Test
    fun countRowReportsShownOfTotal() {
        show(noisy(hideNoise = true))
        rule.onNodeWithTag("interfaces:count").assertExists()
        rule.onNodeWithText("1 of 2 links").assertExists()
    }

    @Test
    fun hideNoiseChipRaisesTheToggle() {
        show(noisy(hideNoise = true))
        rule.onNodeWithTag("chip:Hide noise").performClick()
        assertEquals(1, actions.count("toggleHideNoise"))
    }

    @Test
    fun emptyStateAppearsAndNamesTheFilter() {
        show(
            Fx.state(
                snapshot = Fx.snapshot(networks = listOf(Fx.loopback())),
                settings = AppSettings(hideNoise = true),
            ),
        )
        rule.onNodeWithTag("empty:No links").assertExists()
        rule.onNodeWithText("Hide-noise is on", substring = true).assertExists()
    }

    @Test
    fun emptyStateActionTurnsTheFilterOff() {
        show(
            Fx.state(
                snapshot = Fx.snapshot(networks = listOf(Fx.loopback())),
                settings = AppSettings(hideNoise = true),
            ),
        )
        rule.onNodeWithTag("empty:action").performClick()
        assertEquals(1, actions.count("toggleHideNoise"))
    }

    @Test
    fun emptyStateWithNoNetworksOffersNoFilterAction() {
        show(Fx.state(Fx.barrenSnapshot()))
        rule.onNodeWithTag("empty:No links").assertExists()
        rule.onNodeWithTag("empty:action").assertDoesNotExist()
    }

    @Test
    fun wifiBlockRendersTheRatingAndQuality() {
        show(noisy(hideNoise = true))
        rule.onNodeWithTag("card:Wi-Fi").assertExists()
        rule.onNodeWithText("-55 dBm · good").assertExists()
        rule.onNodeWithText("90%").assertExists()
        rule.onNodeWithText("5 GHz").assertExists()
        rule.onNodeWithText("Wi-Fi 6").assertExists()
    }

    @Test
    fun cellularBlockRendersTypeAndOperator() {
        show(
            Fx.state(
                snapshot = Fx.snapshot(
                    networks = listOf(Fx.cellular()),
                    selectedNetworkId = "net-cell",
                ),
            ),
        )
        rule.onNodeWithTag("card:Cellular").assertExists()
        rule.onNodeWithText("LTE").assertExists()
        rule.onNodeWithText("DuckMobile").assertExists()
        rule.onNodeWithText("3/4").assertExists()
    }

    @Test
    fun tappingARowSelectsThatNetwork() {
        show(noisy(hideNoise = false))
        rule.onNodeWithTag("iface:net-lo").performClick()
        assertEquals("net-lo", actions.lastArg("selectNetwork"))
    }

    @Test
    fun longPressCopiesATabSeparatedSummary() {
        show(noisy(hideNoise = true))
        rule.onNodeWithTag("iface:net-wifi").performTouchInput { longClick() }
        @Suppress("UNCHECKED_CAST")
        val arg = actions.lastArg("copyText") as Pair<String, String>
        assertEquals("interface", arg.first)
        assertTrue(arg.second.contains("\t"))
        assertTrue(arg.second.startsWith("wlan0\twifi\tup\t"))
        assertTrue(arg.second.contains("192.168.1.1"))
    }

    @Test
    fun selectedNetworkDrivesTheDetailPane() {
        show(
            Fx.state(
                snapshot = Fx.snapshot(
                    networks = listOf(Fx.wifi(), Fx.cellular()),
                    selectedNetworkId = "net-cell",
                ),
            ),
        )
        rule.onNodeWithTag("iface:detail").assertExists()
        rule.onNodeWithTag("card:rmnet0").assertExists()
        rule.onNodeWithTag("card:Wi-Fi").assertDoesNotExist()
    }

    @Test
    fun twoPaneStillRendersListAndDetail() {
        show(noisy(hideNoise = true), twoPane = true)
        rule.onNodeWithTag("iface:net-wifi").assertExists()
        rule.onNodeWithTag("iface:detail").assertExists()
    }

    @Test
    fun detailShowsAddressesAndMtu() {
        show(noisy(hideNoise = true))
        rule.onNodeWithText("192.168.1.42/24, fe80::1/64").assertExists()
        rule.onNodeWithText("1500").assertExists()
        rule.onNodeWithText("validated").assertExists()
        rule.onNodeWithText("unmetered").assertExists()
    }
}
