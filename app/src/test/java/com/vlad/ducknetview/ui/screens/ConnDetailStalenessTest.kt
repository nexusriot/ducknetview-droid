package com.vlad.ducknetview.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the detail pane does when the connection it is describing ends.
 *
 * The pane used to resolve its row as `state.conns.firstOrNull { … } ?: sel`,
 * so once the flow left the live table it went on rendering the copy taken when
 * the row was tapped: a connection that had retired a minute earlier still read
 * "established", its age kept climbing on every tick, and its byte totals were
 * frozen at whatever the last poll had seen. On a tablet, with the pane always
 * on screen beside the table, that was a confidently wrong reading sitting
 * there indefinitely — and the Block and Watchlist buttons underneath it still
 * offered to act on a socket that no longer existed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1280dp-h800dp")
class ConnDetailStalenessTest {

    @get:Rule
    val rule = createComposeRule()

    private val key = "tcp:10.0.0.2:44321-93.184.216.34:443"

    private val live = screenConn(key = key, state = ConnState.ESTABLISHED)
    private val retired = screenClosed(
        row = live.copy(state = ConnState.CLOSED),
        lifetimeMillis = 61_000,
        finalRx = 9_000,
        finalTx = 3_000,
    )

    /**
     * "established" also appears on a filter chip and in the table row, so every
     * assertion here has to be scoped to the detail pane's own subtree.
     */
    private fun detailShows(text: String) =
        rule.onNode(hasTestTag(TAG_DETAIL_SHEET) and hasAnyDescendant(hasText(text)))

    /** Renders the screen from a state the test can swap under it, as a tick does. */
    private fun showSwappable(first: UiState): (UiState) -> Unit {
        var current by mutableStateOf(first)
        rule.setContent {
            DuckTheme {
                ConnectionsScreen(
                    state = current,
                    actions = RecordingActions(),
                    twoPane = true,
                )
            }
        }
        return { next -> current = next }
    }

    @Test
    fun `a selected flow that retires stops being reported as live`() {
        val swap = showSwappable(screenState(conns = listOf(live)))
        rule.onNodeWithTag("conn:$key").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        detailShows("established").assertExists()

        // The next tick: the flow is gone from the live table and in history.
        swap(
            screenState(
                conns = emptyList(),
                closed = listOf(retired),
                snapshot = NetSnapshot(atMillis = SCREEN_NOW, closedConns = listOf(retired)),
            )
        )

        detailShows("established").assertDoesNotExist()
    }

    @Test
    fun `the pane falls through to the closed record with its real final figures`() {
        val swap = showSwappable(screenState(conns = listOf(live)))
        rule.onNodeWithTag("conn:$key").performClick()
        swap(
            screenState(
                conns = emptyList(),
                closed = listOf(retired),
                snapshot = NetSnapshot(atMillis = SCREEN_NOW, closedConns = listOf(retired)),
            )
        )

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()
        // The closed detail names a lifetime; the live one never does.
        detailShows("Lifetime").assertExists()
    }

    @Test
    fun `no live actions are offered for a flow that has ended`() {
        val swap = showSwappable(screenState(conns = listOf(live)))
        rule.onNodeWithTag("conn:$key").performClick()
        rule.onNodeWithTag("sheet:block").assertExists()

        swap(
            screenState(
                conns = emptyList(),
                closed = listOf(retired),
                snapshot = NetSnapshot(atMillis = SCREEN_NOW, closedConns = listOf(retired)),
            )
        )

        // The socket is gone and its uid may already be recycled, so acting on
        // it could hit an unrelated app.
        rule.onNodeWithTag("sheet:block").assertDoesNotExist()
    }

    @Test
    fun `a row hidden by a filter rather than closed leaves an empty pane`() {
        val swap = showSwappable(screenState(conns = listOf(live)))
        rule.onNodeWithTag("conn:$key").performClick()
        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertExists()

        // Still open upstream, simply not in the filtered list and not in
        // history: there is nothing truthful left to show.
        swap(screenState(conns = emptyList()))

        rule.onNodeWithTag(TAG_DETAIL_SHEET).assertDoesNotExist()
        rule.onNodeWithText("Select a row to see its detail.").assertExists()
    }

    @Test
    fun `a still-live selection keeps updating from the new tick`() {
        val swap = showSwappable(screenState(conns = listOf(live)))
        rule.onNodeWithTag("conn:$key").performClick()

        swap(screenState(conns = listOf(live.copy(state = ConnState.CLOSING))))

        detailShows("closing").assertExists()
    }

    /**
     * A flow's `network` names the link it actually left on, and that is never
     * a VPN interface — while capture is running the default route is this
     * app's own TUN. So a chip for a VPN link can only empty the table, which
     * is exactly what the "tun0" chip did on the test tablet.
     */
    @Test
    fun `no filter chip is offered for a vpn link, including our own tun`() {
        val snapshot = NetSnapshot(
            atMillis = SCREEN_NOW,
            networks = listOf(
                NetworkRow(
                    id = "wifi",
                    ifaceName = "wlan0",
                    transport = Transport.WIFI,
                    up = true,
                    addresses = listOf("192.168.88.34/24"),
                ),
                NetworkRow(
                    id = "tun",
                    ifaceName = "tun0",
                    transport = Transport.VPN,
                    up = true,
                    isDefault = true,
                    addresses = listOf("10.215.173.1/30"),
                ),
            ),
        )
        rule.setContent {
            DuckTheme {
                ConnectionsScreen(
                    state = screenState(conns = listOf(live), snapshot = snapshot),
                    actions = RecordingActions(),
                    twoPane = true,
                )
            }
        }

        rule.onNodeWithTag("chip:net-wlan0").assertExists()
        rule.onNodeWithTag("chip:net-tun0").assertDoesNotExist()
    }
}
