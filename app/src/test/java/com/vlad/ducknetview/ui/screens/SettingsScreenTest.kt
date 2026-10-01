package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.ui.theme.DuckTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(state: UiState, actions: RecordingActions) {
        rule.setContent {
            DuckTheme { SettingsScreen(state = state, actions = actions) }
        }
    }

    @Test
    fun vpnSwitchIsDisabledWhenAnotherVpnHoldsTheSlot() {
        show(screenState(vpnAvailable = false), RecordingActions())
        rule.onNodeWithTag("settings:vpn-switch").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("only one VPN", substring = true).assertExists()
    }

    @Test
    fun vpnSwitchIsEnabledAndStartsCapture() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:vpn-switch").performScrollTo().assertIsEnabled()
        rule.onNodeWithTag("settings:vpn-switch").performClick()
        assertEquals(1, actions.startVpnCount)
    }

    @Test
    fun vpnSwitchStopsCaptureWhenRunning() {
        val actions = RecordingActions()
        show(screenState(vpnRunning = true), actions)
        rule.onNodeWithTag("settings:vpn-switch").performScrollTo().performClick()
        assertEquals(1, actions.stopVpnCount)
    }

    @Test
    fun intervalChipRaisesSetInterval() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("chip:interval-5").performScrollTo().performClick()
        assertEquals(5, actions.lastInterval)
    }

    @Test
    fun pauseAndUnitSwitchesRaiseTheirToggles() {
        val actions = RecordingActions()
        show(screenState(settings = AppSettings(rateUnit = RateUnit.BYTES)), actions)
        rule.onNodeWithTag("settings:pause").performScrollTo().performClick()
        rule.onNodeWithTag("settings:units").performScrollTo().performClick()
        assertTrue(actions.calls.containsAll(listOf("togglePause", "toggleRateUnit")))
    }

    @Test
    fun alertFieldParsesANumber() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:alertAppBps").performScrollTo().performTextReplacement("500000")
        assertEquals(500_000L, actions.settings.alertAppBps)
    }

    @Test
    fun clearingAnAlertFieldParsesAsZero() {
        val actions = RecordingActions(AppSettings(alertConnBps = 1234))
        show(screenState(settings = AppSettings(alertConnBps = 1234)), actions)
        rule.onNodeWithTag("settings:alertConnBps").performScrollTo().performTextClearance()
        assertEquals(0L, actions.settings.alertConnBps)
    }

    @Test
    fun garbageInAnAlertFieldParsesAsZeroWithoutCrashing() {
        val actions = RecordingActions(AppSettings(alertRttMs = 250))
        show(screenState(settings = AppSettings(alertRttMs = 250)), actions)
        rule.onNodeWithTag("settings:alertRttMs").performScrollTo().performTextReplacement("not a number")
        assertEquals(0, actions.settings.alertRttMs)
    }

    @Test
    fun growthAndFanoutFieldsParse() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:alertConnGrowthPolls").performScrollTo().performTextReplacement("6")
        rule.onNodeWithTag("settings:alertFanoutHosts").performScrollTo().performTextReplacement("12")
        assertEquals(6, actions.settings.alertConnGrowthPolls)
        assertEquals(12, actions.settings.alertFanoutHosts)
    }

    @Test
    fun webhookFieldUpdatesSettings() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:webhook").performScrollTo()
            .performTextReplacement("https://example.invalid/hook")
        assertEquals("https://example.invalid/hook", actions.settings.webhookUrl)
    }

    @Test
    fun broadcastSwitchUpdatesSettings() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:broadcast").performScrollTo().performClick()
        assertTrue(actions.settings.broadcastOnAlert)
    }

    @Test
    fun watchlistEntriesRenderAndDeleteRaisesRemove() {
        val actions = RecordingActions()
        show(screenState(settings = AppSettings(watchlist = listOf("10.0.0.0/8", "re:^cdn"))), actions)
        rule.onNodeWithTag("watchlist:10.0.0.0/8").performScrollTo().assertExists()
        rule.onNodeWithTag("watchlist:del:re:^cdn").performScrollTo().performClick()
        assertEquals("re:^cdn", actions.lastWatchlistRemove)
    }

    @Test
    fun watchlistAddFieldRaisesAddWatchlistEntry() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:watchlist-add").performScrollTo().performTextInput("1.1.1.1")
        rule.onNodeWithTag("settings:watchlist-add-btn").performScrollTo().performClick()
        assertEquals("1.1.1.1", actions.lastWatchlistAdd)
    }

    /**
     * A watchlist rule that can never match must say so where it is shown.
     *
     * Without this an entry with a typo sits in the list looking exactly like a
     * working one, and the alert it was added for simply never fires — the
     * silent failure the search bar already refuses to make for the very same
     * class of mistake.
     */
    @Test
    fun anUnusableWatchlistEntryIsMarkedAsSuch() {
        show(
            screenState(settings = AppSettings(watchlist = listOf("10.0.0.0/8", "[unclosed"))),
            RecordingActions(),
        )
        rule.onNodeWithTag("watchlist:problem:[unclosed").performScrollTo().assertExists()
        // The entry that is fine must not be decorated with a warning.
        rule.onNodeWithTag("watchlist:problem:10.0.0.0/8").assertDoesNotExist()
    }

    @Test
    fun watchlistHelpExplainsTheAcceptedForms() {
        show(screenState(), RecordingActions())
        rule.onNodeWithText("CIDR", substring = true).assertExists()
    }

    @Test
    fun latencyTargetIsAddedThroughUpdateSettings() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:latency-add").performScrollTo().performTextInput("1.0.0.1")
        rule.onNodeWithTag("settings:latency-add-btn").performScrollTo().performClick()
        assertEquals(listOf("1.0.0.1"), actions.settings.latencyTargets)
    }

    @Test
    fun latencyTargetIsRemovedThroughUpdateSettings() {
        val actions = RecordingActions(AppSettings(latencyTargets = listOf("1.0.0.1")))
        show(screenState(settings = AppSettings(latencyTargets = listOf("1.0.0.1"))), actions)
        rule.onNodeWithTag("latency:del:1.0.0.1").performScrollTo().performClick()
        assertTrue(actions.settings.latencyTargets.isEmpty())
    }

    @Test
    fun latencyHelpMentionsTheGatewayDefault() {
        show(screenState(), RecordingActions())
        rule.onNodeWithText("gateway", substring = true).assertExists()
    }

    @Test
    fun metricsSwitchAndPortUpdateSettings() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:metrics-enabled").performScrollTo().performClick()
        rule.onNodeWithTag("settings:metrics-port").performScrollTo().performTextReplacement("9999")
        assertTrue(actions.settings.metricsEnabled)
        assertEquals(9999, actions.settings.metricsPort)
    }

    @Test
    fun exportButtonRaisesExportSnapshotJson() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:export-json").performScrollTo().performClick()
        assertEquals(1, actions.exportJsonCount)
    }

    @Test
    fun privacyCardStatesEverythingStaysOnDevice() {
        show(screenState(), RecordingActions())
        rule.onNodeWithTag("settings:privacy").performScrollTo().assertExists()
        rule.onNodeWithText("stays on this device", substring = true).assertExists()
        rule.onNodeWithText("Packet contents are never", substring = true).assertExists()
    }
}
