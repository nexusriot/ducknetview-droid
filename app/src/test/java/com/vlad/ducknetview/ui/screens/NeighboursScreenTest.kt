package com.vlad.ducknetview.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.model.NeighbourSource
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
 * DESIGN §7: a screen no suite visits is a screen whose crash survives every
 * green run. The Usage screen proved it on the tablet, so this one gets its
 * cases written alongside it rather than after a device finds them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class NeighboursScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val link = Fixtures.network(ifaceName = "wlan0", addresses = listOf("192.168.1.5/24"))

    private fun show(state: UiState, actions: RecordingActions = RecordingActions()) {
        rule.setContent { DuckTheme { NeighboursScreen(state = state, actions = actions) } }
    }

    private fun state(
        rows: List<NeighbourRow> = emptyList(),
        scanAt: Long = 0L,
        running: Boolean = false,
        addresses: List<String> = listOf("192.168.1.5/24"),
        frozen: Boolean = false,
    ): UiState {
        val snap = NetSnapshot(
            atMillis = 10_000L,
            networks = listOf(Fixtures.network(ifaceName = "wlan0", addresses = addresses)),
            neighbours = rows,
            neighbourScanAt = scanAt,
            neighbourScanRunning = running,
            neighbourSubnet = if (scanAt > 0L) "192.168.1.5/24" else "",
            frozen = frozen,
        )
        return UiState(snapshot = snap, neighbours = rows)
    }

    private fun host(
        address: String = "192.168.1.20",
        hostname: String = "",
        rtt: Int = 7,
        source: NeighbourSource = NeighbourSource.PING,
        gateway: Boolean = false,
        self: Boolean = false,
    ) = NeighbourRow(
        address = address,
        hostname = hostname,
        source = source,
        rttMillis = rtt,
        isGateway = gateway,
        isSelf = self,
        lastSeen = 9_000L,
    )

    @Test
    fun `a never-swept screen explains itself and offers the sweep`() {
        val actions = RecordingActions()
        show(state(), actions)
        rule.onNodeWithTag(emptyTag(EMPTY_NEIGHBOURS_NEVER)).assertExists()
        rule.onNodeWithTag("empty:action").performClick()
        assertEquals(1, actions.neighbourScanCount)
    }

    @Test
    fun `the toolbar button runs the sweep`() {
        val actions = RecordingActions()
        show(state(), actions)
        rule.onNodeWithTag("neighbours:scan").performClick()
        assertEquals(1, actions.neighbourScanCount)
    }

    @Test
    fun `a sweep that found nothing does not blame the search`() {
        // The empty case for this screen is "nothing answered", which on a
        // guest network is the correct and expected result. Telling the user
        // to fix a search they did not type is the mistake the Services
        // screen already made once.
        show(state(scanAt = 9_000L))
        rule.onNodeWithTag(emptyTag(EMPTY_NEIGHBOURS)).assertExists()
        rule.onNodeWithText("Nothing on this link answered.", substring = true).assertExists()
    }

    @Test
    fun `rows render with their names and round trips`() {
        show(
            state(
                rows = listOf(
                    host("192.168.1.1", gateway = true, rtt = 2),
                    host("192.168.1.42", hostname = "nas", rtt = 11),
                ),
                scanAt = 9_000L,
            ),
        )
        rule.onNodeWithTag("neighbour:192.168.1.1").assertExists()
        rule.onNodeWithTag("neighbour:192.168.1.42").assertExists()
        rule.onNodeWithText("gateway").assertExists()
        rule.onNodeWithText("nas", substring = true).assertExists()
    }

    @Test
    fun `a host found only by mDNS says so instead of claiming a round trip`() {
        show(
            state(
                rows = listOf(
                    host("192.168.1.50", hostname = "tv", rtt = -1, source = NeighbourSource.MDNS),
                ),
                scanAt = 9_000L,
            ),
        )
        // The row merges its descendants' semantics, so the cell's own tag
        // only exists in the unmerged tree.
        rule.onNodeWithTag("neighbour:rtt:192.168.1.50", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("via mDNS", substring = true).assertExists()
    }

    @Test
    fun `a host found both ways does not credit only one of them`() {
        // Found on the M10: a Canon printer named from an mDNS record was
        // labelled "via ping", because the source was whichever arrived
        // first rather than what actually contributed. A label the name
        // beside it contradicts is worse than no label.
        show(
            state(
                rows = listOf(
                    host("192.168.88.41", hostname = "Canon MF270", rtt = 75)
                        .copy(services = listOf("ipp"), source = NeighbourSource.BOTH),
                ),
                scanAt = 9_000L,
            ),
        )
        rule.onNodeWithText("via ping + mDNS", substring = true).assertExists()
    }

    @Test
    fun `an ipv6 only link says why there is nothing to sweep`() {
        show(state(addresses = listOf("fe80::1/64", "2001:db8::5/64")))
        rule.onNodeWithTag(emptyTag(EMPTY_NEIGHBOURS_NO_V4)).assertExists()
        rule.onNodeWithTag("neighbours:scan").assertIsNotEnabled()
    }

    @Test
    fun `a snapshot reader is never offered a sweep of this device's network`() {
        // FrozenSnapshotTest's rule, in behaviour: a sweep run while a file is
        // on screen would describe this phone's link under that file's banner.
        // True whether or not the file itself carries one.
        show(state(rows = listOf(host()), scanAt = 9_000L, frozen = true))

        rule.onNodeWithTag("neighbours:scan").assertIsNotEnabled()
    }

    @Test
    fun `a snapshot with nothing in it is still not a reason to sweep this device`() {
        show(state(frozen = true))

        rule.onNodeWithTag("neighbours:scan").assertIsNotEnabled()
    }

    @Test
    fun `a snapshot with no sweep in it says so`() {
        show(state(frozen = true))

        rule.onNodeWithTag(emptyTag(EMPTY_NEIGHBOURS_FROZEN)).assertExists()
    }

    /**
     * The exporter did not write the sweep, so this screen told every reader
     * that "a snapshot file carries no neighbour sweep" — true at the time. It
     * writes it now (see SnapshotJsonTest), and the same sentence over a file
     * that does carry twenty hosts would be the lie the first one wasn't. A
     * file from the TUI still has no scan time and still gets the message.
     */
    @Test
    fun `a snapshot that does carry a sweep shows it rather than denying it`() {
        show(state(rows = listOf(host(address = "192.168.1.20")), scanAt = 9_000L, frozen = true))

        rule.onNodeWithTag(emptyTag(EMPTY_NEIGHBOURS_FROZEN)).assertDoesNotExist()
        rule.onNodeWithTag("neighbour:192.168.1.20").assertExists()
    }

    /**
     * A frozen file's rows must not be gated on *this* device's link: the file
     * may have been taken on an IPv6-only or cellular link, or on a network
     * this phone has never seen.
     */
    @Test
    fun `a snapshot's sweep is shown even when this device has no prefix to sweep`() {
        show(
            state(
                rows = listOf(host(address = "192.168.1.21")),
                scanAt = 9_000L,
                addresses = listOf("fe80::1/64"),
                frozen = true,
            )
        )

        rule.onNodeWithTag("neighbour:192.168.1.21").assertExists()
        rule.onNodeWithTag(emptyTag(EMPTY_NEIGHBOURS_NO_V4)).assertDoesNotExist()
    }

    @Test
    fun `a sweep in progress disables its own button`() {
        show(state(running = true))
        rule.onNodeWithTag("neighbours:progress").assertExists()
        rule.onNodeWithTag("neighbours:scan").assertIsNotEnabled()
    }

    @Test
    fun `the range the sweep would cover is stated before it runs`() {
        show(state())
        rule.onNodeWithText("254 addresses in 192.168.1.5/24").assertExists()
    }
}
