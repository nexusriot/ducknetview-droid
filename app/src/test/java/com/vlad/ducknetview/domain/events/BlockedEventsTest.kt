package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.model.BlockedAttempt
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-app firewall used to leave no trace: a refused flow was marked,
 * retired and gone, and since a blocked app has no live connections by
 * construction the Apps screen showed it exactly as it shows an app that is
 * not running. There was no way to tell a working block from a broken one.
 */
class BlockedEventsTest {

    private val events = BlockedEvents()

    private fun attempt(
        uid: Int = 10135,
        label: String = "Chrome",
        remote: String = "142.250.185.78",
        port: Int = 443,
        proto: Proto = Proto.TCP,
        at: Long = 1_000L,
    ) = BlockedAttempt(
        uid = uid,
        proto = proto,
        remoteAddr = remote,
        remotePort = port,
        at = at,
        appLabel = label,
    )

    @Test
    fun `the line says which rule or policy refused`() {
        val out = events.of(listOf(attempt().copy(reason = "blocked while the screen is off")), now = 1_000L)
        assertTrue(out.single().detail.endsWith("· blocked while the screen is off"))
    }

    @Test
    fun `the first refusal is reported at once`() {
        // The user has just pressed Block and is looking at the screen; making
        // them wait out the suppression window for the first confirmation
        // would read as the block not working.
        val out = events.of(listOf(attempt()), now = 1_000L)
        assertEquals(1, out.size)
        assertEquals(EventKind.BLOCKED_ATTEMPT, out[0].kind)
        assertTrue("the app should be named: ${out[0].subject}", out[0].subject.contains("Chrome"))
        assertTrue("the uid should be there too: ${out[0].subject}", out[0].subject.contains("10135"))
    }

    @Test
    fun `a refusal is INFO, not an alert`() {
        // The user asked for this block. WARN would put a notification and a
        // webhook POST behind every retry of an app they have already dealt
        // with — and a blocked app retries without pause.
        val out = events.of(listOf(attempt()), now = 1_000L)
        assertEquals(EventLevel.INFO, out[0].level)
    }

    @Test
    fun `a retrying app does not flood the log`() {
        events.of(listOf(attempt()), now = 1_000L)
        val burst = (0 until 50).map { attempt(at = 1_100L + it) }
        val out = events.of(burst, now = 1_500L)
        assertTrue("a retry burst produced $out", out.isEmpty())
    }

    @Test
    fun `refusals held back by the window are counted into the next line`() {
        events.of(listOf(attempt()), now = 1_000L)
        events.of((0 until 9).map { attempt() }, now = 2_000L)
        val out = events.of(listOf(attempt()), now = 1_000L + BlockedEvents.REFIRE_MILLIS)
        assertEquals(1, out.size)
        assertTrue(
            "the nine suppressed refusals and this one should add up to ten: " +
                out[0].detail,
            out[0].detail.contains("10 connections refused"),
        )
    }

    @Test
    fun `two apps are reported separately`() {
        val out = events.of(
            listOf(attempt(), attempt(uid = 10200, label = "Maps")),
            now = 1_000L,
        )
        assertEquals(2, out.size)
        assertTrue(out.any { it.subject.contains("Chrome") })
        assertTrue(out.any { it.subject.contains("Maps") })
    }

    @Test
    fun `one app's window does not silence another's first refusal`() {
        events.of(listOf(attempt()), now = 1_000L)
        val out = events.of(listOf(attempt(uid = 10200, label = "Maps")), now = 1_500L)
        assertEquals("Maps was silenced by Chrome's window: $out", 1, out.size)
        assertTrue(out[0].subject.contains("Maps"))
    }

    @Test
    fun `the line names what was refused`() {
        val out = events.of(listOf(attempt(remote = "10.0.0.9", port = 8080)), now = 1_000L)
        assertTrue("no destination in: ${out[0].detail}", out[0].detail.contains("10.0.0.9:8080"))
        assertTrue("no protocol in: ${out[0].detail}", out[0].detail.contains("tcp"))
    }

    @Test
    fun `an unlabelled attempt still names its uid`() {
        val out = events.of(listOf(attempt(label = "")), now = 1_000L)
        assertEquals("uid 10135", out[0].subject)
    }

    @Test
    fun `nothing is emitted for an empty tick`() {
        assertTrue(events.of(emptyList(), now = 1_000L).isEmpty())
    }

    @Test
    fun `the suppression table does not grow without bound`() {
        for (i in 0 until 200) {
            events.of(listOf(attempt(uid = 11_000 + i)), now = 1_000L + i)
        }
        // Long after every window has lapsed and with nothing pending, the
        // bookkeeping for apps that stopped trying must be let go of.
        val out = events.of(emptyList(), now = 1_000L + 10 * BlockedEvents.REFIRE_MILLIS)
        assertTrue(out.isEmpty())
        val again = events.of(listOf(attempt(uid = 11_000)), now = 1_000L + 10 * BlockedEvents.REFIRE_MILLIS)
        assertEquals("an app that came back was treated as still suppressed", 1, again.size)
    }

    @Test
    fun `an icmp refusal does not print a port that means nothing`() {
        // ICMP borrows the port fields for the echo identifier, so rendering
        // them reads as a port number nothing is listening on.
        val out = events.of(
            listOf(attempt(proto = Proto.ICMP, remote = "8.8.8.8", port = 3421)),
            now = 1_000L,
        )
        assertTrue("no host in: ${out[0].detail}", out[0].detail.contains("8.8.8.8"))
        assertFalse("an echo id was printed as a port: ${out[0].detail}", out[0].detail.contains("3421"))
    }
}
