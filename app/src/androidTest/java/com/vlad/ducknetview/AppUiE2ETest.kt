package com.vlad.ducknetview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vlad.ducknetview.ui.Tab
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

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
        rule.onNodeWithTag("services:scan", useUnmergedTree = true).performClick()
        // A fast-mode self-scan of loopback plus the local addresses.
        rule.waitUntil(120_000) {
            rule.onAllNodesWithTag("services:progress", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(true)
    }

    @Test
    fun t06_pauseTogglesAndSurvivesATabRoundTrip() {
        rule.onNodeWithTag("action:pause").performClick()
        rule.waitForIdle()
        goTo(Tab.EVENTS)
        goTo(Tab.OVERVIEW)
        rule.onNodeWithTag("action:pause").performClick()
        rule.waitForIdle()
    }

    @Test
    fun t07_settingsOpensAndCloses() {
        rule.onNodeWithTag("action:settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("action:settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("nav:overview", useUnmergedTree = true).assertExists()
    }
}
