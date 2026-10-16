package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.watchlist.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EventKind.CAPTIVE_PORTAL` was declared, filtered on, exported in the CSV
 * vocabulary and constructed nowhere: it could not fire. That is this
 * project's recurring defect class — see `arch/EventKindsProducedTest`, which
 * is the guard, and this, which is the behaviour.
 *
 * A portal matters more than its own line in the log: every other reading the
 * app takes through that link — gateway latency, external IP, reverse DNS, the
 * alert webhook — is answered by the portal rather than by the host it names.
 */
class CaptivePortalEventTest {

    private val engine = EventEngine()

    private fun diff(before: List<NetworkRow>, after: List<NetworkRow>): List<Event> {
        val first = Fixtures.snapshot(atMillis = 1_000L, networks = before)
        engine.diff(null, first, Baseline.EMPTY, Watchlist.EMPTY)
        return engine.diff(
            prev = first,
            cur = Fixtures.snapshot(atMillis = 2_000L, networks = after),
            baseline = Baseline.EMPTY,
            watchlist = Watchlist.EMPTY,
        )
    }

    private fun open(validated: Boolean = true) =
        Fixtures.network(ifaceName = "wlan0", validated = validated, captivePortal = false)

    private fun portal() =
        Fixtures.network(ifaceName = "wlan0", validated = false, captivePortal = true)

    private fun portals(events: List<Event>) = events.filter { it.kind == EventKind.CAPTIVE_PORTAL }

    @Test
    fun `a link that starts intercepting is reported`() {
        val events = portals(diff(listOf(open()), listOf(portal())))
        assertEquals("expected exactly one portal event: $events", 1, events.size)
        assertEquals(EventLevel.WARN, events[0].level)
        assertTrue(
            "the event should say what the user has to do: ${events[0].detail}",
            events[0].detail.contains("sign-in"),
        )
    }

    @Test
    fun `a link that appears already behind a portal is reported`() {
        // Joining the captive network is the common case, not transitioning
        // into one: the capability is already set the first time the link is
        // seen, so a producer keyed only on a change would never fire at all.
        val events = portals(diff(emptyList(), listOf(portal())))
        assertEquals("joining a portal network said nothing: $events", 1, events.size)
        assertEquals(EventLevel.WARN, events[0].level)
    }

    @Test
    fun `an ordinary link is never reported`() {
        assertTrue(portals(diff(listOf(open()), listOf(open()))).isEmpty())
        assertTrue(portals(diff(emptyList(), listOf(open()))).isEmpty())
    }

    @Test
    fun `a portal that stays up is reported once, not every poll`() {
        val first = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(portal()))
        engine.diff(null, first, Baseline.EMPTY, Watchlist.EMPTY)
        val second = Fixtures.snapshot(atMillis = 2_000L, networks = listOf(portal()))
        val events = portals(engine.diff(first, second, Baseline.EMPTY, Watchlist.EMPTY))
        assertTrue("a standing portal re-reported itself: $events", events.isEmpty())
    }

    @Test
    fun `signing in clears the portal`() {
        val events = portals(diff(listOf(portal()), listOf(open(validated = true))))
        assertEquals("expected one cleared event: $events", 1, events.size)
        assertEquals(EventLevel.INFO, events[0].level)
    }

    @Test
    fun `a re-registering link does not fake a sign-in`() {
        // onAvailable fires before onCapabilitiesChanged, so for one rebuild a
        // re-registering link carries no capabilities: no portal flag, and no
        // validation either. Reporting the first half of that as a cleared
        // portal would announce a sign-in the user never performed — the same
        // fabrication class as the Wi-Fi outages in NetworkIdentityTest.
        val bare = Fixtures.network(
            ifaceName = "wlan0",
            validated = false,
            captivePortal = false,
        )
        val events = portals(diff(listOf(portal()), listOf(bare)))
        assertTrue("a capability gap was reported as a sign-in: $events", events.isEmpty())
    }

    @Test
    fun `an untrusted default network is reported, once`() {
        val link = Fixtures.network(ifaceName = "wlan0", isDefault = true)
        val snap = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(link))
            .copy(untrustedDefault = true)
        val first = engine.diff(null, snap, Baseline.EMPTY, Watchlist.EMPTY)
            .filter { it.kind == EventKind.NETWORK_UNTRUSTED }
        // Unlike the topology events this fires on the first snapshot: it is
        // a standing property of where the device is, and it is the line that
        // explains an automatic capture or scan starting at the same moment.
        assertEquals("opening the app on an untrusted network said nothing: $first", 1, first.size)
        assertEquals(EventLevel.WARN, first[0].level)

        val again = engine.diff(
            snap,
            snap.copy(atMillis = 2_000L),
            Baseline.EMPTY,
            Watchlist.EMPTY,
        ).filter { it.kind == EventKind.NETWORK_UNTRUSTED }
        assertTrue("a standing condition reported twice: $again", again.isEmpty())
    }

    @Test
    fun `a trusted network says nothing`() {
        val link = Fixtures.network(ifaceName = "wlan0", isDefault = true)
        val snap = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(link))
        val out = engine.diff(null, snap, Baseline.EMPTY, Watchlist.EMPTY)
            .filter { it.kind == EventKind.NETWORK_UNTRUSTED }
        assertTrue(out.isEmpty())
    }

    @Test
    fun `moving to a different untrusted network is news again`() {
        val home = Fixtures.network(ifaceName = "wlan0", isDefault = true)
        val tether = Fixtures.network(ifaceName = "rmnet0", isDefault = true)
        val first = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(home))
            .copy(untrustedDefault = true)
        engine.diff(null, first, Baseline.EMPTY, Watchlist.EMPTY)
        val second = Fixtures.snapshot(atMillis = 2_000L, networks = listOf(tether))
            .copy(untrustedDefault = true)
        val out = engine.diff(first, second, Baseline.EMPTY, Watchlist.EMPTY)
            .filter { it.kind == EventKind.NETWORK_UNTRUSTED }
        assertEquals("a second untrusted network was silenced by the first: $out", 1, out.size)
    }

    @Test
    fun `the portal clears once the capabilities land for real`() {
        // The poll after the gap above, with the link genuinely signed in: the
        // suppression must be a delay, not a swallow.
        val bare = Fixtures.network(ifaceName = "wlan0", validated = false, captivePortal = false)
        val first = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(portal()))
        engine.diff(null, first, Baseline.EMPTY, Watchlist.EMPTY)
        val second = Fixtures.snapshot(atMillis = 2_000L, networks = listOf(bare))
        engine.diff(first, second, Baseline.EMPTY, Watchlist.EMPTY)
        val third = Fixtures.snapshot(atMillis = 3_000L, networks = listOf(open()))
        val events = portals(engine.diff(second, third, Baseline.EMPTY, Watchlist.EMPTY))
        assertEquals("the real sign-in was swallowed: $events", 1, events.size)
        assertEquals(EventLevel.INFO, events[0].level)
    }
}
