package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.Proto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowTableIcmpTest {

    private fun key(proto: Proto) = FlowKey(proto, "10.0.0.2", 1234, "1.1.1.1", if (proto == Proto.ICMP) 0 else 443)

    @Test
    fun `an ICMP row is labelled echo rather than given an invented service name`() {
        val table = FlowTable()
        val f = table.open(key(Proto.ICMP), ipVersion = 4, now = 1000L)
        val row = table.toRow(f, 1000L, "", "", 0, 0, isNew = false)
        // Port 0 has no service, and looking one up for the echo identifier
        // would name whatever service happens to sit on that number.
        assertEquals("echo", row.service)
    }

    @Test
    fun `a TCP row still gets its well-known service name`() {
        val table = FlowTable()
        val f = table.open(key(Proto.TCP), ipVersion = 4, now = 1000L)
        assertEquals("https", table.toRow(f, 1000L, "", "", 0, 0, isNew = false).service)
    }

    @Test
    fun `ICMP expires on the short window, like the other connectionless protocol`() {
        val table = FlowTable()
        table.open(key(Proto.ICMP), ipVersion = 4, now = 0L)
        table.open(key(Proto.UDP), ipVersion = 4, now = 0L)
        table.open(key(Proto.TCP), ipVersion = 4, now = 0L)

        val expired = ArrayList<Proto>()
        // Past the UDP window but well inside the TCP one: echo has no teardown
        // to wait for, so holding it for ten minutes would strand dead rows.
        table.expire(now = 70_000L, udpIdleMs = 60_000L, tcpIdleMs = 600_000L) {
            expired += it.key.proto
        }

        assertTrue(Proto.ICMP in expired)
        assertTrue(Proto.UDP in expired)
        assertTrue(Proto.TCP !in expired)
    }
}
