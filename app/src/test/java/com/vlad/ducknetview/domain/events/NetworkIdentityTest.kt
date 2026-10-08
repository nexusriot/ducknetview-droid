package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.watchlist.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as "the same link" between two snapshots.
 *
 * [com.vlad.ducknetview.domain.model.NetworkRow.id] is the platform's `Network`
 * object, and Android re-issues its netId whenever it re-registers a network —
 * which it does every time a VPN goes up or down. Keying the diff on it meant
 * that switching this app's own capture on stepped the Wi-Fi's id from 785 to
 * 786 on the test tablet, while the link carried traffic throughout, and the
 * diff reported it as one network vanishing and another appearing: a WARN
 * `network_down … vanished` plus an alert notification, for an outage that
 * never happened and that the app had caused itself.
 */
class NetworkIdentityTest {

    private val engine = EventEngine()

    /**
     * One poll after another, as the engine really sees them: the first
     * snapshot establishes the topology and emits nothing, so a test that
     * skipped it would be diffing against an engine that had never seen a
     * network at all.
     */
    private fun diff(
        before: List<com.vlad.ducknetview.domain.model.NetworkRow>,
        after: List<com.vlad.ducknetview.domain.model.NetworkRow>,
    ): List<com.vlad.ducknetview.domain.model.Event> {
        val first = Fixtures.snapshot(atMillis = 1_000L, networks = before)
        engine.diff(null, first, Baseline.EMPTY, Watchlist(emptyList()))
        return engine.diff(
            prev = first,
            cur = Fixtures.snapshot(atMillis = 2_000L, networks = after),
            baseline = Baseline.EMPTY,
            watchlist = Watchlist(emptyList()),
        )
    }

    private val wifi785 = Fixtures.network(id = "785", ifaceName = "wlan0", isDefault = true)
    private val wifi786 = Fixtures.network(id = "786", ifaceName = "wlan0", isDefault = true)

    @Test
    fun `a re-registered network is not reported as down and up`() {
        val events = diff(listOf(wifi785), listOf(wifi786))
        assertTrue(
            "a netId change was reported as a network event: $events",
            events.none { it.kind == EventKind.NETWORK_DOWN || it.kind == EventKind.NETWORK_UP },
        )
    }

    @Test
    fun `turning capture on does not fabricate a wifi outage`() {
        // Exactly the transition measured on the device: the TUN appears and
        // takes the default route, and the Wi-Fi is re-registered under a new
        // netId at the same time.
        val before = listOf(wifi785)
        val after = listOf(
            Fixtures.network(id = "786", ifaceName = "wlan0", isDefault = false),
            Fixtures.network(
                id = "787",
                ifaceName = "tun0",
                transport = Transport.VPN,
                isDefault = true,
            ),
        )

        val events = diff(before, after)
        assertTrue(
            "the Wi-Fi was reported down while it never stopped carrying traffic: $events",
            events.none { it.kind == EventKind.NETWORK_DOWN },
        )
        // The TUN really is new, so announcing it is correct.
        assertTrue(
            "the new tunnel was not reported at all: $events",
            events.any { it.kind == EventKind.NETWORK_UP && it.subject.contains("tun0") },
        )
        // Losing the default route is true and worth saying.
        assertTrue(
            "the Wi-Fi losing the default route went unreported: $events",
            events.any {
                it.kind == EventKind.NETWORK_CHANGED && it.detail.contains("default network")
            },
        )
    }

    @Test
    fun `a link that really goes away is still reported, one poll later`() {
        // The first absence is not yet an outage — see the flap tests below —
        // so the report arrives on the second, with its level and wording
        // unchanged.
        val first = diff(listOf(wifi785), emptyList())
        assertTrue(
            "one missing poll was enough to call the link down: $first",
            first.none { it.kind == EventKind.NETWORK_DOWN },
        )
        val second = engine.diff(
            prev = Fixtures.snapshot(atMillis = 2_000L, networks = emptyList()),
            cur = Fixtures.snapshot(atMillis = 3_000L, networks = emptyList()),
            baseline = Baseline.EMPTY,
            watchlist = Watchlist(emptyList()),
        )
        val down = second.single { it.kind == EventKind.NETWORK_DOWN }
        assertEquals("vanished", down.detail)
        assertEquals(EventLevel.WARN, down.level)
    }

    @Test
    fun `a link going down in place is still reported`() {
        val events = diff(
            listOf(wifi785),
            listOf(Fixtures.network(id = "785", ifaceName = "wlan0", up = false)),
        )
        val down = events.single { it.kind == EventKind.NETWORK_DOWN }
        assertEquals("up → down", down.detail)
    }

    @Test
    fun `a genuinely new interface is still reported as up`() {
        val events = diff(
            listOf(wifi785),
            listOf(
                wifi786,
                Fixtures.network(
                    id = "790",
                    ifaceName = "rmnet0",
                    transport = Transport.CELLULAR,
                    isDefault = false,
                ),
            ),
        )
        val up = events.single { it.kind == EventKind.NETWORK_UP }
        assertTrue("expected the cellular link to be the new one: $up", up.subject.contains("rmnet0"))
    }

    @Test
    fun `two links are told apart by interface, not by id`() {
        // Same transport, different interfaces: both must survive a renumber.
        val before = listOf(
            Fixtures.network(id = "1", ifaceName = "wlan0"),
            Fixtures.network(id = "2", ifaceName = "wlan1"),
        )
        val after = listOf(
            Fixtures.network(id = "9", ifaceName = "wlan1"),
            Fixtures.network(id = "8", ifaceName = "wlan0"),
        )
        val events = diff(before, after)
        assertTrue(
            "renumbering two links produced network events: $events",
            events.none { it.kind == EventKind.NETWORK_DOWN || it.kind == EventKind.NETWORK_UP },
        )
    }

