package com.vlad.ducknetview.domain.search

import com.vlad.ducknetview.domain.model.SearchMode

/**
 * Row matching for the search field. Plain queries are case-insensitive
 * substrings across every field of a row; a `re:` prefix compiles the rest as
 * a case-insensitive regular expression.
 */
sealed class Matcher {

    abstract fun matches(fields: List<String>): Boolean

    fun matches(vararg fields: String): Boolean = matches(fields.asList())

    /** No query: every row is a match, and nothing is highlighted. */
    object All : Matcher() {
        override fun matches(fields: List<String>): Boolean = true
    }

    data class Contains(val needle: String) : Matcher() {
        override fun matches(fields: List<String>): Boolean =
            fields.any { it.contains(needle, ignoreCase = true) }
    }

    data class Expr(val pattern: String) : Matcher() {
        private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

        override fun matches(fields: List<String>): Boolean =
            fields.any { regex.containsMatchIn(it) }

        /** Match ranges within one field, for highlight rendering. */
        fun ranges(field: String): List<IntRange> =
            regex.findAll(field).map { it.range }.toList()
    }
}

/**
 * A compiled query, or the reason it would not compile. [matcher] is null only
 * when [error] is set — a broken expression must never be turned into a
 * matcher that silently matches nothing.
 */
data class CompileResult(val matcher: Matcher?, val error: String?) {
    val ok: Boolean get() = error == null
}

data class SearchResult<T>(
    val items: List<T>,
    /** Indices into [items] that matched. In FILTER mode that is all of them. */
    val matched: Set<Int>,
    val matchCount: Int,
)

/**
 * Character ranges inside [text] that [query] marks, for cell-level highlight
 * rendering. Honours the same `re:` prefix and case-insensitivity as
 * [Search.compile]; an empty or uncompilable query marks nothing rather than
 * throwing, because the query is re-evaluated on every keystroke.
 *
 * Overlapping and touching ranges are merged, and zero-width matches (`re:x*`)
 * are dropped — a zero-length span would render as an invisible mark.
 */
fun highlightRanges(text: String, query: String): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    val q = query.trim()
    if (q.isEmpty()) return emptyList()

    val found = if (q.startsWith(Search.REGEX_PREFIX)) {
        val expr = q.removePrefix(Search.REGEX_PREFIX).trim()
        if (expr.isEmpty()) return emptyList()
        try {
            Regex(expr, RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.range }
                .filter { !it.isEmpty() }
                .toList()
        } catch (e: RuntimeException) {
            return emptyList()
        }
    } else {
        val out = mutableListOf<IntRange>()
        var at = text.indexOf(q, 0, ignoreCase = true)
        while (at >= 0) {
            out += at until at + q.length
            at = text.indexOf(q, at + q.length, ignoreCase = true)
        }
        out
    }

    return mergeRanges(found)
}

private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    if (ranges.size < 2) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = mutableListOf<IntRange>()
    var current = sorted.first()
    for (r in sorted.drop(1)) {
        // Touching spans are merged too: two marks with no gap read as one anyway.
        if (r.first <= current.last + 1) {
            if (r.last > current.last) current = current.first..r.last
        } else {
            out += current
            current = r
        }
    }
    out += current
    return out
}

object Search {

    const val REGEX_PREFIX = "re:"

    fun isRegexQuery(query: String): Boolean =
        query.trimStart().startsWith(REGEX_PREFIX)

    /**
     * An empty query, and a bare `re:` with nothing after it, both compile to
     * [Matcher.All] — mid-typing is not an error state.
     */
    fun compile(query: String): CompileResult {
        val q = query.trim()
        if (q.isEmpty()) return CompileResult(Matcher.All, null)
        if (!q.startsWith(REGEX_PREFIX)) return CompileResult(Matcher.Contains(q), null)

        val expr = q.removePrefix(REGEX_PREFIX).trim()
        if (expr.isEmpty()) return CompileResult(Matcher.All, null)
        return try {
            CompileResult(Matcher.Expr(expr), null)
        } catch (e: IllegalArgumentException) {
            CompileResult(null, "invalid regex: " + (e.message?.lineSequence()?.first() ?: expr))
        }
    }

    /**
     * FILTER drops non-matching rows; HIGHLIGHT keeps every row and reports
     * which ones matched, so the surrounding context stays on screen.
     */
    fun <T> apply(
        items: List<T>,
        matcher: Matcher?,
        mode: SearchMode,
        fields: (T) -> List<String>,
    ): SearchResult<T> {
        if (matcher == null || matcher is Matcher.All) {
            return SearchResult(items, emptySet(), 0)
        }

        return when (mode) {
            SearchMode.FILTER -> {
                val kept = items.filter { matcher.matches(fields(it)) }
                SearchResult(kept, kept.indices.toSet(), kept.size)
            }

            SearchMode.HIGHLIGHT -> {
                val hits = LinkedHashSet<Int>()
                items.forEachIndexed { i, item -> if (matcher.matches(fields(item))) hits += i }
                SearchResult(items, hits, hits.size)
            }
        }
    }

    /** Index of the next match after [from], wrapping; -1 when there are none. */
    fun next(matched: Set<Int>, from: Int, count: Int, forward: Boolean): Int {
        if (matched.isEmpty() || count <= 0) return -1
        val step = if (forward) 1 else -1
        for (i in 1..count) {
            val idx = ((from + i * step) % count + count) % count
            if (idx in matched) return idx
        }
        return -1
    }
}
