package com.vlad.ducknetview.domain.filter

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.ui.QuickFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {

    private val rows = listOf(
        Fixtures.conn(key = "tcp-public", proto = Proto.TCP, remoteAddr = "8.8.8.8", uid = 10001),
        Fixtures.conn(key = "udp-lan", proto = Proto.UDP, remoteAddr = "192.168.1.1", uid = 1000),
        Fixtures.conn(
            key = "tcp-v6",
            proto = Proto.TCP,
            remoteAddr = "2606:4700::1111",
            uid = 10002,
            state = ConnState.CLOSING,
            network = "cell",
        ),
    )

    @Test
    fun noFiltersReturnsTheSameList() {
        assertSame(rows, Filters.conns(rows, QuickFilters(), emptySet()))
    }

    @Test
    fun protoFilter() {
        val tcp = Filters.conns(rows, QuickFilters(proto = ProtoFilter.TCP), emptySet())
        assertEquals(listOf("tcp-public", "tcp-v6"), tcp.map { it.key })
        val udp = Filters.conns(rows, QuickFilters(proto = ProtoFilter.UDP), emptySet())
        assertEquals(listOf("udp-lan"), udp.map { it.key })
    }

    @Test
    fun ipVersionFilter() {
        val v4 = Filters.conns(rows, QuickFilters(ipVersion = IpVersionFilter.V4), emptySet())
        assertEquals(listOf("tcp-public", "udp-lan"), v4.map { it.key })
        val v6 = Filters.conns(rows, QuickFilters(ipVersion = IpVersionFilter.V6), emptySet())
        assertEquals(listOf("tcp-v6"), v6.map { it.key })
    }

    @Test
    fun stateFilter() {
        val est = Filters.conns(rows, QuickFilters(state = StateFilter.ESTABLISHED), emptySet())
        assertEquals(listOf("tcp-public", "udp-lan"), est.map { it.key })

        val active = Filters.conns(rows, QuickFilters(state = StateFilter.ACTIVE), emptySet())
        assertTrue("a closing socket is not active", active.none { it.key == "tcp-v6" })
        assertEquals(2, active.size)
    }

    @Test
    fun publicOnlyFilter() {
        val out = Filters.conns(rows, QuickFilters(publicOnly = true), emptySet())
        assertEquals(listOf("tcp-public", "tcp-v6"), out.map { it.key })
    }

    @Test
    fun userAppsOnlyUsesTheSuppliedUidSet() {
        val out = Filters.conns(rows, QuickFilters(userAppsOnly = true), setOf(10001, 10002))
        assertEquals(listOf("tcp-public", "tcp-v6"), out.map { it.key })
    }

    @Test
    fun userAppsOnlyStandsDownWhenNoUidsAreKnown() {
        val out = Filters.conns(rows, QuickFilters(userAppsOnly = true), emptySet())
        assertEquals(rows.size, out.size)
    }

    @Test
    fun networkAndUidFilters() {
        assertEquals(
            listOf("tcp-v6"),
            Filters.conns(rows, QuickFilters(network = "cell"), emptySet()).map { it.key },
        )
        assertEquals(
            listOf("udp-lan"),
            Filters.conns(rows, QuickFilters(uid = 1000), emptySet()).map { it.key },
        )
    }

    @Test
    fun filtersCombineAsAnd() {
        val f = QuickFilters(proto = ProtoFilter.TCP, publicOnly = true, network = "wifi")
        assertEquals(listOf("tcp-public"), Filters.conns(rows, f, emptySet()).map { it.key })
    }

    @Test
    fun appsFilterOnUserAppsAndUid() {
        val apps = listOf(
            Fixtures.app(uid = 1000, label = "System", isSystem = true),
            Fixtures.app(uid = 10001, label = "Browser"),
        )
        assertEquals(
            listOf(10001),
            Filters.apps(apps, QuickFilters(userAppsOnly = true), emptySet()).map { it.uid },
        )
        assertEquals(
            listOf(1000),
            Filters.apps(apps, QuickFilters(userAppsOnly = true), setOf(1000)).map { it.uid },
        )
        assertEquals(
            listOf(1000),
            Filters.apps(apps, QuickFilters(uid = 1000), emptySet()).map { it.uid },
        )
    }

    @Test
    fun servicesFilterOnProtoVersionAndExposure() {
        val services = listOf(
            Fixtures.service(proto = Proto.TCP, bindAddr = "0.0.0.0", port = 8080),
            Fixtures.service(proto = Proto.UDP, bindAddr = "127.0.0.1", port = 5353),
            Fixtures.service(proto = Proto.TCP, bindAddr = "::", port = 8081),
        )
        assertEquals(
            listOf(8080, 8081),
            Filters.services(services, QuickFilters(proto = ProtoFilter.TCP)).map { it.port },
        )
        assertEquals(
            listOf(8081),
            Filters.services(services, QuickFilters(ipVersion = IpVersionFilter.V6)).map { it.port },
        )
        assertEquals(
            listOf(8080, 8081),
            Filters.services(services, QuickFilters(publicOnly = true)).map { it.port },
        )
    }
}
