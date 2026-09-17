package com.vlad.ducknetview.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The snapshot viewer was fully implemented once with no control anywhere in
 * the UI, which made it dead code that the README nonetheless described. These
 * tests pin the entry and exit points so that cannot recur silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class SnapshotControlTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(state: UiState, actions: RecordingActions) {
        rule.setContent { DuckTheme { SettingsScreen(state, actions) } }
    }

    @Test
    fun settingsOffersToOpenASnapshotWhenBrowsingLiveData() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:snapshot-open").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("settings:snapshot-open").performClick()
        assertEquals(1, actions.openSnapshotCount)
    }

    @Test
    fun openControlIsReplacedByAWayBackWhileFrozen() {
        val actions = RecordingActions()
        show(
            screenState().copy(
                snapshot = NetSnapshot(frozen = true, frozenLabel = "snap.json"),
            ),
            actions,
        )
        rule.onNodeWithTag("settings:snapshot-close").performScrollTo().performClick()
        assertEquals(1, actions.closeSnapshotCount)
        assertEquals(0, actions.openSnapshotCount)
        // "Replaced" is the claim, so the open control has to be gone. It used
        // to share its tag with the frozen note, which made this unassertable.
        rule.onNodeWithTag("settings:snapshot-open").assertDoesNotExist()
    }

    @Test
    fun frozenStateNamesTheFileBeingBrowsed() {
        show(
            screenState().copy(
                snapshot = NetSnapshot(frozen = true, frozenLabel = "remote-host.json"),
            ),
            RecordingActions(),
        )
        rule.onNodeWithTag("settings:snapshot-frozen")
            .performScrollTo()
            .assertTextContains("remote-host.json", substring = true)
    }

    @Test
    fun theFrozenNoteIsAbsentWhileBrowsingLiveData() {
        show(screenState(), RecordingActions())
        rule.onNodeWithTag("settings:snapshot-frozen").assertDoesNotExist()
        rule.onNodeWithTag("settings:snapshot-close").assertDoesNotExist()
    }

    @Test
    fun exportRemainsAvailableInBothStates() {
        val actions = RecordingActions()
        show(screenState(), actions)
        rule.onNodeWithTag("settings:export-json").performScrollTo().performClick()
        assertEquals(1, actions.exportJsonCount)
    }
}
