package com.vlad.ducknetview.domain.firewall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirewallTest {

    private val browser = 10001
    private val other = 10002
    private val uids = mapOf("com.example.browser" to browser, "com.example.other" to other)
    private val anywhere = LinkContext()

    private fun fw(
        rules: List<FirewallRule> = emptyList(),
        blocklist: Set<String> = emptySet(),
        policies: Map<Int, AppPolicy> = emptyMap(),
        blocked: Set<Int> = emptySet(),
        budget: Set<Int> = emptySet(),
    ) = Firewall.compile(rules, blocklist, policies, blocked, budget, uids::get)

    @Test
    fun emptyFirewallLetsEverythingThrough() {
        assertTrue(Firewall.EMPTY.isEmpty)
        assertNull(Firewall.EMPTY.verdict(browser, "1.2.3.4", "example.com", anywhere))
    }

    @Test
    fun aBlockedAppIsRefusedEverywhere() {
        assertEquals("app blocked", fw(blocked = setOf(browser)).verdict(browser, "1.2.3.4", null, anywhere))
        assertNull(fw(blocked = setOf(browser)).verdict(other, "1.2.3.4", null, anywhere))
    }

    @Test
    fun aBudgetBlockSaysWhy() {
        assertEquals("over its data budget", fw(budget = setOf(browser)).verdict(browser, "1.2.3.4", null, anywhere))
    }

    @Test
    fun aDomainRuleMatchesItselfAndSubdomainsOnly() {
        val f = fw(rules = listOf(FirewallRule("tracker.example")))
        assertNotNull(f.nameVerdict(browser, "tracker.example"))
        assertNotNull(f.nameVerdict(browser, "a.b.tracker.example"))
        assertNull(f.nameVerdict(browser, "nottracker.example"))
        assertNull(f.nameVerdict(browser, "example"))
    }

    @Test
    fun aNameAlreadyLearntRefusesTheFlowWhenItOpens() {
        val f = fw(rules = listOf(FirewallRule("ads.example.com")))
        assertNotNull(f.verdict(browser, "203.0.113.5", "cdn.ads.example.com", anywhere))
        assertNull(f.verdict(browser, "203.0.113.5", null, anywhere))
    }

    @Test
    fun anAppRuleTouchesOnlyThatApp() {
        val f = fw(rules = listOf(FirewallRule("example.com", "com.example.browser")))
        assertNotNull(f.nameVerdict(browser, "www.example.com"))
        assertNull(f.nameVerdict(other, "www.example.com"))
    }

    @Test
    fun aRuleForAnUninstalledPackageCompilesToNothingNotToEveryone() {
        val f = fw(rules = listOf(FirewallRule("example.com", "com.gone")))
        assertNull(f.nameVerdict(browser, "example.com"))
        assertNull(f.nameVerdict(other, "example.com"))
    }

    @Test
    fun addressAndCidrRulesMatchNormalisedForms() {
        val f = fw(rules = listOf(FirewallRule("8.8.8.8"), FirewallRule("10.0.0.0/8"), FirewallRule("2001:db8::1")))
        assertNotNull(f.verdict(browser, "8.8.8.8", null, anywhere))
        assertNotNull(f.verdict(browser, "10.20.30.40", null, anywhere))
        assertNotNull(f.verdict(browser, "2001:0db8:0:0:0:0:0:1", null, anywhere))
        assertNull(f.verdict(browser, "11.0.0.1", null, anywhere))
    }

    @Test
    fun theBlocklistMatchesParentsButNeverABareTld() {
        val f = fw(blocklist = setOf("doubleclick.net"))
        assertTrue(f.nameVerdict(browser, "ad.doubleclick.net")!!.startsWith("blocklist"))
        assertNull(f.nameVerdict(browser, "net"))
    }

    @Test
    fun policiesApplyOnlyOnTheirLink() {
        val f = fw(policies = mapOf(browser to AppPolicy(browser, blockMobile = true, blockScreenOff = true)))
        assertNull(f.verdict(browser, "1.2.3.4", null, LinkContext()))
        assertEquals("blocked on mobile data", f.verdict(browser, "1.2.3.4", null, LinkContext(mobile = true)))
        assertEquals("blocked while the screen is off", f.verdict(browser, "1.2.3.4", null, LinkContext(screenOff = true)))
        assertNull(f.verdict(other, "1.2.3.4", null, LinkContext(mobile = true)))
    }

    @Test
    fun rulesRoundTripThroughTheirEncoding() {
        val r = FirewallRule("Example.COM.", "com.example.browser")
        assertEquals(FirewallRule("example.com", "com.example.browser"), FirewallRule.decode(r.encode()))
        assertNull(FirewallRule.decode("no-bar-here"))
        assertNull(FirewallRule.decode("|com"))
    }

    @Test
    fun problemsAreNamedRatherThanStored() {
        assertNull(FirewallRule.problem("example.com"))
        assertNull(FirewallRule.problem("*.example.com"))
        assertNull(FirewallRule.problem("192.168.0.0/16"))
        assertNotNull(FirewallRule.problem(""))
        assertNotNull(FirewallRule.problem("localhost"))
        assertNotNull(FirewallRule.problem("10.0.0.0/99"))
        assertNotNull(FirewallRule.problem("exa mple.com"))
        assertNotNull(FirewallRule.problem("evil$.com"))
    }

    @Test
    fun policiesRoundTripAndAnEmptyOneVanishes() {
        val p = AppPolicy(42, blockMetered = true, blockUntrusted = true)
        assertEquals(p, AppPolicy.decode(p.encode()))
        assertNull(AppPolicy.decode(AppPolicy(42).encode()))
        assertNull(AppPolicy.decode("garbage"))
        assertEquals(setOf(42), AppPolicy.decodeAll(listOf(p.encode(), "x")).keys)
    }

    @Test
    fun blocklistParsesHostsFormatAndSkipsLocalhost() {
        val parsed = Blocklist.parse(
            sequenceOf(
                "# comment",
                "127.0.0.1 localhost",
                "0.0.0.0 ads.example.com # inline",
                "::1 ip6-localhost",
                ":: tracker.example",
                "bare.example.org",
                "0.0.0.0 0.0.0.0",
                "garbage line here with spaces",
                "",
            )
        )
        assertEquals(setOf("ads.example.com", "tracker.example", "bare.example.org"), parsed)
        assertTrue(Blocklist.matches(parsed, "x.ads.example.com"))
        assertFalse(Blocklist.matches(parsed, "example.com"))
    }

    @Test
    fun blocklistStopsAtItsLimit() {
        val parsed = Blocklist.parse((1..100).asSequence().map { "0.0.0.0 h$it.example" }, limit = 10)
        assertEquals(10, parsed.size)
    }
}
