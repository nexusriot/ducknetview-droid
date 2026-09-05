package com.vlad.ducknetview.domain.group

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTest {

    private val rows = listOf(
        Fixtures.conn(
            key = "1",
            remoteAddr = "93.184.216.34",
            rxBps = 10,
            txBps = 1,
            rxBytes = 100,
            txBytes = 10,
            appLabel = "Browser",
            resolvedHost = "example.com",
        ),
        Fixtures.conn(
            key = "2",
            remoteAddr = "93.184.216.34",
            rxBps = 5,
            txBps = 2,
            rxBytes = 50,
            txBytes = 20,
            appLabel = "Mail",
            state = ConnState.CLOSING,
            resolvedHost = "example.com",
        ),
        Fixtures.conn(key = "3", remoteAddr = "192.168.1.1", appLabel = "Browser"),
    )

    @Test
    fun connectionsAreGroupedByRemoteAddress() {
        val groups = Grouping.of(rows)
        assertEquals(listOf("93.184.216.34", "192.168.1.1"), groups.map { it.host })
        assertEquals(2, groups[0].connCount)
        assertEquals(1, groups[1].connCount)
    }

    @Test
    fun ratesAndTotalsAreSummed() {
        val g = Grouping.of(rows).first()
        assertEquals(15L, g.rxBps)
        assertEquals(3L, g.txBps)
        assertEquals(150L, g.rxTotal)
        assertEquals(30L, g.txTotal)
    }

    @Test
    fun statesAreCounted() {
        val g = Grouping.of(rows).first()
        assertEquals(mapOf("established" to 1, "closing" to 1), g.states)
    }

    @Test
    fun appLabelsAreDistinctAndSorted() {
        val g = Grouping.of(rows).first()
        assertEquals(listOf("Browser", "Mail"), g.apps)

        val sameApp = listOf(
            Fixtures.conn(key = "a", appLabel = "Browser"),
            Fixtures.conn(key = "b", appLabel = "Browser"),
        )
        assertEquals(listOf("Browser"), Grouping.of(sameApp).first().apps)
    }

    @Test
    fun emptyAppLabelsAreNotListed() {
        val g = Grouping.of(listOf(Fixtures.conn(appLabel = ""))).first()
        assertTrue(g.apps.isEmpty())
    }

    @Test
    fun displayIsTheAddressWhenReverseDnsIsOff() {
        val g = Grouping.of(rows, revDns = false).first()
        assertEquals("93.184.216.34", g.display)
        assertEquals("93.184.216.34", g.host)
    }

    @Test
    fun reverseDnsGroupsSeveralAddressesUnderOneName() {
        val cdn = listOf(
            Fixtures.conn(key = "1", remoteAddr = "203.0.113.1", resolvedHost = "cdn.example", rxBps = 1),
            Fixtures.conn(key = "2", remoteAddr = "203.0.113.2", resolvedHost = "cdn.example", rxBps = 2),
        )
        val groups = Grouping.of(cdn, revDns = true)
        assertEquals(1, groups.size)
        assertEquals("cdn.example", groups[0].display)
        assertEquals("203.0.113.1", groups[0].host)
        assertEquals(3L, groups[0].rxBps)
    }

    @Test
    fun unresolvedRowsStillGroupByAddressWithReverseDnsOn() {
        val mixed = listOf(
            Fixtures.conn(key = "1", remoteAddr = "203.0.113.1", resolvedHost = null),
            Fixtures.conn(key = "2", remoteAddr = "203.0.113.1", resolvedHost = ""),
        )
        val groups = Grouping.of(mixed, revDns = true)
        assertEquals(1, groups.size)
        assertEquals("203.0.113.1", groups[0].display)
    }

    @Test
    fun theMostRoutableScopeWins() {
        val mixed = listOf(
            Fixtures.conn(key = "1", remoteAddr = "203.0.113.1", resolvedHost = "shared", rxBps = 1),
            Fixtures.conn(key = "2", remoteAddr = "192.168.1.9", resolvedHost = "shared", rxBps = 1),
        )
        assertEquals(Scope.PUBLIC, Grouping.of(mixed, revDns = true).first().scope)
    }

    @Test
    fun watchlistFlagIsStickyAcrossTheGroup() {
        val mixed = listOf(
            Fixtures.conn(key = "1", remoteAddr = "203.0.113.1", watchlisted = false),
            Fixtures.conn(key = "2", remoteAddr = "203.0.113.1", watchlisted = true),
        )
        assertTrue(Grouping.of(mixed).first().watchlisted)
        assertFalse(Grouping.of(listOf(Fixtures.conn())).first().watchlisted)
    }

    @Test
    fun groupsKeepFirstAppearanceOrder() {
        val order = listOf(
            Fixtures.conn(key = "1", remoteAddr = "9.9.9.9"),
            Fixtures.conn(key = "2", remoteAddr = "1.1.1.1"),
            Fixtures.conn(key = "3", remoteAddr = "9.9.9.9"),
        )
        assertEquals(listOf("9.9.9.9", "1.1.1.1"), Grouping.of(order).map { it.host })
    }

    @Test
    fun emptyInputProducesNoGroups() {
        assertTrue(Grouping.of(emptyList()).isEmpty())
    }
}
