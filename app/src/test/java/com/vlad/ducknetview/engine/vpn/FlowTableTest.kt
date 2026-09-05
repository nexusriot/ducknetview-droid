package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTableTest {

    private fun key(port: Int, proto: Proto = Proto.TCP) =
        FlowKey(proto, "10.215.173.2", port, "93.184.216.34", 443)

    @Test
    fun openIsIdempotentForTheSameKey() {
        val t = FlowTable()
        val a = t.open(key(1000), 4, 1L)
        val b = t.open(key(1000), 4, 2L)
        assertTrue(a === b)
        assertEquals(1, t.size)
    }

    @Test
    fun closeMovesTheFlowIntoHistoryWithItsFinalCounters() {
        val t = FlowTable()
        val f = t.open(key(1001), 4, 100L)
        f.rx.set(4096)
        f.tx.set(512)
        t.close(key(1001), 900L, "Browser", "com.example.browser")

        assertEquals(0, t.size)
        val closed = t.closedSnapshot()
        assertEquals(1, closed.size)
        assertEquals(4096L, closed[0].finalRx)
        assertEquals(512L, closed[0].finalTx)
        assertEquals(800L, closed[0].lifetimeMillis)
        assertEquals("Browser", closed[0].row.appLabel)
        assertEquals(ConnState.CLOSED, closed[0].row.state)
    }

    @Test
    fun closedHistoryIsBoundedAndKeepsTheNewest() {
        val t = FlowTable(closedLimit = 3)
        repeat(6) { i ->
            t.open(key(2000 + i), 4, i.toLong())
            t.close(key(2000 + i), (100 + i).toLong(), "", "")
        }
        val closed = t.closedSnapshot()
        assertEquals(3, closed.size)
        // Newest first, and the three oldest are gone.
        assertEquals(105L, closed[0].closedAt)
        assertEquals(103L, closed[2].closedAt)
    }

    @Test
    fun churnCountersDrainAndReset() {
        val t = FlowTable()
        t.open(key(3000), 4, 1L)
        t.open(key(3001), 4, 1L)
        t.close(key(3000), 2L, "", "")

        val (opened, closed) = t.drainChurn()
        assertEquals(2, opened)
        assertEquals(1, closed)

        val (o2, c2) = t.drainChurn()
        assertEquals(0, o2)
        assertEquals(0, c2)
    }

    @Test
    fun expireUsesADifferentIdleWindowForUdp() {
        val t = FlowTable()
        val tcp = t.open(key(4000, Proto.TCP), 4, 0L)
        val udp = t.open(key(4001, Proto.UDP), 4, 0L)
        tcp.touch(0L)
        udp.touch(0L)

        val doomed = mutableListOf<FlowKey>()
        t.expire(now = 70_000L, udpIdleMs = 60_000L, tcpIdleMs = 600_000L) { doomed += it.key }

        assertEquals(1, doomed.size)
        assertEquals(Proto.UDP, doomed[0].proto)
    }

    @Test
    fun closingAnUnknownKeyIsANoOp() {
        val t = FlowTable()
        t.close(key(5000), 1L, "", "")
        assertTrue(t.closedSnapshot().isEmpty())
    }

    @Test
    fun rowCarriesScopeAndServiceDerivedFromTheRemote() {
        val t = FlowTable()
        val f = t.open(key(6000), 4, 10L)
        f.uid = 10123
        f.rttMillis = 24
        val row = t.toRow(f, 20L, "Browser", "com.example", rxBps = 100, txBps = 50, isNew = true)

        assertEquals("93.184.216.34", row.remoteAddr)
        assertEquals(443, row.remotePort)
        assertEquals(24, row.rttMillis)
        assertEquals(10123, row.uid)
        assertTrue(row.isNew)
        assertEquals(10L, row.ageMillis(20L))
    }

    @Test
    fun getReturnsNullAfterClose() {
        val t = FlowTable()
        t.open(key(7000), 4, 1L)
        assertNotNull(t.get(key(7000)))
        t.close(key(7000), 2L, "", "")
        assertNull(t.get(key(7000)))
    }

    @Test
    fun flowKeyStringIsStableAndBracketsIpv6() {
        val v6 = FlowKey(Proto.TCP, "fd00::1", 100, "2001:db8::2", 443)
        assertEquals("tcp|[fd00::1]:100|[2001:db8::2]:443", v6.toString())
    }
}
