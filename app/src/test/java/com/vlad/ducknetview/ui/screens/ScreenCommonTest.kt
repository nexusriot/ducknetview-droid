package com.vlad.ducknetview.ui.screens

import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.ui.SearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCommonTest {

    @Test
    fun rateModeShowsPerSecondValues() {
        val text = rxTxText(1024, 512, 99, 99, ThroughputMode.RATE, RateUnit.BYTES)
        assertTrue(text.contains("/s"))
        assertTrue(text.contains("1.00 KB/s"))
    }

    @Test
    fun totalModeShowsCumulativeBytes() {
        val text = rxTxText(1024, 512, 2048, 1024, ThroughputMode.TOTAL, RateUnit.BYTES)
        assertFalse(text.contains("/s"))
        assertTrue(text.contains("2.00 KB"))
    }

    @Test
    fun bitsUnitMultipliesRatesByEight() {
        val text = rxTxText(1000, 0, 0, 0, ThroughputMode.RATE, RateUnit.BITS)
        assertTrue(text.contains("Kb/s"))
    }

    @Test
    fun formatClockReturnsDashForAnUnsetTimestamp() {
        assertEquals("-", formatClock(0L, SCREEN_NOW))
    }

    @Test
    fun formatClockUsesClockOnlyForToday() {
        assertEquals(8, formatClock(SCREEN_NOW, SCREEN_NOW).length)
    }

    @Test
    fun formatClockAddsTheDateForOtherDays() {
        val older = SCREEN_NOW - 5L * 86_400_000L
        assertTrue(formatClock(older, SCREEN_NOW).length > 8)
    }

    @Test
    fun appDisplayNameFallsBackToPackageThenUid() {
        assertEquals("Browser", appDisplayName("Browser", "com.example", 10))
        assertEquals("com.example", appDisplayName("", "com.example", 10))
        assertEquals("uid 10", appDisplayName("", "", 10))
    }

    @Test
    fun connTabTextIsTabSeparatedAndCarriesTheRemote() {
        val text = connTabText(screenConn(), revDns = false, unit = RateUnit.BYTES, now = SCREEN_NOW)
        val cells = text.split("\t")
        assertEquals(13, cells.size)
        assertEquals("93.184.216.34:443", cells[3])
    }

    @Test
    fun connTabTextUsesTheResolvedHostWhenRevDnsIsOn() {
        val text = connTabText(screenConn(), revDns = true, unit = RateUnit.BYTES, now = SCREEN_NOW)
        assertTrue(text.contains("example.com:443"))
    }

    @Test
    fun connTabTextLeavesRttEmptyWhenUnmeasured() {
        val text = connTabText(screenConn(rttMillis = -1), revDns = false, unit = RateUnit.BYTES, now = SCREEN_NOW)
        assertEquals("", text.split("\t")[10])
    }

    @Test
    fun closedTabTextCarriesFinalTotalsAndLifetime() {
        val text = closedTabText(screenClosed(), revDns = false, now = SCREEN_NOW)
        assertTrue(text.contains("1m01s"))
        assertTrue(text.contains("8.79 KB"))
    }

    @Test
    fun appTabTextCombinesTodaysBytes() {
        val text = appTabText(screenApp(), RateUnit.BYTES)
        val cells = text.split("\t")
        assertEquals("com.example.browser", cells[1])
        assertEquals("10123", cells[2])
        assertTrue(cells[8].endsWith("MB"))
    }

    @Test
    fun serviceTabTextMarksOffBaselineRows() {
        assertTrue(serviceTabText(screenService(offBaseline = true), SCREEN_NOW).contains("off-baseline"))
        assertFalse(serviceTabText(screenService(), SCREEN_NOW).contains("off-baseline"))
    }

    @Test
    fun eventTabTextCarriesLevelKindAndSubject() {
        val cells = eventTabText(screenEvent(), SCREEN_NOW).split("\t")
        assertEquals("warn", cells[1])
        assertEquals("watchlist_hit", cells[2])
        assertEquals("93.184.216.34", cells[3])
    }

    @Test
    fun emptyTagPrefixesTheTitle() {
        assertEquals("empty:$EMPTY_CONNS", emptyTag(EMPTY_CONNS))
    }

    private fun highlightState(
        query: String = "10.0",
        matched: Set<Int> = emptySet(),
        cursor: Int = -1,
    ) = screenState(
        search = SearchState(query = query, mode = SearchMode.HIGHLIGHT),
        matchedRows = matched,
        matchCursor = cursor,
    )

    @Test
    fun matchTagIsIndexed() {
        assertEquals("match:0", matchTag(0))
        assertEquals("match:17", matchTag(17))
    }

    @Test
    fun matchPositionIsMinusOneWithoutACursor() {
        assertEquals(-1, highlightState(matched = setOf(1, 4)).matchPosition)
    }

    @Test
    fun matchPositionIsMinusOneWithoutMatches() {
        assertEquals(-1, highlightState(matched = emptySet(), cursor = 3).matchPosition)
    }

    @Test
    fun matchPositionIsOneBasedAmongSortedMatches() {
        assertEquals(1, highlightState(matched = setOf(7, 2, 4), cursor = 2).matchPosition)
        assertEquals(2, highlightState(matched = setOf(7, 2, 4), cursor = 4).matchPosition)
        assertEquals(3, highlightState(matched = setOf(7, 2, 4), cursor = 7).matchPosition)
    }

    @Test
    fun matchPositionIsMinusOneWhenTheCursorIsNotAMatch() {
        assertEquals(-1, highlightState(matched = setOf(2, 4), cursor = 3).matchPosition)
    }

    @Test
    fun highlightQueryIsEmptyInFilterMode() {
        assertEquals("", screenState(search = SearchState(query = "10.0")).highlightQuery)
    }

    @Test
    fun highlightQueryIsTheQueryInHighlightMode() {
        assertEquals("10.0", highlightState().highlightQuery)
    }

    @Test
    fun highlightQueryIsEmptyWithoutAQuery() {
        assertEquals("", highlightState(query = "").highlightQuery)
    }

    @Test
    fun highlightIsRegexFollowsThePrefix() {
        assertTrue(highlightState(query = "re:^10").highlightIsRegex)
        assertFalse(highlightState(query = "10").highlightIsRegex)
    }

    @Test
    fun isMatchedRowIsFalseInFilterMode() {
        val state = screenState(search = SearchState(query = "10.0"), matchedRows = setOf(0))
        assertFalse(state.isMatchedRow(0))
    }

    @Test
    fun isMatchedRowIsTrueOnlyForListedIndices() {
        val state = highlightState(matched = setOf(0, 2))
        assertTrue(state.isMatchedRow(0))
        assertFalse(state.isMatchedRow(1))
        assertTrue(state.isMatchedRow(2))
        assertFalse(state.isMatchedRow(99))
    }

    @Test
    fun highlightingNeedsBothAQueryAndTheMode() {
        assertTrue(highlightState().highlighting)
        assertFalse(highlightState(query = "").highlighting)
        assertFalse(screenState(search = SearchState(query = "10.0")).highlighting)
    }
}
