package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.CellularState
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.RouteRow
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.SecurityCounts
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.model.WifiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotJsonTest {

    private fun full(): NetSnapshot = NetSnapshot(
        atMillis = 1_700_000_000_000L,
        engine = EngineMode.VPN,
        paused = true,
        intervalSeconds = 5,
        deviceName = "Pixel \"test\" device",
        uptimeMillis = 86_400_000L,
        networks = listOf(
            Fixtures.network().copy(
                mtu = 1500,
                routes = listOf(RouteRow("0.0.0.0/0", "192.168.1.1", "wlan0", true)),
                rxBytes = 123456789L,
                txBytes = 987654321L,
                rxHistory = listOf(1.5f, 2.25f, 0f),
                wifi = WifiState("my-ssid", "aa:bb:cc:dd:ee:ff", -55, 866, 780, 650, 5180, "Wi-Fi 6"),
            ),
            Fixtures.network(id = "cell", ifaceName = "rmnet0", transport = Transport.CELLULAR).copy(
                cellular = CellularState("LTE", "Operator", 3),
                gateway = null,
                privateDns = "dns.example",
            ),
        ),
        selectedNetworkId = "net-1",
        conns = listOf(
            Fixtures.conn(key = "a", rxBytes = 5_000_000_000L, rttMillis = 42, resolvedHost = "dns.google"),
            Fixtures.conn(key = "b", proto = Proto.UDP, state = ConnState.ACTIVE, remoteAddr = "2001:db8::1"),
        ),
        closedConns = listOf(Fixtures.closed(finalRx = 10, finalTx = 20)),
        apps = listOf(Fixtures.app(uid = 10001), Fixtures.app(uid = 1000, isSystem = true)),
        services = listOf(Fixtures.service(port = 22), Fixtures.service(proto = Proto.UDP, port = 5353)),
        serviceScanAt = 99L,
        serviceScanRunning = true,
        total = Throughput(11, 22),
        totalPeakRx = 33,
        totalPeakTx = 44,
        sessionRx = 55,
        sessionTx = 66,
        rxHistory = listOf(0f, 1.125f, 1024f),
        txHistory = listOf(3f),
        churnHistory = listOf(1f, 0f),
        newConnCount = 2,
        closedConnCount = 1,
        topApps = listOf(Talker("10001", "Browser", 1, 2)),
        topHosts = listOf(Talker("8.8.8.8", "dns.google", 3, 4)),
        latency = listOf(LatencySample("8.8.8.8", "dns", 21, true, listOf(20f, 22f), "note")),
        externalIp = "203.0.113.7",
        externalIpAt = 1234L,
        security = SecurityCounts(1, 2, 3, 4),
        frozen = true,
        frozenLabel = "snapshot.json",
        error = null,
    )

    @Test
    fun fullSnapshotRoundTrips() {
        val original = full()
        val decoded = SnapshotJson.decode(SnapshotJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun emptySnapshotRoundTrips() {
        val original = NetSnapshot()
        assertEquals(original, SnapshotJson.decode(SnapshotJson.encode(original)))
    }

    @Test
    fun encodingIsStableAcrossCalls() {
        val s = full()
        assertEquals(SnapshotJson.encode(s), SnapshotJson.encode(s))
    }

    @Test
    fun stringsWithQuotesAndControlCharactersSurvive() {
        val s = NetSnapshot(deviceName = "a\"b\\c\nd\tef", error = "unicode: ünïcødé ✓")
        val decoded = SnapshotJson.decode(SnapshotJson.encode(s))
        assertEquals(s.deviceName, decoded.deviceName)
        assertEquals(s.error, decoded.error)
    }

    @Test
    fun nullsStayNull() {
        val s = NetSnapshot(externalIp = null, error = null, selectedNetworkId = null)
        val json = SnapshotJson.encode(s)
        assertTrue(json.contains("\"externalIp\":null"))
        val decoded = SnapshotJson.decode(json)
        assertNull(decoded.externalIp)
        assertNull(decoded.error)
        assertNull(decoded.selectedNetworkId)
    }

    @Test
    fun largeCountersKeepFullPrecision() {
        val s = NetSnapshot(sessionRx = 9_007_199_254_740_993L, atMillis = Long.MAX_VALUE)
        val decoded = SnapshotJson.decode(SnapshotJson.encode(s))
        assertEquals(9_007_199_254_740_993L, decoded.sessionRx)
        assertEquals(Long.MAX_VALUE, decoded.atMillis)
    }

    @Test
    fun sparklineFloatsRoundTrip() {
        val s = NetSnapshot(rxHistory = listOf(0f, 0.5f, 1e6f, -3.25f))
        assertEquals(listOf(0f, 0.5f, 1e6f, -3.25f), SnapshotJson.decode(SnapshotJson.encode(s)).rxHistory)
    }

    @Test
    fun enumsRoundTripAndUnknownNamesFallBack() {
        val s = NetSnapshot(
            engine = EngineMode.VPN,
            conns = listOf(Fixtures.conn(proto = Proto.UDP, state = ConnState.SYN_SENT)),
            services = listOf(Fixtures.service(bindAddr = "127.0.0.1")),
        )
        val decoded = SnapshotJson.decode(SnapshotJson.encode(s))
        assertEquals(EngineMode.VPN, decoded.engine)
        assertEquals(Proto.UDP, decoded.conns[0].proto)
        assertEquals(ConnState.SYN_SENT, decoded.conns[0].state)
        assertEquals(Exposure.LOCAL, decoded.services[0].exposure)

        val patched = SnapshotJson.encode(s).replace("\"UDP\"", "\"SCTP\"")
        assertEquals(Proto.OTHER, SnapshotJson.decode(patched).conns[0].proto)
    }

    @Test
    fun missingFieldsFallBackToModelDefaults() {
        val decoded = SnapshotJson.decode("""{"atMillis":7}""")
        assertEquals(7L, decoded.atMillis)
        assertEquals(2, decoded.intervalSeconds)
        assertEquals(EngineMode.API, decoded.engine)
        assertTrue(decoded.conns.isEmpty())
        assertEquals(SecurityCounts(), decoded.security)
    }

    @Test
    fun missingRttDefaultsToUnmeasured() {
        val decoded = SnapshotJson.decode("""{"conns":[{"key":"x"}]}""")
        assertEquals(-1, decoded.conns[0].rttMillis)
        assertEquals(Scope.PUBLIC, decoded.conns[0].scope)
    }

    @Test
    fun whitespaceAndEscapesAreParsed() {
        val decoded = SnapshotJson.decode(
            """
            {
              "deviceName" : "aAb\/c" ,
              "intervalSeconds" : 3
            }
            """.trimIndent(),
        )
        assertEquals("aAb/c", decoded.deviceName)
        assertEquals(3, decoded.intervalSeconds)
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        val decoded = SnapshotJson.decode("{\"deviceName\":\"a\\u0041\\u00e9\\tb\"}")
        assertEquals("aAé\tb", decoded.deviceName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedJsonIsRejected() {
        SnapshotJson.decode("""{"atMillis":}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun trailingContentIsRejected() {
        SnapshotJson.decode("""{} junk""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTopLevelArrayIsRejected() {
        SnapshotJson.decode("[]")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unterminatedStringIsRejected() {
        SnapshotJson.decode("""{"deviceName":"oops}""")
    }
}
