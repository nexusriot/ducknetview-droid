package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.watchlist.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEngineTest {

    private val noBaseline = Baseline.EMPTY
    private val noWatchlist = Watchlist.EMPTY

    private fun kinds(events: List<Event>) = events.map { it.kind }

    private fun of(events: List<Event>, kind: EventKind) = events.filter { it.kind == kind }

    @Test
    fun firstSnapshotIsABaselineNotABurstOfServiceEvents() {
        val engine = EventEngine()
        val cur = Fixtures.snapshot(services = listOf(Fixtures.service(port = 22)))
        val events = engine.diff(null, cur, noBaseline, noWatchlist)
        assertTrue(kinds(events).none { it == EventKind.SERVICE_UP })
    }

    @Test
    fun firstSnapshotEmitsNoNetworkEvents() {
        val engine = EventEngine()
        val cur = Fixtures.snapshot(networks = listOf(Fixtures.network()))
        assertTrue(engine.diff(null, cur, noBaseline, noWatchlist).isEmpty())
    }

    @Test
    fun serviceAppearingIsAWarning() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot()
        val cur = Fixtures.snapshot(services = listOf(Fixtures.service(port = 8080)))
        val events = engine.diff(prev, cur, noBaseline, noWatchlist)
        assertEquals(1, events.size)
        assertEquals(EventKind.SERVICE_UP, events[0].kind)
        assertEquals(EventLevel.WARN, events[0].level)
        assertTrue(events[0].subject.contains("8080"))
    }

    @Test
    fun serviceVanishingIsInformational() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(services = listOf(Fixtures.service(port = 8080)))
        val cur = Fixtures.snapshot()
        val events = engine.diff(prev, cur, noBaseline, noWatchlist)
        assertEquals(listOf(EventKind.SERVICE_DOWN), kinds(events))
        assertEquals(EventLevel.INFO, events[0].level)
    }

    @Test
    fun rebindingIsOneMovedEventNotAnUpAndADown() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "127.0.0.1", port = 5432)),
        )
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "0.0.0.0", port = 5432)),
        )
        val events = engine.diff(prev, cur, noBaseline, noWatchlist)
        assertEquals(listOf(EventKind.SERVICE_MOVED), kinds(events))
        assertTrue(events[0].detail.contains("127.0.0.1"))
    }

    @Test
    fun wideningExposureIsAnAlert() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "127.0.0.1", port = 5432)),
        )
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "0.0.0.0", port = 5432)),
        )
        val moved = of(engine.diff(prev, cur, noBaseline, noWatchlist), EventKind.SERVICE_MOVED)
        assertEquals(EventLevel.ALERT, moved.single().level)
        assertTrue(moved.single().detail.contains("further away"))
    }

    @Test
    fun narrowingExposureIsOnlyInformational() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "0.0.0.0", port = 5432)),
        )
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "127.0.0.1", port = 5432)),
        )
        val moved = of(engine.diff(prev, cur, noBaseline, noWatchlist), EventKind.SERVICE_MOVED)
        assertEquals(EventLevel.INFO, moved.single().level)
    }

    @Test
    fun lanToExposedAlsoCountsAsWidening() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "192.168.1.5", port = 631)),
        )
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "0.0.0.0", port = 631)),
        )
        val moved = of(engine.diff(prev, cur, noBaseline, noWatchlist), EventKind.SERVICE_MOVED)
        assertEquals(EventLevel.ALERT, moved.single().level)
    }

    @Test
    fun localToLanIsWideningToo() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "127.0.0.1", port = 631)),
        )
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "192.168.1.5", port = 631)),
        )
        val moved = of(engine.diff(prev, cur, noBaseline, noWatchlist), EventKind.SERVICE_MOVED)
        assertEquals(EventLevel.ALERT, moved.single().level)
    }

    @Test
    fun aDifferentAppOnTheSamePortIsNotAMove() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "127.0.0.1", port = 8080, appLabel = "A")),
        )
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(bindAddr = "0.0.0.0", port = 8080, appLabel = "B")),
        )
        val events = engine.diff(prev, cur, noBaseline, noWatchlist)
        assertEquals(setOf(EventKind.SERVICE_UP, EventKind.SERVICE_DOWN), kinds(events).toSet())
    }

    @Test
    fun ephemeralUdpSocketsAreNotServiceEvents() {
        val engine = EventEngine()
        val prev = Fixtures.snapshot()
        val cur = Fixtures.snapshot(
            services = listOf(Fixtures.service(proto = Proto.UDP, port = 45123)),
        )
        assertTrue(engine.diff(prev, cur, noBaseline, noWatchlist).isEmpty())
    }

    @Test
    fun offBaselineServiceRaisesAnAlertOnce() {
        val engine = EventEngine()
        val known = Fixtures.service(port = 22)
        val rogue = Fixtures.service(port = 4444, appLabel = "Unknown")
        val baseline = Baseline.from(listOf(known), at = 0)

        val first = Fixtures.snapshot(atMillis = 1, services = listOf(known, rogue))
        val alerts = of(engine.diff(null, first, baseline, noWatchlist), EventKind.OFF_BASELINE)
        assertEquals(1, alerts.size)
        assertEquals(EventLevel.ALERT, alerts[0].level)
        assertTrue(alerts[0].subject.contains("4444"))

        val second = Fixtures.snapshot(atMillis = 2, services = listOf(known, rogue))
        assertTrue(of(engine.diff(first, second, baseline, noWatchlist), EventKind.OFF_BASELINE).isEmpty())
    }

    @Test
    fun watchlistHitIsAlertedOncePerRemote() {
        val engine = EventEngine()
        val watchlist = Watchlist(listOf("45.9.0.0/16"))
        val conn = Fixtures.conn(key = "1", remoteAddr = "45.9.148.99")
        val first = Fixtures.snapshot(atMillis = 1, conns = listOf(conn))
        val hits = of(engine.diff(null, first, noBaseline, watchlist), EventKind.WATCHLIST_HIT)
        assertEquals(1, hits.size)
        assertEquals(EventLevel.ALERT, hits[0].level)

        val second = Fixtures.snapshot(atMillis = 2, conns = listOf(conn, Fixtures.conn(key = "2", remoteAddr = "45.9.148.99")))
        assertTrue(of(engine.diff(first, second, noBaseline, watchlist), EventKind.WATCHLIST_HIT).isEmpty())
    }

    @Test
    fun newPublicHostIsLoggedOncePerAddress() {
        val engine = EventEngine()
        val first = Fixtures.snapshot(
            atMillis = 1,
            conns = listOf(Fixtures.conn(key = "1", remoteAddr = "8.8.8.8")),
        )
        assertEquals(1, of(engine.diff(null, first, noBaseline, noWatchlist), EventKind.NEW_PUBLIC_HOST).size)

        val second = Fixtures.snapshot(
            atMillis = 2,
            conns = listOf(
                Fixtures.conn(key = "2", remoteAddr = "8.8.8.8"),
                Fixtures.conn(key = "3", remoteAddr = "1.1.1.1"),
            ),
        )
        val again = of(engine.diff(first, second, noBaseline, noWatchlist), EventKind.NEW_PUBLIC_HOST)
        assertEquals(1, again.size)
        assertTrue(again[0].subject.contains("1.1.1.1"))
    }

    /**
     * The engine is the only producer of NEW_PUBLIC_HOST, so its session-only
     * dedupe has to be seedable from the persisted host store; otherwise every
     * process start replays hosts this device has known for weeks as first
     * contact. [EventEngine.lastNewHosts] is what the caller persists.
     */
    @Test
    fun seededHostsAreNotReportedAsFirstContact() {
        val engine = EventEngine()
        engine.seedSeenHosts(listOf("8.8.8.8"))
        val cur = Fixtures.snapshot(
            conns = listOf(
                Fixtures.conn(key = "1", remoteAddr = "8.8.8.8"),
                Fixtures.conn(key = "2", remoteAddr = "1.1.1.1"),
            ),
        )
        val events = of(engine.diff(null, cur, noBaseline, noWatchlist), EventKind.NEW_PUBLIC_HOST)
        assertEquals(1, events.size)
        assertTrue(events[0].subject.contains("1.1.1.1"))
    }

    @Test
    fun newlySeenHostsAreReportedForPersistenceAndResetEachDiff() {
        val engine = EventEngine()
        val first = Fixtures.snapshot(
            atMillis = 1,
            conns = listOf(Fixtures.conn(key = "1", remoteAddr = "8.8.8.8")),
        )
        engine.diff(null, first, noBaseline, noWatchlist)
        assertEquals(listOf("8.8.8.8"), engine.lastNewHosts)

        // The same host again is not news, and must not be re-offered for
        // persistence on every subsequent tick.
        engine.diff(first, first, noBaseline, noWatchlist)
        assertTrue(engine.lastNewHosts.isEmpty())
    }

    @Test
    fun privateAndLoopbackRemotesAreNotNewPublicHosts() {
        val engine = EventEngine()
        val cur = Fixtures.snapshot(
            conns = listOf(
                Fixtures.conn(key = "1", remoteAddr = "192.168.1.1"),
                Fixtures.conn(key = "2", remoteAddr = "127.0.0.1"),
                Fixtures.conn(key = "3", remoteAddr = "fe80::1"),
            ),
        )
        assertTrue(of(engine.diff(null, cur, noBaseline, noWatchlist), EventKind.NEW_PUBLIC_HOST).isEmpty())
    }

    @Test
    fun floodIsTruncatedLoudly() {
        val engine = EventEngine()
        val conns = (1..25).map { Fixtures.conn(key = "c$it", remoteAddr = "203.0.113.$it") }
        val events = engine.diff(null, Fixtures.snapshot(conns = conns), noBaseline, noWatchlist)

        assertEquals(EventEngine.PER_KIND_CAP, of(events, EventKind.NEW_PUBLIC_HOST).size)
        val suppressed = of(events, EventKind.SUPPRESSED).single()
        assertEquals("5 more new_public_host suppressed", suppressed.subject)
        assertEquals(EventLevel.INFO, suppressed.level)
    }

    @Test
    fun suppressionIsCountedPerKind() {
        val engine = EventEngine(perKindCap = 2)
        val prev = Fixtures.snapshot()
        val cur = Fixtures.snapshot(
            services = (1..5).map { Fixtures.service(port = 9000 + it) },
            conns = (1..4).map { Fixtures.conn(key = "c$it", remoteAddr = "198.51.100.$it") },
        )
        val events = engine.diff(prev, cur, noBaseline, noWatchlist)
        assertEquals(2, of(events, EventKind.SERVICE_UP).size)
        assertEquals(2, of(events, EventKind.NEW_PUBLIC_HOST).size)
        val subjects = of(events, EventKind.SUPPRESSED).map { it.subject }.toSet()
        assertEquals(setOf("3 more service_up suppressed", "2 more new_public_host suppressed"), subjects)
    }

    @Test
    fun networkUpDownAndVanished() {
        val engine = EventEngine()
        val wifi = Fixtures.network(id = "w", ifaceName = "wlan0")
        val cell = Fixtures.network(id = "c", ifaceName = "rmnet0", isDefault = false)

        val a = Fixtures.snapshot(networks = listOf(wifi))
        val b = Fixtures.snapshot(networks = listOf(wifi, cell))
        assertEquals(listOf(EventKind.NETWORK_UP), kinds(engine.diff(a, b, noBaseline, noWatchlist)))

        val c = Fixtures.snapshot(networks = listOf(wifi.copy(up = false), cell))
        val down = engine.diff(b, c, noBaseline, noWatchlist)
        assertEquals(listOf(EventKind.NETWORK_DOWN), kinds(down))
        assertEquals(EventLevel.WARN, down[0].level)

        val d = Fixtures.snapshot(networks = listOf(cell))
        val gone = engine.diff(c, d, noBaseline, noWatchlist)
        assertEquals(listOf(EventKind.NETWORK_DOWN), kinds(gone))
        assertEquals("vanished", gone[0].detail)
    }

    @Test
    fun networkAttributeChangeIsReported() {
        val engine = EventEngine()
        val before = Fixtures.network(addresses = listOf("192.168.1.5"))
        val after = before.copy(addresses = listOf("192.168.1.77"), metered = true)
        val events = engine.diff(
            Fixtures.snapshot(networks = listOf(before)),
            Fixtures.snapshot(networks = listOf(after)),
            noBaseline,
            noWatchlist,
        )
        val changed = of(events, EventKind.NETWORK_CHANGED).single()
        assertTrue(changed.detail.contains("192.168.1.77"))
        assertTrue(changed.detail.contains("metered"))
    }

    @Test
    fun anUnchangedNetworkSaysNothing() {
        val engine = EventEngine()
        val n = Fixtures.network()
        assertTrue(
            engine.diff(
                Fixtures.snapshot(networks = listOf(n)),
                Fixtures.snapshot(networks = listOf(n)),
                noBaseline,
                noWatchlist,
            ).isEmpty(),
        )
    }

    @Test
    fun everyEventCarriesTheSnapshotTimestamp() {
        val engine = EventEngine()
        val cur = Fixtures.snapshot(
            atMillis = 987_654,
            conns = listOf(Fixtures.conn(remoteAddr = "8.8.4.4")),
        )
        val events = engine.diff(null, cur, noBaseline, noWatchlist)
        assertNotNull(events.firstOrNull())
        assertTrue(events.all { it.at == 987_654L })
    }

    @Test
    fun seenSetsAreBoundedAndResettable() {
        val engine = EventEngine(seenCap = 4)
        val many = (1..10).map { Fixtures.conn(key = "c$it", remoteAddr = "203.0.113.$it") }
        engine.diff(null, Fixtures.snapshot(conns = many), noBaseline, noWatchlist)

        // The oldest addresses have been evicted, so they read as new again.
        val repeat = engine.diff(
            null,
            Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.1"))),
            noBaseline,
            noWatchlist,
        )
        assertEquals(1, of(repeat, EventKind.NEW_PUBLIC_HOST).size)

        engine.reset()
        val afterReset = engine.diff(
            null,
            Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.10"))),
            noBaseline,
            noWatchlist,
        )
        assertEquals(1, of(afterReset, EventKind.NEW_PUBLIC_HOST).size)
    }
}
