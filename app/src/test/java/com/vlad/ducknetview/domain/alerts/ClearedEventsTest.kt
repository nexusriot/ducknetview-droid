package com.vlad.ducknetview.domain.alerts

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearedEventsTest {

    private val s = AppSettings(alertAppBps = 1_000, alertConnBps = 1_000, alertRttMs = 100)
    private val hot = Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 5_000)))
    private val calm = Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 10)))

    @Test
    fun aBreachIsReportedOverAfterAMinuteUnder() {
        val rules = AlertRules()
        assertEquals(EventKind.THRESHOLD_APP_BPS, rules.evaluate(hot, s, 0).single().kind)
        assertTrue(rules.evaluate(calm, s, 30_000).isEmpty())
        val cleared = rules.evaluate(calm, s, 60_000).single()
        assertEquals(EventKind.THRESHOLD_APP_BPS_CLEARED, cleared.kind)
        assertEquals(EventLevel.INFO, cleared.level)
        assertTrue(cleared.subject.contains("Browser"))
    }

    @Test
    fun aSubjectStillOverDuringTheCooldownIsNotCleared() {
        val rules = AlertRules()
        rules.evaluate(hot, s, 0)
        // Over at 50 s: the cooldown suppresses the alert but the breach is live.
        assertTrue(rules.evaluate(hot, s, 50_000).isEmpty())
        assertTrue(rules.evaluate(calm, s, 100_000).isEmpty())
        assertEquals(
            EventKind.THRESHOLD_APP_BPS_CLEARED,
            rules.evaluate(calm, s, 110_000).single().kind,
        )
    }

    @Test
    fun aSubjectThatWentAwayIsCleared() {
        val rules = AlertRules()
        val conn = Fixtures.snapshot(conns = listOf(Fixtures.conn(rxBps = 5_000, rttMillis = 500)))
        val kinds = rules.evaluate(conn, s, 0).map { it.kind }.toSet()
        assertEquals(setOf(EventKind.THRESHOLD_CONN_BPS, EventKind.THRESHOLD_RTT), kinds)
        val after = rules.evaluate(Fixtures.snapshot(), s, 60_000).map { it.kind }.toSet()
        assertEquals(
            setOf(EventKind.THRESHOLD_CONN_BPS_CLEARED, EventKind.THRESHOLD_RTT_CLEARED),
            after,
        )
    }

    @Test
    fun clearedIsReportedOnce() {
        val rules = AlertRules()
        rules.evaluate(hot, s, 0)
        assertEquals(1, rules.evaluate(calm, s, 60_000).size)
        assertTrue(rules.evaluate(calm, s, 120_000).isEmpty())
    }

    @Test
    fun switchingTheRuleOffDropsTheBreachSilently() {
        val rules = AlertRules()
        rules.evaluate(hot, s, 0)
        assertTrue(rules.evaluate(calm, s.copy(alertAppBps = 0), 60_000).isEmpty())
        assertTrue(rules.evaluate(calm, s, 120_000).isEmpty())
    }

    @Test
    fun burstRulesHaveNoClearedCounterpart() {
        val rules = AlertRules()
        val fan = s.copy(alertFanoutHosts = 2)
        val burst = Fixtures.snapshot(
            conns = listOf(
                Fixtures.conn(key = "a", remoteAddr = "1.1.1.1"),
                Fixtures.conn(key = "b", remoteAddr = "2.2.2.2"),
            ),
        )
        assertEquals(EventKind.FANOUT, rules.evaluate(burst, fan, 0).single().kind)
        assertTrue(rules.evaluate(Fixtures.snapshot(), fan, 120_000).isEmpty())
    }
}
