package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.ui.theme.DuckTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.ui.SearchState
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class ConnectionsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val plainKey = "tcp:10.0.0.2:44321-93.184.216.34:443"

    private fun show(state: UiState, actions: RecordingActions = RecordingActions(), twoPane: Boolean = false) {
        rule.setContent {
            DuckTheme {
                ConnectionsScreen(state = state, actions = actions, twoPane = twoPane)
            }
        }
    }

    @Test
    fun apiModeShowsEnableCaptureEmptyStateAndNoTable() {
        show(screenState(caps = Capabilities.API, conns = listOf(screenConn())))
        rule.onNodeWithTag(emptyTag(EMPTY_CONNS_NO_CAPTURE)).assertExists()
        rule.onNodeWithTag("conn:$plainKey").assertDoesNotExist()
        rule.onNodeWithTag("conns:delta").assertDoesNotExist()
    }

    @Test
    fun apiModeEnableCaptureRaisesStartVpn() {
        val actions = RecordingActions()
        show(screenState(caps = Capabilities.API), actions)
        rule.onNodeWithText("Enable capture").performClick()
        assertEquals(1, actions.startVpnCount)
    }

    @Test
    fun vpnModeRendersRows() {
        show(screenState(conns = listOf(screenConn(), screenConn(key = "udp:10.0.0.2:5353-224.0.0.251:5353"))))
        rule.onNodeWithTag("conn:$plainKey").assertExists()
        rule.onNodeWithTag("conn:udp:10.0.0.2:5353-224.0.0.251:5353").assertExists()
        rule.onNodeWithTag(emptyTag(EMPTY_CONNS_NO_CAPTURE)).assertDoesNotExist()
    }

    @Test
    fun watchlistedAndNewRowsBothRender() {
        show(
            screenState(
                conns = listOf(
                    screenConn(key = "w", watchlisted = true),
                    screenConn(key = "n", isNew = true),
                ),
            ),
        )
        rule.onNodeWithTag("conn:w").assertIsDisplayed()
        rule.onNodeWithTag("conn:n").assertIsDisplayed()
    }

    @Test
    fun watchlistTintWinsOverNewTint() {
        assertEquals(DuckColors.Watchlist.copy(alpha = ROW_TINT_ALPHA), rowTint(watchlisted = true, isNew = true))
        assertEquals(DuckColors.NewRow.copy(alpha = ROW_TINT_ALPHA), rowTint(watchlisted = false, isNew = true))
    }

    @Test
    fun groupingModeRendersGroupsOnly() {
        show(
            screenState(
                settings = AppSettings(groupByHost = true),
                conns = listOf(screenConn()),
                groups = listOf(screenGroup()),
            ),
        )
        rule.onNodeWithTag("group:93.184.216.34").assertExists()
        rule.onNodeWithTag("conn:$plainKey").assertDoesNotExist()
    }

    @Test
    fun closedModeRendersClosedRowsOnly() {
        show(
            screenState(
                settings = AppSettings(showClosed = true),
                conns = listOf(screenConn()),
                closed = listOf(screenClosed()),
            ),
        )
        rule.onNodeWithTag("conn:tcp:10.0.0.2:5000-1.1.1.1:53").assertExists()
        rule.onNodeWithTag("conn:$plainKey").assertDoesNotExist()
    }

    @Test
    fun flatModeUsedWhenNeitherToggleIsSet() {
        show(
            screenState(
                conns = listOf(screenConn()),
                groups = listOf(screenGroup()),
                closed = listOf(screenClosed()),
            ),
        )
        rule.onNodeWithTag("conn:$plainKey").assertExists()
        rule.onNodeWithTag("group:93.184.216.34").assertDoesNotExist()
    }

    @Test
    fun rttColumnHiddenWhenCapabilityIsMissing() {
        show(screenState(caps = Capabilities.VPN.copy(hasRtt = false), conns = listOf(screenConn())))
        rule.onNodeWithTag("conn:rtt:$plainKey", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun rttColumnShownWhenCapabilityIsPresent() {
        show(screenState(conns = listOf(screenConn())))
        rule.onNodeWithTag("conn:rtt:$plainKey", useUnmergedTree = true).assertExists()
    }

    @Test
    fun rttColumnHiddenForRowsWithoutAMeasurement() {
        show(screenState(conns = listOf(screenConn(rttMillis = -1))))
        rule.onNodeWithTag("conn:rtt:$plainKey", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun deltaCounterShowsOpenedAndClosedCounts() {
        show(
            screenState(
                snapshot = NetSnapshot(atMillis = SCREEN_NOW, newConnCount = 3, closedConnCount = 2),
                conns = listOf(screenConn()),
            ),
        )
        rule.onNodeWithText("Δ +3/-2").assertExists()
    }

    @Test
    fun emptyFlatTableShowsItsOwnEmptyState() {
        show(screenState())
        rule.onNodeWithTag(emptyTag(EMPTY_CONNS)).assertExists()
    }

    @Test
    fun tappingARowOpensTheDetailSheet() {
        show(screenState(conns = listOf(screenConn())))
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithTag("conn:$plainKey").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
    }

    @Test
    fun detailSheetWatchlistActionRaisesToggleWatchlistWithTheRemoteAddress() {
        val actions = RecordingActions()
        show(screenState(conns = listOf(screenConn())), actions)
        rule.onNodeWithTag("conn:$plainKey").performClick()
        rule.onNodeWithTag("sheet:watchlist").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("93.184.216.34", actions.lastWatchlistToggle)
    }

    @Test
    fun detailSheetBlockActionRaisesBlockAppForTheOwningUid() {
        val actions = RecordingActions()
        show(screenState(conns = listOf(screenConn())), actions)
        rule.onNodeWithTag("conn:$plainKey").performClick()
        rule.onNodeWithTag("sheet:block").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(10123 to true, actions.lastBlock)
    }

    @Test
    fun longPressCopiesTheRowAsTabSeparatedText() {
        val actions = RecordingActions()
        show(screenState(conns = listOf(screenConn())), actions)
        rule.onNodeWithTag("conn:$plainKey").performTouchInput { longClick() }
        val copy = actions.lastCopy
        assertNotNull(copy)
        assertEquals("connection", copy!!.first)
        assertTrue(copy.second.contains("\t"))
        assertTrue(copy.second.contains("93.184.216.34:443"))
    }

    @Test
    fun exportIconRaisesExportCurrentTable() {
        val actions = RecordingActions()
        show(screenState(conns = listOf(screenConn())), actions)
        rule.onNodeWithTag("conns:export").performClick()
        assertEquals(1, actions.exportTableCount)
    }

    @Test
    fun protoChipRaisesSetProtoFilter() {
        val actions = RecordingActions()
        show(screenState(conns = listOf(screenConn())), actions)
        rule.onNodeWithTag("chip:proto-udp").performScrollTo().performClick()
        assertEquals(ProtoFilter.UDP, actions.lastProtoFilter)
    }

    @Test
    fun toggleChipsRaiseTheirActions() {
        val actions = RecordingActions()
        show(screenState(conns = listOf(screenConn())), actions)
        rule.onNodeWithTag("chip:revdns").performScrollTo().performClick()
        rule.onNodeWithTag("chip:group").performScrollTo().performClick()
        rule.onNodeWithTag("chip:closed").performScrollTo().performClick()
        rule.onNodeWithTag("chip:mode").performScrollTo().performClick()
        assertTrue(actions.calls.containsAll(listOf("toggleRevDns", "toggleGrouping", "toggleShowClosed", "toggleThroughputMode")))
    }

    @Test
    fun twoPaneShowsTheDetailPaneInsteadOfASheet() {
        show(screenState(conns = listOf(screenConn())), twoPane = true)
        rule.onNodeWithTag("conn:$plainKey").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
    }

    private val markConns = listOf(
        screenConn(key = "a", remoteAddr = "93.184.216.34"),
        screenConn(key = "b", remoteAddr = "10.0.0.5"),
        screenConn(key = "c", remoteAddr = "10.0.0.6"),
    )

    private fun markState(
        query: String = "10.0",
        matched: Set<Int> = setOf(1, 2),
        cursor: Int = -1,
        conns: List<com.vlad.ducknetview.domain.model.ConnRow> = markConns,
    ) = screenState(
        conns = conns,
        search = SearchState(query = query, mode = SearchMode.HIGHLIGHT),
        matchCount = matched.size,
        matchedRows = matched,
        matchCursor = cursor,
    )

    @Test
    fun filterModeRendersNoMatchTags() {
        show(
            screenState(
                conns = markConns,
                search = SearchState(query = "10.0", mode = SearchMode.FILTER),
                matchCount = 2,
                matchedRows = setOf(1, 2),
            ),
        )
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun filterModeRendersNoMatchNavigation() {
        show(
            screenState(
                conns = markConns,
                search = SearchState(query = "10.0", mode = SearchMode.FILTER),
                matchCount = 2,
                matchedRows = setOf(1, 2),
            ),
        )
        rule.onNodeWithTag("search:prev").assertDoesNotExist()
        rule.onNodeWithTag("search:next").assertDoesNotExist()
        rule.onNodeWithTag("search:position").assertDoesNotExist()
    }

    @Test
    fun highlightModeTagsMatchedRows() {
        show(markState())
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertExists()
    }

    @Test
    fun highlightModeLeavesNonMatchingRowsUntagged() {
        show(markState())
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun highlightModeKeepsNonMatchingRowsVisible() {
        show(markState())
        rule.onNodeWithTag("conn:a").assertExists()
        rule.onNodeWithTag("conn:b").assertExists()
        rule.onNodeWithTag("conn:c").assertExists()
    }

    @Test
    fun cursorRowCarriesTheCursorTag() {
        show(markState(cursor = 2))
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertExists()
    }

    @Test
    fun cursorRowKeepsItsIndexedTagToo() {
        show(markState(cursor = 2))
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertExists()
    }

    @Test
    fun noCursorMeansNoCursorTag() {
        show(markState(cursor = -1))
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun matchNavigationShowsThePosition() {
        show(markState(cursor = 2))
        rule.onNodeWithTag("search:position").assertExists()
        rule.onNodeWithText("2 / 2").assertExists()
    }

    @Test
    fun matchPositionIsDashUntilTheCursorMoves() {
        show(markState())
        rule.onNodeWithText("- / 2").assertExists()
    }

    @Test
    fun nextButtonRaisesNextMatch() {
        val actions = RecordingActions()
        show(markState(), actions)
        rule.onNodeWithTag("search:next").performClick()
        assertEquals(1, actions.nextMatchCount)
        assertEquals(0, actions.prevMatchCount)
    }

    @Test
    fun prevButtonRaisesPrevMatch() {
        val actions = RecordingActions()
        show(markState(), actions)
        rule.onNodeWithTag("search:prev").performClick()
        assertEquals(1, actions.prevMatchCount)
        assertEquals(0, actions.nextMatchCount)
    }

    @Test
    fun matchNavigationIsHiddenWithoutMatches() {
        show(markState(matched = emptySet()))
        rule.onNodeWithTag("search:next").assertDoesNotExist()
        rule.onNodeWithTag("search:prev").assertDoesNotExist()
    }

    @Test
    fun matchNavigationIsHiddenWithoutAQuery() {
        show(markState(query = "", matched = emptySet()))
        rule.onNodeWithTag("search:next").assertDoesNotExist()
    }

    @Test
    fun cursorBeyondTheListDoesNotCrash() {
        show(markState(matched = setOf(1), cursor = 99))
        rule.onNodeWithTag("conn:a").assertExists()
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun matchedIndicesBeyondTheListDoNotCrash() {
        show(markState(matched = setOf(1, 42), cursor = 42))
        rule.onNodeWithTag("conn:c").assertExists()
        rule.onNodeWithTag(matchTag(42), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun cursorOnAnEmptyTableDoesNotCrash() {
        show(markState(matched = setOf(0), cursor = 0, conns = emptyList()))
        rule.onNodeWithTag(emptyTag(EMPTY_CONNS)).assertExists()
    }

    @Test
    fun matchTintOutranksTheWatchlistTint() {
        show(
            markState(
                matched = setOf(0),
                cursor = 0,
                conns = listOf(screenConn(key = "w", watchlisted = true, remoteAddr = "10.0.0.5")),
            ),
        )
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("conn:w").assertExists()
    }

    @Test
    fun matchTintOutranksTheNewRowTint() {
        show(
            markState(
                matched = setOf(0),
                conns = listOf(screenConn(key = "n", isNew = true, remoteAddr = "10.0.0.5")),
            ),
        )
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("conn:n").assertExists()
    }

    @Test
    fun watchlistedRowOutsideTheMatchSetStaysUntagged() {
        show(
            markState(
                matched = emptySet(),
                conns = listOf(screenConn(key = "w", watchlisted = true)),
            ),
        )
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("conn:w").assertExists()
    }

    @Test
    fun regexQueryStillTagsMatchedRows() {
        show(markState(query = "re:^10\\.", matched = setOf(1, 2), cursor = 1))
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("1 / 2").assertExists()
    }
}
