package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.ui.theme.DuckTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.ui.SearchState
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
class ServicesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        state: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = false,
    ) {
        rule.setContent {
            DuckTheme { ServicesScreen(state = state, actions = actions, twoPane = twoPane) }
        }
    }

    /** Renders against a state holder so a test can push a fresh snapshot in. */
    private fun showLive(
        initial: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = true,
    ): MutableState<UiState> {
        val holder = mutableStateOf(initial)
        rule.setContent {
            DuckTheme { ServicesScreen(state = holder.value, actions = actions, twoPane = twoPane) }
        }
        return holder
    }

    private fun scanned(vararg rows: com.vlad.ducknetview.domain.model.ServiceRow): UiState =
        screenState(
            services = rows.toList(),
            snapshot = NetSnapshot(atMillis = SCREEN_NOW, serviceScanAt = SCREEN_NOW - 60_000),
        )

    @Test
    fun neverScannedShowsTheSelfScanExplanation() {
        show(screenState())
        rule.onNodeWithTag(emptyTag(EMPTY_SERVICES_NEVER)).assertExists()
        rule.onNodeWithText("scans this device itself", substring = true).assertExists()
        rule.onNodeWithText("Last scan never").assertExists()
    }

    @Test
    fun scannedButEmptyShowsTheOtherEmptyState() {
        show(scanned())
        rule.onNodeWithTag(emptyTag(EMPTY_SERVICES)).assertExists()
        rule.onNodeWithTag(emptyTag(EMPTY_SERVICES_NEVER)).assertDoesNotExist()
    }

    @Test
    fun scanNowRaisesScanServices() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("services:scan").performClick()
        assertEquals(1, actions.scanCount)
    }

    @Test
    fun scanButtonDisabledAndProgressShownWhileScanning() {
        show(
            screenState(
                snapshot = NetSnapshot(atMillis = SCREEN_NOW, serviceScanRunning = true),
            ),
        )
        rule.onNodeWithTag("services:progress").assertExists()
        rule.onNodeWithTag("services:scan").assertIsNotEnabled()
    }

    @Test
    fun fullRangeSwitchRaisesSetScanFullRange() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("services:fullrange").performClick()
        assertEquals(true, actions.lastScanFullRange)
    }

    @Test
    fun saveBaselineRaisesTheAction() {
        val actions = RecordingActions()
        show(scanned(screenService()), actions)
        rule.onNodeWithTag("services:save-baseline").performClick()
        assertTrue(actions.calls.contains("saveBaseline"))
    }

    @Test
    fun clearBaselineIsDisabledWithoutABaseline() {
        show(scanned(screenService()))
        rule.onNodeWithTag("services:clear-baseline").assertIsNotEnabled()
    }

    @Test
    fun clearBaselineRaisesTheActionWhenABaselineExists() {
        val actions = RecordingActions()
        show(
            screenState(
                services = listOf(screenService()),
                settings = AppSettings(baselineAt = SCREEN_NOW - 3_600_000),
                snapshot = NetSnapshot(atMillis = SCREEN_NOW, serviceScanAt = SCREEN_NOW),
            ),
            actions,
        )
        rule.onNodeWithTag("services:clear-baseline").performClick()
        assertTrue(actions.calls.contains("clearBaseline"))
    }

    @Test
    fun rowsRenderWithProtoAndPortTags() {
        show(scanned(screenService(), screenService(proto = Proto.UDP, port = 5353, service = "mdns")))
        rule.onNodeWithTag("service:tcp:8080").assertExists()
        rule.onNodeWithTag("service:udp:5353").assertExists()
    }

    @Test
    fun acceptIconOnlyShownForOffBaselineRows() {
        show(scanned(screenService(), screenService(port = 9000, offBaseline = true)))
        rule.onNodeWithTag("accept:tcp:8080").assertDoesNotExist()
        rule.onNodeWithTag("accept:tcp:9000").assertExists()
        rule.onNodeWithTag("service:offbaseline:tcp:9000", useUnmergedTree = true).assertExists()
    }

    @Test
    fun acceptIconRaisesAcceptIntoBaselineWithTheRow() {
        val actions = RecordingActions()
        val row = screenService(port = 9000, offBaseline = true)
        show(scanned(row), actions)
        rule.onNodeWithTag("accept:tcp:9000").performClick()
        assertEquals(row, actions.lastAcceptedService)
    }

    @Test
    fun newBadgeRendersForNewRows() {
        show(scanned(screenService(isNew = true)))
        rule.onNodeWithTag("service:new:tcp:8080", useUnmergedTree = true).assertExists()
    }

    @Test
    fun tappingARowOpensTheDetailSheet() {
        show(scanned(screenService()))
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithTag("service:tcp:8080").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
    }

    @Test
    fun lastScanTimeRendersWhenScanned() {
        show(scanned(screenService()))
        rule.onNodeWithText("Last scan ${formatClock(SCREEN_NOW - 60_000, SCREEN_NOW)}").assertExists()
    }

    @Test
    fun onePaneTapStillOpensAModalBottomSheet() {
        show(scanned(screenService()))
        rule.onNodeWithTag("service:tcp:8080").performClick()
        rule.onNodeWithTag("detail:tcp 0.0.0.0:8080").assertExists()
    }

    @Test
    fun onePaneShowsNoPlaceholderPane() {
        show(scanned(screenService()))
        rule.onNodeWithText("Select a listener to see its detail.").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneRendersTheInlinePaneAndNoModalSheet() {
        show(scanned(screenService()), twoPane = true)
        rule.onNodeWithTag("service:tcp:8080").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        rule.onNodeWithTag("detail:tcp 0.0.0.0:8080").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneShowsThePlaceholderUntilARowIsPicked() {
        show(scanned(screenService()), twoPane = true)
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a listener to see its detail.").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneKeepsTheScanAndBaselineControls() {
        show(scanned(screenService()), twoPane = true)
        rule.onNodeWithTag("services:scan").assertExists()
        rule.onNodeWithTag("services:fullrange").assertExists()
        rule.onNodeWithTag("services:save-baseline").assertExists()
        rule.onNodeWithTag("services:clear-baseline").assertExists()
        rule.onNodeWithTag("services:baseline-state").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailFollowsANewerScanOfTheSameListener() {
        val holder = showLive(scanned(screenService()))
        rule.onNodeWithTag("service:tcp:8080").performClick()
        rule.onNodeWithText("unknown").assertExists()

        rule.runOnIdle { holder.value = scanned(screenService(uid = 10500, appLabel = "Duckd")) }

        rule.onNodeWithText("Duckd").assertExists()
        rule.onNodeWithText("unknown").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneDetailPicksUpAnOffBaselineFlagArrivingLater() {
        val holder = showLive(scanned(screenService()))
        rule.onNodeWithTag("service:tcp:8080").performClick()
        rule.onNodeWithTag("sheet:accept").assertDoesNotExist()

        rule.runOnIdle { holder.value = scanned(screenService(offBaseline = true)) }

        rule.onNodeWithTag("sheet:accept").assertExists()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneAcceptActionRaisesAcceptIntoBaseline() {
        val actions = RecordingActions()
        val row = screenService(port = 9000, offBaseline = true)
        show(scanned(row), actions, twoPane = true)
        rule.onNodeWithTag("service:tcp:9000").performClick()
        // The pane scrolls, so the action can sit below the fold on a short viewport.
        rule.onNodeWithTag("sheet:accept").performScrollTo().performClick()
        assertEquals(row, actions.lastAcceptedService)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w1280dp-h800dp")
    fun twoPaneFallsBackToThePlaceholderWhenTheRowDisappears() {
        val holder = showLive(scanned(screenService()))
        rule.onNodeWithTag("service:tcp:8080").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()

        rule.runOnIdle { holder.value = scanned() }

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a listener to see its detail.").assertExists()
    }

    private val markServices = listOf(
        screenService(port = 22, service = "ssh"),
        screenService(port = 8080, service = "http-alt"),
        screenService(port = 8443, service = "https-alt"),
    )

    private fun markState(
        query: String = "80",
        matched: Set<Int> = setOf(1, 2),
        cursor: Int = -1,
        mode: SearchMode = SearchMode.HIGHLIGHT,
    ) = screenState(
        services = markServices,
        search = SearchState(query = query, mode = mode),
        matchCount = matched.size,
        matchedRows = matched,
        matchCursor = cursor,
    )

    @Test
    fun highlightModeTagsMatchedListeners() {
        show(markState())
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(2), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(matchTag(0), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun highlightModeKeepsUnmatchedListenersVisible() {
        show(markState())
        rule.onNodeWithTag("service:tcp:22").assertExists()
    }

    @Test
    fun filterModeTagsNoListeners() {
        show(markState(mode = SearchMode.FILTER))
        rule.onNodeWithTag(matchTag(1), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("search:next").assertDoesNotExist()
    }

    @Test
    fun cursorListenerCarriesTheCursorTag() {
        show(markState(cursor = 1))
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("search:position").assertExists()
    }

    @Test
    fun serviceMatchNavigationRaisesActions() {
        val actions = RecordingActions()
        show(markState(), actions)
        rule.onNodeWithTag("search:next").performClick()
        rule.onNodeWithTag("search:prev").performClick()
        assertEquals(1, actions.nextMatchCount)
        assertEquals(1, actions.prevMatchCount)
    }

    @Test
    fun serviceCursorBeyondTheListDoesNotCrash() {
        show(markState(matched = setOf(1), cursor = 77))
        rule.onNodeWithTag("service:tcp:22").assertExists()
        rule.onNodeWithTag(TAG_MATCH_CURSOR, useUnmergedTree = true).assertDoesNotExist()
    }
}
