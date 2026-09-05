package com.vlad.ducknetview.engine.api

import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Proto
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortScannerTest {

    @Test
    fun `the fast list covers the classic service ports`() {
        val ports = PortScanner.ports(full = false)
        for (p in listOf(21, 22, 23, 25, 53, 80, 443, 445, 3306, 5432, 8080, 8443)) {
            assertTrue("fast list is missing $p", ports.contains(p))
        }
    }

    @Test
    fun `the fast list is a sane size, sorted and free of duplicates`() {
        val ports = PortScanner.ports(full = false)
        assertTrue("unexpected size ${ports.size}", ports.size in 150..400)
        assertEquals(ports.sorted(), ports)
        assertEquals(ports.distinct().size, ports.size)
    }

    @Test
    fun `every fast port is a legal TCP port`() {
        assertTrue(PortScanner.ports(full = false).all { it in 1..PortScanner.MAX_PORT })
    }

    @Test
    fun `the full sweep is the whole port space`() {
        val ports = PortScanner.ports(full = true)
        assertEquals(65535, ports.size)
        assertEquals(1, ports.first())
        assertEquals(PortScanner.MAX_PORT, ports.last())
    }

    @Test
    fun `loopback is always a target even with no local addresses`() {
        assertEquals(listOf(PortScanner.LOOPBACK), PortScanner.targetAddrs(emptyList()))
    }

    @Test
    fun `local addresses are added after loopback without duplicating it`() {
        val out = PortScanner.targetAddrs(listOf("127.0.0.1", "192.168.1.5", "192.168.1.5"))
        assertEquals(listOf("127.0.0.1", "192.168.1.5"), out)
    }

    @Test
    fun `normalizeAddr strips CIDR prefix, IPv6 scope and brackets`() {
        assertEquals("192.168.1.5", PortScanner.normalizeAddr("192.168.1.5/24"))
        assertEquals("fe80::1", PortScanner.normalizeAddr("fe80::1%wlan0"))
        assertEquals("fe80::1", PortScanner.normalizeAddr("fe80::1%wlan0/64"))
        assertEquals("::1", PortScanner.normalizeAddr("[::1]"))
        assertNull(PortScanner.normalizeAddr("  "))
        assertNull(PortScanner.normalizeAddr(null))
    }

    @Test
    fun `no hits means no rows`() {
        assertTrue(PortScanner.mergeHits(emptyList(), 1L).isEmpty())
    }

    @Test
    fun `a loopback-only hit is bound LOCAL`() {
        val rows = PortScanner.mergeHits(listOf(PortScanner.Hit("127.0.0.1", 8080)), 7L)
        assertEquals(1, rows.size)
        assertEquals(Exposure.LOCAL, rows[0].exposure)
        assertEquals("127.0.0.1", rows[0].bindAddr)
        assertEquals(Proto.TCP, rows[0].proto)
    }

    @Test
    fun `a LAN-address hit is reachable off-host`() {
        val rows = PortScanner.mergeHits(listOf(PortScanner.Hit("192.168.1.5", 8080)), 7L)
        assertEquals(1, rows.size)
        assertTrue(rows[0].exposure != Exposure.LOCAL)
    }

    @Test
    fun `a port answering on both loopback and LAN is one row at the widest exposure`() {
        val rows = PortScanner.mergeHits(
            listOf(PortScanner.Hit("127.0.0.1", 8080), PortScanner.Hit("192.168.1.5", 8080)),
            7L,
        )
        assertEquals(1, rows.size)
        assertTrue(
            "loopback must not win over a LAN answer",
            PortScanner.rank(rows[0].exposure) > PortScanner.rank(Exposure.LOCAL),
        )
        assertEquals("192.168.1.5", rows[0].bindAddr)
    }

    @Test
    fun `hit order does not change the merged exposure`() {
        val a = PortScanner.mergeHits(
            listOf(PortScanner.Hit("192.168.1.5", 22), PortScanner.Hit("127.0.0.1", 22)), 1L,
        )
        val b = PortScanner.mergeHits(
            listOf(PortScanner.Hit("127.0.0.1", 22), PortScanner.Hit("192.168.1.5", 22)), 1L,
        )
        assertEquals(a[0].exposure, b[0].exposure)
        assertEquals(a[0].bindAddr, b[0].bindAddr)
    }

    @Test
    fun `widest picks the higher-ranked exposure either way round`() {
        assertEquals(Exposure.EXPOSED, PortScanner.widest(Exposure.LOCAL, Exposure.EXPOSED))
        assertEquals(Exposure.EXPOSED, PortScanner.widest(Exposure.EXPOSED, Exposure.LOCAL))
        assertEquals(Exposure.LAN, PortScanner.widest(Exposure.LOCAL, Exposure.LAN))
        assertEquals(Exposure.LOCAL, PortScanner.widest(Exposure.LOCAL, Exposure.LOCAL))
    }

    @Test
    fun `merged rows are sorted by port and stamped with the scan time`() {
        val rows = PortScanner.mergeHits(
            listOf(
                PortScanner.Hit("127.0.0.1", 9000),
                PortScanner.Hit("127.0.0.1", 22),
                PortScanner.Hit("127.0.0.1", 443),
            ),
            4242L,
        )
        assertEquals(listOf(22, 443, 9000), rows.map { it.port })
        assertTrue(rows.all { it.firstSeen == 4242L && it.lastSeen == 4242L })
    }

    @Test
    fun `a merged row carries a service name`() {
        val rows = PortScanner.mergeHits(listOf(PortScanner.Hit("127.0.0.1", 22)), 1L)
        assertNotNull(rows[0].service)
    }

    @Test
    fun `a merged row has a stable key`() {
        val rows = PortScanner.mergeHits(listOf(PortScanner.Hit("127.0.0.1", 22)), 1L)
        assertEquals("tcp|127.0.0.1:22", rows[0].key)
    }

    @Test
    fun `scanning nothing yields nothing and reports zero progress`() = runBlocking {
        var reported = -1
        val rows = PortScanner().scanPorts(emptyList(), emptyList(), 100, 1L) { done, _ ->
            reported = done
        }
        assertTrue(rows.isEmpty())
        assertEquals(0, reported)
    }

    @Test
    fun `a real listening loopback port is found end to end`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        try {
            val port = server.localPort
            val rows = PortScanner().scanPorts(
                addrs = listOf(PortScanner.LOOPBACK),
                ports = listOf(port),
                timeoutMs = 1000,
                now = 99L,
            )
            assertEquals(1, rows.size)
            assertEquals(port, rows[0].port)
            assertEquals(Exposure.LOCAL, rows[0].exposure)
            assertEquals(99L, rows[0].firstSeen)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a closed loopback port is not reported`() = runBlocking {
        val probe = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = probe.localPort
        probe.close()
        val rows = PortScanner().scanPorts(listOf(PortScanner.LOOPBACK), listOf(port), 500, 1L)
        assertTrue("port $port should be closed", rows.isEmpty())
    }

    @Test
    fun `a scan of a mixed port set finds only the listener`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val deadPort = dead.localPort
        dead.close()
        try {
            val rows = PortScanner().scanPorts(
                addrs = listOf(PortScanner.LOOPBACK),
                ports = listOf(server.localPort, deadPort),
                timeoutMs = 1000,
                now = 1L,
            )
            assertEquals(listOf(server.localPort), rows.map { it.port })
            assertFalse(rows.any { it.port == deadPort })
        } finally {
            server.close()
        }
    }

    @Test
    fun `progress reaches the total`() = runBlocking {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        try {
            var lastDone = 0
            var lastTotal = 0
            PortScanner().scanPorts(
                addrs = listOf(PortScanner.LOOPBACK),
                ports = listOf(server.localPort),
                timeoutMs = 1000,
                now = 1L,
            ) { done, total ->
                lastDone = done
                lastTotal = total
            }
            assertEquals(1, lastTotal)
            assertEquals(1, lastDone)
        } finally {
            server.close()
        }
    }
}
