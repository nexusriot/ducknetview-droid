package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.SecurityCounts
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.model.Transport
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrometheusTest {

    private data class Sample(val name: String, val labels: Map<String, String>, val value: Double)

    private val nameRe = Regex("^[a-zA-Z_:][a-zA-Z0-9_:]*$")

    @Test
    fun emptySnapshotRendersParseableOutput() {
        val out = Prometheus.render(NetSnapshot())
        assertTrue(out.isNotEmpty())
        val samples = samples(out)
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            assertTrue("bad metric name: ${s.name}", nameRe.matches(s.name))
            assertFalse("NaN in ${s.name}", s.value.isNaN())
            assertFalse("infinite in ${s.name}", s.value.isInfinite())
        }
    }

    @Test
    fun emptySnapshotHasNoEmptyLabelValues() {
        val out = Prometheus.render(NetSnapshot())
        for (s in samples(out)) {
            for ((k, v) in s.labels) assertTrue("empty label $k on ${s.name}", v.isNotEmpty())
        }
    }

    @Test
    fun everyMetricIsPrefixed() {
        val out = Prometheus.render(fullSnapshot())
        for (s in samples(out)) assertTrue(s.name, s.name.startsWith("ducknetview_"))
        for (name in families(out)) assertTrue(name, name.startsWith("ducknetview_"))
    }

    @Test
    fun helpAndTypeAreEmittedExactlyOncePerFamily() {
        val out = Prometheus.render(fullSnapshot())
        val help = out.lines().filter { it.startsWith("# HELP ") }.map { it.split(' ')[2] }
        val type = out.lines().filter { it.startsWith("# TYPE ") }.map { it.split(' ')[2] }
        assertEquals(help.size, help.distinct().size)
        assertEquals(type.size, type.distinct().size)
        assertEquals(help.toSet(), type.toSet())
    }

    /**
     * The family count is quoted in README.md and docs/DESIGN.md, and drifted
     * from the code once already: `series_truncated` was added, described in
     * the prose, and never added to the total. Pin it here so adding a family
     * fails until the number that documents it is updated too.
     */
    @Test
    fun theFamilyCountIsWhatTheDocsClaim() {
        assertEquals(
            "README.md and docs/DESIGN.md say how many families this emits; " +
                "update both when this number changes",
            43,
            families(Prometheus.render(fullSnapshot())).size,
        )
    }

    @Test
    fun everySampleBelongsToADeclaredFamily() {
        val out = Prometheus.render(fullSnapshot())
        val declared = families(out)
        for (s in samples(out)) assertTrue("undeclared ${s.name}", s.name in declared)
    }

    @Test
    fun typeLinePrecedesItsSamples() {
        val out = Prometheus.render(fullSnapshot())
        val lines = out.lines()
        val typeAt = HashMap<String, Int>()
        lines.forEachIndexed { i, l -> if (l.startsWith("# TYPE ")) typeAt[l.split(' ')[2]] = i }
        lines.forEachIndexed { i, l ->
            if (l.isBlank() || l.startsWith("#")) return@forEachIndexed
            val name = parse(l).name
            assertTrue("$name sample before its TYPE", typeAt.getValue(name) < i)
        }
    }

    @Test
    fun everyFamilyIsDeclaredAsAGauge() {
        val out = Prometheus.render(fullSnapshot())
        val types = out.lines().filter { it.startsWith("# TYPE ") }.map { it.split(' ')[3] }
        assertTrue(types.isNotEmpty())
        assertEquals(setOf("gauge"), types.toSet())
    }

    @Test
    fun helpTextIsPresentForEveryFamily() {
        val out = Prometheus.render(fullSnapshot())
        for (l in out.lines().filter { it.startsWith("# HELP ") }) {
            assertTrue("empty help: $l", l.split(' ', limit = 4).getOrElse(3) { "" }.isNotBlank())
        }
    }

    @Test
    fun outputEndsWithANewline() {
        assertTrue(Prometheus.render(NetSnapshot()).endsWith("\n"))
    }

    @Test
    fun upIsAlwaysOne() {
        assertEquals(1.0, one(Prometheus.render(NetSnapshot()), "ducknetview_up").value, 0.0)
    }

    @Test
    fun engineModeIsZeroForApi() {
        val out = Prometheus.render(NetSnapshot(engine = EngineMode.API))
        assertEquals(0.0, one(out, "ducknetview_engine_mode").value, 0.0)
    }

    @Test
    fun engineModeIsOneForVpnCapture() {
        val out = Prometheus.render(NetSnapshot(engine = EngineMode.VPN))
        assertEquals(1.0, one(out, "ducknetview_engine_mode").value, 0.0)
    }

    @Test
    fun infoCarriesDeviceAndEngine() {
        val out = Prometheus.render(NetSnapshot(deviceName = "Pixel 7", engine = EngineMode.VPN))
        val info = one(out, "ducknetview_info")
        assertEquals(1.0, info.value, 0.0)
        assertEquals("Pixel 7", info.labels["device"])
        assertEquals("vpn", info.labels["engine"])
    }

    @Test
    fun infoFallsBackWhenTheDeviceNameIsBlank() {
        val info = one(Prometheus.render(NetSnapshot()), "ducknetview_info")
        assertEquals("unknown", info.labels["device"])
    }

    @Test
    fun extraLabelsLandOnInfo() {
        val out = Prometheus.render(NetSnapshot(), mapOf("version" to "1.2.3", "site" to "lab"))
        val info = one(out, "ducknetview_info")
        assertEquals("1.2.3", info.labels["version"])
        assertEquals("lab", info.labels["site"])
    }

    @Test
    fun extraLabelNamesAreSanitised() {
        val out = Prometheus.render(NetSnapshot(), mapOf("my label!" to "x"))
        val info = one(out, "ducknetview_info")
        assertEquals("x", info.labels["my_label_"])
    }

    @Test
    fun extraLabelNameStartingWithADigitIsPrefixed() {
        val out = Prometheus.render(NetSnapshot(), mapOf("1st" to "x"))
        assertEquals("x", one(out, "ducknetview_info").labels["_1st"])
    }

    @Test
    fun unusableExtraLabelNameIsDropped() {
        val out = Prometheus.render(NetSnapshot(), mapOf("!!!" to "x", "ok" to "y"))
        val info = one(out, "ducknetview_info")
        assertEquals(setOf("device", "engine", "ok"), info.labels.keys)
    }

    @Test
    fun extraLabelsCannotOverrideTheBuiltInOnes() {
        val out = Prometheus.render(
            NetSnapshot(deviceName = "real"),
            mapOf("device" to "spoofed", "engine" to "spoofed"),
        )
        val info = one(out, "ducknetview_info")
        assertEquals("real", info.labels["device"])
        assertEquals(1, out.lines().count { it.startsWith("ducknetview_info") })
    }

    @Test
    fun emptyExtraLabelValueBecomesUnknown() {
        val out = Prometheus.render(NetSnapshot(), mapOf("site" to ""))
        assertEquals("unknown", one(out, "ducknetview_info").labels["site"])
    }

    @Test
    fun appLabelWithQuoteBackslashAndNewlineIsEscaped() {
        val nasty = "ev\"il\\app\nsecond line"
        val out = Prometheus.render(
            NetSnapshot(apps = listOf(AppRow(uid = 10, packageName = "com.evil", label = nasty, sessionRx = 5))),
        )
        assertTrue(out.contains("""app="ev\"il\\app\nsecond line""""))
        val s = one(out, "ducknetview_app_session_receive_bytes")
        assertEquals(nasty, s.labels["app"])
        assertEquals(5.0, s.value, 0.0)
    }

    @Test
    fun escapedLabelNeverBreaksTheLineFraming() {
        val out = Prometheus.render(
            NetSnapshot(apps = listOf(AppRow(uid = 1, packageName = "p", label = "a\nb\nc"))),
        )
        // Three source lines must still be one exposition line.
        assertEquals(1, out.lines().count { it.startsWith("ducknetview_app_connections") })
    }

    @Test
    fun carriageReturnIsDroppedFromLabels() {
        val out = Prometheus.render(
            NetSnapshot(apps = listOf(AppRow(uid = 1, packageName = "p", label = "a\r\nb"))),
        )
        assertEquals("a\nb", one(out, "ducknetview_app_connections").labels["app"])
        assertFalse(out.contains('\r'))
    }

    @Test
    fun unicodeHostnameSurvivesIntact() {
        val host = "пример.рф"
        val out = Prometheus.render(NetSnapshot(conns = listOf(conn(remote = "1.2.3.4", resolved = host))))
        assertEquals(host, one(out, "ducknetview_host_connections").labels["host"])
    }

    @Test
    fun hostnameWithQuotesIsEscaped() {
        val out = Prometheus.render(
            NetSnapshot(conns = listOf(conn(remote = "1.2.3.4", resolved = """we"ird\host"""))),
        )
        assertEquals("""we"ird\host""", one(out, "ducknetview_host_connections").labels["host"])
    }

    @Test
    fun deviceRatesAndSessionTotalsAreExported() {
        val out = Prometheus.render(
            NetSnapshot(total = Throughput(rxBps = 1234, txBps = 99), sessionRx = 50_000, sessionTx = 7),
        )
        assertEquals(1234.0, one(out, "ducknetview_receive_bytes_per_second").value, 0.0)
        assertEquals(99.0, one(out, "ducknetview_transmit_bytes_per_second").value, 0.0)
        assertEquals(50_000.0, one(out, "ducknetview_session_receive_bytes").value, 0.0)
        assertEquals(7.0, one(out, "ducknetview_session_transmit_bytes").value, 0.0)
    }

    @Test
    fun perNetworkSeriesAreLabelledByIfaceAndTransport() {
        val out = Prometheus.render(fullSnapshot())
        val rx = all(out, "ducknetview_iface_receive_bytes_per_second")
        assertEquals(2, rx.size)
        val wlan = rx.first { it.labels["iface"] == "wlan0" }
        assertEquals("wifi", wlan.labels["transport"])
        assertEquals(2048.0, wlan.value, 0.0)
        assertEquals(
            4096.0,
            all(out, "ducknetview_iface_transmit_bytes_per_second")
                .first { it.labels["iface"] == "rmnet0" }.value,
            0.0,
        )
        assertEquals(
            1_000_000.0,
            all(out, "ducknetview_iface_receive_bytes").first { it.labels["iface"] == "wlan0" }.value,
            0.0,
        )
        assertEquals(
            2_000_000.0,
            all(out, "ducknetview_iface_transmit_bytes").first { it.labels["iface"] == "wlan0" }.value,
            0.0,
        )
    }

    @Test
    fun ifaceUpIsZeroForADownLink() {
        val out = Prometheus.render(fullSnapshot())
        assertEquals(
            0.0,
            all(out, "ducknetview_iface_up").first { it.labels["iface"] == "rmnet0" }.value,
            0.0,
        )
    }

    @Test
    fun blankIfaceNameBecomesUnknown() {
        val out = Prometheus.render(
            NetSnapshot(networks = listOf(NetworkRow(id = "1", ifaceName = "", transport = Transport.OTHER, up = true))),
        )
        assertEquals("unknown", one(out, "ducknetview_iface_up").labels["iface"])
    }

    @Test
    fun connectionsAreCountedInTotalAndByScope() {
        val out = Prometheus.render(fullSnapshot())
        assertEquals(4.0, one(out, "ducknetview_connections_active").value, 0.0)
        val byScope = all(out, "ducknetview_connections").associate { it.labels.getValue("scope") to it.value }
        assertEquals(Scope.entries.map { it.toString() }.toSet(), byScope.keys)
        assertEquals(3.0, byScope.getValue("public"), 0.0)
        assertEquals(1.0, byScope.getValue("private"), 0.0)
        assertEquals(0.0, byScope.getValue("loopback"), 0.0)
    }

    @Test
    fun listenersAreCountedByExposure() {
        val out = Prometheus.render(fullSnapshot())
        val byExposure = all(out, "ducknetview_listeners").associate { it.labels.getValue("exposure") to it.value }
        assertEquals(setOf("local", "lan", "exposed"), byExposure.keys)
        assertEquals(1.0, byExposure.getValue("exposed"), 0.0)
        assertEquals(1.0, byExposure.getValue("local"), 0.0)
        assertEquals(0.0, byExposure.getValue("lan"), 0.0)
    }

    @Test
    fun securityCountersAreExported() {
        val out = Prometheus.render(fullSnapshot())
        assertEquals(2.0, one(out, "ducknetview_listeners_off_baseline").value, 0.0)
        assertEquals(1.0, one(out, "ducknetview_exposed_services").value, 0.0)
        assertEquals(3.0, one(out, "ducknetview_watchlist_hits").value, 0.0)
        assertEquals(4.0, one(out, "ducknetview_public_connections").value, 0.0)
    }

    @Test
    fun eventCountsAreExportedByKind() {
        val out = Prometheus.render(fullSnapshot())
        val byKind = all(out, "ducknetview_events").associate { it.labels.getValue("kind") to it.value }
        assertEquals(setOf("new_connection", "closed_connection", "new_listener", "new_flow"), byKind.keys)
        assertEquals(6.0, byKind.getValue("new_connection"), 0.0)
        assertEquals(2.0, byKind.getValue("closed_connection"), 0.0)
        assertEquals(1.0, byKind.getValue("new_listener"), 0.0)
    }

    @Test
    fun latencyIsExportedInSecondsPerTarget() {
        val out = Prometheus.render(fullSnapshot())
        val gw = all(out, "ducknetview_latency_seconds").first { it.labels["target"] == "192.168.1.1" }
        assertEquals(0.023, gw.value, 1e-9)
        assertEquals("gateway", gw.labels["name"])
    }

    @Test
    fun failedLatencyProbeHasNoSecondsSampleButIsMarkedDown() {
        val out = Prometheus.render(fullSnapshot())
        assertTrue(all(out, "ducknetview_latency_seconds").none { it.labels["target"] == "1.1.1.1" })
        assertEquals(
            0.0,
            all(out, "ducknetview_latency_up").first { it.labels["target"] == "1.1.1.1" }.value,
            0.0,
        )
    }

    @Test
    fun latencyIsFormattedIndependentlyOfTheDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val out = Prometheus.render(fullSnapshot())
            assertTrue(out.contains(" 0.023\n"))
            for (s in samples(out)) assertFalse(s.value.isNaN())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun appCountsAreExported() {
        val out = Prometheus.render(fullSnapshot())
        assertEquals(3.0, one(out, "ducknetview_apps").value, 0.0)
        assertEquals(2.0, one(out, "ducknetview_apps_with_sockets").value, 0.0)
    }

    @Test
    fun appThroughputIsLabelledByAppPackageAndUid() {
        val out = Prometheus.render(fullSnapshot())
        val rx = all(out, "ducknetview_app_receive_bytes_per_second").first { it.labels["uid"] == "10001" }
        assertEquals("Browser", rx.labels["app"])
        assertEquals("com.browser", rx.labels["package"])
        assertEquals(500.0, rx.value, 0.0)
    }

    @Test
    fun appLabelFallsBackToPackageThenUid() {
        val out = Prometheus.render(
            NetSnapshot(
                apps = listOf(
                    AppRow(uid = 1, packageName = "com.pkg", label = "", sessionRx = 10),
                    AppRow(uid = 2, packageName = "", label = ""),
                ),
            ),
        )
        val byUid = all(out, "ducknetview_app_connections").associateBy { it.labels.getValue("uid") }
        assertEquals("com.pkg", byUid.getValue("1").labels["app"])
        assertEquals("uid 2", byUid.getValue("2").labels["app"])
        assertEquals("unknown", byUid.getValue("2").labels["package"])
    }

    @Test
    fun appSeriesAreCappedAndTheDropIsReported() {
        val apps = (1..70).map { AppRow(uid = it, packageName = "p$it", label = "app$it", sessionRx = it.toLong()) }
        val out = Prometheus.render(NetSnapshot(apps = apps))
        assertEquals(50, all(out, "ducknetview_app_receive_bytes_per_second").size)
        assertEquals(20.0, truncated(out, "app"), 0.0)
        assertEquals(70.0, one(out, "ducknetview_apps").value, 0.0)
    }

    @Test
    fun theBiggestAppsAreTheOnesKept() {
        val apps = (1..70).map { AppRow(uid = it, packageName = "p$it", label = "app$it", sessionRx = it.toLong()) }
        val out = Prometheus.render(NetSnapshot(apps = apps))
        val kept = all(out, "ducknetview_app_session_receive_bytes").map { it.value }
        assertEquals(70.0, kept.max(), 0.0)
        assertEquals(21.0, kept.min(), 0.0)
    }

    @Test
    fun hostSeriesAreCappedAndTheDropIsReported() {
        val conns = (1..64).map { conn(remote = "10.0.0.$it", rxBytes = it.toLong()) }
        val out = Prometheus.render(NetSnapshot(conns = conns))
        assertEquals(50, all(out, "ducknetview_host_receive_bytes").size)
        assertEquals(14.0, truncated(out, "host"), 0.0)
        assertEquals(64.0, one(out, "ducknetview_hosts").value, 0.0)
    }

    @Test
    fun truncationGaugeIsZeroWhenNothingIsDropped() {
        val out = Prometheus.render(fullSnapshot())
        assertEquals(0.0, truncated(out, "app"), 0.0)
        assertEquals(0.0, truncated(out, "host"), 0.0)
    }

    @Test
    fun truncationFamilyIsPresentEvenForAnEmptySnapshot() {
        val out = Prometheus.render(NetSnapshot())
        assertEquals(0.0, truncated(out, "app"), 0.0)
        assertEquals(0.0, truncated(out, "host"), 0.0)
    }

    @Test
    fun hostSeriesAggregateEveryConnectionToThatHost() {
        val out = Prometheus.render(
            NetSnapshot(
                conns = listOf(
                    conn(remote = "9.9.9.9", rxBytes = 10, txBytes = 1, rxBps = 5),
                    conn(remote = "9.9.9.9", port = 444, rxBytes = 30, txBytes = 2, rxBps = 7),
                ),
            ),
        )
        assertEquals(2.0, one(out, "ducknetview_host_connections").value, 0.0)
        assertEquals(40.0, one(out, "ducknetview_host_receive_bytes").value, 0.0)
        assertEquals(3.0, one(out, "ducknetview_host_transmit_bytes").value, 0.0)
        assertEquals(12.0, one(out, "ducknetview_host_receive_bytes_per_second").value, 0.0)
    }

    @Test
    fun hostKeyPrefersTheResolvedName() {
        val out = Prometheus.render(
            NetSnapshot(
                conns = listOf(
                    conn(remote = "1.1.1.1", resolved = "one.example"),
                    conn(remote = "1.1.1.2", resolved = "one.example"),
                ),
            ),
        )
        assertEquals(1.0, one(out, "ducknetview_hosts").value, 0.0)
        assertEquals("one.example", one(out, "ducknetview_host_connections").labels["host"])
    }

    @Test
    fun hostScopeIsCarriedAsALabel() {
        val out = Prometheus.render(NetSnapshot(conns = listOf(conn(remote = "10.0.0.1", scope = Scope.PRIVATE))))
        assertEquals("private", one(out, "ducknetview_host_connections").labels["scope"])
    }

    @Test
    fun timestampsAreExportedInSeconds() {
        val out = Prometheus.render(NetSnapshot(atMillis = 1_700_000_123_500L, uptimeMillis = 90_500L))
        assertEquals(1_700_000_123.5, one(out, "ducknetview_snapshot_timestamp_seconds").value, 1e-6)
        assertEquals(90.5, one(out, "ducknetview_uptime_seconds").value, 1e-9)
    }

    @Test
    fun pausedAndIntervalAreExported() {
        val out = Prometheus.render(NetSnapshot(paused = true, intervalSeconds = 5))
        assertEquals(1.0, one(out, "ducknetview_paused").value, 0.0)
        assertEquals(5.0, one(out, "ducknetview_poll_interval_seconds").value, 0.0)
    }

    @Test
    fun escapeLabelHandlesTheThreeSpecialCharacters() {
        assertEquals("""a\\b\"c\nd""", Prometheus.escapeLabel("a\\b\"c\nd"))
    }

    @Test
    fun escapeHelpLeavesQuotesAlone() {
        assertEquals("""say "hi"\nnow""", Prometheus.escapeHelp("say \"hi\"\nnow"))
    }

    @Test
    fun sanitizeLabelNameRejectsWhatCannotBeSpelled() {
        assertEquals("a_b", Prometheus.sanitizeLabelName("a-b"))
        assertEquals("_9", Prometheus.sanitizeLabelName("9"))
        assertNull(Prometheus.sanitizeLabelName(""))
        assertNull(Prometheus.sanitizeLabelName("--"))
        assertNotNull(Prometheus.sanitizeLabelName("_ok"))
    }

    private fun fullSnapshot() = NetSnapshot(
        atMillis = 1_700_000_000_000L,
        engine = EngineMode.VPN,
        deviceName = "Pixel 7",
        uptimeMillis = 60_000L,
        networks = listOf(
            NetworkRow(
                id = "n1",
                ifaceName = "wlan0",
                transport = Transport.WIFI,
                up = true,
                isDefault = true,
                addresses = listOf("192.168.1.23/24"),
                rxBytes = 1_000_000,
                txBytes = 2_000_000,
                rxBps = 2048,
                txBps = 512,
            ),
            NetworkRow(
                id = "n2",
                ifaceName = "rmnet0",
                transport = Transport.CELLULAR,
                up = false,
                rxBps = 1024,
                txBps = 4096,
            ),
        ),
        conns = listOf(
            conn(remote = "1.1.1.1", resolved = "one.one.one.one", rxBytes = 100, txBytes = 20),
            conn(remote = "8.8.8.8", port = 53, proto = Proto.UDP),
            conn(remote = "9.9.9.9", isNew = true),
            conn(remote = "192.168.1.1", scope = Scope.PRIVATE),
        ),
        apps = listOf(
            AppRow(
                uid = 10001,
                packageName = "com.browser",
                label = "Browser",
                connCount = 3,
                rxBps = 500,
                txBps = 100,
                sessionRx = 9_000,
                sessionTx = 400,
            ),
            AppRow(uid = 10002, packageName = "com.mail", label = "Mail", connCount = 1, sessionRx = 10),
            AppRow(uid = 10003, packageName = "com.idle", label = "Idle"),
        ),
        services = listOf(
            ServiceRow(proto = Proto.TCP, bindAddr = "0.0.0.0", port = 8080, exposure = Exposure.EXPOSED, isNew = true),
            ServiceRow(proto = Proto.TCP, bindAddr = "127.0.0.1", port = 631, exposure = Exposure.LOCAL),
        ),
        serviceScanAt = 1_700_000_000_000L,
        total = Throughput(rxBps = 3072, txBps = 4608),
        sessionRx = 12_345,
        sessionTx = 678,
        newConnCount = 6,
        closedConnCount = 2,
        latency = listOf(
            LatencySample(target = "192.168.1.1", label = "gateway", millis = 23, ok = true),
            LatencySample(target = "1.1.1.1", label = "cloudflare", millis = -1, ok = false),
        ),
        security = SecurityCounts(
            exposedServices = 1,
            publicConns = 4,
            watchlistHits = 3,
            offBaseline = 2,
        ),
    )

    private fun conn(
        remote: String,
        port: Int = 443,
        proto: Proto = Proto.TCP,
        scope: Scope = Scope.PUBLIC,
        resolved: String? = null,
        rxBytes: Long = 0,
        txBytes: Long = 0,
        rxBps: Long = 0,
        isNew: Boolean = false,
    ) = ConnRow(
        key = "$remote:$port:${rxBytes}",
        proto = proto,
        localAddr = "192.168.1.23",
        localPort = 40000,
        remoteAddr = remote,
        remotePort = port,
        state = ConnState.ESTABLISHED,
        uid = 10001,
        scope = scope,
        resolvedHost = resolved,
        rxBytes = rxBytes,
        txBytes = txBytes,
        rxBps = rxBps,
        isNew = isNew,
    )

    private fun truncated(out: String, family: String): Double =
        all(out, "ducknetview_series_truncated").first { it.labels["family"] == family }.value

    private fun families(out: String): Set<String> =
        out.lines().filter { it.startsWith("# TYPE ") }.map { it.split(' ')[2] }.toSet()

    private fun all(out: String, name: String): List<Sample> = samples(out).filter { it.name == name }

    private fun one(out: String, name: String): Sample {
        val hits = all(out, name)
        assertEquals("expected one $name", 1, hits.size)
        return hits.first()
    }

    private fun samples(out: String): List<Sample> =
        out.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map(::parse)

    /** A deliberately strict reader: a bad escape or an unquoted value throws. */
    private fun parse(line: String): Sample {
        val open = line.indexOf('{')
        if (open < 0) {
            val sp = line.indexOf(' ')
            require(sp > 0) { "no value in: $line" }
            return Sample(line.substring(0, sp), emptyMap(), line.substring(sp + 1).trim().toDouble())
        }
        val close = labelsEnd(line, open)
        return Sample(
            line.substring(0, open),
            parseLabels(line.substring(open + 1, close)),
            line.substring(close + 1).trim().toDouble(),
        )
    }

    private fun labelsEnd(line: String, open: Int): Int {
        var i = open + 1
        var inQuote = false
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote && c == '\\' -> i++
                c == '"' -> inQuote = !inQuote
                !inQuote && c == '}' -> return i
            }
            i++
        }
        throw IllegalArgumentException("unterminated label set: $line")
    }

    private fun parseLabels(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eq = s.indexOf('=', i)
            require(eq > i) { "malformed labels: $s" }
            val key = s.substring(i, eq)
            require(nameRe.matches(key)) { "bad label name: $key" }
            require(s[eq + 1] == '"') { "unquoted label value: $s" }
            val sb = StringBuilder()
            var j = eq + 2
            while (j < s.length && s[j] != '"') {
                if (s[j] == '\\') {
                    j++
                    when (s[j]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        else -> throw IllegalArgumentException("bad escape in: $s")
                    }
                } else {
                    sb.append(s[j])
                }
                j++
            }
            require(j < s.length) { "unterminated label value: $s" }
            out[key] = sb.toString()
            i = j + 1
            if (i < s.length && s[i] == ',') i++
        }
        return out
    }
}
