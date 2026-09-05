package com.vlad.ducknetview.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.ui.screens.OverviewScreen
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class OverviewScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = FakeActions()

    private fun show(state: UiState) {
        rule.setContent { DuckTheme { OverviewScreen(state = state, actions = actions) } }
    }

    @Test
    fun churnCardIsHiddenInApiMode() {
        show(Fx.apiState())
        rule.onNodeWithTag("card:Connection churn").assertDoesNotExist()
    }

    @Test
    fun churnCardIsShownInVpnMode() {
        show(Fx.vpnState())
        rule.onNodeWithTag("card:Connection churn").assertExists()
    }

    @Test
    fun churnCardShowsTheDelta() {
        show(Fx.vpnState())
        rule.onNodeWithTag("churn:delta").assertTextEquals("Δ +4/-2")
    }

    @Test
    fun topTalkersAreHiddenWhenEmpty() {
        show(Fx.apiState())
        rule.onNodeWithTag("card:Top talkers").assertDoesNotExist()
    }

    @Test
    fun topTalkersAreShownWhenPresent() {
        show(Fx.vpnState())
        rule.onNodeWithTag("card:Top talkers").assertExists()
        rule.onNodeWithTag("talker:app:uid:10120").assertExists()
        rule.onNodeWithTag("talker:host:203.0.113.20").assertExists()
    }

    @Test
    fun pausedMarkerIsHiddenWhenRunning() {
        show(Fx.apiState())
        rule.onNodeWithText("⏸ PAUSED").assertDoesNotExist()
    }

    @Test
    fun pausedMarkerIsShownWhenPaused() {
        show(Fx.state(Fx.snapshot(paused = true)))
        rule.onNodeWithText("⏸ PAUSED").assertExists()
    }

    @Test
    fun headerShowsDeviceUptimeEngineAndInterval() {
        show(Fx.apiState())
        rule.onNodeWithTag("overview:device").assertTextEquals("Pixel Duck")
        rule.onNodeWithTag("overview:uptime").assertTextEquals("up 3h 25m")
        rule.onNodeWithText("API mode").assertExists()
        rule.onNodeWithText("every 2s").assertExists()
    }

    @Test
    fun headerShowsTheVpnBadgeInCaptureMode() {
        show(Fx.vpnState())
        rule.onNodeWithText("VPN capture").assertExists()
        rule.onNodeWithText("API mode").assertDoesNotExist()
    }

    @Test
    fun securityTilesShowTheirCounts() {
        show(Fx.apiState())
        rule.onNodeWithTag("tile:Exposed").assertTextContains("2")
        rule.onNodeWithTag("tile:Public conns").assertTextContains("3")
        rule.onNodeWithTag("tile:Watchlist").assertTextContains("1")
        rule.onNodeWithTag("tile:Off-baseline").assertTextContains("4")
    }

    @Test
    fun exposedTileNavigatesToServices() {
        show(Fx.apiState())
        rule.onNodeWithTag("tile:Exposed").performClick()
        assertEquals(Tab.SERVICES, actions.lastArg("setTab"))
    }

    @Test
    fun publicConnsTileNavigatesToConnections() {
        show(Fx.apiState())
        rule.onNodeWithTag("tile:Public conns").performClick()
        assertEquals(Tab.CONNECTIONS, actions.lastArg("setTab"))
    }

    @Test
    fun watchlistTileNavigatesToConnections() {
        show(Fx.apiState())
        rule.onNodeWithTag("tile:Watchlist").performClick()
        assertEquals(Tab.CONNECTIONS, actions.lastArg("setTab"))
    }

    @Test
    fun offBaselineTileNavigatesToServices() {
        show(Fx.apiState())
        rule.onNodeWithTag("tile:Off-baseline").performClick()
        assertEquals(Tab.SERVICES, actions.lastArg("setTab"))
    }

    @Test
    fun apiModeOffersTheCaptureCta() {
        show(Fx.apiState())
        rule.onNodeWithTag("overview:enablecapture").performScrollTo().performClick()
        assertEquals(1, actions.count("startVpn"))
    }

    @Test
    fun vpnModeHidesTheCaptureCta() {
        show(Fx.vpnState())
        rule.onNodeWithTag("card:Capture is off").assertDoesNotExist()
        rule.onNodeWithTag("overview:enablecapture").assertDoesNotExist()
    }

    @Test
    fun gatewayCardShowsGatewayAndDns() {
        show(Fx.apiState())
        rule.onNodeWithTag("card:Gateway & DNS").assertExists()
        rule.onNodeWithText("192.168.1.1").assertExists()
        rule.onNodeWithText("8.8.8.8, 1.1.1.1").assertExists()
        rule.onNodeWithText("dns.duckpond.net").assertExists()
    }

    @Test
    fun gatewayCardIsHiddenWithoutANamedNetwork() {
        show(Fx.state(Fx.barrenSnapshot()))
        rule.onNodeWithTag("card:Gateway & DNS").assertDoesNotExist()
    }

    @Test
    fun latencyCardListsEachTarget() {
        show(Fx.apiState())
        rule.onNodeWithTag("card:Path latency").assertExists()
        rule.onNodeWithTag("latency:192.168.1.1").assertExists()
        rule.onNodeWithTag("latency:1.1.1.1").assertExists()
        rule.onNodeWithText("unreachable").assertExists()
        rule.onNodeWithText("no route to host").assertExists()
    }

    @Test
    fun latencyCardIsHiddenWithoutSamples() {
        show(Fx.state(Fx.snapshot(latencySamples = emptyList())))
        rule.onNodeWithTag("card:Path latency").assertDoesNotExist()
    }

    @Test
    fun throughputCardIsHiddenWhenNothingHasBeenMeasured() {
        show(Fx.state(Fx.barrenSnapshot()))
        rule.onNodeWithTag("card:Throughput").assertDoesNotExist()
    }

    @Test
    fun throughputCardIsShownOnceThereAreCounters() {
        show(Fx.apiState())
        rule.onNodeWithTag("card:Throughput").assertExists()
    }

    @Test
    fun externalIpCardRefreshRaisesTheAction() {
        show(Fx.apiState())
        rule.onNodeWithTag("overview:externalip").assertExists()
        rule.onNodeWithTag("externalip:refresh").performScrollTo().performClick()
        assertEquals(1, actions.count("refreshExternalIp"))
    }

    @Test
    fun externalIpCardIsHiddenWhenUnknown() {
        show(Fx.state(Fx.snapshot(externalIp = null)))
        rule.onNodeWithTag("card:External IP").assertDoesNotExist()
    }

    @Test
    fun securityCardSurvivesAnEmptySnapshot() {
        show(Fx.state(Fx.barrenSnapshot()))
        rule.onNodeWithTag("card:Security").assertExists()
        rule.onNodeWithTag("tile:Exposed").assertTextContains("0")
    }

    /** The churn panel follows the engine that produced the snapshot, not the
     *  capability flags, so a stale API capability set cannot hide it. */
    @Test
    fun engineModeDrivesTheChurnCardNotTheCapabilities() {
        show(Fx.state(Fx.snapshot(engine = EngineMode.VPN), caps = Capabilities.API))
        rule.onNodeWithTag("card:Connection churn").assertExists()
        rule.onNodeWithTag("card:Capture is off").assertExists()
    }
}
