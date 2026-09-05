package com.vlad.ducknetview.domain.search

import com.vlad.ducknetview.domain.model.SearchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {

    private val rows = listOf("alpha 10.0.0.1", "BETA 8.8.8.8", "gamma 10.0.0.2")

    private fun matcher(q: String): Matcher = Search.compile(q).matcher!!

    @Test
    fun emptyQueryMatchesEverything() {
        val r = Search.compile("")
        assertTrue(r.ok)
        assertEquals(Matcher.All, r.matcher)
        assertTrue(r.matcher!!.matches(listOf("anything")))
    }

    @Test
    fun blankQueryIsTrimmedToEmpty() {
        assertEquals(Matcher.All, Search.compile("   ").matcher)
    }

    @Test
    fun plainQueryIsCaseInsensitiveSubstring() {
        val m = matcher("beta")
        assertTrue(m.matches(listOf("BETA 8.8.8.8")))
        assertTrue(m.matches(listOf("no", "a BeTa row")))
        assertFalse(m.matches(listOf("alpha")))
    }

    @Test
    fun regexPrefixCompilesCaseInsensitively() {
        val m = matcher("re:^b.*8$")
        assertTrue(m is Matcher.Expr)
        assertTrue(m.matches(listOf("BETA 8.8.8.8")))
        assertFalse(m.matches(listOf("alpha 10.0.0.1")))
    }

    @Test
    fun invalidRegexReportsWhyAndProducesNoMatcher() {
        val r = Search.compile("re:[unclosed")
        assertNull("a broken expression must not become a silent no-match", r.matcher)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("invalid regex"))
        assertFalse(r.ok)
    }

    @Test
    fun secondInvalidRegexShape() {
        val r = Search.compile("re:a{2,1}")
        assertNull(r.matcher)
        assertNotNull(r.error)
    }

    @Test
    fun bareRegexPrefixIsNotAnError() {
        val r = Search.compile("re:")
        assertTrue(r.ok)
        assertEquals(Matcher.All, r.matcher)
    }

    @Test
    fun filterModeDropsNonMatchingRows() {
        val res = Search.apply(rows, matcher("10.0.0"), SearchMode.FILTER) { listOf(it) }
        assertEquals(listOf("alpha 10.0.0.1", "gamma 10.0.0.2"), res.items)
        assertEquals(2, res.matchCount)
        assertEquals(setOf(0, 1), res.matched)
    }

    @Test
    fun highlightModeKeepsEveryRow() {
        val res = Search.apply(rows, matcher("10.0.0"), SearchMode.HIGHLIGHT) { listOf(it) }
        assertEquals(rows, res.items)
        assertEquals(setOf(0, 2), res.matched)
        assertEquals(2, res.matchCount)
    }

    @Test
    fun matchAllShortCircuits() {
        val res = Search.apply(rows, Matcher.All, SearchMode.FILTER) { listOf(it) }
        assertEquals(rows, res.items)
        assertEquals(0, res.matchCount)
    }

    @Test
    fun nullMatcherLeavesRowsAlone() {
        val res = Search.apply(rows, null, SearchMode.FILTER) { listOf(it) }
        assertEquals(rows, res.items)
    }

    @Test
    fun matchesAnyFieldNotJustTheFirst() {
        val m = matcher("wlan0")
        assertTrue(m.matches(listOf("tcp", "192.168.1.5:443", "wlan0")))
        assertFalse(m.matches(listOf("tcp", "192.168.1.5:443", "rmnet0")))
    }

    @Test
    fun regexRangesForHighlighting() {
        val m = Search.compile("re:o+").matcher as Matcher.Expr
        assertEquals(listOf(1..2, 5..5), m.ranges("foo bor"))
    }

    @Test
    fun nextMatchWrapsForwardAndBackward() {
        val matched = setOf(1, 4)
        assertEquals(4, Search.next(matched, 1, 6, forward = true))
        assertEquals(1, Search.next(matched, 4, 6, forward = true))
        assertEquals(1, Search.next(matched, 4, 6, forward = false))
        assertEquals(4, Search.next(matched, 1, 6, forward = false))
        assertEquals(-1, Search.next(emptySet(), 0, 6, forward = true))
        assertEquals(-1, Search.next(matched, 0, 0, forward = true))
    }

    @Test
    fun regexQueryDetection() {
        assertTrue(Search.isRegexQuery("re:foo"))
        assertTrue(Search.isRegexQuery("  re:foo"))
        assertFalse(Search.isRegexQuery("foo"))
        assertFalse(Search.isRegexQuery("core:foo"))
    }
}
