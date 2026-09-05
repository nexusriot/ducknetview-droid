package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.ui.theme.DuckTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.ui.SearchState
import com.vlad.ducknetview.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class AppsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(state: UiState, actions: RecordingActions = RecordingActions()) {
        rule.setContent {
            DuckTheme { AppsScreen(state = state, actions = actions) }
        }
    }

    @Test
    fun rowsRenderWithStableTags() {
        show(screenState(apps = listOf(screenApp(), screenApp(uid = 10456, label = "Mail"))))
        rule.onNodeWithTag("app:10123").assertExists()
        rule.onNodeWithTag("app:10456").assertExists()
    }

    @Test
    fun todayColumnHiddenWithoutUsageAccess() {
        show(screenState(apps = listOf(screenApp())))
        rule.onNodeWithTag("app:today:10123", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun todayColumnShownWithUsageAccess() {
        show(screenState(apps = listOf(screenApp()), usageAccessGranted = true))
        rule.onNodeWithTag("app:today:10123", useUnmergedTree = true).assertExists()
    }

    @Test
    fun usageAccessCardAppearsAndRequestsPermission() {
        val actions = RecordingActions()
        show(screenState(apps = listOf(screenApp())), actions)
        rule.onNodeWithTag("apps:usage-card").assertExists()
        rule.onNodeWithTag("apps:usage-grant").performClick()
        assertEquals(1, actions.usageAccessCount)
    }

    @Test
    fun usageAccessCardCanBeDismissed() {
        show(screenState(apps = listOf(screenApp())))
        rule.onNodeWithTag("apps:usage-dismiss").performClick()
        rule.onNodeWithTag("apps:usage-card").assertDoesNotExist()
    }

    @Test
    fun usageAccessCardAbsentWhenGranted() {
        show(screenState(apps = listOf(screenApp()), usageAccessGranted = true))
        rule.onNodeWithTag("apps:usage-card").assertDoesNotExist()
    }

    @Test
    fun connCountHiddenInApiMode() {
        show(screenState(apps = listOf(screenApp()), caps = Capabilities.API))
        rule.onNodeWithTag("app:conns:10123", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun connCountShownInVpnMode() {
        show(screenState(apps = listOf(screenApp())))
        rule.onNodeWithTag("app:conns:10123", useUnmergedTree = true).assertExists()
    }

    @Test
    fun blockedIndicatorRendersForBlockedApps() {
        show(screenState(apps = listOf(screenApp(blocked = true))))
        rule.onNodeWithTag("app:blocked:10123", useUnmergedTree = true).assertExists()
    }

    @Test
    fun blockedOnlyChipFiltersTheRenderedList() {
        show(screenState(apps = listOf(screenApp(), screenApp(uid = 10456, label = "Mail", blocked = true))))
        rule.onNodeWithTag("chip:blocked").performScrollTo().performClick()
        rule.onNodeWithTag("app:10456").assertExists()
        rule.onNodeWithTag("app:10123").assertDoesNotExist()
    }

    @Test
    fun detailSheetBlockActionRaisesBlockApp() {
        val actions = RecordingActions()
        show(screenState(apps = listOf(screenApp())), actions)
        rule.onNodeWithTag("app:10123").performClick()
        rule.onNodeWithTag("sheet:block").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(10123 to true, actions.lastBlock)
    }

    @Test
    fun detailSheetExcludeActionRaisesExcludeApp() {
        val actions = RecordingActions()
        show(screenState(apps = listOf(screenApp())), actions)
        rule.onNodeWithTag("app:10123").performClick()
        rule.onNodeWithTag("sheet:exclude").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(10123 to true, actions.lastExclude)
    }

    @Test
    fun emptyListShowsEmptyState() {
        show(screenState())
        rule.onNodeWithTag(emptyTag(EMPTY_APPS)).assertExists()
    }

    @Test
    fun mineChipRaisesToggleUserAppsOnly() {
        val actions = RecordingActions()
        show(screenState(apps = listOf(screenApp())), actions)
        rule.onNodeWithTag("chip:mine").performScrollTo().performClick()
        assertEquals(true, actions.calls.contains("toggleUserAppsOnly"))
    }

    private val markApps = listOf(
        screenApp(uid = 1, label = "Browser"),
        screenApp(uid = 2, label = "Mail"),
        screenApp(uid = 3, label = "Maps", blocked = true),
    )

    private fun markState(
        query: String = "ma",
        matched: Set<Int> = setOf(1, 2),
        cursor: Int = -1,
        mode: SearchMode = SearchMode.HIGHLIGHT,
    ) = screenState(
        apps = markApps,
        search = SearchState(query = query, mode = mode),
        matchCount = matched.size,
        matchedRows = matched,
        matchCursor = cursor,
    )

    @Test
    fun highlightModeTagsMatchedApps() {
        show(markState())
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun filterModeTagsNoAppRows() {
        show(markState(mode = SearchMode.FILTER))
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("search:position").assertDoesNotExist()
    }

    @Test
    fun cursorAppCarriesTheCursorTag() {
        show(markState(cursor = 1))
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1 / 2").assertExists()
    }

    @Test
    fun appMatchNavigationRaisesActions() {
        val actions = RecordingActions()
        show(markState(), actions)
        rule.onNodeWithTag("search:next").performClick()
        rule.onNodeWithTag("search:prev").performClick()
        assertEquals(1, actions.nextMatchCount)
        assertEquals(1, actions.prevMatchCount)
    }

    @Test
    fun appCursorBeyondTheListDoesNotCrash() {
        show(markState(matched = setOf(1), cursor = 500))
        rule.onNodeWithTag("app:1").assertExists()
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun blockedOnlyViewKeepsMatchIndicesAlignedWithTheFullList() {
        show(markState(matched = setOf(2), cursor = 2))
        rule.onNodeWithTag("chip:blocked").performClick()
        rule.onNodeWithTag("app:3").assertExists()
        rule.onNodeWithTag("app:1").assertDoesNotExist()
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertDoesNotExist()
    }
}
