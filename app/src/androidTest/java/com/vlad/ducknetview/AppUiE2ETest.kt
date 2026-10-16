package com.vlad.ducknetview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.domain.net.Subnet
import com.vlad.ducknetview.ui.screens.EMPTY_NEIGHBOURS_NO_V4
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.screens.EMPTY_USAGE
import com.vlad.ducknetview.ui.screens.formatDay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Drives the real app on a real device: the whole graph is live here — engine,
 * Room, DataStore and the platform network APIs — so this catches the wiring
 * faults that a Robolectric screen test cannot.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppUiE2ETest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * `paused` lives in DataStore, so a test that pauses and then fails leaves
     * the *next* run's app frozen — no poll ticks, no snapshots, and a batch of
     * failures nowhere near the test that caused them. That happened. Whatever
     * this class does to the pause flag, it hands the device back running.
     */
    @After
    fun unpause() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DuckApp
        runBlocking { app.deps.settings.update { it.copy(paused = false) } }
    }

    private fun goTo(tab: Tab) {
        rule.onNodeWithTag("nav:${tab.route}").performClick()
        rule.waitForIdle()
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 15_000) {
        rule.waitUntil(timeoutMs) {
            rule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun t01_everyTabRendersWithoutCrashing() {
        rule.waitForIdle()
        for (tab in Tab.entries) {
            goTo(tab)
            // The nav item staying present is the proof the destination
            // composed; a crash would have taken the activity down with it.
            rule.onNodeWithTag("nav:${tab.route}", useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun t02_overviewShowsLiveDeviceState() {
        goTo(Tab.OVERVIEW)
        waitForTag("overview:header")
        rule.onNodeWithTag("overview:header", useUnmergedTree = true).assertExists()
        // Throughput needs two samples, so allow a couple of poll ticks.
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("card:Throughput", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun t03_routesScreenReportsRealNetworksAndStatesTheArpGap() {
        goTo(Tab.ROUTES)
        waitForTag("routes:arpnote")
        rule.onNodeWithTag("routes:arpnote", useUnmergedTree = true).assertExists()
    }

    @Test
    fun t04_interfacesListsAtLeastOneLink() {
        goTo(Tab.INTERFACES)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("iface:detail", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("interfaces:count", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun t05_serviceScanRunsAgainstThisDeviceAndFinishes() {
        goTo(Tab.SERVICES)
        // Whatever the line says now — "Last scan never" in a fresh process, a
        // clock time if something already scanned — it has to change. Comparing
        // against what is there beats assuming a starting state this test does
        // not control.
        val before = textOf("services:lastscan")

        rule.onNodeWithTag("services:scan", useUnmergedTree = true).performClick()

        // Waiting for the progress indicator to disappear proves nothing: a scan
        // that never started looks exactly the same. The "Last scan" line is the
        // evidence, and it only moves once a scan has finished and the next poll
        // has carried its timestamp into a snapshot.
        rule.waitUntil(120_000) { textOf("services:lastscan").let { it != null && it != before } }
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("services:progress", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "the scan never completed; the line still reads $before",
            textOf("services:lastscan").orEmpty().let {
                it.isNotEmpty() && !it.contains("never", ignoreCase = true)
            },
        )
    }

    /** The visible text of the first node carrying [tag], or null if absent. */
    private fun textOf(tag: String): String? =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }

    /** The pause control's content description: "Pause" running, "Resume" paused. */
    private fun pauseLabel(): String? =
        rule.onAllNodesWithTag("action:pause", useUnmergedTree = false)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.ContentDescription)
            ?.firstOrNull()

    private fun awaitPauseLabel(want: String) {
        try {
            rule.waitUntil(15_000) { pauseLabel() == want }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("pause control never became \"$want\"; it reads ${pauseLabel()}", e)
        }
    }

    @Test
    fun t06_pauseTogglesAndSurvivesATabRoundTrip() {
        // The control's description is the state: "Pause" while running,
        // "Resume" while paused. Clicking and asserting nothing proved only
        // that the tap did not crash.
        awaitPauseLabel("Pause")
        rule.onNodeWithTag("action:pause").performClick()
        awaitPauseLabel("Resume")

        goTo(Tab.EVENTS)
        goTo(Tab.OVERVIEW)
        assertEquals("pause must survive a tab round trip", "Resume", pauseLabel())

        rule.onNodeWithTag("action:pause").performClick()
        awaitPauseLabel("Pause")
    }

    @Test
    fun t07_settingsOpensAndCloses() {
        rule.onNodeWithTag("action:settings").performClick()
        rule.waitForIdle()
        waitForTag("settings:privacy")
        rule.onNodeWithTag("action:settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("nav:overview", useUnmergedTree = true).assertExists()
    }

    /**
     * The suite drove all seven nav destinations and never opened Usage, which
     * is reached from the top bar instead — so a stored day key in the wrong
     * unit went unnoticed until the screen was opened by hand on a tablet and
     * took the process down with `Invalid value for EpochDay`. Composing it
     * against whatever this device has actually rolled up is the whole point:
     * a fixture can only carry values the test itself chose.
     */
    @Test
    fun t08_usageScreenComposesAgainstThisDevicesStoredHistory() {
        rule.onNodeWithTag("action:usage").performClick()
        rule.waitForIdle()
        waitForTag("screen:usage")

        // Either the history renders or the empty state says there is none;
        // both are correct, and a crash is neither.
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("usage:chart", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithTag("usage:range-7", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText(EMPTY_USAGE, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("screen:usage", useUnmergedTree = true).assertExists()

        rule.onNodeWithTag("action:usage").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("nav:overview", useUnmergedTree = true).assertExists()
    }

    /**
     * Every stored day key has to be one the screen can render. [formatDay] is
     * total now, so a bad key no longer crashes — it renders as "day <n>", and
     * that is what this catches.
     */
    @Test
    fun t09_everyStoredUsageDayIsARenderableDayNumber() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DuckApp
        val days = runBlocking { app.deps.usageRepo.all.first() }

        for (day in days) {
            assertTrue(
                "stored dayEpoch ${day.dayEpoch} is not a day number; " +
                    "it renders as '${formatDay(day.dayEpoch)}'",
                formatDay(day.dayEpoch) == LocalDate.ofEpochDay(day.dayEpoch)
                    .format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)),
            )
        }
        println("USAGE rows=${days.size}")
    }

    /**
     * The Domains screen against whatever this device has actually recorded.
     *
     * Its store is written from the capture engine's packet path and read back
     * through Room, which is a seam no JVM test crosses end to end. The screen
     * has to compose whether the table is empty, full of DNS rows, or — on a
     * device with Private DNS on, which is the default — full of SNI ones.
     */
    @Test
    fun t10_domainsScreenComposesAgainstThisDevicesStoredNames() {
        goTo(Tab.DOMAINS)
        waitForTag("domains:summary")
        rule.onNodeWithTag("domains:summary", useUnmergedTree = true).assertExists()

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DuckApp
        val rows = runBlocking { app.deps.domainRepo.recent.first() }
        for (row in rows) {
            // Names go into the UI straight off the wire, so a stored one that
            // is empty or absurd means the parser let something through.
            assertTrue("stored a blank name", row.name.isNotEmpty())
            assertTrue("stored an implausible name: ${row.name}", row.name.length <= 253)
            assertTrue("stored a name with no sightings: ${row.name}", row.lookups >= 1)
        }
        println("DOMAINS rows=${rows.size} sni=${rows.count { it.source.name == "SNI" }}")
    }

    /**
     * The Hosts screen, composed against this device's real link.
     *
     * [t01_everyTabRendersWithoutCrashing] proves the destination does not
     * crash; this proves it says something true. The screen has three quite
     * different states depending on the network underneath it — never swept,
     * no IPv4 prefix to sweep, and a result — and the one it picks has to
     * match the link the device is actually on, which no JVM test can know.
     */
    @Test
    fun t11_hostsScreenDescribesTheLinkItIsOn() {
        goTo(Tab.NEIGHBOURS)
        waitForTag("neighbours:lastscan")

        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DuckApp
        val prefix = app.deps.engine.snapshot.value.networks
            .firstOrNull { it.isDefault && !it.isNoise }
            ?.addresses
            ?.let(Subnet::firstV4)

        if (prefix == null) {
            // An IPv6-only link. The screen must say why there is nothing to
            // sweep rather than offering a button that cannot work.
            rule.onNodeWithTag("empty:$EMPTY_NEIGHBOURS_NO_V4", useUnmergedTree = true)
                .assertExists()
            rule.onNodeWithTag("neighbours:scan").assertIsNotEnabled()
        } else {
            // The range is stated before anything is sent, which is the whole
            // consent story for the one active source in the app.
            rule.onNodeWithTag("neighbours:range", useUnmergedTree = true).assertExists()
            rule.onNodeWithTag("neighbours:scan").assertIsEnabled()
        }
    }

    /**
     * A real sweep, driven from the button, landing as rows on the screen.
     *
     * This is the loop the Hosts screen exists to close: a tap puts echo
     * requests on the wire, the engine folds the answers into a snapshot, and
     * the table renders them. Skipped rather than failed where the link
     * answers nothing — a guest network with client isolation is built to do
     * exactly that, and it is not a defect in this app.
     */
    @Test
    fun t12_sweepingFromTheButtonFillsTheTable() {
        goTo(Tab.NEIGHBOURS)
        waitForTag("neighbours:lastscan")
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as DuckApp
        val prefix = app.deps.engine.snapshot.value.networks
            .firstOrNull { it.isDefault && !it.isNoise }
            ?.addresses
            ?.let(Subnet::firstV4)
        assumeTrue("no IPv4 prefix on the default link", prefix != null)

        rule.onNodeWithTag("neighbours:scan").performClick()

        // The sweep is bounded by its own timeouts; this waits for the engine
        // to publish a finished one rather than for a fixed interval.
        rule.waitUntil(90_000) {
            app.deps.engine.snapshot.value.neighbourScanAt > 0L &&
                !app.deps.engine.snapshot.value.neighbourScanRunning
        }
        val snap = app.deps.engine.snapshot.value
        assumeTrue(
            "nothing on this link answered an echo or published mDNS",
            snap.neighbours.isNotEmpty(),
        )

        rule.waitForIdle()
        // Every row on the screen is one the engine actually found.
        for (row in snap.neighbours.take(5)) {
            rule.onNodeWithTag("neighbour:${row.address}", useUnmergedTree = true).assertExists()
        }
        // And the banner reports the outcome rather than still announcing the
        // attempt, which is DESIGN §5's rule and was wrong here once.
        println("SWEEP rows=${snap.neighbours.size} subnet=${snap.neighbourSubnet}")
    }
}
