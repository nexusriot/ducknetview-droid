package com.vlad.ducknetview.data.work

import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.baseline.EPHEMERAL_PORT_FLOOR
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ServiceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `androidx.work:work-testing` is not resolvable offline and dependencies may
 * not be added, so the workers' decisions live in pure objects and are tested
 * here without a WorkManager.
 */
class ServiceScanLogicTest {

    private val now = 1_700_000_000_000L

    private fun row(
        port: Int,
        proto: Proto = Proto.TCP,
        addr: String = "192.168.1.5",
        exposure: Exposure = Exposure.LAN,
        appLabel: String = "",
    ) = ServiceRow(
        proto = proto,
        bindAddr = addr,
        port = port,
        service = "",
        exposure = exposure,
        appLabel = appLabel,
    )

    private fun baselineOf(vararg rows: ServiceRow) = Baseline.from(rows.toList(), now)

    @Test
    fun `an empty baseline reports nothing at all`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080), row(22)),
            Baseline.EMPTY,
            emptyMap(),
            now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `an empty baseline is not merely quiet, it does no work`() {
        val rows = ServiceScanLogic.offBaselineRows(
            listOf(row(8080)),
            Baseline.of(emptyList(), 0L),
            emptyMap(),
            now,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `one off-baseline listener produces exactly one alert event`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(22), row(8080)),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(1, alerts.size)
        assertEquals(EventLevel.ALERT, alerts.single().level)
        assertEquals(EventKind.OFF_BASELINE, alerts.single().kind)
    }

    @Test
    fun `the alert subject names the protocol, address and port`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080, addr = "10.0.0.9")),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals("tcp 10.0.0.9:8080", alerts.single().subject)
    }

    @Test
    fun `the alert is stamped with the scan time`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(now, alerts.single().at)
    }

    @Test
    fun `the alert detail says why and where it came from`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080, exposure = Exposure.EXPOSED)),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        val detail = alerts.single().detail
        assertTrue(detail.contains("not in the saved baseline"))
        assertTrue(detail.contains("exposed"))
        assertTrue(detail.contains("background scan"))
    }

    @Test
    fun `an app label is appended to the detail when there is one`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080, appLabel = "Some Server")),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertTrue(alerts.single().detail.endsWith("Some Server"))
    }

    @Test
    fun `a listener inside the baseline is never reported`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(22), row(80)),
            baselineOf(row(22), row(80)),
            emptyMap(),
            now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `every off-baseline listener gets its own event`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080), row(9090), row(22)),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(2, alerts.size)
        assertEquals(setOf(8080, 9090), alerts.map { it.subject.substringAfterLast(':').toInt() }.toSet())
    }

    @Test
    fun `the same key scanned twice in one pass alerts once`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080), row(8080)),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `a key alerted five hours ago is suppressed`() {
        val last = mapOf(row(8080).baselineKey to now - 5L * 60 * 60 * 1000)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a key alerted one second inside the window is suppressed`() {
        val last = mapOf(row(8080).baselineKey to now - (ServiceScanLogic.RATE_LIMIT_MILLIS - 1000L))
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a key alerted exactly the window ago alerts again`() {
        val last = mapOf(row(8080).baselineKey to now - ServiceScanLogic.RATE_LIMIT_MILLIS)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `a key alerted seven hours ago alerts again`() {
        val last = mapOf(row(8080).baselineKey to now - 7L * 60 * 60 * 1000)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `the rate limit is per key, not global`() {
        val last = mapOf(row(8080).baselineKey to now - 1000L)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080), row(9090)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertEquals(1, alerts.size)
        assertTrue(alerts.single().subject.endsWith(":9090"))
    }

    @Test
    fun `a stamp in the future does not silence the alert`() {
        val last = mapOf(row(8080).baselineKey to now + 30L * 24 * 60 * 60 * 1000)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `a stamp for some other key does not silence this one`() {
        val last = mapOf("tcp|1.2.3.4:1" to now)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(8080)),
            baselineOf(row(22)),
            last,
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `a UDP ephemeral port is never off-baseline`() {
        val udp = row(EPHEMERAL_PORT_FLOOR + 100, proto = Proto.UDP)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(udp),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a UDP service below the ephemeral floor is still checked`() {
        val udp = row(53, proto = Proto.UDP)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(udp),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(1, alerts.size)
        assertTrue(alerts.single().subject.startsWith("udp "))
    }

    @Test
    fun `a TCP port above the ephemeral floor is still checked`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(row(EPHEMERAL_PORT_FLOOR + 1)),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `the rows returned carry the keys the throttle has to store`() {
        val rows = ServiceScanLogic.offBaselineRows(
            listOf(row(8080, addr = "10.0.0.9")),
            baselineOf(row(22)),
            emptyMap(),
            now,
        )
        assertEquals(listOf("tcp|10.0.0.9:8080"), rows.map { it.baselineKey })
    }

    @Test
    fun `rows and alerts agree one for one`() {
        val scanned = listOf(row(8080), row(9090), row(22))
        val baseline = baselineOf(row(22))
        val rows = ServiceScanLogic.offBaselineRows(scanned, baseline, emptyMap(), now)
        val alerts = ServiceScanLogic.offBaselineAlerts(scanned, baseline, emptyMap(), now)
        assertEquals(rows.size, alerts.size)
    }

    @Test
    fun `the same bind address on a different port is a different key`() {
        val a = row(8080, addr = "10.0.0.1")
        val b = row(8081, addr = "10.0.0.1")
        assertFalse(a.baselineKey == b.baselineKey)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(a, b),
            baselineOf(row(22)),
            mapOf(a.baselineKey to now),
            now,
        )
        assertEquals(1, alerts.size)
    }

    @Test
    fun `an empty scan result reports nothing`() {
        val alerts = ServiceScanLogic.offBaselineAlerts(emptyList(), baselineOf(row(22)), emptyMap(), now)
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `the rate limit window is six hours`() {
        assertEquals(6L * 60 * 60 * 1000, ServiceScanLogic.RATE_LIMIT_MILLIS)
    }
}
