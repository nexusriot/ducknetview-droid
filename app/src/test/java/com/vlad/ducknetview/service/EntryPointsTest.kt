package com.vlad.ducknetview.service

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.model.Throughput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryPointsTest {

    @Test
    fun bootStartsCaptureOnlyWithBothTheSettingAndConsent() {
        assertTrue(BootReceiver.shouldStart(enabled = true, consent = true))
        assertFalse(BootReceiver.shouldStart(enabled = true, consent = false))
        assertFalse(BootReceiver.shouldStart(enabled = false, consent = true))
    }

    @Test
    fun automationKnowsExactlyItsActions() {
        for (c in AutomationReceiver.Command.entries) {
            assertEquals(c, AutomationReceiver.Command.of(c.action))
        }
        assertNull(AutomationReceiver.Command.of("com.vlad.ducknetview.action.FORMAT_PHONE"))
        assertNull(AutomationReceiver.Command.of(null))
    }

    @Test
    fun widgetLinesBeforeAnySnapshot() {
        val (a, b) = RatesWidget.lines(null, capturing = false, unit = RateUnit.BYTES)
        assertEquals("ducknetview", a)
        assertTrue(b.contains("open the app"))
    }

    @Test
    fun widgetLinesNameRatesModeAndTopTalker() {
        val snap = Fixtures.snapshot(conns = listOf(Fixtures.conn())).copy(
            total = Throughput(rxBps = 2048, txBps = 0),
            topApps = listOf(Talker("10001", "Browser", 1, 1)),
        )
        val (rates, status) = RatesWidget.lines(snap, capturing = true, unit = RateUnit.BYTES)
        assertTrue(rates.startsWith("↓"))
        assertTrue(status.contains("capturing"))
        assertTrue(status.contains("1 conns"))
        assertTrue(status.contains("top: Browser"))
        val (_, api) = RatesWidget.lines(snap, capturing = false, unit = RateUnit.BYTES)
        assertTrue(api.startsWith("API mode"))
        assertFalse(api.contains("conns"))
    }
}
