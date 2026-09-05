package com.vlad.ducknetview.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class InfoSheetsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun apiState() = screenState(
        caps = Capabilities.API,
        snapshot = NetSnapshot(atMillis = SCREEN_NOW, engine = EngineMode.API),
    )

    private fun vpnState() = screenState(
        caps = Capabilities.VPN,
        snapshot = NetSnapshot(atMillis = SCREEN_NOW, engine = EngineMode.VPN),
        vpnRunning = true,
    )

    private fun apiInfo(tab: Tab) = infoFor(tab, Capabilities.API, EngineMode.API)
    private fun vpnInfo(tab: Tab) = infoFor(tab, Capabilities.VPN, EngineMode.VPN)

    @Test
    fun `every tab has content in API mode`() {
        for (tab in Tab.entries) {
            val lines = apiInfo(tab)
            assertTrue("$tab had no API-mode content", lines.isNotEmpty())
        }
    }

    @Test
    fun `every tab has content in capture mode`() {
        for (tab in Tab.entries) {
            val lines = vpnInfo(tab)
            assertTrue("$tab had no capture-mode content", lines.isNotEmpty())
        }
    }

    @Test
    fun `no heading or body is blank for any tab in either mode`() {
        for (tab in Tab.entries) {
            for (lines in listOf(apiInfo(tab), vpnInfo(tab))) {
                for ((heading, body) in lines) {
                    assertTrue("$tab had a blank heading", heading.isNotBlank())
                    assertTrue("$tab / $heading had a blank body", body.isNotBlank())
                }
            }
        }
    }

    @Test
    fun `headings are unique within a tab`() {
        for (tab in Tab.entries) {
            for (lines in listOf(apiInfo(tab), vpnInfo(tab))) {
                val headings = lines.map { it.first }
                assertEquals("$tab repeated a heading", headings.size, headings.toSet().size)
            }
        }
    }

    @Test
    fun `the connections entry differs between the two modes`() {
        assertNotEquals(apiInfo(Tab.CONNECTIONS), vpnInfo(Tab.CONNECTIONS))
    }

    @Test
    fun `connections in API mode explains why no live table is possible`() {
        val text = apiInfo(Tab.CONNECTIONS).joinToString(" ") { "${it.first} ${it.second}" }
        assertTrue(text.contains("sock_diag"))
        assertTrue(text.contains("Android 10"))
        assertTrue(text.contains("capture", ignoreCase = true))
    }

    @Test
    fun `connections in capture mode does not claim the table is empty`() {
        val text = vpnInfo(Tab.CONNECTIONS).joinToString(" ") { it.second }
        assertFalse(text.contains("empty by design"))
        assertTrue(text.contains("RTT"))
    }

    @Test
    fun `connections says packet contents are never stored in both modes`() {
        for (lines in listOf(apiInfo(Tab.CONNECTIONS), vpnInfo(Tab.CONNECTIONS))) {
            val text = lines.joinToString(" ") { it.second }
            assertTrue(text.contains("never stored"))
        }
    }

    @Test
    fun `services explains the self-scan and the UDP gap in both modes`() {
        for (lines in listOf(apiInfo(Tab.SERVICES), vpnInfo(Tab.SERVICES))) {
            val text = lines.joinToString(" ") { "${it.first} ${it.second}" }
            assertTrue(text.contains("forbids enumerating"))
            assertTrue(text.contains("UDP"))
        }
    }

    @Test
    fun `services owning-app wording follows the mode`() {
        assertTrue(apiInfo(Tab.SERVICES).any { it.second.contains("without the capture engine") })
        assertTrue(vpnInfo(Tab.SERVICES).any { it.second.contains("uid lookup") })
    }

    @Test
    fun `apps says CPU and memory of other processes are unavailable`() {
        for (lines in listOf(apiInfo(Tab.APPS), vpnInfo(Tab.APPS))) {
            val text = lines.joinToString(" ") { it.second }
            assertTrue(text.contains("CPU%"))
            assertTrue(text.contains("unavailable on Android"))
        }
    }

    @Test
    fun `apps live-rate wording follows the capability, not the tab`() {
        assertTrue(apiInfo(Tab.APPS).any { it.second.startsWith("Coarse") })
        assertTrue(vpnInfo(Tab.APPS).any { it.second.startsWith("Exact") })
    }

    @Test
    fun `routes says the neighbour table is gone on Android 10 and later`() {
        for (lines in listOf(apiInfo(Tab.ROUTES), vpnInfo(Tab.ROUTES))) {
            val text = lines.joinToString(" ") { "${it.first} ${it.second}" }
            assertTrue(text.contains("ARP"))
            assertTrue(text.contains("Android 10"))
        }
    }

    @Test
    fun `routes distinguishes a live TUN route from a future one`() {
        assertTrue(vpnInfo(Tab.ROUTES).any { it.second.startsWith("A default route") })
        assertTrue(apiInfo(Tab.ROUTES).any { it.second.startsWith("Starting capture") })
    }

    @Test
    fun `links names the location permission and the missing counters`() {
        for (lines in listOf(apiInfo(Tab.INTERFACES), vpnInfo(Tab.INTERFACES))) {
            val text = lines.joinToString(" ") { "${it.first} ${it.second}" }
            assertTrue(text.contains("location permission"))
            assertTrue(text.contains("drop"))
        }
    }

    @Test
    fun `events covers change detection in both modes`() {
        for (lines in listOf(apiInfo(Tab.EVENTS), vpnInfo(Tab.EVENTS))) {
            val text = lines.joinToString(" ") { "${it.first} ${it.second}" }
            assertTrue(text.contains("watchlist"))
            assertTrue(text.contains("exposure"))
        }
    }

    @Test
    fun `events says which alert rules cannot run without capture`() {
        assertTrue(apiInfo(Tab.EVENTS).any { it.second.contains("not evaluated") })
        assertTrue(vpnInfo(Tab.EVENTS).any { it.second.contains("All five alert rules") })
    }

    @Test
    fun `overview leads with the engine the app is actually in`() {
        assertEquals("Engine: API", apiInfo(Tab.OVERVIEW).first().first)
        assertEquals("Engine: capture", vpnInfo(Tab.OVERVIEW).first().first)
    }

    @Test
    fun `overview admits the external IP lookup leaves the device`() {
        val text = apiInfo(Tab.OVERVIEW).joinToString(" ") { it.second }
        assertTrue(text.contains("ipify"))
    }

    @Test
    fun `capabilities alone can flip the wording without the engine field`() {
        val mixed = infoFor(Tab.CONNECTIONS, Capabilities.VPN, EngineMode.API)
        assertEquals(vpnInfo(Tab.CONNECTIONS), mixed)
    }

    @Test
    fun `usage info names the permission and the retention window`() {
        val text = usageInfo(false).joinToString(" ") { "${it.first} ${it.second}" }
        assertTrue(text.contains("Usage Access"))
        assertTrue(text.contains("40 days"))
        assertTrue(text.contains("top 50"))
    }

    @Test
    fun `usage info changes once access is granted`() {
        assertNotEquals(usageInfo(false), usageInfo(true))
        assertTrue(usageInfo(true).first().second.contains("granted"))
    }

    @Test
    fun `the screen info sheet renders the current tab's content`() {
        rule.setContent {
            DuckTheme { ScreenInfoSheet(Tab.CONNECTIONS, apiState(), onDismiss = {}) }
        }
        rule.onNodeWithTag("info:connections").assertExists()
        rule.onNodeWithText("No live table in API mode").assertExists()
    }

    @Test
    fun `the same sheet in capture mode shows the capture wording`() {
        rule.setContent {
            DuckTheme { ScreenInfoSheet(Tab.CONNECTIONS, vpnState(), onDismiss = {}) }
        }
        rule.onNodeWithTag("info:connections").assertExists()
        rule.onNodeWithText("Live flows").assertExists()
        rule.onNodeWithText("No live table in API mode").assertDoesNotExist()
    }

    @Test
    fun `at least one entry per tab reacts to the mode`() {
        for (tab in Tab.entries) {
            assertNotEquals("$tab reads the same in both modes", apiInfo(tab), vpnInfo(tab))
        }
    }

    @Test
    fun `no entry promises a live connection table while in API mode`() {
        for (tab in Tab.entries) {
            val text = apiInfo(tab).joinToString(" ") { it.second }
            assertFalse(
                "$tab claimed exact per-connection data in API mode",
                text.contains("per-connection totals are exact"),
            )
        }
    }

    @Test
    fun `the info sheet close button raises the dismiss callback`() {
        var dismissed = 0
        rule.setContent {
            DuckTheme { ScreenInfoSheet(Tab.APPS, apiState(), onDismiss = { dismissed++ }) }
        }
        rule.onNodeWithTag("info:close").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, dismissed)
    }

    @Test
    fun `the usage info sheet renders`() {
        rule.setContent {
            DuckTheme { UsageInfoSheet(apiState(), onDismiss = {}) }
        }
        rule.onNodeWithTag("info:usage").assertExists()
        rule.onNodeWithText("Per-app history").assertExists()
    }

    @Test
    fun `onboarding content covers both engines, privacy and the single-VPN rule`() {
        val text = ONBOARDING_LINES.joinToString(" ") { "${it.first} ${it.second}" }
        assertTrue(text.contains("API mode"))
        assertTrue(text.contains("VpnService"))
        assertTrue(text.contains("never stored"))
        assertTrue(text.contains("single active VPN"))
    }

    @Test
    fun `onboarding lines are all populated`() {
        assertTrue(ONBOARDING_LINES.isNotEmpty())
        for ((heading, body) in ONBOARDING_LINES) {
            assertTrue(heading.isNotBlank())
            assertTrue(body.isNotBlank())
        }
    }

    @Test
    fun `the onboarding sheet renders its title and both buttons`() {
        rule.setContent {
            DuckTheme { OnboardingSheet(onDismiss = {}, onEnableCapture = {}) }
        }
        rule.onNodeWithTag("info:onboarding").assertExists()
        rule.onNodeWithText(ONBOARDING_TITLE).assertExists()
        rule.onNodeWithTag("info:enable").assertExists()
        rule.onNodeWithTag("info:later").assertExists()
    }

    @Test
    fun `not now raises the settings update that marks onboarding seen`() {
        val actions = RecordingActions()
        rule.setContent {
            DuckTheme {
                OnboardingSheet(
                    onDismiss = { actions.updateSettings { s -> s.copy(onboardingShown = true) } },
                    onEnableCapture = {},
                )
            }
        }
        rule.onNodeWithTag("info:later").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(actions.settings.onboardingShown)
        assertTrue(actions.calls.contains("updateSettings"))
    }

    @Test
    fun `enable capture both marks onboarding seen and starts the engine`() {
        val actions = RecordingActions()
        rule.setContent {
            DuckTheme {
                OnboardingSheet(
                    onDismiss = {},
                    onEnableCapture = {
                        actions.updateSettings { s -> s.copy(onboardingShown = true) }
                        actions.startVpn()
                    },
                )
            }
        }
        rule.onNodeWithTag("info:enable").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(actions.settings.onboardingShown)
        assertEquals(1, actions.startVpnCount)
    }

    @Test
    fun `onboarding starts hidden for a user who has already seen it`() {
        val seen = AppSettings(onboardingShown = true)
        assertTrue(seen.onboardingShown)
        assertFalse(AppSettings().onboardingShown)
    }
}
