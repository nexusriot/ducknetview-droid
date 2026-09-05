package com.vlad.ducknetview.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vlad.ducknetview.ui.components.AdaptiveScaffold
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class AdaptiveScaffoldTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = FakeActions()
    private val tabs = mutableListOf<Tab>()

    private fun show(
        widthDp: Int,
        state: UiState = Fx.apiState(),
        current: Tab = Tab.OVERVIEW,
    ) {
        rule.setContent {
            DuckTheme {
                AdaptiveScaffold(
                    state = state,
                    actions = actions,
                    current = current,
                    onTab = { tabs += it },
                    widthDp = widthDp,
                ) {
                    Text("body", modifier = Modifier.testTag("body"))
                }
            }
        }
    }

    @Test
    fun expandedWidthUsesANavigationRail() {
        show(widthDp = 840)
        rule.onNodeWithTag("nav:rail").assertExists()
    }

    @Test
    fun expandedWidthHasNoBottomBar() {
        show(widthDp = 840)
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
    }

    @Test
    fun compactWidthUsesABottomBar() {
        show(widthDp = 411)
        rule.onNodeWithTag("nav:bar").assertExists()
    }

    @Test
    fun compactWidthHasNoRail() {
        show(widthDp = 411)
        rule.onNodeWithTag("nav:rail").assertDoesNotExist()
    }

    @Test
    fun allSevenDestinationsArePresentOnCompact() {
        show(widthDp = 411)
        Tab.entries.forEach { tab ->
            rule.onNodeWithTag("nav:${tab.route}").assertExists()
        }
    }

    @Test
    fun allSevenDestinationsArePresentOnExpanded() {
        show(widthDp = 840)
        Tab.entries.forEach { tab ->
            rule.onNodeWithTag("nav:${tab.route}").assertExists()
        }
    }

    @Test
    fun contentIsRendered() {
        show(widthDp = 411)
        rule.onNodeWithTag("body").assertExists()
    }

    @Test
    fun tappingADestinationRaisesOnTab() {
        show(widthDp = 411)
        rule.onNodeWithTag("nav:routes").performClick()
        assertEquals(listOf(Tab.ROUTES), tabs)
    }

    @Test
    fun tappingADestinationOnTheRailRaisesOnTab() {
        show(widthDp = 840)
        rule.onNodeWithTag("nav:events").performClick()
        assertEquals(listOf(Tab.EVENTS), tabs)
    }

    // The badge sits inside a NavigationBarItem, which merges its descendants'
    // semantics, so it is only addressable in the unmerged tree.
    @Test
    fun eventsBadgeAppearsWhenAlertsAreUnacked() {
        show(widthDp = 411, state = Fx.state(unackedAlerts = 3))
        rule.onNodeWithTag("badge:events", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test
    fun eventsBadgeIsAbsentWhenNothingIsUnacked() {
        show(widthDp = 411, state = Fx.state(unackedAlerts = 0))
        rule.onNodeWithTag("badge:events", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun eventsBadgeAlsoAppearsOnTheRail() {
        show(widthDp = 840, state = Fx.state(unackedAlerts = 12))
        rule.onNodeWithTag("badge:events", useUnmergedTree = true).assertExists()
    }

    @Test
    fun statusBannerIsShownAndDismissable() {
        show(widthDp = 411, state = Fx.state(status = "Capture stopped by the system"))
        rule.onNodeWithTag("status:banner").assertExists()
        rule.onNodeWithText("Capture stopped by the system").assertExists()
        rule.onNodeWithTag("status:dismiss").performClick()
        assertEquals(1, actions.count("dismissStatus"))
    }

    @Test
    fun noStatusBannerWithoutAStatus() {
        show(widthDp = 411)
        rule.onNodeWithTag("status:banner").assertDoesNotExist()
    }

    @Test
    fun railAppearsExactlyAtTheMediumBreakpoint() {
        show(widthDp = 600)
        rule.onNodeWithTag("nav:rail").assertExists()
        rule.onNodeWithTag("nav:bar").assertDoesNotExist()
    }
}
