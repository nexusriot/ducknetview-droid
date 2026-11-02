package com.vlad.ducknetview.domain.diff

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.export.SnapshotJson
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDiffTest {

    private fun diff(old: NetSnapshot, new: NetSnapshot) = SnapshotDiffer.diff(old, new)

    @Test
    fun `identical snapshots differ in nothing`() {
        val s = Fixtures.snapshot(
            conns = listOf(Fixtures.conn()),
            apps = listOf(Fixtures.app()),
            services = listOf(Fixtures.service()),
            networks = listOf(Fixtures.network()),
        )
        val d = diff(s, s)
        assertTrue(d.isEmpty)
        assertEquals(0, d.changeCount)
    }

    @Test
    fun `two empty snapshots differ in nothing`() {
        assertTrue(diff(NetSnapshot(), NetSnapshot()).isEmpty)
    }

    @Test
    fun `counters and rates are not changes`() {
        val old = Fixtures.snapshot(apps = listOf(Fixtures.app(sessionRx = 1, rxBps = 5)))
        val new = Fixtures.snapshot(atMillis = 99_000, apps = listOf(Fixtures.app(sessionRx = 9_000, rxBps = 0)))
        assertTrue(diff(old, new).isEmpty)
    }

    @Test
    fun `a new listener is gained and a missing one is lost`() {
        val ssh = Fixtures.service(port = 22, bindAddr = "0.0.0.0")
        val web = Fixtures.service(port = 8080, bindAddr = "0.0.0.0")
        val d = diff(Fixtures.snapshot(services = listOf(ssh)), Fixtures.snapshot(services = listOf(web)))
        assertEquals(listOf(web), d.listenersGained)
        assertEquals(listOf(ssh), d.listenersLost)
        assertTrue(d.listenersMoved.isEmpty())
        assertEquals(2, d.changeCount)
    }

    @Test
    fun `a rebind on the same port is a move, not a loss and a gain`() {
        val local = Fixtures.service(port = 8080, bindAddr = "127.0.0.1")
        val wide = Fixtures.service(port = 8080, bindAddr = "0.0.0.0")
        val d = diff(Fixtures.snapshot(services = listOf(local)), Fixtures.snapshot(services = listOf(wide)))
        assertEquals(listOf(local to wide), d.listenersMoved)
        assertTrue(d.listenersGained.isEmpty())
        assertTrue(d.listenersLost.isEmpty())
        assertEquals(1, d.changeCount)
    }

    @Test
    fun `the protocol is part of a move`() {
        val tcp = Fixtures.service(proto = Proto.TCP, port = 5353, bindAddr = "127.0.0.1")
        val udp = Fixtures.service(proto = Proto.UDP, port = 5353, bindAddr = "0.0.0.0")
        val d = diff(Fixtures.snapshot(services = listOf(tcp)), Fixtures.snapshot(services = listOf(udp)))
        assertTrue(d.listenersMoved.isEmpty())
        assertEquals(listOf(udp), d.listenersGained)
        assertEquals(listOf(tcp), d.listenersLost)
    }

    @Test
    fun `an extra bind beside a surviving one is a gain`() {
        val local = Fixtures.service(port = 8080, bindAddr = "127.0.0.1")
        val wide = Fixtures.service(port = 8080, bindAddr = "0.0.0.0")
        val d = diff(Fixtures.snapshot(services = listOf(local)), Fixtures.snapshot(services = listOf(local, wide)))
        assertEquals(listOf(wide), d.listenersGained)
        assertTrue(d.listenersMoved.isEmpty())
        assertTrue(d.listenersLost.isEmpty())
    }

    @Test
    fun `moves pair one to one and leave the remainder as gains or losses`() {
        val a = Fixtures.service(port = 9000, bindAddr = "127.0.0.1")
        val b = Fixtures.service(port = 9000, bindAddr = "::1")
        val c = Fixtures.service(port = 9000, bindAddr = "0.0.0.0")
        val d = diff(Fixtures.snapshot(services = listOf(a, b)), Fixtures.snapshot(services = listOf(c)))
        assertEquals(listOf(a to c), d.listenersMoved)
        assertEquals(listOf(b), d.listenersLost)
        assertTrue(d.listenersGained.isEmpty())
    }

    @Test
    fun `a listener owned by a different uid is still the same listener`() {
        val old = Fixtures.service(port = 8080, uid = 10002, appLabel = "A")
        val new = Fixtures.service(port = 8080, uid = 10099, appLabel = "B")
        assertTrue(diff(Fixtures.snapshot(services = listOf(old)), Fixtures.snapshot(services = listOf(new))).isEmpty)
    }

    @Test
    fun `apps are matched by package, so a new uid is not a change`() {
        val old = Fixtures.app(uid = 10001)
        val new = Fixtures.app(uid = 10555)
        assertTrue(diff(Fixtures.snapshot(apps = listOf(old)), Fixtures.snapshot(apps = listOf(new))).isEmpty)
    }

    @Test
    fun `apps appear and go`() {
        val browser = Fixtures.app()
        val mail = Fixtures.app(uid = 10002, label = "Mail", packageName = "com.example.mail")
        val d = diff(Fixtures.snapshot(apps = listOf(browser)), Fixtures.snapshot(apps = listOf(mail)))
        assertEquals(listOf(mail), d.appsAppeared)
        assertEquals(listOf(browser), d.appsGone)
    }

    @Test
    fun `apps without a package fall back to their label`() {
        val oldKernel = Fixtures.app(uid = 0, label = "Kernel", packageName = "")
        val newKernel = Fixtures.app(uid = 1000, label = "Kernel", packageName = "")
        val media = Fixtures.app(uid = 1013, label = "Media", packageName = "")
        val d = diff(
            Fixtures.snapshot(apps = listOf(oldKernel)),
            Fixtures.snapshot(apps = listOf(newKernel, media)),
        )
        assertEquals(listOf(media), d.appsAppeared)
        assertTrue(d.appsGone.isEmpty())
    }

    @Test
    fun `apps with neither package nor label are told apart by uid`() {
        val a = Fixtures.app(uid = 1, label = "", packageName = "")
        val b = Fixtures.app(uid = 2, label = "", packageName = "")
        val d = diff(Fixtures.snapshot(apps = listOf(a)), Fixtures.snapshot(apps = listOf(b)))
        assertEquals(listOf(b), d.appsAppeared)
        assertEquals(listOf(a), d.appsGone)
    }

    @Test
    fun `a host the old snapshot never contacted is a first contact`() {
        val old = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "93.184.216.34")))
        val new = Fixtures.snapshot(
            conns = listOf(
                Fixtures.conn(key = "a", remoteAddr = "93.184.216.34"),
                Fixtures.conn(key = "b", remoteAddr = "203.0.113.7"),
                Fixtures.conn(key = "c", remoteAddr = "203.0.113.7", remotePort = 80),
            ),
        )
        assertEquals(listOf("203.0.113.7"), diff(old, new).hostsFirstContact)
    }

    @Test
    fun `closed connections count on both sides`() {
        val old = Fixtures.snapshot().copy(closedConns = listOf(Fixtures.closed(Fixtures.conn(remoteAddr = "198.51.100.1"))))
        val new = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "198.51.100.1")))
            .copy(closedConns = listOf(Fixtures.closed(Fixtures.conn(remoteAddr = "198.51.100.2"))))
        assertEquals(listOf("198.51.100.2"), diff(old, new).hostsFirstContact)
    }

    @Test
    fun `a first contact is reported by its resolved name`() {
        val new = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.7", resolvedHost = "api.example.org")))
        assertEquals(listOf("api.example.org"), diff(Fixtures.snapshot(), new).hostsFirstContact)
    }

    @Test
    fun `a host known by address or by name is not a first contact`() {
        // Same address, newly resolved.
        val old1 = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.7")))
        val new1 = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.7", resolvedHost = "a.example")))
        assertTrue(diff(old1, new1).hostsFirstContact.isEmpty())
        // Same name, a different address of the same service.
        val old2 = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.7", resolvedHost = "a.example")))
        val new2 = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.8", resolvedHost = "a.example")))
        assertTrue(diff(old2, new2).hostsFirstContact.isEmpty())
    }

    @Test
    fun `an empty resolved name is treated as none`() {
        val new = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.9", resolvedHost = "")))
        assertEquals(listOf("203.0.113.9"), diff(Fixtures.snapshot(), new).hostsFirstContact)
    }

    @Test
    fun `hosts that went quiet are not reported`() {
        val old = Fixtures.snapshot(conns = listOf(Fixtures.conn(remoteAddr = "203.0.113.7")))
        assertTrue(diff(old, Fixtures.snapshot()).isEmpty)
    }

    @Test
    fun `networks are keyed by transport and interface, not netId`() {
        val old = Fixtures.network(id = "100")
        val new = Fixtures.network(id = "101")
        assertTrue(diff(Fixtures.snapshot(networks = listOf(old)), Fixtures.snapshot(networks = listOf(new))).isEmpty)
    }

    @Test
    fun `networks appear and go`() {
        val wifi = Fixtures.network()
        val cell = Fixtures.network(id = "net-2", ifaceName = "rmnet0", transport = Transport.CELLULAR)
        val vpn = Fixtures.network(id = "net-3", ifaceName = "rmnet0", transport = Transport.VPN)
        val d = diff(Fixtures.snapshot(networks = listOf(wifi, cell)), Fixtures.snapshot(networks = listOf(vpn)))
        assertEquals(listOf(vpn), d.networksAppeared)
        assertEquals(listOf(wifi, cell), d.networksGone)
        assertEquals(3, d.changeCount)
        assertFalse(d.isEmpty)
    }

    @Test
    fun `diffs survive a json round trip of both snapshots`() {
        val old = Fixtures.snapshot(
            services = listOf(Fixtures.service(port = 8080, bindAddr = "127.0.0.1")),
            apps = listOf(Fixtures.app()),
            networks = listOf(Fixtures.network()),
        )
        val new = Fixtures.snapshot(
            services = listOf(Fixtures.service(port = 8080, bindAddr = "0.0.0.0")),
            apps = listOf(Fixtures.app(), Fixtures.app(uid = 10009, label = "Chat", packageName = "com.example.chat")),
            conns = listOf(Fixtures.conn(remoteAddr = "2001:db8::5")),
            networks = listOf(Fixtures.network()),
        )
        val d = diff(SnapshotJson.decode(SnapshotJson.encode(old)), SnapshotJson.decode(SnapshotJson.encode(new)))
        assertEquals(1, d.listenersMoved.size)
        assertEquals(listOf("com.example.chat"), d.appsAppeared.map { it.packageName })
        assertEquals(listOf("2001:db8::5"), d.hostsFirstContact)
        assertEquals(3, d.changeCount)
    }
}
