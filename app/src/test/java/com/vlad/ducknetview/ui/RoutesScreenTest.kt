package com.vlad.ducknetview.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vlad.ducknetview.ui.screens.RoutesScreen
import com.vlad.ducknetview.ui.screens.TAG_DETAIL_SHEET
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class RoutesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = FakeActions()

    private fun show(state: UiState, twoPane: Boolean = false) {
        rule.setContent {
            DuckTheme { RoutesScreen(state = state, actions = actions, twoPane = twoPane) }
        }
    }

    /** Renders against a state holder so a test can push a fresh snapshot in. */
    private fun showLive(initial: UiState, twoPane: Boolean = true): MutableState<UiState> {
        val holder = mutableStateOf(initial)
        rule.setContent {
            DuckTheme { RoutesScreen(state = holder.value, actions = actions, twoPane = twoPane) }
        }
        return holder
    }

    @Test
    fun rendersOneCardPerNetworkIncludingNoisyOnes() {
        show(
            Fx.state(
                Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular(), Fx.loopback())),
            ),
        )
        rule.onNodeWithTag("route:net-wifi").assertExists()
        rule.onNodeWithTag("route:net-cell").assertExists()
        rule.onNodeWithTag("route:net-lo").assertExists()
        rule.onNodeWithTag("card:wlan0 · wifi").assertExists()
        rule.onNodeWithTag("card:rmnet0 · cellular").assertExists()
    }

    @Test
    fun cardCountMatchesNetworkCount() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular()))))
        val cards = rule.onAllNodesWithTag("route:net-wifi").fetchSemanticsNodes().size +
            rule.onAllNodesWithTag("route:net-cell").fetchSemanticsNodes().size
        assertEquals(2, cards)
        rule.onNodeWithTag("route:net-lo").assertDoesNotExist()
    }

    @Test
    fun arpNoteIsAlwaysRendered() {
        show(Fx.state(Fx.snapshot()))
        rule.onNodeWithTag("routes:arpnote").assertExists()
        rule.onNodeWithText("neighbour table", substring = true).assertExists()
    }

    @Test
    fun arpNoteIsRenderedEvenWithoutNetworks() {
        show(Fx.state(Fx.barrenSnapshot()))
        rule.onNodeWithTag("card:What this screen cannot show").assertExists()
        rule.onNodeWithTag("routes:arpnote").assertExists()
    }

    @Test
    fun cadenceNoteExplainsTheCallbackRefresh() {
        show(Fx.state(Fx.snapshot()))
        rule.onNodeWithTag("routes:cadencenote").assertExists()
        rule.onNodeWithText("network change", substring = true).assertExists()
    }

    @Test
    fun routeLinesShowDestinationGatewayAndDevice() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi()))))
        rule.onNodeWithTag("route:net-wifi:0.0.0.0/0").assertExists()
        rule.onNodeWithTag("route:net-wifi:192.168.1.0/24").assertExists()
        rule.onNodeWithText("via 192.168.1.1").assertExists()
        rule.onNodeWithText("via -").assertExists()
        assertEquals(2, rule.onAllNodesWithText("dev wlan0").fetchSemanticsNodes().size)
        // One badge on the card header, one on the default route line.
        assertEquals(2, rule.onAllNodesWithTag("badge:default").fetchSemanticsNodes().size)
    }

    @Test
    fun dnsAndPrivateDnsAreListed() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi()))))
        rule.onNodeWithText("8.8.8.8, 1.1.1.1").assertExists()
        rule.onNodeWithText("dns.duckpond.net").assertExists()
    }

    @Test
    fun networkWithoutRoutesSaysSo() {
        show(
            Fx.state(
                Fx.snapshot(networks = listOf(Fx.wifi().copy(routes = emptyList()))),
            ),
        )
        rule.onNodeWithText("no routes reported for this network").assertExists()
    }

    @Test
    fun onePaneRendersEveryTableInlineWithNoDetailPane() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular()))))
        rule.onNodeWithTag("route:net-wifi:0.0.0.0/0").assertExists()
        rule.onNodeWithTag("route:net-cell:0.0.0.0/0").assertExists()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a network to see its routes and DNS.").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneShowsThePlaceholderUntilANetworkIsPicked() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular()))), twoPane = true)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a network to see its routes and DNS.").assertExists()
        rule.onNodeWithTag("route:net-wifi:0.0.0.0/0").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneRendersTheInlinePaneForTheSelectedNetworkOnly() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular()))), twoPane = true)
        rule.onNodeWithTag("route:net-wifi").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        rule.onNodeWithTag("route:net-wifi:0.0.0.0/0").assertExists()
        rule.onNodeWithTag("route:net-cell:0.0.0.0/0").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneListsEveryNetworkOnTheLeft() {
        show(
            Fx.state(Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular(), Fx.loopback()))),
            twoPane = true,
        )
        rule.onNodeWithTag("route:net-wifi").assertExists()
        rule.onNodeWithTag("route:net-cell").assertExists()
        rule.onNodeWithTag("route:net-lo").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun arpNoteStaysVisibleInTwoPaneToo() {
        show(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi()))), twoPane = true)
        rule.onNodeWithTag("routes:arpnote").assertExists()
        rule.onNodeWithTag("routes:cadencenote").assertExists()
        rule.onNodeWithText("neighbour table", substring = true).assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailFollowsARouteAndDnsChangeOnTheSelectedNetwork() {
        val holder = showLive(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi()))))
        rule.onNodeWithTag("route:net-wifi").performClick()
        rule.onNodeWithText("8.8.8.8, 1.1.1.1").assertExists()

        rule.runOnIdle {
            holder.value = Fx.state(
                Fx.snapshot(
                    networks = listOf(Fx.wifi().copy(dnsServers = listOf("9.9.9.9"))),
                ),
            )
        }

        rule.onNodeWithText("9.9.9.9").assertExists()
        rule.onNodeWithText("8.8.8.8, 1.1.1.1").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneFallsBackToThePlaceholderWhenTheNetworkGoesAway() {
        val holder = showLive(Fx.state(Fx.snapshot(networks = listOf(Fx.wifi(), Fx.cellular()))))
        rule.onNodeWithTag("route:net-wifi").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()

        rule.runOnIdle {
            holder.value = Fx.state(Fx.snapshot(networks = listOf(Fx.cellular())))
        }

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a network to see its routes and DNS.").assertExists()
        rule.onNodeWithTag("routes:arpnote").assertExists()
    }
}
