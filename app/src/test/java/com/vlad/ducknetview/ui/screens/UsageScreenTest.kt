package com.vlad.ducknetview.ui.screens

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.vlad.ducknetview.domain.usage.DailyUsage
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun mb(n: Long): Long = n * 1024L * 1024L

/** Three days whose totals are round numbers, so the tiles are easy to assert. */
private val THREE_DAYS = listOf(
    DailyUsage(
        dayEpoch = 20_000L,
        rx = mb(100),
        tx = mb(10),
        apps = mapOf("Browser" to mb(80), "Mail" to mb(20)),
        hosts = mapOf("cdn.example.com" to mb(70), "mail.example.com" to mb(30)),
    ),
    DailyUsage(
        dayEpoch = 20_001L,
        rx = mb(200),
        tx = mb(20),
        apps = mapOf("Browser" to mb(150), "Maps" to mb(60)),
        hosts = mapOf("cdn.example.com" to mb(200)),
    ),
    DailyUsage(
        dayEpoch = 20_002L,
        rx = mb(300),
        tx = mb(30),
        apps = mapOf("Browser" to mb(300)),
        hosts = mapOf("tiles.example.com" to mb(330)),
    ),
)

private val THIRTY_FIVE_DAYS = (0L until 35L).map {
    DailyUsage(dayEpoch = 20_000L + it, rx = mb(it + 1))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class UsageScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        state: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = false,
    ) {
        rule.setContent {
            DuckTheme { UsageScreen(state = state, actions = actions, twoPane = twoPane) }
        }
    }

    /** Renders against a state holder so a test can push a fresh rollup in. */
    private fun showLive(
        initial: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = true,
    ): MutableState<UiState> {
        val holder = mutableStateOf(initial)
        rule.setContent {
            DuckTheme { UsageScreen(state = holder.value, actions = actions, twoPane = twoPane) }
        }
        return holder
    }

    /** Asserts the tagged tile or key/value row renders [text] somewhere inside. */
    private fun assertShows(tag: String, text: String) {
        rule.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun screenRootIsTagged() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("screen:usage").assertExists()
    }

    @Test
    fun emptyStateWhenThereIsNoHistory() {
        show(screenState(usageAccessGranted = true))
        rule.onNodeWithTag(emptyTag(EMPTY_USAGE)).assertExists()
    }

    @Test
    fun emptyStateActionRaisesRefreshUsage() {
        val actions = RecordingActions()
        show(screenState(usageAccessGranted = true), actions)
        rule.onNodeWithTag("empty:action").performScrollTo().performClick()
        assertEquals(1, actions.refreshUsageCount)
    }

    @Test
    fun emptyStateGoesAwayOnceThereIsHistory() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag(emptyTag(EMPTY_USAGE)).assertDoesNotExist()
    }

    @Test
    fun accessCardShownWhenUsageAccessIsMissing() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = false))
        rule.onNodeWithTag("usage:access-card").assertExists()
    }

    @Test
    fun accessCardAbsentWhenUsageAccessIsGranted() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:access-card").assertDoesNotExist()
    }

    @Test
    fun accessCardButtonRaisesRequestUsageAccess() {
        val actions = RecordingActions()
        show(screenState(usage = THREE_DAYS, usageAccessGranted = false), actions)
        rule.onNodeWithTag("usage:access-grant").performScrollTo().performClick()
        assertEquals(1, actions.usageAccessCount)
    }

    @Test
    fun deviceHistoryStillRendersWithoutUsageAccess() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = false))
        rule.onNodeWithTag("usage:day:20002").assertExists()
        rule.onNodeWithTag("usage:total-rx").assertExists()
    }

    @Test
    fun defaultRangeIsTheLastSevenDays() {
        show(screenState(usage = THIRTY_FIVE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20034").assertExists()
        rule.onNodeWithTag("usage:day:20028").assertExists()
        rule.onNodeWithTag("usage:day:20027").assertDoesNotExist()
    }

    @Test
    fun thirtyDayChipWidensTheRange() {
        show(screenState(usage = THIRTY_FIVE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:range-30").performScrollTo().performClick()
        rule.onNodeWithTag("usage:day:20005").assertExists()
        rule.onNodeWithTag("usage:day:20004").assertDoesNotExist()
    }

    @Test
    fun allChipShowsEveryRecordedDay() {
        show(screenState(usage = THIRTY_FIVE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:range-all").performScrollTo().performClick()
        rule.onNodeWithTag("usage:day:20000").assertExists()
        rule.onNodeWithTag("usage:day:20034").assertExists()
    }

    @Test
    fun sevenDayChipNarrowsTheRangeBackDown() {
        show(screenState(usage = THIRTY_FIVE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:range-all").performScrollTo().performClick()
        rule.onNodeWithTag("usage:range-7").performScrollTo().performClick()
        rule.onNodeWithTag("usage:day:20000").assertDoesNotExist()
        rule.onNodeWithTag("usage:day:20028").assertExists()
    }

    @Test
    fun rangeChipsAreAbsentWithoutHistory() {
        show(screenState(usageAccessGranted = true))
        rule.onNodeWithTag("usage:range-7").assertDoesNotExist()
    }

    @Test
    fun totalsMatchTheFixtureArithmetic() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        assertShows("usage:total-rx", "600 MB")
        assertShows("usage:total-tx", "60.0 MB")
        assertShows("usage:total-all", "660 MB")
        assertShows("usage:average", "220 MB")
    }

    @Test
    fun totalsFollowTheSelectedRange() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:range-all").performScrollTo().performClick()
        assertShows("usage:total-rx", "600 MB")
    }

    @Test
    fun chartAndBusiestDayAreRendered() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:chart").assertExists()
        rule.onNodeWithTag("usage:busiest", useUnmergedTree = true)
            .assertTextContains("330 MB", substring = true)
    }

    @Test
    fun busiestDayNamesTheHeaviestDate() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:busiest", useUnmergedTree = true)
            .assertTextContains(formatDay(20_002L), substring = true)
    }

    @Test
    fun everyDayInRangeGetsARow() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20000").assertExists()
        rule.onNodeWithTag("usage:day:20001").assertExists()
        rule.onNodeWithTag("usage:day:20002").assertExists()
    }

    @Test
    fun tappingADayOpensTheDetailSheet() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
    }

    @Test
    fun detailSheetIsTitledWithTheDay() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("detail:${formatDay(20_001L)}").assertExists()
    }

    @Test
    fun detailSheetListsThatDaysTopAppsAndHosts() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("kv:app Browser", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("kv:host cdn.example.com", useUnmergedTree = true).assertExists()
    }

    @Test
    fun detailSheetCopyRaisesCopyText() {
        val actions = RecordingActions()
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), actions)
        rule.onNodeWithTag("usage:day:20002").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("detail:copy").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("usage", actions.lastCopy?.first)
        assertTrue(actions.lastCopy?.second?.contains("330 MB") == true)
    }

    @Test
    fun detailSheetCloseDismissesIt() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20002").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("detail:close").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
    }

    @Test
    fun longPressingADayCopiesIt() {
        val actions = RecordingActions()
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), actions)
        rule.onNodeWithTag("usage:day:20000").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals("usage", actions.lastCopy?.first)
        assertTrue(actions.lastCopy?.second?.contains("110 MB") == true)
    }

    @Test
    fun topAppsAreAggregatedOverTheRange() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:app:Browser").assertExists()
        rule.onNodeWithTag("usage:app:Mail").assertExists()
        rule.onNodeWithTag("usage:app:Maps").assertExists()
        rule.onNode(
            hasTestTag("usage:app:Browser") and hasAnyDescendant(hasText("530 MB")),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun topHostsAreAggregatedOverTheRange() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:host:cdn.example.com").assertExists()
        rule.onNode(
            hasTestTag("usage:host:cdn.example.com") and hasAnyDescendant(hasText("270 MB")),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun longPressingAnAppRowCopiesTheRow() {
        val actions = RecordingActions()
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), actions)
        rule.onNodeWithTag("usage:app:Browser")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals("usage" to "Browser\t530 MB", actions.lastCopy)
    }

    @Test
    fun longPressingAHostRowCopiesTheRow() {
        val actions = RecordingActions()
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), actions)
        rule.onNodeWithTag("usage:host:tiles.example.com")
            .performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals("usage" to "tiles.example.com\t330 MB", actions.lastCopy)
    }

    @Test
    fun refreshRaisesRefreshUsage() {
        val actions = RecordingActions()
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), actions)
        rule.onNodeWithTag("usage:refresh").performClick()
        assertEquals(1, actions.refreshUsageCount)
    }

    @Test
    fun loadingShowsTheIndicatorInsteadOfTheEmptyState() {
        show(screenState(usageAccessGranted = true, usageLoading = true))
        rule.onNodeWithTag("usage:loading").assertExists()
        rule.onNodeWithTag(emptyTag(EMPTY_USAGE)).assertDoesNotExist()
    }

    @Test
    fun indicatorIsAbsentWhenNotLoading() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:loading").assertDoesNotExist()
    }

    @Test
    fun loadingOverExistingHistoryStillShowsTheDays() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true, usageLoading = true))
        rule.onNodeWithTag("usage:loading").assertExists()
        rule.onNodeWithTag("usage:day:20002").assertExists()
    }

    @Test
    fun onePaneShowsNoPlaceholderPane() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithText("Select a day to see its top apps and hosts.").assertDoesNotExist()
    }

    @Test
    fun onePaneTapStillOpensAModalBottomSheet() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("detail:${formatDay(20_001L)}").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneRendersTheInlinePaneAndNoModalSheet() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), twoPane = true)
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        rule.onNodeWithTag("detail:${formatDay(20_001L)}").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneShowsThePlaceholderUntilADayIsPicked() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), twoPane = true)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a day to see its top apps and hosts.").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneKeepsRangeChipsTotalsAndChart() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), twoPane = true)
        rule.onNodeWithTag("usage:range-7").assertExists()
        rule.onNodeWithTag("usage:range-30").assertExists()
        rule.onNodeWithTag("usage:range-all").assertExists()
        rule.onNodeWithTag("usage:chart").assertExists()
        assertShows("usage:total-rx", "600 MB")
        assertShows("usage:total-all", "660 MB")
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneStillListsTheRangeAggregatedTopRows() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), twoPane = true)
        rule.onNodeWithTag("usage:app:Browser").assertExists()
        rule.onNodeWithTag("usage:host:cdn.example.com").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailListsTheSelectedDaysTopAppsAndHosts() {
        show(screenState(usage = THREE_DAYS, usageAccessGranted = true), twoPane = true)
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("kv:app Browser", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("kv:host cdn.example.com", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailFollowsAnUpdatedRollupForTheSameDay() {
        val holder = showLive(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        assertShows("kv:Down", "200 MB")

        rule.runOnIdle {
            holder.value = screenState(
                usage = THREE_DAYS.map { if (it.dayEpoch == 20_001L) it.copy(rx = mb(500)) else it },
                usageAccessGranted = true,
            )
        }

        assertShows("kv:Down", "500 MB")
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneFallsBackToThePlaceholderWhenTheDayIsTrimmed() {
        val holder = showLive(screenState(usage = THREE_DAYS, usageAccessGranted = true))
        rule.onNodeWithTag("usage:day:20001").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()

        rule.runOnIdle {
            holder.value = screenState(
                usage = THREE_DAYS.filter { it.dayEpoch != 20_001L },
                usageAccessGranted = true,
            )
        }

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a day to see its top apps and hosts.").assertExists()
    }
}
