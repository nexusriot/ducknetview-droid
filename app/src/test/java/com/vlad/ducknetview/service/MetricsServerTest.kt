package com.vlad.ducknetview.service

import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Throughput
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Real sockets throughout: the whole point of this class is what happens on the
 * wire, and a mocked stream would prove nothing about it.
 */
class MetricsServerTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private var snapshot = NetSnapshot(deviceName = "Test Device", total = Throughput(rxBps = 42))
    private var provider: () -> NetSnapshot = { snapshot }

    private val server = MetricsServer { provider() }
    private val strays = ArrayList<ServerSocket>()

    @After
    fun tearDown() {
        server.stop()
        strays.forEach { runCatching { it.close() } }
    }

    private fun startOnEphemeralPort(): Int {
        assertTrue(server.start(0))
        return server.boundPort!!
    }

    @Test
    fun metricsRespondsWith200AndThePrometheusContentType() {
        val port = startOnEphemeralPort()
        val conn = open(port, "/metrics")
        assertEquals(200, conn.responseCode)
        assertEquals(MetricsServer.METRICS_TYPE, conn.getHeaderField("Content-Type"))
        conn.disconnect()
    }

    @Test
    fun metricsBodyIsTheRenderedExposition() {
        val port = startOnEphemeralPort()
        val body = get(port, "/metrics")
        assertTrue(body.contains("# HELP ducknetview_up"))
        assertTrue(body.contains("# TYPE ducknetview_up gauge"))
        assertTrue(body.contains("\nducknetview_up 1\n"))
        assertTrue(body.contains("""device="Test Device""""))
    }

    @Test
    fun theBodyReflectsTheLatestSnapshot() {
        val port = startOnEphemeralPort()
        assertTrue(get(port, "/metrics").contains("ducknetview_engine_mode 0"))
        snapshot = snapshot.copy(engine = EngineMode.VPN)
        assertTrue(get(port, "/metrics").contains("ducknetview_engine_mode 1"))
    }

    @Test
    fun contentLengthMatchesTheBody() {
        val port = startOnEphemeralPort()
        val conn = open(port, "/metrics")
        val body = conn.inputStream.readBytes()
        assertEquals(body.size, conn.getHeaderFieldInt("Content-Length", -1))
        conn.disconnect()
    }

    @Test
    fun aQueryStringIsIgnored() {
        val port = startOnEphemeralPort()
        val conn = open(port, "/metrics?collect=all")
        assertEquals(200, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun rootServesAPageLinkingToMetrics() {
        val port = startOnEphemeralPort()
        val conn = open(port, "/")
        assertEquals(200, conn.responseCode)
        assertTrue(conn.getHeaderField("Content-Type").startsWith("text/html"))
        val body = conn.inputStream.readBytes().decodeToString()
        assertTrue(body.contains("""href="/metrics""""))
        conn.disconnect()
    }

    @Test
    fun anUnknownPathIs404() {
        val port = startOnEphemeralPort()
        val conn = open(port, "/admin")
        assertEquals(404, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun aPostIs405WithAnAllowHeader() {
        val port = startOnEphemeralPort()
        val reply = raw(port, "POST /metrics HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello")
        assertTrue(reply, reply.startsWith("HTTP/1.1 405"))
        assertTrue(reply, reply.contains("Allow: GET"))
    }

    @Test
    fun aDeleteIsAlso405() {
        val port = startOnEphemeralPort()
        val conn = open(port, "/metrics")
        conn.requestMethod = "DELETE"
        assertEquals(405, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun aMalformedRequestLineDoesNotKillTheAcceptLoop() {
        val port = startOnEphemeralPort()
        val reply = raw(port, "not an http request at all\r\n\r\n")
        assertTrue(reply.startsWith("HTTP/1.1 400"))
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun binaryGarbageDoesNotKillTheAcceptLoop() {
        val port = startOnEphemeralPort()
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write(byteArrayOf(0, 1, 2, 3, 7, 27, 127))
            s.getOutputStream().flush()
        }
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun anOversizedRequestLineDoesNotKillTheAcceptLoop() {
        val port = startOnEphemeralPort()
        val huge = "GET /" + "a".repeat(20_000) + " HTTP/1.1\r\n\r\n"
        runCatching { raw(port, huge) }
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun tooManyHeadersDoNotKillTheAcceptLoop() {
        val port = startOnEphemeralPort()
        val headers = buildString {
            append("GET /metrics HTTP/1.1\r\n")
            repeat(2_000) { append("X-Pad-$it: ").append("v".repeat(80)).append("\r\n") }
            append("\r\n")
        }
        runCatching { raw(port, headers) }
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun aClientThatDisconnectsImmediatelyDoesNotKillTheAcceptLoop() {
        val port = startOnEphemeralPort()
        repeat(3) { Socket("127.0.0.1", port).close() }
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun aClientThatVanishesMidRequestDoesNotKillTheAcceptLoop() {
        val port = startOnEphemeralPort()
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET /metr".toByteArray())
            s.getOutputStream().flush()
        }
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun aProviderThatThrowsStillProducesAResponse() {
        provider = { throw IllegalStateException("engine is gone") }
        val port = startOnEphemeralPort()
        val conn = open(port, "/metrics")
        assertEquals(200, conn.responseCode)
        assertTrue(conn.inputStream.readBytes().decodeToString().contains("ducknetview_up 0"))
        conn.disconnect()
    }

    @Test
    fun manySequentialScrapesAllSucceed() {
        val port = startOnEphemeralPort()
        repeat(10) { assertTrue(get(port, "/metrics").contains("ducknetview_up 1")) }
    }

    @Test
    fun explicitHeadersAndAKeepAliveRequestAreServed() {
        val port = startOnEphemeralPort()
        val reply = raw(
            port,
            "GET /metrics HTTP/1.1\r\nHost: phone.local\r\nUser-Agent: Prometheus/2.0\r\n" +
                "Accept: */*\r\nConnection: keep-alive\r\n\r\n",
        )
        assertTrue(reply.startsWith("HTTP/1.1 200 OK"))
        assertTrue(reply.contains("ducknetview_up 1"))
    }

    @Test
    fun aLoneLfRequestLineIsAccepted() {
        val port = startOnEphemeralPort()
        val reply = raw(port, "GET /metrics HTTP/1.0\n\n")
        assertTrue(reply.startsWith("HTTP/1.1 200 OK"))
    }

    @Test
    fun runningTracksStartAndStop() {
        assertFalse(server.running.value)
        startOnEphemeralPort()
        assertTrue(server.running.value)
        server.stop()
        assertFalse(server.running.value)
        assertNull(server.boundPort)
    }

    @Test
    fun boundPortReportsTheEphemeralPortActuallyTaken() {
        val port = startOnEphemeralPort()
        assertTrue(port > 0)
        assertEquals(port, server.boundPort)
    }

    @Test
    fun startingOnATakenPortFailsWithAReadableError() {
        val taken = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0), 1)
        }
        strays += taken
        assertFalse(server.start(taken.localPort))
        assertFalse(server.running.value)
        assertNull(server.boundPort)
        val error = server.lastError.value
        assertNotNull(error)
        assertTrue(error!!, error.contains("already in use"))
        assertTrue(error, error.contains(taken.localPort.toString()))
    }

    @Test
    fun anOutOfRangePortFailsWithAReadableError() {
        assertFalse(server.start(70_000))
        assertTrue(server.lastError.value!!.contains("out of range"))
        assertFalse(server.running.value)
    }

    @Test
    fun aSuccessfulStartClearsAPreviousError() {
        assertFalse(server.start(70_000))
        assertNotNull(server.lastError.value)
        startOnEphemeralPort()
        assertNull(server.lastError.value)
    }

    /**
     * Only a successful start used to clear the message, so a rejected port
     * left its complaint on screen after the user had both corrected the port
     * and switched the endpoint off: the field read 9187, the switch was off,
     * and the line underneath still said "port 99999 is out of range".
     */
    @Test
    fun turningTheEndpointOffClearsAPreviousError() {
        assertFalse(server.start(70_000))
        assertNotNull(server.lastError.value)
        server.stop()
        assertNull("the failure outlived the endpoint it was about", server.lastError.value)
        assertFalse(server.running.value)
    }

    @Test
    fun stopReleasesThePortSoTheSamePortCanBeReused() {
        val port = startOnEphemeralPort()
        server.stop()
        assertTrue(server.start(port))
        assertEquals(port, server.boundPort)
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun aSecondServerCanTakeThePortOnceTheFirstStops() {
        val port = startOnEphemeralPort()
        val other = MetricsServer { snapshot }
        try {
            assertFalse(other.start(port))
            server.stop()
            assertTrue(other.start(port))
            assertEquals(200, open(port, "/metrics").responseCode)
        } finally {
            other.stop()
        }
    }

    @Test
    fun stopIsIdempotent() {
        startOnEphemeralPort()
        server.stop()
        server.stop()
        assertFalse(server.running.value)
    }

    @Test
    fun stopBeforeAnyStartIsHarmless() {
        server.stop()
        assertFalse(server.running.value)
        assertNull(server.lastError.value)
    }

    @Test
    fun startingTwiceOnTheSamePortIsANoOp() {
        val port = startOnEphemeralPort()
        assertTrue(server.start(port))
        assertEquals(port, server.boundPort)
        assertEquals(200, open(port, "/metrics").responseCode)
    }

    @Test
    fun rapidTogglingLeavesNoSocketBehind() {
        val port = startOnEphemeralPort()
        repeat(15) {
            server.stop()
            assertTrue(server.start(port))
        }
        assertTrue(server.running.value)
        assertEquals(200, open(port, "/metrics").responseCode)
        server.stop()
        // The port must be free for anyone else the moment stop() returns.
        ServerSocket().use { probe ->
            probe.reuseAddress = true
            probe.bind(InetSocketAddress(port), 1)
        }
    }

    @Test
    fun theServerIsReachableOnANonLoopbackBindAddress() {
        val port = startOnEphemeralPort()
        // A wildcard bind is what makes LAN scraping work; 0.0.0.0 as a client
        // target resolves to this host and only succeeds against a wildcard.
        Socket().use { s ->
            s.connect(InetSocketAddress("0.0.0.0", port), 2_000)
            s.getOutputStream().write("GET /metrics HTTP/1.0\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val reply = BufferedReader(InputStreamReader(s.getInputStream())).readText()
            assertTrue(reply.startsWith("HTTP/1.1 200 OK"))
        }
    }

    @Test
    fun lanUrlPrefersANonLoopbackIpv4() {
        assertEquals(
            "http://192.168.1.23:9187/metrics",
            MetricsServer.lanUrl(listOf("127.0.0.1", "fe80::1", "192.168.1.23"), 9187),
        )
    }

    @Test
    fun lanUrlStripsThePrefixLength() {
        assertEquals(
            "http://10.0.0.5:9187/metrics",
            MetricsServer.lanUrl(listOf("10.0.0.5/24"), 9187),
        )
    }

    @Test
    fun lanUrlBracketsIpv6WhenThereIsNoIpv4() {
        assertEquals(
            "http://[2001:db8::1]:9187/metrics",
            MetricsServer.lanUrl(listOf("fe80::abcd/64", "2001:db8::1/64"), 9187),
        )
    }

    @Test
    fun lanUrlHasNothingToOfferForLoopbackOnly() {
        assertNull(MetricsServer.lanUrl(listOf("127.0.0.1/8", "::1/128"), 9187))
        assertNull(MetricsServer.lanUrl(emptyList(), 9187))
        assertNull(MetricsServer.lanUrl(listOf("", "   "), 9187))
    }

    private fun open(port: Int, path: String): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = false
        }

    private fun get(port: Int, path: String): String {
        val conn = open(port, path)
        try {
            assertEquals(200, conn.responseCode)
            return conn.inputStream.readBytes().decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    private fun raw(port: Int, request: String): String =
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            s.soTimeout = 5_000
            s.getOutputStream().write(request.toByteArray())
            s.getOutputStream().flush()
            BufferedReader(InputStreamReader(s.getInputStream())).readText()
        }
}
