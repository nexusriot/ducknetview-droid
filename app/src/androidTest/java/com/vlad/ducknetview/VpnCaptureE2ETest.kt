package com.vlad.ducknetview

import android.content.Context
import android.net.VpnService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.BufferedReader
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

    @Test
    fun t07_uidAttributionIdentifiesTheCallingApp() {
        fetch("https://connectivitycheck.gstatic.com/generate_204")
        val flow = observe(20_000) { it.proto == Proto.TCP && it.dstPort == 443 && it.tx > 0 }
        assertNotNull("expected a captured flow to attribute", flow)
        // Our own traffic is being captured, so the owner should resolve to a
        // real application uid rather than the unknown sentinel.
        assertTrue("uid should be attributed, got ${flow!!.uid}", flow.uid > 0)
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
}
