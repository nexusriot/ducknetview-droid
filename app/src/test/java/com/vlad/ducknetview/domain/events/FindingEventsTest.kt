package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.usage.AppBudget
import com.vlad.ducknetview.domain.usage.AppBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindingEventsTest {

    private val off = AppSettings()

    @Test
    fun aCleartextFlowIsReportedOncePerAppAndProtocol() {
        val f = FindingEvents()
        val http = Fixtures.conn(key = "a", remotePort = 80).copy(cleartext = "http")
        val snap = Fixtures.snapshot(conns = listOf(http, http.copy(key = "b", remoteAddr = "1.1.1.1")))
        val first = f.of(snap, off, 0)
        assertEquals(listOf(EventKind.CLEARTEXT_FLOW), first.map { it.kind })
        assertEquals(EventLevel.INFO, first[0].level)
        assertTrue(first[0].detail.contains("http"))
        assertTrue(f.of(snap, off, 1).isEmpty())
    }

    @Test
    fun deprecatedTlsIsAWarningPerAppAndHost() {
        val f = FindingEvents()
        val old = Fixtures.conn().copy(tls = "TLS 1.0", tlsDeprecated = true)
        val e = f.of(Fixtures.snapshot(conns = listOf(old)), off, 0).single()
        assertEquals(EventKind.TLS_DEPRECATED, e.kind)
        assertEquals(EventLevel.WARN, e.level)
        assertTrue(f.of(Fixtures.snapshot(conns = listOf(old)), off, 1).isEmpty())
    }

    @Test
    fun backgroundDataFiresOnceAndRearmsWhenTheDayResets() {
        val f = FindingEvents()
        val s = off.copy(alertBackgroundBytes = 1_000)
        val heavy = Fixtures.snapshot(apps = listOf(Fixtures.app().copy(todayBackground = 5_000)))
        val light = Fixtures.snapshot(apps = listOf(Fixtures.app().copy(todayBackground = 10)))
        assertEquals(EventKind.BACKGROUND_DATA, f.of(heavy, s, 0).single().kind)
        assertTrue(f.of(heavy, s, 1).isEmpty())
        assertTrue(f.of(light, s, 2).isEmpty())
        assertEquals(1, f.of(heavy, s, 3).size)
    }

    @Test
    fun noSplitMeansNoBackgroundVerdict() {
        val f = FindingEvents()
        val s = off.copy(alertBackgroundBytes = 1)
        val unknown = Fixtures.snapshot(apps = listOf(Fixtures.app().copy(todayBackground = -1)))
        assertTrue(f.of(unknown, s, 0).isEmpty())
    }

    @Test
    fun anAppBudgetIsReportedOncePerCycle() {
        val f = FindingEvents()
        val s = off.copy(appBudgets = AppBudgets.encode(listOf(AppBudget("com.example.browser", 100, true))))
        val snap = Fixtures.snapshot(apps = listOf(Fixtures.app()))
        val used = mapOf("com.example.browser" to 500L)
        val e = f.of(snap, s, 0, used, cycleStart = 1).single()
        assertEquals(EventKind.APP_BUDGET, e.kind)
        assertTrue(e.detail.contains("blocked"))
        assertTrue(f.of(snap, s, 1, used, cycleStart = 1).isEmpty())
        assertEquals(1, f.of(snap, s, 2, used, cycleStart = 2).size)
    }
}
