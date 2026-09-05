package com.vlad.ducknetview.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRollupTest {

    @Test
    fun mergingIntoAnEmptyHistoryCreatesTheDay() {
        val out = UsageRollup.merge(emptyList(), DailyUsage(10, rx = 5, tx = 3))
        assertEquals(1, out.size)
        assertEquals(10, out[0].dayEpoch)
        assertEquals(8, out[0].total)
    }

    @Test
    fun twoSessionsOnTheSameDayAreSummed() {
        var days = UsageRollup.merge(emptyList(), DailyUsage(10, rx = 5, tx = 3))
        days = UsageRollup.merge(days, DailyUsage(10, rx = 1, tx = 1))
        assertEquals(1, days.size)
        assertEquals(6, days[0].rx)
        assertEquals(4, days[0].tx)
    }

    @Test
    fun perNameCountsAreSummedPerDay() {
        var days = UsageRollup.merge(
            emptyList(),
            DailyUsage(1, apps = mapOf("Browser" to 100L), hosts = mapOf("8.8.8.8" to 10L)),
        )
        days = UsageRollup.merge(
            days,
            DailyUsage(1, apps = mapOf("Browser" to 50L, "Mail" to 5L), hosts = mapOf("1.1.1.1" to 1L)),
        )
        assertEquals(150L, days[0].apps["Browser"])
        assertEquals(5L, days[0].apps["Mail"])
        assertEquals(2, days[0].hosts.size)
    }

    @Test
    fun differentDaysStaySeparateAndSorted() {
        var days = UsageRollup.merge(emptyList(), DailyUsage(3, rx = 1))
        days = UsageRollup.merge(days, DailyUsage(1, rx = 1))
        days = UsageRollup.merge(days, DailyUsage(2, rx = 1))
        assertEquals(listOf(1L, 2L, 3L), days.map { it.dayEpoch })
    }

    @Test
    fun historyIsTrimmedToFortyDays() {
        var days = emptyList<DailyUsage>()
        for (d in 1..45) days = UsageRollup.merge(days, DailyUsage(d.toLong(), rx = 1))
        assertEquals(UsageRollup.MAX_DAYS, days.size)
        assertEquals(6L, days.first().dayEpoch)
        assertEquals(45L, days.last().dayEpoch)
    }

    @Test
    fun onlyTheTopNamesPerDaySurvive() {
        val big = (1..60).associate { "app-$it" to it.toLong() }
        val days = UsageRollup.merge(emptyList(), DailyUsage(1, apps = big))
        assertEquals(UsageRollup.TOP_N, days[0].apps.size)
        assertTrue(days[0].apps.containsKey("app-60"))
        assertTrue(!days[0].apps.containsKey("app-1"))
    }

    @Test
    fun mergeCountsKeepsTheBiggestAndBreaksTiesByName() {
        val out = UsageRollup.mergeCounts(
            mapOf("a" to 1L, "b" to 5L),
            mapOf("a" to 1L, "c" to 5L),
            keep = 2,
        )
        assertEquals(setOf("b", "c"), out.keys)
    }

    @Test
    fun mergeCountsIsAPlainSumBelowTheCap() {
        val out = UsageRollup.mergeCounts(mapOf("a" to 1L), mapOf("a" to 2L, "b" to 3L), keep = 50)
        assertEquals(mapOf("a" to 3L, "b" to 3L), out)
    }

    @Test
    fun totalsAcrossDays() {
        val days = listOf(DailyUsage(1, rx = 10, tx = 1), DailyUsage(2, rx = 5, tx = 2))
        assertEquals(15L, UsageRollup.totalRx(days))
        assertEquals(3L, UsageRollup.totalTx(days))
    }

    @Test
    fun recentReturnsTheTailOldestFirst() {
        val days = (1..10).map { DailyUsage(it.toLong(), rx = 1) }
        val recent = UsageRollup.recent(days.shuffled(), 3)
        assertEquals(listOf(8L, 9L, 10L), recent.map { it.dayEpoch })
        assertTrue(UsageRollup.recent(days, 0).isEmpty())
        assertEquals(10, UsageRollup.recent(days, 99).size)
    }

    @Test
    fun mergingDoesNotMutateTheInput() {
        val original = listOf(DailyUsage(1, rx = 1))
        UsageRollup.merge(original, DailyUsage(1, rx = 9))
        assertEquals(1L, original[0].rx)
    }
}
