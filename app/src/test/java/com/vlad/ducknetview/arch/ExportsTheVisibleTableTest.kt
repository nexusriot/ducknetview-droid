package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Share button must write the table that is on screen.
 *
 * `Csv`'s own first line says "every table exports the view the user is looking
 * at", and for the Connections screen it did not. That screen renders one of
 * three tables — live flows, retired flows, or flows folded by remote host —
 * and `exportCurrentTable` called `Csv.conns` in all three, because the two
 * mode flags the screen switches on were never consulted. Measured on the M10:
 * a screen reading "8 closed" produced a file of fourteen `established` rows
 * with no close time, lifetime or final totals in it, and a screen reading
 * "4 hosts" produced the flat table.
 *
 * Two invariants, both source scans because both faults compile:
 * the export must branch on every flag the screen branches on, and every table
 * `Csv` can render must be reachable from the export.
 */
class ExportsTheVisibleTableTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val viewModel: File get() = File(sourceRoot, "ui/MainViewModel.kt")
    private val connectionsScreen: File get() = File(sourceRoot, "ui/screens/ConnectionsScreen.kt")
    private val csv: File get() = File(sourceRoot, "domain/export/Csv.kt")

    /**
     * The body of `exportCurrentTable`, up to the next top-level `override
     * fun`, with comments stripped — a comment naming a flag is not the export
     * consulting it, and the comment explaining this very fault names them.
     */
    private fun exportBody(): String = viewModel.readText()
        .substringAfter("override fun exportCurrentTable()")
        .substringBefore("override fun ")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    /**
     * The settings flags the Connections screen chooses its table with, read
     * out of the screen rather than hard-coded, so a third mode is covered the
     * day it is added.
     */
    private fun connectionsModeFlags(): List<String> {
        val selector = connectionsScreen.readText()
            .substringAfter("val table: @Composable (Modifier) -> Unit")
            .substringBefore("if (twoPane)")
        return Regex("""settings\.([a-zA-Z][A-Za-z0-9_]*)\s*->""")
            .findAll(selector)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    @Test
    fun `the connections screen really does switch tables on flags`() {
        val flags = connectionsModeFlags()
        assertTrue(
            "no mode flags found in the Connections table selector — this guard " +
                "would pass vacuously, so the parsing above has drifted",
            flags.size >= 2,
        )
    }

    @Test
    fun `the connections export branches on every mode the screen renders`() {
        val body = exportBody()
        for (flag in connectionsModeFlags()) {
            assertTrue(
                "the Connections screen renders a different table when `$flag` is set and " +
                    "exportCurrentTable never looks at it, so the Share button writes " +
                    "a table the user is not looking at",
                body.contains(flag),
            )
        }
    }

    @Test
    fun `every table Csv can render is reachable from the export`() {
        // The plumbing, not a table: `of`/`line`/`field` build any CSV.
        val plumbing = setOf("of", "line", "field")
        val builders = Regex("""^\s{4}fun ([a-z][A-Za-z0-9_]*)\(""", RegexOption.MULTILINE)
            .findAll(csv.readText())
            .map { it.groupValues[1] }
            .filter { it !in plumbing }
            .toList()
        assertTrue("no Csv table builders found — the parsing has drifted", builders.isNotEmpty())

        val wired = viewModel.readText()
        val orphans = builders.filter { !wired.contains("Csv.$it(") }
        assertTrue(
            "Csv can render tables no screen's Share button reaches, so the work is " +
                "there and unreachable: $orphans",
            orphans.isEmpty(),
        )
    }
}
