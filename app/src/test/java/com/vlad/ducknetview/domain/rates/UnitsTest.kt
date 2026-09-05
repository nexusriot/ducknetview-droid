package com.vlad.ducknetview.domain.rates

import com.vlad.ducknetview.domain.model.RateUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitsTest {

    @Test
    fun bytesScaleThroughBinaryUnits() {
        assertEquals("0 B", Units.bytes(0))
        assertEquals("999 B", Units.bytes(999))
        assertEquals("1.00 KB", Units.bytes(1024))
        assertEquals("1.00 MB", Units.bytes(1024L * 1024))
        assertEquals("1.00 GB", Units.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun bitsModeMultipliesByEightSoALinkReadsAtItsRatedSpeed() {
        // 12.5 MB/s is a 100 Mb/s link; the toggle exists to say so.
        val bytes = 12_500_000L
        assertTrue(Units.rate(bytes, RateUnit.BYTES).endsWith("B/s"))
        val bits = Units.rate(bytes, RateUnit.BITS)
        assertTrue(bits, bits.contains("95.4") || bits.contains("95.3"))
        assertTrue(bits.endsWith("Mb/s"))
    }

    @Test
    fun rateUnitLabelsMatchTheMode() {
        assertEquals("B/s", Units.rateUnitLabel(RateUnit.BYTES))
        assertEquals("b/s", Units.rateUnitLabel(RateUnit.BITS))
    }

    @Test
    fun negativeValuesKeepTheirSign() {
        assertTrue(Units.bytes(-2048).startsWith("-"))
    }

    @Test
    fun ageIsCompactAcrossEveryMagnitude() {
        assertEquals("-", Units.age(-1))
        assertEquals("0s", Units.age(0))
        assertEquals("59s", Units.age(59_000))
        assertEquals("1m00s", Units.age(60_000))
        assertEquals("3m12s", Units.age(192_000))
        assertEquals("2h05m", Units.age(7_500_000))
        assertEquals("3d4h", Units.age(273_600_000))
    }

    @Test
    fun uptimeSpellsOutDaysHoursMinutes() {
        assertEquals("5m", Units.uptime(300_000))
        assertEquals("2h 10m", Units.uptime(7_800_000))
        assertEquals("1d 1h 1m", Units.uptime(90_060_000))
    }

    @Test
    fun millisRenderSubTenMillisecondsWithADecimal() {
        assertEquals("-", Units.millis(-1))
        assertEquals("3.0 ms", Units.millis(3))
        assertEquals("42 ms", Units.millis(42))
    }
}
