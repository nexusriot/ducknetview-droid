package com.vlad.ducknetview.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRangesTest {

    private fun marked(text: String, query: String): List<String> =
        highlightRanges(text, query).map { text.substring(it.first, it.last + 1) }

    @Test
    fun plainSubstringIsMarkedOnce() {
        assertEquals(listOf(4..8), highlightRanges("tcp 10.0.0.2:443", "10.0."))
    }

    @Test
    fun rangesAddressTheMatchingSubstring() {
        assertEquals(listOf("10.0."), marked("tcp 10.0.0.2:443", "10.0."))
    }

    @Test
    fun everyOccurrenceIsMarked() {
        assertEquals(listOf(0..2, 8..10), highlightRanges("foo bar foo", "foo"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(listOf("Foo", "FOO"), marked("Foo and FOO", "foo"))
    }

    @Test
    fun queryCaseDoesNotMatter() {
        assertEquals(listOf("browser"), marked("browser", "BROWSER"))
    }

    @Test
    fun noMatchGivesNoRanges() {
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", "beta"))
    }

    @Test
    fun emptyQueryGivesNoRanges() {
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", ""))
    }

    @Test
    fun blankQueryGivesNoRanges() {
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", "   "))
    }

    @Test
    fun emptyTextGivesNoRanges() {
        assertEquals(emptyList<IntRange>(), highlightRanges("", "alpha"))
    }

    @Test
    fun queryLongerThanTextGivesNoRanges() {
        assertEquals(emptyList<IntRange>(), highlightRanges("ab", "abcdef"))
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        assertEquals(listOf("beta"), marked("alpha beta", "  beta  "))
    }

    @Test
    fun regexPrefixIsHonoured() {
        assertEquals(listOf("10.0.0.2"), marked("to 10.0.0.2 now", "re:[0-9.]+"))
    }

    @Test
    fun regexIsCaseInsensitive() {
        assertEquals(listOf("BETA"), marked("alpha BETA", "re:beta"))
    }

    @Test
    fun regexAnchorsApplyToTheWholeField() {
        assertEquals(listOf("alpha"), marked("alpha beta", "re:^alpha"))
        assertEquals(emptyList<String>(), marked("alpha beta", "re:^beta"))
    }

    @Test
    fun regexPrefixWithWhitespaceStillCompiles() {
        assertEquals(listOf("beta"), marked("alpha beta", "re:  beta"))
    }

    @Test
    fun bareRegexPrefixGivesNoRanges() {
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", "re:"))
    }

    @Test
    fun invalidRegexGivesNoRangesAndDoesNotThrow() {
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", "re:[unclosed"))
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", "re:*"))
        assertEquals(emptyList<IntRange>(), highlightRanges("alpha", "re:a{2,1}"))
    }

    @Test
    fun adjacentPlainMatchesAreMerged() {
        assertEquals(listOf(0..3), highlightRanges("aaaa", "aa"))
    }

    @Test
    fun adjacentRegexMatchesAreMerged() {
        assertEquals(listOf(0..2), highlightRanges("aaa b", "re:a"))
    }

    @Test
    fun separatedMatchesAreNotMerged() {
        assertEquals(listOf(0..0, 2..2), highlightRanges("a a", "re:a"))
    }

    @Test
    fun mergedRangesComeBackInOrder() {
        val ranges = highlightRanges("x foo y foo z foo", "foo")
        assertEquals(listOf(2..4, 8..10, 14..16), ranges)
    }

    @Test
    fun zeroWidthRegexProducesNoSpansAndTerminates() {
        assertEquals(emptyList<IntRange>(), highlightRanges("abc", "re:x*"))
    }

    @Test
    fun zeroWidthAlternativeKeepsOnlyTheRealMatch() {
        assertEquals(listOf(0..1), highlightRanges("aab", "re:a*"))
    }

    @Test
    fun emptyAnchorRegexProducesNoSpans() {
        assertEquals(emptyList<IntRange>(), highlightRanges("abc", "re:^"))
        assertEquals(emptyList<IntRange>(), highlightRanges("abc", "re:\\b"))
    }

    @Test
    fun unicodeTextIsMatchedByCodeUnitOffsets() {
        assertEquals(listOf("мир"), marked("привет мир", "МИР"))
    }

    @Test
    fun unicodeOffsetsSurviveAstralCharacters() {
        val text = "🦆 duck"
        val ranges = highlightRanges(text, "duck")
        assertEquals(1, ranges.size)
        assertEquals("duck", text.substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test
    fun everyRangeStaysInsideTheText() {
        val text = "10.0.0.2 -> 10.0.0.3"
        highlightRanges(text, "10.0.0.").forEach {
            assertTrue(it.first >= 0)
            assertTrue(it.last < text.length)
            assertTrue(!it.isEmpty())
        }
    }

    @Test
    fun matchAtTheVeryEndIsMarked() {
        assertEquals(listOf(6..9), highlightRanges("proto tcp4", "tcp4"))
    }

    @Test
    fun touchingAlternativesCollapseIntoOneSpan() {
        // Two marks with no gap between them must not come back as two spans.
        assertEquals(listOf(0..3), highlightRanges("abab", "re:ab|ba"))
    }
}
