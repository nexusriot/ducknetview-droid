package com.vlad.ducknetview.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp")
class DomainsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        state: UiState,
        actions: RecordingActions = RecordingActions(),
        twoPane: Boolean = false,
    ) {
        rule.setContent {
            DuckTheme { DomainsScreen(state = state, actions = actions, twoPane = twoPane) }
        }
    }

    private fun domain(
        name: String,
        uid: Int = 10100,
        source: NameSource = NameSource.DNS,
        lookups: Int = 3,
        app: String = "Browser",
        watchlisted: Boolean = false,
    ) = DomainRow(
        name = name,
        uid = uid,
        appLabel = app,
        packageName = "com.example.browser",
        source = source,
        lookups = lookups,
        firstSeen = SCREEN_NOW - 600_000,
        lastSeen = SCREEN_NOW - 30_000,
        addresses = listOf("93.184.216.34"),
        watchlisted = watchlisted,
    )

    @Test
    fun withCaptureOffTheScreenExplainsWhyItIsEmpty() {
        show(screenState(caps = Capabilities.API))
        rule.onNodeWithTag(emptyTag(EMPTY_DOMAINS_NO_CAPTURE)).assertExists()
        rule.onNodeWithText("capture engine relays", substring = true).assertExists()
    }

    @Test
    fun withCaptureOnAndNothingSeenYetTheOtherEmptyStateShows() {
        show(screenState(caps = Capabilities.VPN))
        rule.onNodeWithTag(emptyTag(EMPTY_DOMAINS)).assertExists()
        rule.onNodeWithTag(emptyTag(EMPTY_DOMAINS_NO_CAPTURE)).assertDoesNotExist()
    }

    @Test
    fun theEmptyStateUnderPrivateDnsSaysLookupsAreEncrypted() {
        // An empty table on such a network is the encryption working, not a gap
        // in what the app can see, and it has to say which.
        show(screenState(caps = Capabilities.VPN, privateDns = "dns.example.net"))
        rule.onNodeWithText("Lookups on this network are encrypted", substring = true).assertExists()
    }

    @Test
    fun thePrivateDnsBannerNamesTheResolver() {
        show(
            screenState(
                caps = Capabilities.VPN,
                domains = listOf(domain("example.com")),
                privateDns = "dns.example.net",
            )
        )
        rule.onNodeWithTag("domains:privatedns").assertExists()
        rule.onNodeWithText("dns.example.net", substring = true).assertExists()
    }

    @Test
    fun withoutPrivateDnsThereIsNoBannerToExplainAway() {
        show(screenState(caps = Capabilities.VPN, domains = listOf(domain("example.com"))))
        rule.onNodeWithTag("domains:privatedns").assertDoesNotExist()
    }

    @Test
    fun aRowShowsTheNameTheAppAndTheLookupCount() {
        show(screenState(caps = Capabilities.VPN, domains = listOf(domain("example.com"))))
        rule.onNodeWithTag("domain:example.com:10100").assertExists()
        rule.onNodeWithText("example.com").assertExists()
        rule.onNodeWithText("Browser", substring = true).assertExists()
        rule.onNodeWithText("3 lookups", substring = true).assertExists()
    }

    @Test
    fun theSourceOfEachNameIsOnTheRow() {
        show(
            screenState(
                caps = Capabilities.VPN,
                domains = listOf(
                    domain("dns.example.com", source = NameSource.DNS),
                    domain("sni.example.com", uid = 10200, source = NameSource.SNI),
                ),
            )
        )
        // The tags sit inside a merged clickable row, so the unmerged tree is
        // the only place they are addressable.
        rule.onNodeWithTag("domain:source:dns.example.com:10100", useUnmergedTree = true)
            .assertExists()
        rule.onNodeWithTag("domain:source:sni.example.com:10200", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun theSummaryCallsOutHowManyNamesCameFromSni() {
        show(
            screenState(
                caps = Capabilities.VPN,
                domains = listOf(
                    domain("a.example.com"),
                    domain("b.example.com", uid = 10200, source = NameSource.SNI),
                ),
            )
        )
        rule.onNodeWithText("2 names · 1 from SNI").assertExists()
    }

    @Test
    fun clearRaisesClearDomains() {
        val actions = RecordingActions()
        show(screenState(caps = Capabilities.VPN, domains = listOf(domain("example.com"))), actions)
        rule.onNodeWithTag("domains:clear").performClick()
        assertTrue("clearDomains" in actions.calls)
    }

    @Test
    fun exportRaisesExportCurrentTable() {
        val actions = RecordingActions()
        show(screenState(caps = Capabilities.VPN, domains = listOf(domain("example.com"))), actions)
        rule.onNodeWithTag("domains:export").performClick()
        assertTrue("exportCurrentTable" in actions.calls)
    }

    @Test
    fun aSortChipRaisesSetSortForTheDomainsTable() {
        val actions = RecordingActions()
        show(screenState(caps = Capabilities.VPN, domains = listOf(domain("example.com"))), actions)
        rule.onNodeWithText("lookups").performClick()
        assertEquals("domains" to "lookups", actions.lastSort)
    }

    @Test
    fun tappingARowOpensItsDetailWithTheSourceSpeltOut() {
        show(
            screenState(
                caps = Capabilities.VPN,
                domains = listOf(domain("sni.example.com", source = NameSource.SNI)),
            )
        )
        rule.onNodeWithTag("domain:sni.example.com:10100").performClick()
        rule.onNodeWithText("TLS SNI (no DNS answer was visible)", substring = true).assertExists()
    }

    @Test
    fun theTwoPaneLayoutPromptsBeforeAnythingIsSelected() {
        show(screenState(caps = Capabilities.VPN, domains = listOf(domain("example.com"))), twoPane = true)
        rule.onNodeWithText("Select a name to see its detail.").assertExists()
    }
}
