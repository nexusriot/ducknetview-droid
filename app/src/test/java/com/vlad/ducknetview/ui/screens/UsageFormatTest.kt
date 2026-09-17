package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.domain.usage.DailyUsage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageFormatTest {

    private fun day(
        epoch: Long,
        rx: Long = 0,
        tx: Long = 0,
        apps: Map<String, Long> = emptyMap(),
        hosts: Map<String, Long> = emptyMap(),
    ) = DailyUsage(dayEpoch = epoch, rx = rx, tx = tx, apps = apps, hosts = hosts)

    @Test
    fun aggregateSumsAppsAcrossDays() {
        val (apps, _) = aggregate(
            listOf(
                day(1, apps = mapOf("browser" to 100L)),
                day(2, apps = mapOf("browser" to 50L)),
            ),
        )
        assertEquals(150L, apps["browser"])
    }

    @Test
    fun aggregateKeepsKeysPresentInOnlySomeDays() {
        val (apps, _) = aggregate(
            listOf(
                day(1, apps = mapOf("browser" to 100L, "mail" to 10L)),
                day(2, apps = mapOf("browser" to 50L, "maps" to 7L)),
            ),
        )
        assertEquals(setOf("browser", "mail", "maps"), apps.keys)
        assertEquals(10L, apps["mail"])
        assertEquals(7L, apps["maps"])
    }

    @Test
    fun aggregateKeepsAppsAndHostsSeparate() {
        val (apps, hosts) = aggregate(
            listOf(
                day(1, apps = mapOf("shared" to 3L), hosts = mapOf("shared" to 5L)),
                day(2, apps = mapOf("shared" to 4L), hosts = mapOf("other" to 9L)),
            ),
        )
        assertEquals(7L, apps["shared"])
        assertEquals(5L, hosts["shared"])
        assertEquals(9L, hosts["other"])
        assertEquals(1, apps.size)
    }

    @Test
    fun aggregateOrdersBiggestFirst() {
        val (apps, _) = aggregate(
            listOf(day(1, apps = mapOf("small" to 1L, "big" to 900L, "mid" to 50L))),
        )
        assertEquals(listOf("big", "mid", "small"), apps.keys.toList())
    }

    @Test
    fun aggregateBreaksEqualBytesByName() {
        val (_, hosts) = aggregate(
            listOf(day(1, hosts = mapOf("zeta" to 10L, "alpha" to 10L))),
        )
        assertEquals(listOf("alpha", "zeta"), hosts.keys.toList())
    }

    @Test
    fun aggregateOfNoDaysIsEmpty() {
        val (apps, hosts) = aggregate(emptyList())
        assertEquals(emptyMap<String, Long>(), apps)
        assertEquals(emptyMap<String, Long>(), hosts)
    }

    @Test
    fun byBytesDescSortsAndKeepsEveryEntry() {
        val out = byBytesDesc(mapOf("a" to 1L, "b" to 3L, "c" to 2L))
        assertEquals(listOf("b", "c", "a"), out.keys.toList())
        assertEquals(3, out.size)
    }

    @Test
    fun rangeWithFewerDaysThanRequestedReturnsAll() {
        val days = listOf(day(10), day(11), day(12))
        assertEquals(3, rangeOf(days, 7).size)
    }

    @Test
    fun rangeWithExactlyNDaysReturnsAll() {
        val days = (1L..7L).map { day(it) }
        assertEquals(7, rangeOf(days, 7).size)
    }

    @Test
    fun rangeWithMoreDaysKeepsTheNewest() {
        val days = (1L..30L).map { day(it) }
        val out = rangeOf(days, 7)
        assertEquals(7, out.size)
        assertEquals(30L, out.first().dayEpoch)
        assertEquals(24L, out.last().dayEpoch)
    }

    @Test
    fun nullRangeMeansEveryDay() {
        val days = (1L..30L).map { day(it) }
        assertEquals(30, rangeOf(days, null).size)
    }

    @Test
    fun rangeOrdersNewestFirstFromUnsortedInput() {
        val days = listOf(day(5), day(1), day(9), day(3))
        assertEquals(listOf(9L, 5L, 3L, 1L), rangeOf(days, null).map { it.dayEpoch })
    }

    @Test
    fun rangeOfUnsortedInputStillTakesTheNewest() {
        val days = listOf(day(5), day(1), day(9), day(3))
        assertEquals(listOf(9L, 5L), rangeOf(days, 2).map { it.dayEpoch })
    }

    @Test
    fun nonPositiveRangeSelectsNothing() {
        val days = (1L..5L).map { day(it) }
        assertEquals(emptyList<DailyUsage>(), rangeOf(days, 0))
        assertEquals(emptyList<DailyUsage>(), rangeOf(days, -3))
    }

    @Test
    fun rangeOfEmptyHistoryIsEmpty() {
        assertEquals(emptyList<DailyUsage>(), rangeOf(emptyList(), 7))
        assertEquals(emptyList<DailyUsage>(), rangeOf(emptyList(), null))
    }

    @Test
    fun busiestDayIsTheHeaviestTotal() {
        val days = listOf(day(1, rx = 10, tx = 10), day(2, rx = 100), day(3, tx = 5))
        assertEquals(2L, busiestDay(days)?.dayEpoch)
    }

    @Test
    fun busiestDayCountsRxAndTxTogether() {
        val days = listOf(day(1, rx = 60), day(2, rx = 30, tx = 40))
        assertEquals(2L, busiestDay(days)?.dayEpoch)
    }

    @Test
    fun busiestDayTieResolvesToTheNewerDay() {
        val days = listOf(day(1, rx = 50), day(9, rx = 50), day(4, rx = 50))
        assertEquals(9L, busiestDay(days)?.dayEpoch)
        assertEquals(9L, busiestDay(days.reversed())?.dayEpoch)
    }

    @Test
    fun busiestDayOfEmptyHistoryIsNull() {
        assertNull(busiestDay(emptyList()))
    }

    @Test
    fun dailyAverageOfEmptyHistoryIsZero() {
        assertEquals(0L, dailyAverage(emptyList()))
    }

    @Test
    fun dailyAverageDividesTotalByDayCount() {
        val days = listOf(day(1, rx = 100), day(2, rx = 200), day(3, rx = 300, tx = 300))
        assertEquals(300L, dailyAverage(days))
    }

    @Test
    fun dailyAverageTruncatesRatherThanRounding() {
        val days = listOf(day(1, rx = 10), day(2, rx = 11))
        assertEquals(10L, dailyAverage(days))
    }

    @Test
    fun dailyAverageOfAllZeroDaysIsZero() {
        assertEquals(0L, dailyAverage(listOf(day(1), day(2))))
    }

    @Test
    fun formatDayAtEpochZeroIsTheStartOfNineteenSeventy() {
        assertEquals("Thu Jan 1", formatDay(0L))
    }

    @Test
    fun formatDayAtAKnownDate() {
        assertEquals("Sat Jan 1", formatDay(LocalDate.of(2000, 1, 1).toEpochDay()))
        assertEquals("Sun Mar 14", formatDay(LocalDate.of(2021, 3, 14).toEpochDay()))
    }

    @Test
    fun formatDayBeforeEpochGoesBackwards() {
        assertEquals("Wed Dec 31", formatDay(-1L))
    }

    @Test
    fun formatDayIsIndependentOfTheDefaultTimeZone() {
        val epoch = LocalDate.of(2021, 3, 14).toEpochDay()
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
            assertEquals("Sun Mar 14", formatDay(epoch))
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Midway"))
            assertEquals("Sun Mar 14", formatDay(epoch))
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    /**
     * A row carrying a millisecond timestamp instead of a day number used to
     * throw out of here and take the whole app down with it — the usage screen
     * crashed on open, repeatedly, on a real device. The unit mismatch is fixed
     * at the producer; this keeps the formatter from ever being the thing that
     * kills the process again.
     */
    @Test
    fun formatDayLabelsAValueItCannotRenderInsteadOfThrowing() {
        val millis = 1_789_243_200_000L
        assertEquals("day $millis", formatDay(millis))
        assertEquals("day ${Long.MIN_VALUE}", formatDay(Long.MIN_VALUE))
        assertEquals("day ${Long.MAX_VALUE}", formatDay(Long.MAX_VALUE))
    }

    @Test
    fun formatDayStillRendersTheEdgesOfTheSupportedRange() {
        assertEquals("Mon Jan 1", formatDay(LocalDate.of(-999_999_999, 1, 1).toEpochDay()))
        assertEquals("Fri Dec 31", formatDay(LocalDate.of(999_999_999, 12, 31).toEpochDay()))
    }
}
