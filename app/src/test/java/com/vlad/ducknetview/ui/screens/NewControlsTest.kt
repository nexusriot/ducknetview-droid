package com.vlad.ducknetview.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vlad.ducknetview.domain.diff.SnapshotDiff
import com.vlad.ducknetview.domain.firewall.AppPolicy
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.SecurityCounts
import com.vlad.ducknetview.ui.DiffView
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The controls this round added, each driven to the action it raises. The
 * project's recurring defect is a feature that renders and calls nothing, so
 * each test clicks and checks the call, not just the node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w900dp-h1200dp")
class NewControlsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun settings(state: UiState, actions: RecordingActions) =
        rule.setContent { DuckTheme { SettingsScreen(state = state, actions = actions) } }

    @Test
    fun aGlobalFirewallRuleIsAddedFromSettings() {
        val actions = RecordingActions()
        settings(screenState(), actions)
        rule.onNodeWithTag("settings:rule-add").performScrollTo().performTextInput("ads.example.com")
        rule.onNodeWithTag("settings:rule-add-btn").performScrollTo().performClick()
        assertEquals("addFirewallRule" to "|ads.example.com", actions.args.last())
    }

    @Test
    fun importsAndComparisonRaiseTheirPickers() {
        val actions = RecordingActions()
        settings(screenState(), actions)
        rule.onNodeWithTag("settings:blocklist-import").performScrollTo().performClick()
        rule.onNodeWithTag("settings:asn-import").performScrollTo().performClick()
        rule.onNodeWithTag("settings:compare").performScrollTo().performClick()
        assertTrue(actions.calls.containsAll(listOf("importBlocklist", "importAsn", "compareSnapshot")))
    }

    @Test
    fun startOnBootWithoutConsentSaysWhyItCannotWork() {
        val s = screenState().let { it.copy(settings = it.settings.copy(startOnBoot = true), vpnConsentGranted = false) }
        settings(s, RecordingActions())
        rule.onNodeWithTag("settings:boot-needs-consent").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theAutomationSwitchWritesItsSetting() {
        val actions = RecordingActions()
        settings(screenState(), actions)
        rule.onNodeWithTag("settings:automation").performScrollTo().performClick()
        assertTrue(actions.settings.automationEnabled)
    }

    @Test
    fun aViewIsSavedUnderTheTypedName() {
        val actions = RecordingActions()
        settings(screenState(), actions)
        rule.onNodeWithTag("settings:view-name").performScrollTo().performTextInput("vpn busiest")
        rule.onNodeWithTag("settings:view-save").performScrollTo().performClick()
        assertEquals("saveView" to "vpn busiest", actions.args.last())
    }

    @Test
    fun theMtuChipWritesTheSetting() {
        val actions = RecordingActions()
        settings(screenState(), actions)
        rule.onNodeWithTag("chip:mtu-9000").performScrollTo().performClick()
        assertEquals(9000, actions.settings.tunMtu)
    }

    @Test
    fun aConnectionCanBeResetAndItsHostBlockedForItsApp() {
        val actions = RecordingActions()
        val conn = screenConn().copy(resolvedHost = "api.example.com")
        rule.setContent {
            DuckTheme { ConnectionsScreen(screenState(conns = listOf(conn)), actions, twoPane = true) }
        }
        rule.onNodeWithTag("conn:${conn.key}").performClick()
        rule.onNodeWithTag("sheet:terminate").performClick()
        rule.onNodeWithTag("sheet:block-host").performClick()
        assertEquals("terminateConnection" to conn.key, actions.args[actions.args.size - 2])
        assertEquals("addFirewallRule" to "com.example.browser|api.example.com", actions.args.last())
    }

    @Test
    fun aClosingRowOffersNoFlowActionsOnceClosed() {
        val conn = screenConn(state = ConnState.CLOSED)
        rule.setContent {
            DuckTheme { ConnectionsScreen(screenState(conns = listOf(conn)), RecordingActions(), twoPane = true) }
        }
        rule.onNodeWithTag("conn:${conn.key}").performClick()
        rule.onNodeWithTag("sheet:terminate").assertDoesNotExist()
    }

    @Test
    fun cleartextAndOldTlsAreMarkedOnTheRow() {
        val conn = screenConn().copy(cleartext = "http", tls = "TLS 1.0", tlsDeprecated = true, state = ConnState.ESTABLISHED)
        rule.setContent {
            DuckTheme { ConnectionsScreen(screenState(conns = listOf(conn)), RecordingActions(), twoPane = true) }
        }
        rule.onNodeWithTag("conn:cleartext:${conn.key}", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("conn:tls:${conn.key}", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun anAppPolicyChipSendsTheWholePolicy() {
        val actions = RecordingActions()
        val app = screenApp()
        rule.setContent {
            DuckTheme { AppsScreen(screenState(apps = listOf(app)), actions, twoPane = true) }
        }
        rule.onNodeWithTag("app:${app.uid}").performClick()
        rule.onNodeWithTag("policy:metered").performScrollTo().performClick()
        assertEquals("setAppPolicy" to AppPolicy(app.uid, blockMetered = true), actions.args.last())
        rule.onNodeWithTag("budget:1 GB").performScrollTo().performClick()
        assertEquals("setAppBudget" to "com.example.browser|1000000000|false", actions.args.last())
    }

    @Test
    fun theDiffScreenSaysNothingChangedInWords() {
        val actions = RecordingActions()
        rule.setContent { DuckTheme { DiffScreen(DiffView("old.json", emptyDiff()), actions) } }
        rule.onNodeWithText("Nothing differs", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("diff:close").performClick()
        assertTrue("closeDiff" in actions.calls)
    }

    @Test
    fun theDiffScreenListsOnlySectionsWithChanges() {
        val d = emptyDiff().copy(hostsFirstContact = listOf("new.example.com"))
        rule.setContent { DuckTheme { DiffScreen(DiffView("old.json", d), RecordingActions()) } }
        rule.onNodeWithText("new.example.com").assertIsDisplayed()
        rule.onNodeWithText("Apps gone").assertDoesNotExist()
    }

    @Test
    fun overviewShowsCleartextOnlyWithCapture() {
        val snap = NetSnapshot(atMillis = SCREEN_NOW, security = SecurityCounts(cleartextConns = 2))
        rule.setContent {
            DuckTheme { OverviewScreen(screenState(snapshot = snap), RecordingActions()) }
        }
        rule.onNodeWithTag("tile:Cleartext").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun domainsEmptiedByTheSearchBlameTheSearch() {
        rule.setContent {
            DuckTheme {
                DomainsScreen(screenState(search = com.vlad.ducknetview.ui.SearchState("zzz")), RecordingActions())
            }
        }
        rule.onNodeWithTag(emptyTag(EMPTY_NO_MATCH)).assertIsDisplayed()
        rule.onNodeWithTag(emptyTag(EMPTY_DOMAINS)).assertDoesNotExist()
    }

    @Test
    fun eventsEmptiedByTheLevelFilterBlameTheFilter() {
        rule.setContent {
            DuckTheme {
                EventsScreen(
                    screenState(eventFilter = com.vlad.ducknetview.domain.model.EventLevelFilter.ALERTS),
                    RecordingActions(),
                )
            }
        }
        rule.onNodeWithTag(emptyTag(EMPTY_NO_MATCH)).assertIsDisplayed()
    }

    private fun emptyDiff() = SnapshotDiff(
        listenersGained = emptyList(),
        listenersLost = emptyList(),
        listenersMoved = emptyList(),
        appsAppeared = emptyList(),
        appsGone = emptyList(),
        hostsFirstContact = emptyList(),
        networksAppeared = emptyList(),
        networksGone = emptyList(),
    )
}
