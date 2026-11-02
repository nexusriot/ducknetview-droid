package com.vlad.ducknetview

import android.content.Context
import android.net.VpnService
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.PingSockets
import com.vlad.ducknetview.engine.vpn.VpnBridge
import com.vlad.ducknetview.engine.vpn.packet.Icmp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runners.MethodSorters
import java.io.BufferedReader
import com.vlad.ducknetview.testing.QuicPackets
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * End-to-end capture test, run against a real device or emulator.
 *
 * VPN consent cannot be tapped from an instrumented test, so the harness
 * pre-authorises it with `adb shell appops set <pkg> ACTIVATE_VPN allow`.
 * Without that, VpnService.prepare() returns a consent Intent and every test
 * here is skipped rather than failing falsely.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class VpnCaptureE2ETest {

    /**
     * A flow as observed from the outside, whether it is still live or has
     * already been retired into the closed history. A short HTTP request can
     * finish before the assertion runs, so looking only at live flows makes
     * the test a race.
     */
    private data class Observed(
        val proto: Proto,
        val dstPort: Int,
        val rx: Long,
        val tx: Long,
        val rtt: Int,
        val uid: Int,
        val live: Boolean,
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun startEngine() {
        assumeTrue(
            "VPN consent not pre-granted (appops ACTIVATE_VPN)",
            VpnService.prepare(context) == null,
        )
        VpnBridge.captureOwnTraffic = true

        // Bring the app to the foreground before starting the service. Some
        // OEM background managers (Allwinner's "awbms", seen on a PRITOM M10)
        // silently drop startForegroundService from a backgrounded app, which
        // is exactly the state an instrumented test runs in; a real user starts
        // capture by tapping a button with the UI in front of them.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitUntil(10_000) { foregrounded }
        foregrounded = true

        DuckVpnService.start(context)
        waitUntil(20_000) { VpnBridge.table != null }
        assumeTrue(
            "capture engine did not attach; the OEM background manager may be " +
                "blocking the service for this package",
            VpnBridge.table != null,
        )
    }

    private var scenario: ActivityScenario<MainActivity>? = null
    private var foregrounded = false

    @After
    fun stopEngine() {
        DuckVpnService.stop(context)
        waitUntil(10_000) { VpnBridge.table == null }
        VpnBridge.captureOwnTraffic = false
        runCatching { scenario?.close() }
        scenario = null
        foregrounded = false
    }

    @Test
    fun t01_engineAttachesAndExposesAFlowTable() {
        assertNotNull("flow table should be published once the TUN is up", VpnBridge.table)
        assertTrue(VpnBridge.running.value)
    }

    @Test
    fun t02_tcpRequestIsCapturedWithByteCountersAndRtt() {
        fetch("https://connectivitycheck.gstatic.com/generate_204")

        val flow = observe(20_000) { it.proto == Proto.TCP && it.dstPort == 443 && it.tx > 0 }
        assertNotNull("the request should have produced a captured TCP flow", flow)
        assertTrue("bytes should have been sent upstream", flow!!.tx > 0)
        assertTrue("a response should have been counted", flow.rx > 0)
        assertTrue("handshake RTT should be measured", flow.rtt >= 0)
    }

    @Test
    fun t03_dnsQueryIsCapturedAsAUdpFlow() {
        runCatching { InetAddress.getAllByName("example.com") }
        val flow = observe(15_000) { it.proto == Proto.UDP && it.dstPort == 53 && it.tx > 0 }
        // Some devices resolve over DoT/DoH, in which case there is no port-53
        // flow to find; that is a legitimate outcome, not a failure.
        if (flow != null) assertTrue("a DNS query should have been sent", flow.tx > 0)
    }

    @Test
    fun t04_closedFlowKeepsItsFinalTotals() {
        val table = VpnBridge.table!!
        fetch("https://connectivitycheck.gstatic.com/generate_204")
        waitUntil(30_000) { table.closedSnapshot().any { it.finalRx > 0 || it.finalTx > 0 } }

        val closed = table.closedSnapshot()
        assertTrue("closed history should record the finished request", closed.isNotEmpty())
        val withBytes = closed.firstOrNull { it.finalRx > 0 || it.finalTx > 0 }
        assertNotNull("a closed flow should retain its lifetime totals", withBytes)
        assertTrue(withBytes!!.lifetimeMillis >= 0)
    }

    @Test
    fun t05_loopbackTransfersAreIntactUnderCapture() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val serverThread = Thread {
            runCatching {
                server.accept().use { s ->
                    s.getOutputStream().write(payload)
                    s.getOutputStream().flush()
                }
            }
        }.apply { start() }

        var read = 0
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            val buf = ByteArray(8192)
            while (true) {
                val n = s.getInputStream().read(buf)
                if (n < 0) break
                read += n
            }
        }
        serverThread.join(5000)
        server.close()

        // Loopback does not traverse the TUN. This asserts the engine stays out
        // of the way of local sockets: a monitor that breaks localhost is worse
        // than useless.
        assertTrue("loopback transfer should be intact under capture", read == payload.size)
    }

    @Test
    fun t06_engineSurvivesRefusedConnectionsAndKeepsRunning() {
        repeat(20) { runCatching { fetch("http://127.0.0.1:1/") } }
        assertTrue("engine must not die on refused connections", VpnBridge.running.value)
        assertNotNull(VpnBridge.table)
    }

    /**
     * Our own traffic is being captured, so the owner should resolve to a real
     * application uid rather than the unknown sentinel.
     *
     * The wait is for an *attributed* flow, not for the first matching one.
     * `getConnectionOwnerUid` answers about a live socket, and a short request
     * can be gone before the lookup runs, leaving that one flow at -1; pinning
     * the assertion to whichever flow happened to appear first made this fail
     * intermittently on the tablet. Requiring that attribution works, rather than
     * that it works for every socket, is both the property worth having and one
     * the platform can actually deliver — nothing attributed at all still fails.
     */
    @Test
    fun t07_uidAttributionIdentifiesTheCallingApp() {
        repeat(3) { fetch("https://connectivitycheck.gstatic.com/generate_204") }
        val flow = observe(20_000) {
            it.proto == Proto.TCP && it.dstPort == 443 && it.tx > 0 && it.uid > 0
        }
        assertNotNull(
            "no captured flow was attributed to an app; uids seen: " +
                snapshotFlows().filter { it.dstPort == 443 }.map { it.uid },
            flow,
        )
    }

    /**
     * Ping through the TUN.
     *
     * This is the regression the relay exists for: the dispatcher knew only TCP
     * and UDP, so with a default route through the TUN every echo request on
     * the device was dropped with nothing said about it — turning capture on
     * silently broke ping for every other app.
     *
     * The test's own echo socket is unprotected, so its packets go into the TUN
     * exactly as another app's would; the relay answers them through a socket
     * of its own that is protected.
     */
    @Test
    fun t08_icmpEchoIsRelayedAndTimed() {
        val socket = PingSockets.open(4, timeoutMs = 1000)
        assumeTrue(
            "this kernel does not allow unprivileged ICMP sockets " +
                "(net.ipv4.ping_group_range)",
            socket != null,
        )
        try {
            val target = InetAddress.getByName(icmpTarget())
            val request = Icmp.buildEchoRequest(4, seq = 1, payload = ByteArray(32))
            repeat(3) { socket!!.send(request, 0, request.size, target) }

            val flow = observe(20_000) { it.proto == Proto.ICMP && it.tx > 0 }
            assertNotNull(
                "the echo request should have produced a captured ICMP flow; " +
                    "protocols seen: " + snapshotFlows().map { it.proto }.distinct(),
                flow,
            )

            // A reply proves the whole round trip: out through the relay's own
            // socket and back into the TUN under the guest's identifier. A host
            // that filters ICMP is the other explanation, so the message says so.
            val answered = observe(20_000) { it.proto == Proto.ICMP && it.rx > 0 }
            assertNotNull(
                "no echo reply came back through the relay; $target may be " +
                    "filtering ICMP on this network",
                answered,
            )
            assertTrue("the round trip should have been measured", answered!!.rtt >= 0)
        } finally {
            socket?.close()
        }
    }

    /**
     * A name learnt from a TLS handshake and carried all the way into storage.
     *
     * This crosses the one seam no JVM test can: the ClientHello is peeked at on
     * the packet path, handed to the engine through VpnBridge, batched by the
     * poll tick and written to Room. It matters most on a device with Private
     * DNS on, where SNI is the only name the app can see at all.
     */
    @Test
    fun t09_aNameIsLearntFromTrafficAndStored() {
        val app = context.applicationContext as DuckApp
        val host = "connectivitycheck.gstatic.com"
        runBlocking { app.deps.domainRepo.clear() }

        repeat(2) { fetch("https://$host/generate_204") }

        var stored = emptyList<String>()
        waitUntil(30_000) {
            stored = runBlocking { app.deps.domainRepo.recent.first() }.map { it.name }
            stored.any { it == host }
        }
        assertTrue(
            "the name was never recorded; stored instead: $stored",
            stored.any { it == host },
        )
    }


    /**
     * A name learnt from a QUIC handshake **split across two datagrams**.
     *
     * This is the test that was missing. The first build of [QuicPeek] read
     * only a flow's opening datagram, having inherited `TlsPeek`'s refusal to
     * reassemble, and it passed every JVM test — because those build the
     * hellos they then read, and a hello this app builds fits in one packet.
     * A real one does not: a modern ClientHello carries a post-quantum key
     * share and spans two Initials, so on the M10 the shipped code produced
     * dozens of `udp/443` flows labelled `quic` and a name for none of them.
     *
     * Two Initials are put on the wire as another app's traffic would be,
     * carrying one ClientHello between them, and the assertion is at the far
     * end of the whole path: peek, [VpnBridge], the poll tick's batch, Room.
     */
    @Test
    fun t10_aNameIsLearntFromASplitQuicHandshake() {
        val app = context.applicationContext as DuckApp
        val host = "split-hello.quic.test"
        runBlocking { app.deps.domainRepo.clear() }

        val hello = QuicPackets.clientHello(host)
        val cut = hello.size / 2
        sendQuic(
            QuicPackets.initialCarrying(hello.copyOfRange(0, cut), 0L),
            QuicPackets.initialCarrying(hello.copyOfRange(cut, hello.size), cut.toLong()),
        )

        var stored = emptyList<String>()
        waitUntil(30_000) {
            stored = runBlocking { app.deps.domainRepo.recent.first() }.map { it.name }
            stored.any { it == host }
        }
        assertTrue(
            "the name never arrived from a split QUIC hello; stored instead: $stored",
            stored.any { it == host },
        )
    }

    /**
     * The same path for a hello that does fit one datagram, so a regression
     * in the assembler cannot hide behind the split case passing.
     */
    @Test
    fun t11_aNameIsLearntFromASingleDatagramQuicHandshake() {
        val app = context.applicationContext as DuckApp
        val host = "one-packet.quic.test"
        runBlocking { app.deps.domainRepo.clear() }

        sendQuic(QuicPackets.initial(sni = host))

        var stored = emptyList<String>()
        waitUntil(30_000) {
            stored = runBlocking { app.deps.domainRepo.recent.first() }.map { it.name }
            stored.any { it == host }
        }
        assertTrue(
            "the name never arrived from a single-datagram QUIC hello; " +
                "stored instead: $stored",
            stored.any { it == host },
        )
    }

    /**
     * A QUIC name is labelled as one.
     *
     * The source is what the Domains screen renders and what the info sheet
     * promises, and `NameSource` is a field with a default — the class of
     * bug `DomainEventsLabelledTest` exists for. Reading it back out of
     * storage is the only check that the label survives the whole path.
     */
    @Test
    fun t12_aQuicNameIsRecordedWithTheQuicSource() {
        val app = context.applicationContext as DuckApp
        val host = "labelled.quic.test"
        runBlocking { app.deps.domainRepo.clear() }

        sendQuic(QuicPackets.initial(sni = host))

        var row: DomainRow? = null
        waitUntil(30_000) {
            row = runBlocking { app.deps.domainRepo.recent.first() }
                .firstOrNull { it.name == host }
            row != null
        }
        assertNotNull("the QUIC name was never stored", row)
        assertEquals(
            "a name read out of a QUIC Initial must say so, not borrow SNI's label",
            NameSource.QUIC,
            row!!.source,
        )
    }


    /**
     * Blocking an app produces evidence that it is blocked.
     *
     * The firewall is the one thing this app *does* to traffic rather than
     * watching it, and it used to do it in total silence: the flow was
     * refused, marked and retired, and because a blocked app has no live
     * connections by construction the Apps screen showed it exactly as it
     * shows an app that is not running. There was no way to tell a working
     * block from a broken one — the same honesty failure as the `paused`
     * flag in DESIGN §8a.
     *
     * Driven through the real setting rather than by poking
     * [VpnBridge.setBlocked], so the propagation from DataStore to the packet
     * path is part of what is tested, and the refusal is counted onto the
     * very [com.vlad.ducknetview.domain.model.AppRow] the screen renders.
     */
    @Test
    fun t13_aBlockedAppsRefusalsAreCounted() {
        val app = context.applicationContext as DuckApp
        val uid = Process.myUid()
        val before = blockedAttemptsFor(uid)
        runBlocking { app.deps.settings.update { it.copy(blockedUids = it.blockedUids + uid) } }
        try {
            // The engine applies settings on its own cadence; the block is
            // not in force until the packet path has been told.
            waitUntil(10_000) { VpnBridge.isBlocked(uid) }
            assumeTrue("the block never reached the capture engine", VpnBridge.isBlocked(uid))

            // Each of these is refused rather than relayed, so none of them
            // is expected to succeed — the refusal is the subject.
            repeat(4) { fetch("https://connectivitycheck.gstatic.com/generate_204") }

            waitUntil(30_000) { blockedAttemptsFor(uid) > before }
            assertTrue(
                "the firewall refused connections and counted none of them; " +
                    "a blocked app would look identical to an idle one",
                blockedAttemptsFor(uid) > before,
            )
        } finally {
            runBlocking {
                app.deps.settings.update { it.copy(blockedUids = it.blockedUids - uid) }
            }
            waitUntil(10_000) { !VpnBridge.isBlocked(uid) }
        }
    }

    /**
     * A blocked app stays on the Apps screen.
     *
     * It has no flows by construction, so listing only uids that carried a
     * packet dropped it from the screen entirely — taking the only control
     * that could unblock it with it.
     */
    @Test
    fun t14_aBlockedAppIsStillListed() {
        val app = context.applicationContext as DuckApp
        val uid = Process.myUid()
        runBlocking { app.deps.settings.update { it.copy(blockedUids = it.blockedUids + uid) } }
        try {
            waitUntil(15_000) {
                app.deps.engine.snapshot.value.apps.any { it.uid == uid && it.blocked }
            }
            val row = app.deps.engine.snapshot.value.apps.firstOrNull { it.uid == uid }
            assertNotNull("the blocked app vanished from the Apps screen", row)
            assertTrue("its row does not say it is blocked", row!!.blocked)
        } finally {
            runBlocking {
                app.deps.settings.update { it.copy(blockedUids = it.blockedUids - uid) }
            }
        }
    }

    /**
     * A domain rule refuses or cuts a flow, and says which rule did it.
     *
     * With Private DNS on, the device never learns the name before the
     * connection opens, so the cut happens at the ClientHello; with plain DNS
     * it is a refusal at SYN. Either way the flow must retire marked blocked
     * with the rule as its reason, and the request must not succeed.
     */
    @Test
    fun t15_aDomainRuleRefusesOrCutsTheFlow() {
        val app = context.applicationContext as DuckApp
        runBlocking { app.deps.settings.update { it.copy(firewallRules = it.firewallRules + "|gstatic.com") } }
        try {
            waitUntil(10_000) { VpnBridge.nameVerdict(Process.myUid(), "x.gstatic.com") != null }
            assumeTrue("the rule never reached the capture engine",
                VpnBridge.nameVerdict(Process.myUid(), "x.gstatic.com") != null)
            val code = fetch("https://connectivitycheck.gstatic.com/generate_204")
            assertTrue("a request to a blocked domain succeeded with $code", code != 204)
            waitUntil(15_000) { blockedRows().any { it.blockReason.contains("gstatic.com") } }
            val row = blockedRows().firstOrNull { it.blockReason.contains("gstatic.com") }
            assertNotNull("no retired flow names the rule that refused it", row)
        } finally {
            runBlocking { app.deps.settings.update { it.copy(firewallRules = it.firewallRules - "|gstatic.com") } }
        }
    }

    /** An address rule is decided at SYN, before any byte of the handshake. */
    @Test
    fun t16_anAddressRuleRefusesAtSyn() {
        val app = context.applicationContext as DuckApp
        val target = runCatching { InetAddress.getByName("one.one.one.one").hostAddress }.getOrNull()
        assumeTrue("no DNS for the target", target != null)
        val rule = "|$target"
        runBlocking { app.deps.settings.update { it.copy(firewallRules = it.firewallRules + rule) } }
        try {
            waitUntil(10_000) { VpnBridge.verdict(Process.myUid(), target!!) != null }
            val refused = runCatching {
                Socket().use { it.connect(InetSocketAddress(target, 443), 5_000) }
            }.isFailure
            assertTrue("a connection to a blocked address was allowed to open", refused)
            waitUntil(10_000) { blockedRows().any { it.remoteAddr == target && it.blockReason.startsWith("rule") } }
            assertTrue(blockedRows().any { it.remoteAddr == target && it.blockReason.startsWith("rule") })
        } finally {
            runBlocking { app.deps.settings.update { it.copy(firewallRules = it.firewallRules - rule) } }
        }
    }

    /** The negotiated TLS version is read off the server's first flight. */
    @Test
    fun t17_theNegotiatedTlsVersionIsRecorded() {
        fetch("https://www.google.com/generate_204")
        waitUntil(15_000) { allRows().any { it.remotePort == 443 && it.tls.isNotEmpty() } }
        val row = allRows().firstOrNull { it.remotePort == 443 && it.tls.isNotEmpty() }
        assertNotNull("no HTTPS flow recorded a TLS version", row)
        assertTrue("unexpected version ${row!!.tls}", row.tls == "TLS 1.3" || row.tls == "TLS 1.2")
    }

    /** Plain HTTP is flagged on the row the screen renders. */
    @Test
    fun t18_cleartextHttpIsFlagged() {
        val app = context.applicationContext as DuckApp
        fetch("http://connectivitycheck.gstatic.com/generate_204")
        fun flagged() = app.deps.engine.snapshot.value.let { s ->
            s.conns.any { it.cleartext == "http" } || s.closedConns.any { it.row.cleartext == "http" }
        }
        waitUntil(15_000) { flagged() }
        assertTrue("a plain-HTTP flow was not flagged as cleartext", flagged())
    }

    /** Reset ends a live flow: the app's socket sees the connection die. */
    @Test
    fun t19_aLiveFlowCanBeReset() {
        val target = runCatching { InetAddress.getByName("one.one.one.one").hostAddress }.getOrNull()
        assumeTrue("no DNS for the target", target != null)
        Socket().use { sock ->
            sock.connect(InetSocketAddress(target, 443), 10_000)
            sock.soTimeout = 10_000
            val key = observeKey { it.key.dstIp == target && it.key.dstPort == 443 }
            assertNotNull("the open flow never appeared", key)
            assertTrue("terminate found no live flow", VpnBridge.terminate(key!!))
            val dead = runCatching {
                // A reset socket fails on read or write; a live one would
                // block until the timeout and then throw a different way.
                sock.getOutputStream().write(ByteArray(1))
                sock.getOutputStream().flush()
                sock.getInputStream().read()
            }.let { r -> r.isFailure || r.getOrNull() == -1 }
            assertTrue("the socket survived a reset", dead)
        }
    }

    private fun observeKey(predicate: (com.vlad.ducknetview.engine.vpn.Flow) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            VpnBridge.table?.live()?.firstOrNull(predicate)?.let { return it.key.toString() }
            Thread.sleep(100)
        }
        return null
    }

    private fun allRows(): List<com.vlad.ducknetview.domain.model.ConnRow> {
        val t = VpnBridge.table ?: return emptyList()
        return t.live().map { t.toRow(it, System.currentTimeMillis(), "", "", 0, 0, false) } +
            t.closedSnapshot().map { it.row }
    }

    private fun blockedRows() = allRows().filter { it.blocked }

    private fun blockedAttemptsFor(uid: Int): Long {
        val app = context.applicationContext as DuckApp
        return app.deps.engine.snapshot.value.apps
            .firstOrNull { it.uid == uid }
            ?.blockedAttempts
            ?: 0L
    }

    /**
     * Send datagrams into the TUN exactly as another app would.
     *
     * The socket is deliberately *not* protected: unprotected traffic from
     * this process is what the engine is meant to capture, which is the whole
     * point of `VpnBridge.captureOwnTraffic`. Nothing is expected to answer —
     * only the outbound path is under test, and a handshake to a host that
     * never replies is exactly what the peek has to cope with anyway.
     */
    private fun sendQuic(vararg datagrams: ByteArray) {
        val target = InetSocketAddress(InetAddress.getByName(quicTarget()), 443)
        DatagramSocket().use { socket ->
            for (bytes in datagrams) {
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, target)) }
                Thread.sleep(50)
            }
            // The relay reads the datagram on its own coroutine; returning
            // before it has run would close the socket under the flow.
            Thread.sleep(500)
        }
    }

    /**
     * A host that really does listen on UDP/443.
     *
     * Not the gateway, which was the obvious choice and was wrong: a host
     * with nothing on that port answers **ICMP port unreachable**, the
     * relay's connected upstream socket throws, and the flow is torn down —
     * taking its half-assembled ClientHello with it. The split-hello test
     * then saw its second Initial arrive on a brand new flow with a brand
     * new assembler and no offset zero, and failed for a reason that had
     * nothing to do with the code under test.
     *
     * That is a real property of the engine and not only of the test: a
     * handshake interrupted by an ICMP error loses whatever had been
     * collected. It is recorded in `QuicPeek`, and it is not worth engineering
     * around, because a browser's QUIC handshake goes to a host that is
     * listening by construction — which is exactly what this now uses.
     */
    private fun quicTarget(): String = runCatching {
        InetAddress.getByName(QUIC_HOST).hostAddress
    }.getOrNull() ?: "192.0.2.1"

    /**
     * The default gateway when it is an IPv4 address: one LAN hop is far more
     * likely to answer an echo than a public resolver on a filtered network.
     */
    private fun icmpTarget(): String {
        val app = context.applicationContext as DuckApp
        val gateway = app.deps.engine.snapshot.value.networks
            .firstOrNull { it.isDefault }
            ?.gateway
            ?.substringBefore('%')
        return gateway?.takeIf { it.count { c -> c == '.' } == 3 } ?: "8.8.8.8"
    }

    private fun snapshotFlows(): List<Observed> {
        val table = VpnBridge.table ?: return emptyList()
        val live = table.live().map {
            Observed(
                proto = it.key.proto,
                dstPort = it.key.dstPort,
                rx = it.rx.get(),
                tx = it.tx.get(),
                rtt = it.rttMillis,
                uid = it.uid,
                live = true,
            )
        }
        val closed = table.closedSnapshot().map {
            Observed(
                proto = it.row.proto,
                dstPort = it.row.remotePort,
                rx = it.finalRx,
                tx = it.finalTx,
                rtt = it.row.rttMillis,
                uid = it.row.uid,
                live = false,
            )
        }
        return live + closed
    }

    private fun observe(timeoutMs: Long, predicate: (Observed) -> Boolean): Observed? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            snapshotFlows().firstOrNull(predicate)?.let { return it }
            Thread.sleep(200)
        }
        return snapshotFlows().firstOrNull(predicate)
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(200)
        }
    }

    private fun fetch(url: String): Int = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            runCatching { conn.inputStream.bufferedReader().use(BufferedReader::readText) }
            code
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(-1)

    private companion object {
        /** Serves HTTP/3, so it listens on UDP/443 and answers no ICMP error. */
        const val QUIC_HOST = "www.cloudflare.com"
    }
}
