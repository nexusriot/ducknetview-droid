package com.vlad.ducknetview.domain.rates

import com.vlad.ducknetview.domain.model.RateUnit
import kotlin.math.abs

/**
 * Byte/rate/duration formatting. Ported from the TUI's probe.FormatRate and
 * friends so the two tools read the same way.
 */
object Units {

    private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    private val BIT_UNITS = arrayOf("b", "Kb", "Mb", "Gb", "Tb", "Pb")

    /** Cumulative byte counts, always in bytes regardless of the rate unit. */
    fun bytes(n: Long): String = scale(n.toDouble(), BYTE_UNITS, "")

    /**
     * A throughput rate. In BITS mode the value is multiplied by 8 first,
     * which is what makes a "100 Mb/s" link read as 100 and not 12.5.
     */
    fun rate(bytesPerSecond: Long, unit: RateUnit): String = when (unit) {
        RateUnit.BYTES -> scale(bytesPerSecond.toDouble(), BYTE_UNITS, "/s")
        RateUnit.BITS -> scale(bytesPerSecond.toDouble() * 8.0, BIT_UNITS, "/s")
    }

    /** Short unit label for axis captions ("B/s" / "b/s"). */
    fun rateUnitLabel(unit: RateUnit): String =
        if (unit == RateUnit.BITS) "b/s" else "B/s"

    private fun scale(value: Double, units: Array<String>, suffix: String): String {
        var v = abs(value)
        val sign = if (value < 0) "-" else ""
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        val text = when {
            i == 0 -> String.format("%.0f", v)
            v >= 100 -> String.format("%.0f", v)
            v >= 10 -> String.format("%.1f", v)
            else -> String.format("%.2f", v)
        }
        return "$sign$text ${units[i]}$suffix"
    }

    /**
     * Compact age/lifetime rendering ("4s", "3m12s", "2h05m", "3d4h"), the
     * same shape the TUI's AGE column uses.
     */
    fun age(millis: Long): String {
        if (millis < 0) return "-"
        val s = millis / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m${(s % 60).toString().padStart(2, '0')}s"
            s < 86400 -> "${s / 3600}h${((s % 3600) / 60).toString().padStart(2, '0')}m"
            else -> "${s / 86400}d${((s % 86400) / 3600)}h"
        }
    }

    /** Uptime, spelled out a little more than [age]. */
    fun uptime(millis: Long): String {
        val s = millis / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    fun millis(ms: Int): String = when {
        ms < 0 -> "-"
        ms < 10 -> String.format("%.1f ms", ms.toDouble())
        else -> "$ms ms"
    }
}
