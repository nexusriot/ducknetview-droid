package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every table the UI puts on screen must pass through filter, search and sort.
 *
 * Four of the five did. The closed-connection history was assigned straight off
 * the snapshot — `closed = snap.closedConns` — so with the "closed" chip on,
 * the chips removed nothing, the sort header reordered nothing, and typing an
 * app's name into the search box reported "0 matches" above a table made
 * entirely of that app's rows. `Sorters.closed` had been written and unit
 * tested for it and had no production caller at all.
 *
 * A source check because the defect is in the wiring, not in any of the three
 * stages: each one works, and nothing called them.
 */
class TablePipelineTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val viewModel: String by lazy {
        File(sourceRoot, "ui/MainViewModel.kt").readText()
    }

    /** The UiState fields a screen renders as a table of rows. */
    private val tableFields = listOf("conns", "closed", "apps", "services", "domains", "events")

    @Test
    fun `no table is published straight off the snapshot`() {
        val raw = tableFields.filter { field ->
            Regex("""^\s*$field\s*=\s*snap\.\w+\s*,""", RegexOption.MULTILINE)
                .containsMatchIn(viewModel)
        }
        assertTrue(
            "these UiState tables are assigned directly from the snapshot, so the " +
                "filter chips, the search box and the sort header cannot reach them: $raw",
            raw.isEmpty(),
        )
    }

    @Test
    fun `the closed table goes through all three stages`() {
        assertTrue(
            "the closed history is not filtered",
            viewModel.contains("Filters.closed("),
        )
        assertTrue(
            "the closed history is not searched",
            Regex("""closedSearched\s*=\s*Search\.apply""").containsMatchIn(viewModel),
        )
        assertTrue(
            "the closed history is not sorted",
            viewModel.contains("Sorters.closedByConnColumn("),
        )
    }

    @Test
    fun `the match counter follows whichever table the conns tab is showing`() {
        // The counter is read off one SearchResult, and the Conns tab can be
        // showing the live table or the closed one. Reporting the live count
        // over the closed table is the bug this guards.
        val branch = viewModel
            .substringAfter("val visibleSearch")
            .substringBefore("}")
        assertTrue(
            "the Conns branch of the match counter does not account for showClosed, " +
                "so it will report matches in a table that is not on screen",
            branch.contains("showClosed"),
        )
        assertTrue(
            "the Conns branch of the match counter never reaches closedSearched",
            branch.contains("closedSearched"),
        )
    }
}
