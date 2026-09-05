package com.vlad.ducknetview.domain.alerts

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {

    private val off = AppSettings()

    @Test
    fun noThresholdsMeansNoEvents() {
        val rules = AlertRules()
        val snap = Fixtures.snapshot(
            apps = listOf(Fixtures.app(rxBps = 10_000_000)),
            conns = listOf(Fixtures.conn(rxBps = 10_000_000, rttMillis = 5_000)),
        )
        assertTrue(rules.evaluate(snap, off, 1_000).isEmpty())
    }

    @Test
    fun appThroughputAboveThresholdAlerts() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 1_000)
        val snap = Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 600, txBps = 500)))
        val events = rules.evaluate(snap, s, 1_000)
        assertEquals(listOf(EventKind.THRESHOLD_APP_BPS), events.map { it.kind })
        assertEquals(EventLevel.ALERT, events[0].level)
        assertTrue(events[0].subject.contains("Browser"))
    }

    @Test
    fun appBelowThresholdStaysQuiet() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 1_000)
        val snap = Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 499, txBps = 500)))
        assertTrue(rules.evaluate(snap, s, 1_000).isEmpty())
    }

    @Test
    fun rateLimitingHoldsTheSameSubjectForOneMinute() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 1_000)
        val snap = Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 5_000)))

        assertEquals(1, rules.evaluate(snap, s, 0).size)
        assertEquals(0, rules.evaluate(snap, s, 30_000).size)
        assertEquals(0, rules.evaluate(snap, s, 59_999).size)
        assertEquals(1, rules.evaluate(snap, s, 60_000).size)
        assertEquals(0, rules.evaluate(snap, s, 90_000).size)
    }

    @Test
    fun rateLimitingIsPerSubject() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 1_000)
        val snap = Fixtures.snapshot(
            apps = listOf(
                Fixtures.app(uid = 1, label = "One", rxBps = 5_000),
                Fixtures.app(uid = 2, label = "Two", rxBps = 5_000),
            ),
        )
        assertEquals(2, rules.evaluate(snap, s, 0).size)
        assertEquals(0, rules.evaluate(snap, s, 1_000).size)
    }

    @Test
    fun connectionThroughputAlerts() {
        val rules = AlertRules()
        val s = off.copy(alertConnBps = 2_000)
        val snap = Fixtures.snapshot(conns = listOf(Fixtures.conn(rxBps = 1_500, txBps = 700)))
        val events = rules.evaluate(snap, s, 0)
        assertEquals(listOf(EventKind.THRESHOLD_CONN_BPS), events.map { it.kind })
        assertTrue(events[0].subject.contains("→"))
    }

    @Test
    fun rttAlertsAsAWarningAndIgnoresUnmeasured() {
        val rules = AlertRules()
        val s = off.copy(alertRttMs = 200)
        val snap = Fixtures.snapshot(
            conns = listOf(
                Fixtures.conn(key = "slow", rttMillis = 250),
                Fixtures.conn(key = "fast", rttMillis = 20),
                Fixtures.conn(key = "unknown", rttMillis = -1),
            ),
        )
        val events = rules.evaluate(snap, s, 0)
        assertEquals(1, events.size)
        assertEquals(EventKind.THRESHOLD_RTT, events[0].kind)
        assertEquals(EventLevel.WARN, events[0].level)
    }

    @Test
    fun connectionGrowthNeedsConsecutivePolls() {
        val rules = AlertRules()
        val s = off.copy(alertConnGrowthPolls = 3)
        var now = 0L
        val counts = listOf(1, 2, 3, 4)
        val fired = counts.map { c ->
            val out = rules.evaluate(
                Fixtures.snapshot(apps = listOf(Fixtures.app(connCount = c))),
                s,
                now,
            )
            now += 1_000
            out.size
        }
        // The first poll has no baseline; growth is only counted from the second.
        assertEquals(listOf(0, 0, 0, 1), fired)
    }

    @Test
    fun growthRunResetsWhenConnectionsDrop() {
        val rules = AlertRules()
        val s = off.copy(alertConnGrowthPolls = 2)
        fun poll(count: Int, at: Long) =
            rules.evaluate(Fixtures.snapshot(apps = listOf(Fixtures.app(connCount = count))), s, at)

        assertEquals(0, poll(1, 0).size)
        assertEquals(0, poll(2, 1_000).size)
        assertEquals(0, poll(1, 2_000).size)
        assertEquals(0, poll(2, 3_000).size)
        assertEquals(1, poll(3, 4_000).size)
    }

    @Test
    fun fanoutFiresOnManyNewHostsInOnePoll() {
        val rules = AlertRules()
        val s = off.copy(alertFanoutHosts = 5)
        val conns = (1..6).map { Fixtures.conn(key = "c$it", remoteAddr = "203.0.113.$it") }
        val events = rules.evaluate(Fixtures.snapshot(conns = conns), s, 0)
        assertEquals(listOf(EventKind.FANOUT), events.map { it.kind })
        assertEquals(EventLevel.ALERT, events[0].level)
        assertTrue(events[0].detail.contains("6 new remote hosts"))
    }

    @Test
    fun fanoutIgnoresHostsAlreadySeenForThatApp() {
        val rules = AlertRules()
        val s = off.copy(alertFanoutHosts = 3)
        val first = (1..3).map { Fixtures.conn(key = "a$it", remoteAddr = "203.0.113.$it") }
        assertEquals(1, rules.evaluate(Fixtures.snapshot(conns = first), s, 0).size)
        // The same three hosts again are not new, so nothing fires even after
        // the cooldown has expired.
        assertEquals(0, rules.evaluate(Fixtures.snapshot(conns = first), s, 200_000).size)
    }

    @Test
    fun fanoutIsCountedPerApp() {
        val rules = AlertRules()
        val s = off.copy(alertFanoutHosts = 3)
        val conns = (1..2).flatMap { uid ->
            (1..2).map { Fixtures.conn(key = "u$uid-$it", uid = uid, remoteAddr = "203.0.113.$uid$it") }
        }
        assertTrue(rules.evaluate(Fixtures.snapshot(conns = conns), s, 0).isEmpty())
    }

    @Test
    fun firedHistoryIsPrunedOnEveryCallNotJustTheThroughputPath() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 1_000)
        rules.evaluate(Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 5_000))), s, 0)
        assertEquals(1, rules.firedCount)

        // Every threshold now disabled: the pruning still has to happen, or a
        // long session accumulates suppression entries forever.
        rules.evaluate(Fixtures.snapshot(), off, 200_000)
        assertEquals(0, rules.firedCount)
    }

    @Test
    fun stateForVanishedAppsIsDropped() {
        val rules = AlertRules()
        val s = off.copy(alertConnGrowthPolls = 2, alertFanoutHosts = 2)
        val conns = listOf(
            Fixtures.conn(key = "a", uid = 7, remoteAddr = "203.0.113.1"),
            Fixtures.conn(key = "b", uid = 7, remoteAddr = "203.0.113.2"),
        )
        rules.evaluate(Fixtures.snapshot(conns = conns, apps = listOf(Fixtures.app(uid = 7))), s, 0)
        rules.evaluate(Fixtures.snapshot(), s, 1_000)

        // uid 7 is gone, so its host memory went with it and the same hosts
        // count as new again.
        assertEquals(1, rules.evaluate(Fixtures.snapshot(conns = conns), s, 200_000).size)
    }

    @Test
    fun resetForgetsEverything() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 1_000)
        val snap = Fixtures.snapshot(apps = listOf(Fixtures.app(rxBps = 5_000)))
        assertEquals(1, rules.evaluate(snap, s, 0).size)
        rules.reset()
        assertEquals(0, rules.firedCount)
        assertEquals(1, rules.evaluate(snap, s, 1_000).size)
    }

    @Test
    fun rulesRunTogether() {
        val rules = AlertRules()
        val s = off.copy(alertAppBps = 100, alertConnBps = 100, alertRttMs = 10)
        val snap = Fixtures.snapshot(
            apps = listOf(Fixtures.app(rxBps = 1_000)),
            conns = listOf(Fixtures.conn(rxBps = 1_000, rttMillis = 900)),
        )
        val kinds = rules.evaluate(snap, s, 0).map { it.kind }.toSet()
        assertEquals(
            setOf(
                EventKind.THRESHOLD_APP_BPS,
                EventKind.THRESHOLD_CONN_BPS,
                EventKind.THRESHOLD_RTT,
            ),
            kinds,
        )
    }
}
