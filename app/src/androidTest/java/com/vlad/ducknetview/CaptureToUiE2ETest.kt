package com.vlad.ducknetview

import android.net.VpnService
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Closes the whole loop on a real device: packets off the TUN become flows,
 * flows become a snapshot, and the snapshot renders as rows the user can see.
 * Every layer in between is live — no fakes.
 */
@RunWith(AndroidJUnit4::class)
class CaptureToUiE2ETest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startCapture() {
        assumeTrue(
            "VPN consent not pre-granted (appops ACTIVATE_VPN)",
            VpnService.prepare(context) == null,
        )
        VpnBridge.captureOwnTraffic = true
        rule.waitForIdle()
        DuckVpnService.start(context)
        rule.waitUntil(25_000) { VpnBridge.table != null }
    }

    @After
    fun stopCapture() {
        DuckVpnService.stop(context)
        VpnBridge.captureOwnTraffic = false
    }

    @Test
    fun capturedTrafficAppearsAsRowsOnTheConnectionsScreen() {
        repeat(3) { fetch("https://connectivitycheck.gstatic.com/generate_204") }

        rule.onNodeWithTag("nav:connections").performClick()
        rule.waitForIdle()

        // The snapshot is produced on the poll tick, so allow a few cycles for
        // the flows to travel engine -> assembler -> UI.
        rule.waitUntil(40_000) { connRowCount() > 0 }
        assertTrue("expected captured flows to render as rows", connRowCount() > 0)
    }

    @Test
    fun overviewReportsCaptureModeOnceTheEngineIsUp() {
        rule.onNodeWithTag("nav:overview").performClick()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("badge:VPN capture", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Rows are tagged "conn:<flow key>", so match on the prefix. */
    private fun connRowCount(): Int = rule.onAllNodes(
        SemanticsMatcher("testTag starts with conn:") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag).orEmpty()
            tag.startsWith("conn:") && !tag.startsWith("conn:rtt:")
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private fun fetch(url: String): Int = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Connection", "close")
            val code = c.responseCode
            runCatching { c.inputStream.bufferedReader().use(BufferedReader::readText) }
            code
        } finally {
            c.disconnect()
        }
    }.getOrDefault(-1)
}
