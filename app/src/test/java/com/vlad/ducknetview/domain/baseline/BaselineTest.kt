package com.vlad.ducknetview.domain.baseline

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineTest {

    /**
     * A real save time. `savedAt` is what tells a saved baseline apart from the
     * absence of one, so 0 is no longer a don't-care placeholder here.
     */
    private val SAVED_AT = 1_000L

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
        val b = Baseline.from(listOf(sshd), at = SAVED_AT)
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
        val b = Baseline.from(listOf(sshd), at = SAVED_AT)
        assertFalse(b.isOffBaseline(sshd))
        assertTrue(b.isOffBaseline(http))
        assertEquals(listOf(http), b.offBaseline(listOf(sshd, http)))
    }

    @Test
    fun ephemeralUdpNeverDirtiesTheBaseline() {
        val b = Baseline.from(listOf(sshd, ephemeral), at = SAVED_AT)
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
        val b = Baseline.from(listOf(sshd), at = SAVED_AT)
        assertSame(b, b.accept(ephemeral))
        assertSame(b, b.accept(sshd))
    }

    @Test
    fun removeDropsAKey() {
        val b = Baseline.from(listOf(sshd, http), at = SAVED_AT)
        val trimmed = b.remove(http)
        assertEquals(1, trimmed.size)
        assertTrue(trimmed.isOffBaseline(http))
        assertSame(trimmed, trimmed.remove(http))
    }

    @Test
    fun baselineIsImmutable() {
        val b = Baseline.from(listOf(sshd), at = SAVED_AT)
        b.accept(http)
        assertEquals(1, b.size)
    }

    @Test
    fun sortedKeysStayDiffable() {
        val b = Baseline.of(listOf("udp|0.0.0.0:5353", "tcp|0.0.0.0:22"), at = SAVED_AT)
        assertEquals(listOf("tcp|0.0.0.0:22", "udp|0.0.0.0:5353"), b.sortedKeys())
    }

    /**
     * A phone with nothing listening is the right moment to take a baseline,
     * and the baseline it produces is empty. The opt-in gate used to be
     * `keys.isEmpty()`, which cannot tell that apart from "no baseline yet", so
     * exactly that baseline could never flag anything: on the test tablet,
     * saving one with 0 listeners and then starting a server still reported
     * "0 off".
     */
    @Test
    fun `a baseline saved with no listeners still flags one that appears later`() {
        val baseline = Baseline.from(emptyList(), at = 1_000L)
        assertTrue("a saved baseline reported itself as absent", baseline.saved)

        val newListener = Fixtures.service(proto = Proto.TCP, bindAddr = "192.168.1.5", port = 9187)
        assertTrue(
            "a listener that appeared after the baseline was not flagged",
            baseline.isOffBaseline(newListener),
        )
        assertEquals(listOf(newListener), baseline.offBaseline(listOf(newListener)))
    }

    @Test
    fun `no baseline at all still flags nothing`() {
        val none = Baseline.EMPTY
        assertFalse("the never-saved baseline claimed to be saved", none.saved)
        val row = Fixtures.service(proto = Proto.TCP, bindAddr = "192.168.1.5", port = 9187)
        assertFalse(none.isOffBaseline(row))
        assertEquals(emptyList<com.vlad.ducknetview.domain.model.ServiceRow>(), none.offBaseline(listOf(row)))
    }

    @Test
    fun `an empty saved baseline still skips the sockets a baseline cannot cover`() {
        val baseline = Baseline.from(emptyList(), at = 1_000L)
        // An outbound DNS query looks like a UDP service on an ephemeral port;
        // flagging those would make every baseline dirty a second after it was
        // taken, saved-empty or not.
        val ephemeralUdp = Fixtures.service(
            proto = Proto.UDP,
            bindAddr = "192.168.1.5",
            port = EPHEMERAL_PORT_FLOOR + 1,
        )
        assertFalse(baseline.isOffBaseline(ephemeralUdp))
    }
}