    @Test
    fun `a row with no interface name still falls back to its id`() {
        val before = listOf(Fixtures.network(id = "5", ifaceName = ""))
        val after = listOf(Fixtures.network(id = "6", ifaceName = ""))
        val events = diff(before, after)
        // Nothing stable to key on, so the old behaviour is the only option
        // left — but it must not throw or silently drop the change.
        assertTrue(
            "an unnamed link change produced nothing at all: $events",
            events.any { it.kind == EventKind.NETWORK_DOWN || it.kind == EventKind.NETWORK_UP },
        )
    }

    // ---------------- a flap is not an outage ----------------

    /**
     * Android re-registers a network by losing it and offering it again, and
     * the two callbacks can land either side of a poll, so the list is briefly
     * without an interface that never stopped carrying traffic. On the test
     * tablet, killing the app while capture was on produced exactly that: a
     * WARN "network_down wlan0 vanished" at 10:54:57 and "network_up wlan0
     * appeared" at 10:54:59, with a notification for the first.
     */
    @Test
    fun `an interface missing for a single poll is neither down nor up`() {
        val engine = EventEngine()
        val wifi = Fixtures.network(id = "785", ifaceName = "wlan0")
        val a = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(wifi))
        val b = Fixtures.snapshot(atMillis = 2_000L, networks = emptyList())
        val c = Fixtures.snapshot(
            atMillis = 3_000L,
            networks = listOf(Fixtures.network(id = "786", ifaceName = "wlan0")),
        )

        engine.diff(null, a, Baseline.EMPTY, Watchlist(emptyList()))
        val gap = engine.diff(a, b, Baseline.EMPTY, Watchlist(emptyList()))
        val back = engine.diff(b, c, Baseline.EMPTY, Watchlist(emptyList()))

        assertTrue(
            "a one-poll gap was reported as the link going down: $gap",
            gap.none { it.kind == EventKind.NETWORK_DOWN },
        )
        assertTrue(
            "the link coming straight back was reported as a new network: $back",
            back.none { it.kind == EventKind.NETWORK_UP },
        )
    }

    @Test
    fun `an interface missing for two polls really is reported down`() {
        val engine = EventEngine()
        val wifi = Fixtures.network(id = "785", ifaceName = "wlan0")
        val a = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(wifi))
        val gone1 = Fixtures.snapshot(atMillis = 2_000L, networks = emptyList())
        val gone2 = Fixtures.snapshot(atMillis = 3_000L, networks = emptyList())

        engine.diff(null, a, Baseline.EMPTY, Watchlist(emptyList()))
        engine.diff(a, gone1, Baseline.EMPTY, Watchlist(emptyList()))
        val second = engine.diff(gone1, gone2, Baseline.EMPTY, Watchlist(emptyList()))

        val down = second.single { it.kind == EventKind.NETWORK_DOWN }
        assertEquals("vanished", down.detail)
        assertEquals(EventLevel.WARN, down.level)
    }

    @Test
    fun `a link is reported down only once however long it stays away`() {
        val engine = EventEngine()
        val wifi = Fixtures.network(id = "785", ifaceName = "wlan0")
        val a = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(wifi))
        val gone = Fixtures.snapshot(atMillis = 2_000L, networks = emptyList())

        engine.diff(null, a, Baseline.EMPTY, Watchlist(emptyList()))
        engine.diff(a, gone, Baseline.EMPTY, Watchlist(emptyList()))
        engine.diff(gone, gone, Baseline.EMPTY, Watchlist(emptyList()))

        val later = (1..3).flatMap {
            engine.diff(gone, gone, Baseline.EMPTY, Watchlist(emptyList()))
        }
        assertTrue(
            "the same outage was reported again on every later poll: $later",
            later.none { it.kind == EventKind.NETWORK_DOWN },
        )
    }

    @Test
    fun `a link that comes back after being reported down is reported up`() {
        val engine = EventEngine()
        val wifi = Fixtures.network(id = "785", ifaceName = "wlan0")
        val a = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(wifi))
        val gone = Fixtures.snapshot(atMillis = 2_000L, networks = emptyList())
        val backAgain = Fixtures.snapshot(atMillis = 5_000L, networks = listOf(wifi))

        engine.diff(null, a, Baseline.EMPTY, Watchlist(emptyList()))
        engine.diff(a, gone, Baseline.EMPTY, Watchlist(emptyList()))
        engine.diff(gone, gone, Baseline.EMPTY, Watchlist(emptyList()))
        val restored = engine.diff(gone, backAgain, Baseline.EMPTY, Watchlist(emptyList()))

        val up = restored.single { it.kind == EventKind.NETWORK_UP }
        assertEquals("appeared", up.detail)
    }

    @Test
    fun `restarting capture does not re-announce every link`() {
        // reset() forgets the per-session dedupe sets. The link topology is not
        // one of those: clearing it would make every network "appear" again.
        val engine = EventEngine()
        val wifi = Fixtures.network(id = "785", ifaceName = "wlan0")
        val a = Fixtures.snapshot(atMillis = 1_000L, networks = listOf(wifi))
        engine.diff(null, a, Baseline.EMPTY, Watchlist(emptyList()))
        engine.reset()
        val after = engine.diff(a, a.copy(atMillis = 2_000L), Baseline.EMPTY, Watchlist(emptyList()))
        assertTrue(
            "a capture restart re-announced the links: $after",
            after.none { it.kind == EventKind.NETWORK_UP },
        )
    }
}
