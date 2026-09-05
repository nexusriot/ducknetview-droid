package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.ui.theme.DuckTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.ui.SearchState
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.EventLevelFilter
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
class EventsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        state: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = false,
    ) {
        rule.setContent {
            DuckTheme { EventsScreen(state = state, actions = actions, twoPane = twoPane) }
        }
    }

    /** Renders against a state holder so a test can push a fresh log in. */
    private fun showLive(
        initial: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = true,
    ): MutableState<UiState> {
        val holder = mutableStateOf(initial)
        rule.setContent {
            DuckTheme { EventsScreen(state = holder.value, actions = actions, twoPane = twoPane) }
        }
        return holder
    }

    @Test
    fun rowsRenderWithStableTags() {
        show(
            screenState(
                events = listOf(
                    screenEvent(),
                    screenEvent(id = 2L, level = EventLevel.ALERT, kind = EventKind.OFF_BASELINE, subject = "tcp/9000"),
                ),
            ),
        )
        rule.onNodeWithTag("event:1").assertExists()
        rule.onNodeWithTag("event:2").assertExists()
        rule.onNodeWithText("off_baseline").assertExists()
    }

    @Test
    fun emptyStateExplainsTheLogRecordsChanges() {
        show(screenState())
        rule.onNodeWithTag(emptyTag(EMPTY_EVENTS)).assertExists()
        rule.onNodeWithText("records what changed", substring = true).assertExists()
    }

    @Test
    fun allLevelChipRaisesSetEventFilter() {
        val actions = RecordingActions()
        show(screenState(events = listOf(screenEvent()), eventFilter = EventLevelFilter.ALERTS), actions)
        rule.onNodeWithTag("chip:level-all").performScrollTo().performClick()
        assertEquals(EventLevelFilter.ALL, actions.lastEventFilter)
    }

    @Test
    fun warnLevelChipRaisesSetEventFilter() {
        val actions = RecordingActions()
        show(screenState(events = listOf(screenEvent())), actions)
        rule.onNodeWithTag("chip:level-warn").performScrollTo().performClick()
        assertEquals(EventLevelFilter.WARN_PLUS, actions.lastEventFilter)
    }

    @Test
    fun alertsLevelChipRaisesSetEventFilter() {
        val actions = RecordingActions()
        show(screenState(events = listOf(screenEvent())), actions)
        rule.onNodeWithTag("chip:level-alerts").performScrollTo().performClick()
        assertEquals(EventLevelFilter.ALERTS, actions.lastEventFilter)
    }

    @Test
    fun headerActionsRaiseAckClearAndExport() {
        val actions = RecordingActions()
        show(screenState(events = listOf(screenEvent()), unackedAlerts = 2), actions)
        rule.onNodeWithTag("events:ack").performClick()
        rule.onNodeWithTag("events:clear").performClick()
        rule.onNodeWithTag("events:export").performClick()
        assertTrue(actions.calls.containsAll(listOf("ackAlerts", "clearEvents")))
        assertEquals(1, actions.exportEventsCount)
    }

    @Test
    fun unackedCountIsShownInTheHeader() {
        show(screenState(events = listOf(screenEvent()), unackedAlerts = 2))
        rule.onNodeWithText("1 events · 2 unacked").assertExists()
    }

    @Test
    fun timestampUsesClockOnlyForTodaysEvents() {
        val e = screenEvent(at = SCREEN_NOW - 10_000)
        show(screenState(events = listOf(e)))
        rule.onNodeWithText(formatClock(e.at, SCREEN_NOW)).assertExists()
    }

    @Test
    fun timestampIncludesTheDateForOlderEvents() {
        val old = SCREEN_NOW - 3L * 86_400_000L
        val e = screenEvent(at = old)
        show(screenState(events = listOf(e)))
        val text = formatClock(old, SCREEN_NOW)
        assertTrue(text.length > 8)
        rule.onNodeWithText(text).assertExists()
    }

    @Test
    fun tappingAnEventOpensTheDetailSheet() {
        show(screenState(events = listOf(screenEvent())))
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithTag("event:1").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
    }

    @Test
    fun onePaneTapStillOpensAModalBottomSheet() {
        show(screenState(events = listOf(screenEvent())))
        rule.onNodeWithTag("event:1").performClick()
        rule.onNodeWithTag("detail:${EventKind.WATCHLIST_HIT.label}").assertExists()
    }

    @Test
    fun onePaneShowsNoPlaceholderPane() {
        show(screenState(events = listOf(screenEvent())))
        rule.onNodeWithText("Select an event to see its detail.").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneRendersTheInlinePaneAndNoModalSheet() {
        show(screenState(events = listOf(screenEvent())), twoPane = true)
        rule.onNodeWithTag("event:1").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        rule.onNodeWithTag("detail:${EventKind.WATCHLIST_HIT.label}").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneShowsThePlaceholderUntilAnEventIsPicked() {
        show(screenState(events = listOf(screenEvent())), twoPane = true)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select an event to see its detail.").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneKeepsTheHeaderAndLevelChips() {
        show(screenState(events = listOf(screenEvent())), twoPane = true)
        rule.onNodeWithTag("events:count").assertExists()
        rule.onNodeWithTag("events:ack").assertExists()
        rule.onNodeWithTag("chip:level-all").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailFollowsAnUpdateToTheSelectedEvent() {
        val holder = showLive(screenState(events = listOf(screenEvent())))
        rule.onNodeWithTag("event:1").performClick()
        rule.onNodeWithText("warn").assertExists()

        rule.runOnIdle {
            holder.value = screenState(events = listOf(screenEvent(level = EventLevel.ALERT)))
        }

        rule.onNodeWithText("alert").assertExists()
        rule.onNodeWithText("warn").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailKeepsTheSelectionWhenOtherEventsArrive() {
        val holder = showLive(screenState(events = listOf(screenEvent())))
        rule.onNodeWithTag("event:1").performClick()

        rule.runOnIdle {
            holder.value = screenState(
                events = listOf(
                    screenEvent(id = 2L, kind = EventKind.SERVICE_UP, subject = "tcp/22"),
                    screenEvent(),
                ),
            )
        }

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        rule.onNodeWithTag("kv:Subject", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneFallsBackToThePlaceholderWhenTheEventIsCleared() {
        val holder = showLive(screenState(events = listOf(screenEvent())))
        rule.onNodeWithTag("event:1").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()

        rule.runOnIdle { holder.value = screenState() }

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select an event to see its detail.").assertExists()
    }

    private val markEvents = listOf(
        screenEvent(id = 1L, subject = "wlan0"),
        screenEvent(id = 2L, subject = "10.0.0.5"),
        screenEvent(id = 3L, subject = "10.0.0.6"),
    )

    private fun markState(
        query: String = "10.0",
        matched: Set<Int> = setOf(1, 2),
        cursor: Int = -1,
        mode: SearchMode = SearchMode.HIGHLIGHT,
    ) = screenState(
        events = markEvents,
        search = SearchState(query = query, mode = mode),
        matchCount = matched.size,
        matchedRows = matched,
        matchCursor = cursor,
    )

    @Test
    fun highlightModeTagsMatchedEvents() {
        show(markState())
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun highlightModeKeepsUnmatchedEventsVisible() {
        show(markState())
        rule.onNodeWithTag("event:1").assertExists()
    }

    @Test
    fun filterModeTagsNoEvents() {
        show(markState(mode = SearchMode.FILTER))
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("search:position").assertDoesNotExist()
    }

    @Test
    fun cursorEventCarriesTheCursorTag() {
        show(markState(cursor = 2))
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("2 / 2").assertExists()
    }

    @Test
    fun eventMatchNavigationRaisesActions() {
        val actions = RecordingActions()
        show(markState(), actions)
        rule.onNodeWithTag("search:next").performClick()
        rule.onNodeWithTag("search:prev").performClick()
        assertEquals(1, actions.nextMatchCount)
        assertEquals(1, actions.prevMatchCount)
    }

    @Test
    fun eventCursorBeyondTheListDoesNotCrash() {
        show(markState(matched = setOf(1), cursor = 64))
        rule.onNodeWithTag("event:1").assertExists()
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertDoesNotExist()
    }
}
