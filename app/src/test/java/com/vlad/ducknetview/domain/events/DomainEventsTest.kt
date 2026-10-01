package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.domain.watchlist.Watchlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEventsTest {

    private val none = Watchlist(emptyList())

    private fun row(
        name: String,
        source: NameSource = NameSource.DNS,
        app: String = "Browser",
        addresses: List<String> = listOf("93.184.216.34"),
    ) = DomainRow(
        name = name,
        uid = 10100,
        appLabel = app,
        source = source,
        lookups = 1,
        firstSeen = 1000L,
        lastSeen = 1000L,
        addresses = addresses,
    )

    @Test
    fun `a first sighting is logged at info`() {
        val events = DomainEvents.of(listOf(row("example.com")), none, at = 5000L)
        val e = events.single()
        assertEquals(EventKind.NEW_DOMAIN, e.kind)
        assertEquals(EventLevel.INFO, e.level)
        assertEquals("example.com", e.subject)
        assertEquals(5000L, e.at)
        assertTrue(e.detail.contains("resolved by DNS"))
        assertTrue(e.detail.contains("Browser"))
    }

    @Test
    fun `an SNI sighting says so, because it means DNS was not readable`() {
        val events = DomainEvents.of(listOf(row("example.com", NameSource.SNI)), none, at = 1L)
        assertTrue(events.single().detail.contains("seen in TLS SNI"))
    }

    @Test
    fun `a name on the watchlist is an alert, not a note`() {
        val watchlist = Watchlist(listOf(".*\\.tracker\\.net"))
        val events = DomainEvents.of(listOf(row("ads.tracker.net")), watchlist, at = 1L)
        val e = events.single()
        assertEquals(EventLevel.ALERT, e.level)
        assertTrue(e.detail.contains("on the watchlist"))
    }

    @Test
    fun `a watchlisted address reached under an innocuous name still alerts`() {
        // The rule may name the network rather than the host, and a new name
        // resolving into it is exactly the sighting worth raising.
        val watchlist = Watchlist(listOf("203.0.113.0/24"))
        val events = DomainEvents.of(
            listOf(row("cdn.example.com", addresses = listOf("203.0.113.9"))),
            watchlist,
            at = 1L,
        )
        assertEquals(EventLevel.ALERT, events.single().level)
    }

    @Test
    fun `one tick cannot flood the log, and says how much it held back`() {
        val rows = (1..DomainEvents.PER_TICK_CAP + 7).map { row("host$it.example.com") }
        val events = DomainEvents.of(rows, none, at = 1L)

        assertEquals(DomainEvents.PER_TICK_CAP + 1, events.size)
        val last = events.last()
        assertEquals(EventKind.SUPPRESSED, last.kind)
        // Truncation is never silent, matching the event engine's own rule.
        assertEquals("7 more suppressed", last.detail)
        assertEquals(EventKind.NEW_DOMAIN.label, last.subject)
    }

    @Test
    fun `exactly the cap produces no suppression line`() {
        val rows = (1..DomainEvents.PER_TICK_CAP).map { row("host$it.example.com") }
        val events = DomainEvents.of(rows, none, at = 1L)
        assertEquals(DomainEvents.PER_TICK_CAP, events.size)
        assertTrue(events.none { it.kind == EventKind.SUPPRESSED })
    }

    @Test
    fun `nothing new produces nothing`() {
        assertTrue(DomainEvents.of(emptyList(), none, at = 1L).isEmpty())
    }

    @Test
    fun `an unattributed name still logs`() {
        // getConnectionOwnerUid can answer INVALID_UID for a short flow; the
        // name is still worth recording without an app beside it.
        val events = DomainEvents.of(listOf(row("example.com", app = "")), none, at = 1L)
        assertTrue(events.single().detail.contains("resolved by DNS"))
    }
}
