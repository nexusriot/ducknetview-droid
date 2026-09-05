package com.vlad.ducknetview.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollCadenceTest {

    private fun interval(configured: Int, interactive: Boolean = true, frozen: Boolean = false) =
        PollCadence.effectiveIntervalSeconds(configured, interactive, frozen)

    @Test
    fun anInteractiveScreenPollsAtTheConfiguredInterval() {
        for (s in 1..10) assertEquals(s, interval(s))
    }

    @Test
    fun theConfiguredIntervalIsClampedToTheSettingsRange() {
        assertEquals(1, interval(0))
        assertEquals(1, interval(-5))
        assertEquals(10, interval(99))
    }

    @Test
    fun aDarkScreenBacksTheIntervalOff() {
        assertEquals(5, interval(1, interactive = false))
        assertEquals(10, interval(2, interactive = false))
        assertEquals(25, interval(5, interactive = false))
    }

    @Test
    fun theBackoffIsCappedRatherThanUnbounded() {
        assertEquals(30, interval(6, interactive = false))
        assertEquals(30, interval(10, interactive = false))
        assertEquals(30, interval(1000, interactive = false))
    }

    @Test
    fun aFrozenEngineIdlesAtTheCapWhateverTheScreenIsDoing() {
        assertEquals(30, interval(1, interactive = true, frozen = true))
        assertEquals(30, interval(10, interactive = false, frozen = true))
    }

    @Test
    fun theDarkScreenIntervalIsNeverShorterThanTheLitOne() {
        for (s in 1..10) {
            assertTrue(
                "configured $s",
                interval(s, interactive = false) >= interval(s, interactive = true),
            )
        }
    }

    @Test
    fun everyIntervalStaysWithinTheDeclaredBounds() {
        for (s in -3..40) {
            for (interactive in listOf(true, false)) {
                for (frozen in listOf(true, false)) {
                    val v = interval(s, interactive, frozen)
                    assertTrue(v >= PollCadence.MIN_INTERVAL_SECONDS)
                    assertTrue(v <= PollCadence.MAX_INTERVAL_SECONDS)
                }
            }
        }
    }
}
