package com.vlad.ducknetview.domain.sort

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.ui.HostGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortersTest {

    private val rate = ThroughputMode.RATE
    private val total = ThroughputMode.TOTAL

    private val conns = listOf(
        Fixtures.conn(key = "a", rxBps = 10, rxBytes = 900, rttMillis = 50, firstSeen = 100),
        Fixtures.conn(key = "b", rxBps = 300, rxBytes = 10, rttMillis = -1, firstSeen = 500),
        Fixtures.conn(key = "c", rxBps = 20, rxBytes = 5000, rttMillis = 5, firstSeen = 300),
    )

    @Test
    fun rateModeSortsOnBytesPerSecond() {
        val out = conns.sortedWith(Sorters.conns("rx", desc = true, mode = rate, now = 1_000))
        assertEquals(listOf("b", "c", "a"), out.map { it.key })
    }

    @Test
    fun totalModeSortsOnCumulativeBytes() {
        val out = conns.sortedWith(Sorters.conns("rx", desc = true, mode = total, now = 1_000))
        assertEquals(listOf("c", "a", "b"), out.map { it.key })
    }

    @Test
    fun ascendingIsTheExactReverseOfDescendingApartFromTies() {
        val down = conns.sortedWith(Sorters.conns("rx", desc = true, mode = rate, now = 0))
        val up = conns.sortedWith(Sorters.conns("rx", desc = false, mode = rate, now = 0))
        assertEquals(down.map { it.key }, up.map { it.key }.reversed())
    }

    @Test
    fun tiesFallBackToAStableKeySoRowsDoNotJitter() {
        val tied = listOf(
            Fixtures.conn(key = "zz", rxBps = 5),
            Fixtures.conn(key = "aa", rxBps = 5),
            Fixtures.conn(key = "mm", rxBps = 5),
        )
        val a = tied.sortedWith(Sorters.conns("rx", desc = true, mode = rate, now = 0))
        val b = tied.shuffled().sortedWith(Sorters.conns("rx", desc = true, mode = rate, now = 0))
        assertEquals(listOf("aa", "mm", "zz"), a.map { it.key })
        assertEquals(a.map { it.key }, b.map { it.key })
    }

    @Test
    fun tieBreakStaysAscendingInBothDirections() {
        val tied = listOf(Fixtures.conn(key = "b"), Fixtures.conn(key = "a"))
        assertEquals(
            listOf("a", "b"),
            tied.sortedWith(Sorters.conns("rx", desc = true, mode = rate, now = 0)).map { it.key },
        )
        assertEquals(
            listOf("a", "b"),
            tied.sortedWith(Sorters.conns("rx", desc = false, mode = rate, now = 0)).map { it.key },
        )
    }

    @Test
    fun ageSortsYoungestFirstAscending() {
        val out = conns.sortedWith(Sorters.conns("age", desc = false, mode = rate, now = 1_000))
        assertEquals(listOf("b", "c", "a"), out.map { it.key })
    }

    @Test
    fun unmeasuredRttSortsLast() {
        val out = conns.sortedWith(Sorters.conns("rtt", desc = false, mode = rate, now = 0))
        assertEquals(listOf("c", "a", "b"), out.map { it.key })
    }

    @Test
    fun scopeSortsMostRoutableFirst() {
        assertTrue(Sorters.scopeRank(Scope.PUBLIC) < Sorters.scopeRank(Scope.PRIVATE))
        assertTrue(Sorters.scopeRank(Scope.PRIVATE) < Sorters.scopeRank(Scope.MULTICAST))
        assertTrue(Sorters.scopeRank(Scope.MULTICAST) < Sorters.scopeRank(Scope.LOOPBACK))

        val rows = listOf(
            Fixtures.conn(key = "loop", remoteAddr = "127.0.0.1"),
            Fixtures.conn(key = "pub", remoteAddr = "8.8.8.8"),
            Fixtures.conn(key = "lan", remoteAddr = "192.168.1.9"),
        )
        val out = rows.sortedWith(Sorters.conns("scope", desc = false, mode = rate, now = 0))
        assertEquals(listOf("pub", "lan", "loop"), out.map { it.key })
    }

    @Test
    fun localSortsNumericallyByPort() {
        val rows = listOf(
            Fixtures.conn(key = "hi", localPort = 40000),
            Fixtures.conn(key = "lo", localPort = 900),
        )
        val out = rows.sortedWith(Sorters.conns("local", desc = false, mode = rate, now = 0))
        assertEquals(listOf("lo", "hi"), out.map { it.key })
    }

    @Test
    fun processSortsCaseInsensitively() {
        val rows = listOf(
            Fixtures.conn(key = "1", appLabel = "zeta"),
            Fixtures.conn(key = "2", appLabel = "Alpha"),
            Fixtures.conn(key = "3", appLabel = "beta"),
        )
        val out = rows.sortedWith(Sorters.conns("process", desc = false, mode = rate, now = 0))
        assertEquals(listOf("Alpha", "beta", "zeta"), out.map { it.appLabel })
    }

    @Test
    fun protoAndStateSortByName() {
        val rows = listOf(
            Fixtures.conn(key = "u", proto = Proto.UDP, state = ConnState.ACTIVE),
            Fixtures.conn(key = "t", proto = Proto.TCP, state = ConnState.ESTABLISHED),
        )
        assertEquals(
            listOf("t", "u"),
            rows.sortedWith(Sorters.conns("proto", desc = false, mode = rate, now = 0)).map { it.key },
        )
        assertEquals(
            listOf("u", "t"),
            rows.sortedWith(Sorters.conns("state", desc = false, mode = rate, now = 0)).map { it.key },
        )
    }

    @Test
    fun unknownColumnStillProducesATotalOrder() {
        val out = conns.sortedWith(Sorters.conns("nonsense", desc = true, mode = rate, now = 0))
        assertEquals(listOf("a", "b", "c"), out.map { it.key })
    }

    @Test
    fun appsSortOnConnsRateTotalAndToday() {
        val apps = listOf(
            Fixtures.app(uid = 1, label = "one", connCount = 3, rxBps = 5, sessionRx = 900, todayRx = 10),
            Fixtures.app(uid = 2, label = "two", connCount = 9, rxBps = 1, sessionRx = 100, todayRx = 90),
        )
        assertEquals(
            listOf(2, 1),
            apps.sortedWith(Sorters.apps("conns", true, ThroughputMode.RATE)).map { it.uid },
        )
        assertEquals(
            listOf(1, 2),
            apps.sortedWith(Sorters.apps("rx", true, ThroughputMode.RATE)).map { it.uid },
        )
        assertEquals(
            listOf(1, 2),
            apps.sortedWith(Sorters.apps("rx", true, ThroughputMode.TOTAL)).map { it.uid },
        )
        assertEquals(
            listOf(2, 1),
            apps.sortedWith(Sorters.apps("today", true, ThroughputMode.RATE)).map { it.uid },
        )
        assertEquals(
            listOf(1, 2),
            apps.sortedWith(Sorters.apps("name", false, ThroughputMode.RATE)).map { it.uid },
        )
    }

    @Test
    fun servicesSortByPortAndExposure() {
        val rows = listOf(
            Fixtures.service(bindAddr = "127.0.0.1", port = 9000),
            Fixtures.service(bindAddr = "0.0.0.0", port = 80),
            Fixtures.service(bindAddr = "192.168.1.5", port = 443),
        )
        assertEquals(
            listOf(80, 443, 9000),
            rows.sortedWith(Sorters.services("port", false)).map { it.port },
        )
        assertEquals(
            listOf(9000, 443, 80),
            rows.sortedWith(Sorters.services("exposure", false)).map { it.port },
        )
        val named = listOf(
            Fixtures.service(port = 22, service = "ssh"),
            Fixtures.service(port = 80, service = "http"),
            Fixtures.service(port = 5432, service = ""),
        )
        assertEquals(
            listOf("", "http", "ssh"),
            named.sortedWith(Sorters.services("service", false)).map { it.service },
        )
    }

    @Test
    fun closedSortsOnFinalCountersAndLifetime() {
        val rows = listOf(
            Fixtures.closed(Fixtures.conn(key = "a"), closedAt = 10, lifetimeMillis = 900, finalRx = 5),
            Fixtures.closed(Fixtures.conn(key = "b"), closedAt = 20, lifetimeMillis = 100, finalRx = 50),
        )
        assertEquals(
            listOf("b", "a"),
            rows.sortedWith(Sorters.closed("closedAt", true)).map { it.row.key },
        )
        assertEquals(
            listOf("a", "b"),
            rows.sortedWith(Sorters.closed("lifetime", true)).map { it.row.key },
        )
        assertEquals(
            listOf("b", "a"),
            rows.sortedWith(Sorters.closed("rx", true)).map { it.row.key },
        )
    }

    @Test
    fun groupsSortOnBytesInBothModes() {
        val groups = listOf(
            group("a", conns = 1, rxBps = 100, rxTotal = 1),
            group("b", conns = 9, rxBps = 1, rxTotal = 100),
        )
        assertEquals(
            listOf("a", "b"),
            groups.sortedWith(Sorters.groups("bytes", true, ThroughputMode.RATE)).map { it.host },
        )
        assertEquals(
            listOf("b", "a"),
            groups.sortedWith(Sorters.groups("bytes", true, ThroughputMode.TOTAL)).map { it.host },
        )
        assertEquals(
            listOf("b", "a"),
            groups.sortedWith(Sorters.groups("conns", true, ThroughputMode.RATE)).map { it.host },
        )
    }

    @Test
    fun columnRegistriesAreExposedPerTable() {
        assertEquals(Sorters.CONN_COLUMNS, Sorters.columnsFor("conns"))
        assertTrue(Sorters.CONN_COLUMNS.containsAll(listOf("rx", "tx", "rtt", "age", "scope")))
        assertEquals(listOf("conns", "rx", "tx", "name", "today"), Sorters.APP_COLUMNS)
        assertEquals(listOf("proto", "port", "service", "exposure"), Sorters.SERVICE_COLUMNS)
        assertEquals(listOf("closedAt", "lifetime", "rx", "tx"), Sorters.CLOSED_COLUMNS)
        assertEquals(listOf("conns", "host", "bytes"), Sorters.GROUP_COLUMNS)
        assertTrue(Sorters.columnsFor("nope").isEmpty())
    }

    private fun group(host: String, conns: Int, rxBps: Long, rxTotal: Long) = HostGroup(
        host = host,
        display = host,
        connCount = conns,
        rxBps = rxBps,
        txBps = 0,
        rxTotal = rxTotal,
        txTotal = 0,
        states = emptyMap(),
        apps = emptyList(),
        scope = Scope.PUBLIC,
        watchlisted = false,
    )
}
