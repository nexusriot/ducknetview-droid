package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing from the live device may appear while a snapshot file is open.
 *
 * Five of the eight tables are built from the snapshot, so opening a file
 * replaces them. Three — the event log, the name history and the usage
 * rollups — come from this device's own Room database instead, and they were
 * passed through untouched: the Events screen went on listing this phone's
 * events, with timestamps *after* the snapshot was taken, under a banner
 * reading "browsing ducknetview_snap.json". The documented use for the feature
 * is inspecting a snapshot taken on a server, so that is two machines' data in
 * one view with nothing saying which is which.
 *
 * The snapshot format carries none of the three — there is no `events`,
 * `domains` or `usage` key in it — so under a frozen snapshot they are empty
 * and the screens say why.
 */
class FrozenSnapshotTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val viewModel: String by lazy { File(sourceRoot, "ui/MainViewModel.kt").readText() }

    @Test
    fun `the room-backed tables are gated on the frozen snapshot`() {
        for (name in listOf("liveEvents", "liveDomains", "liveUsage")) {
            assertTrue(
                "$name is not derived from the frozen state, so a snapshot view will " +
                    "show this device's own rows",
                Regex("""val $name = if \(frozen != null\) emptyList\(\)""")
                    .containsMatchIn(viewModel),
            )
        }
    }

    @Test
    fun `no room-backed table reaches UiState un-gated`() {
        // Past the gate's own three lines, the raw sources must not be read
        // again, or whichever use was missed bypasses the gate. The bound is
        // the end of the gate block, and the match is anchored so that
        // `misc.usageLoading` — a spinner flag, not a table — does not count.
        val afterGate = viewModel
            .substringAfter("val liveUsage =")
            .substringAfter("\n")
        for (raw in listOf("misc.domains", "misc.usage")) {
            assertTrue(
                "$raw is still read directly after the frozen gate",
                !Regex(Regex.escape(raw) + """\b(?!Loading)""").containsMatchIn(afterGate),
            )
        }
    }

    @Test
    fun `the snapshot format really does carry none of the three`() {
        // If a later version adds them, this test should fail and the gate
        // above should become "read them from the snapshot" instead.
        val json = File(sourceRoot, "domain/export/SnapshotJson.kt").readText()
        val encoded = json.substringAfter("fun encode").substringBefore("fun decode")
        for (key in listOf("\"events\"", "\"domains\"", "\"usage\"")) {
            assertTrue(
                "the snapshot now encodes $key, so the frozen screens should show it " +
                    "rather than being blank",
                !encoded.contains(key),
            )
        }
    }

    @Test
    fun `each frozen table says why it is empty`() {
        for (screen in listOf("EventsScreen.kt", "DomainsScreen.kt", "UsageScreen.kt")) {
            val text = File(sourceRoot, "ui/screens/$screen").readText()
            assertTrue(
                "$screen renders an empty table under a snapshot without saying why",
                text.contains("frozenTableMessage("),
            )
        }
    }

    @Test
    fun `no empty state tells a snapshot reader to change this device`() {
        // "Enable capture" starts *this* phone's VPN. Offered while reading a
        // file taken somewhere else, it acts on the wrong machine, and it can
        // never fill the table being looked at — the file is fixed.
        val conns = File(sourceRoot, "ui/screens/ConnectionsScreen.kt").readText()
        val emptyState = conns.substringAfter("EMPTY_CONNS_NO_CAPTURE").substringBefore("return")
        assertTrue(
            "the Conns empty state offers Enable capture unconditionally",
            emptyState.contains("if (frozen) null else"),
        )

        // Same for the advice text: "until capture is on" is not something the
        // reader of a file can act on.
        val domains = File(sourceRoot, "ui/screens/DomainsScreen.kt").readText()
        val frozenAt = domains.indexOf("state.snapshot.frozen")
        val noCaptureAt = domains.indexOf("!state.caps.hasConnections")
        assertTrue(
            "the no-capture branch is checked before the frozen one, so a snapshot " +
                "taken in API mode is explained as this device's capture being off",
            frozenAt in 0 until noCaptureAt,
        )
    }
}
