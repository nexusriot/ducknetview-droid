package com.vlad.ducknetview.data

import com.vlad.ducknetview.data.db.EventEntity
import com.vlad.ducknetview.data.db.toDomain
import com.vlad.ducknetview.data.db.toEntity
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.usage.DailyUsage
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {

    @Test
    fun `event round-trips through its entity`() {
        val e = Event(
            id = 7L,
            at = 1_700_000_000_000L,
            level = EventLevel.ALERT,
            kind = EventKind.WATCHLIST_HIT,
            subject = "185.199.108.153:443",
            detail = "matched 185.199.0.0/16",
        )
        assertEquals(e, e.toEntity().toDomain())
    }

    @Test
    fun `every event kind survives the label round-trip`() {
        for (kind in EventKind.entries) {
            val e = Event(at = 1L, level = EventLevel.INFO, kind = kind, subject = "s")
            assertEquals(kind, e.toEntity().toDomain().kind)
        }
    }

    @Test
    fun `every event level survives the round-trip`() {
        for (level in EventLevel.entries) {
            val e = Event(at = 1L, level = level, kind = EventKind.FANOUT, subject = "s")
            assertEquals(level, e.toEntity().toDomain().level)
        }
    }

    @Test
    fun `an unknown stored kind or level degrades instead of throwing`() {
        val row = EventEntity(
            id = 1L,
            at = 5L,
            level = "CATASTROPHE",
            kind = "invented_in_a_later_build",
            subject = "s",
            detail = "",
        )
        val e = row.toDomain()
        assertEquals(EventLevel.INFO, e.level)
        assertEquals(EventKind.SUPPRESSED, e.kind)
    }

    @Test
    fun `closed connection round-trips its persisted columns`() {
        val src = closedConn()
        val back = src.toEntity().toDomain()
        assertEquals(src.closedAt, back.closedAt)
        assertEquals(src.lifetimeMillis, back.lifetimeMillis)
        assertEquals(src.finalRx, back.finalRx)
        assertEquals(src.finalTx, back.finalTx)
        assertEquals(src.row.proto, back.row.proto)
        assertEquals(src.row.localAddr, back.row.localAddr)
        assertEquals(src.row.localPort, back.row.localPort)
        assertEquals(src.row.remoteAddr, back.row.remoteAddr)
        assertEquals(src.row.remotePort, back.row.remotePort)
        assertEquals(src.row.uid, back.row.uid)
        assertEquals(src.row.appLabel, back.row.appLabel)
        assertEquals(src.row.service, back.row.service)
        assertEquals(src.row.scope, back.row.scope)
        assertEquals(src.row.network, back.row.network)
        assertEquals(src.row.resolvedHost, back.row.resolvedHost)
    }

    @Test
    fun `a rehydrated closed connection carries final totals and a closed state`() {
        val back = closedConn().toEntity().toDomain()
        assertEquals(ConnState.CLOSED, back.row.state)
        assertEquals(4096L, back.row.rxBytes)
        assertEquals(512L, back.row.txBytes)
        assertEquals(0L, back.row.rxBps)
        assertEquals(0L, back.row.txBps)
        assertEquals(1_000_000L, back.row.lastSeen)
        assertEquals(1_000_000L - 30_000L, back.row.firstSeen)
        assertEquals(30_000L, back.row.ageMillis(1_000_000L))
    }

    @Test
    fun `an ipv6 closed connection gets a bracketed rebuilt key`() {
        val src = closedConn().let {
            it.copy(row = it.row.copy(localAddr = "fd00::1", remoteAddr = "2606:4700::1111"))
        }
        val back = src.toEntity().toDomain()
        assertEquals("tcp|[fd00::1]:54321|[2606:4700::1111]:443", back.row.key)
    }

    @Test
    fun `a null resolved host stays null`() {
        val src = closedConn().let { it.copy(row = it.row.copy(resolvedHost = null)) }
        assertEquals(null, src.toEntity().toDomain().row.resolvedHost)
    }

    @Test
    fun `daily usage round-trips including awkward map keys`() {
        val u = DailyUsage(
            dayEpoch = 20_000L,
            rx = 123_456_789L,
            tx = 987L,
            apps = mapOf("com.example;app" to 10L, "org.foo=bar" to 20L, "日本語" to 30L),
            hosts = mapOf("a.example.com" to 1L, "b;c=d" to 2L),
        )
        assertEquals(u, u.toEntity().toDomain())
    }

    @Test
    fun `daily usage with empty maps round-trips`() {
        val u = DailyUsage(dayEpoch = 1L, rx = 0L, tx = 0L, apps = emptyMap(), hosts = emptyMap())
        assertEquals(u, u.toEntity().toDomain())
    }

    private fun closedConn(): ClosedConn = ClosedConn(
        row = ConnRow(
            key = "live-flow-key",
            proto = Proto.TCP,
            localAddr = "10.215.173.2",
            localPort = 54321,
            remoteAddr = "140.82.121.4",
            remotePort = 443,
            state = ConnState.ESTABLISHED,
            uid = 10123,
            appLabel = "Example",
            packageName = "com.example",
            service = "https",
            scope = Scope.PUBLIC,
            rxBytes = 4096L,
            txBytes = 512L,
            network = "wifi0",
            resolvedHost = "github.com",
        ),
        closedAt = 1_000_000L,
        lifetimeMillis = 30_000L,
        finalRx = 4096L,
        finalTx = 512L,
    )
}
