package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.domain.usage.DailyUsage
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure presentation maths for the usage-history screen, kept out of the
 * composable so the arithmetic can be tested without Compose or Android.
 */
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

/**
 * Sums the per-day app and host maps over [days]. Both results are ordered
 * biggest first, ties broken by name, so a redraw cannot shuffle the rows.
 */
fun aggregate(days: List<DailyUsage>): Pair<Map<String, Long>, Map<String, Long>> {
    val apps = LinkedHashMap<String, Long>()
    val hosts = LinkedHashMap<String, Long>()
    for (day in days) {
        for ((name, n) in day.apps) apps[name] = (apps[name] ?: 0L) + n
        for ((name, n) in day.hosts) hosts[name] = (hosts[name] ?: 0L) + n
    }
    return byBytesDesc(apps) to byBytesDesc(hosts)
}

fun byBytesDesc(counts: Map<String, Long>): Map<String, Long> {
    val sorted = counts.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
    val out = LinkedHashMap<String, Long>(sorted.size)
    for (e in sorted) out[e.key] = e.value
    return out
}

/**
 * The newest [range] days, newest first; a null [range] means every day kept.
 * A non-positive range selects nothing rather than throwing.
 */
fun rangeOf(days: List<DailyUsage>, range: Int?): List<DailyUsage> {
    val newestFirst = days.sortedByDescending { it.dayEpoch }
    if (range == null) return newestFirst
    if (range <= 0) return emptyList()
    return newestFirst.take(range)
}

/** The heaviest day; two equally heavy days resolve to the more recent one. */
fun busiestDay(days: List<DailyUsage>): DailyUsage? =
    days.maxWithOrNull(compareBy<DailyUsage> { it.total }.thenBy { it.dayEpoch })

fun dailyAverage(days: List<DailyUsage>): Long =
    if (days.isEmpty()) 0L else days.sumOf { it.total } / days.size

/**
 * Day numbers are timezone-free, so [LocalDate.ofEpochDay] needs no clock.
 *
 * Total on purpose. `ofEpochDay` throws for anything outside its range, and a
 * row carrying a millisecond timestamp instead of a day number used to take the
 * whole app down the moment the usage screen composed it. A formatter is the
 * wrong place to discover a unit mismatch, so an unrenderable value is labelled
 * rather than thrown: the screen stays up and the bad value is visible.
 */
fun formatDay(dayEpoch: Long): String =
    try {
        LocalDate.ofEpochDay(dayEpoch).format(DAY_FORMAT)
    } catch (e: DateTimeException) {
        "day $dayEpoch"
    }
