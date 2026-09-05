package com.vlad.ducknetview.domain.baseline

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    private val sshd = Fixtures.service(proto = Proto.TCP, bindAddr = "0.0.0.0", port = 22)
    private val http = Fixtures.service(proto = Proto.TCP, bindAddr = "127.0.0.1", port = 8080)
    private val mdns = Fixtures.service(proto = Proto.UDP, bindAddr = "0.0.0.0", port = 5353)
    private val ephemeral = Fixtures.service(proto = Proto.UDP, bindAddr = "0.0.0.0", port = 43211)

    @Test
    fun eligibilityExcludesEphemeralUdpOnly() {
        assertTrue(baselineEligible(sshd))
        assertTrue(baselineEligible(mdns))
        assertTrue(baselineEligible(Fixtures.service(proto = Proto.TCP, port = 40000)))
        assertTrue(baselineEligible(Fixtures.service(proto = Proto.UDP, port = 32767)))
        assertFalse(baselineEligible(Fixtures.service(proto = Proto.UDP, port = 32768)))
        assertFalse(baselineEligible(ephemeral))
    }

    @Test
    fun fromSkipsEphemeralUdpSockets() {
        val b = Baseline.from(listOf(sshd, http, mdns, ephemeral), at = 5_000)
        assertEquals(3, b.size)
        assertEquals(5_000, b.savedAt)
        assertFalse(b.keys.any { it.contains("43211") })
    }

    @Test
    fun keysUseTheProtoBindPortShape() {
        val b = Baseline.from(listOf(sshd), at = 0)
        assertEquals(setOf("tcp|0.0.0.0:22"), b.keys)
        assertEquals("tcp|0.0.0.0:22", sshd.baselineKey)
    }

    @Test
    fun withoutASavedBaselineNothingIsOffBaseline() {
        assertTrue(Baseline.EMPTY.isEmpty)
        assertFalse(Baseline.EMPTY.isOffBaseline(sshd))
        assertTrue(Baseline.EMPTY.offBaseline(listOf(sshd, http)).isEmpty())
    }

    @Test
    fun offBaselineFindsTheUnexpectedListener() {
        val b = Baseline.from(listOf(sshd), at = 0)
        assertFalse(b.isOffBaseline(sshd))
        assertTrue(b.isOffBaseline(http))
        assertEquals(listOf(http), b.offBaseline(listOf(sshd, http)))
    }

    @Test
    fun ephemeralUdpNeverDirtiesTheBaseline() {
        val b = Baseline.from(listOf(sshd, ephemeral), at = 0)
        // Every poll shows a different client-side UDP port; if those counted,
        // the baseline would be permanently off.
        val laterScan = listOf(
            sshd,
            Fixtures.service(proto = Proto.UDP, bindAddr = "0.0.0.0", port = 51234),
            Fixtures.service(proto = Proto.UDP, bindAddr = "0.0.0.0", port = 60001),
        )
        assertTrue(b.offBaseline(laterScan).isEmpty())
        assertEquals(1, Baseline.comparable(laterScan))
    }

    @Test
    fun acceptBlessesOneListenerOnly() {
        val b = Baseline.from(listOf(sshd), at = 7).accept(http)
        assertTrue(b.contains(http))
        assertTrue(b.contains(sshd))
        assertFalse(b.isOffBaseline(http))
        assertEquals(7, b.savedAt)
    }

    @Test
    fun acceptIsANoOpForIneligibleAndKnownRows() {
        val b = Baseline.from(listOf(sshd), at = 0)
        assertSame(b, b.accept(ephemeral))
        assertSame(b, b.accept(sshd))
    }

    @Test
    fun removeDropsAKey() {
        val b = Baseline.from(listOf(sshd, http), at = 0)
        val trimmed = b.remove(http)
        assertEquals(1, trimmed.size)
        assertTrue(trimmed.isOffBaseline(http))
        assertSame(trimmed, trimmed.remove(http))
    }

    @Test
    fun baselineIsImmutable() {
        val b = Baseline.from(listOf(sshd), at = 0)
        b.accept(http)
        assertEquals(1, b.size)
    }

    @Test
    fun sortedKeysStayDiffable() {
        val b = Baseline.of(listOf("udp|0.0.0.0:5353", "tcp|0.0.0.0:22"), at = 0)
        assertEquals(listOf("tcp|0.0.0.0:22", "udp|0.0.0.0:5353"), b.sortedKeys())
    }
}
