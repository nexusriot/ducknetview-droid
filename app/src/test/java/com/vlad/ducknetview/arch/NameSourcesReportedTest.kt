package com.vlad.ducknetview.arch

import com.vlad.ducknetview.domain.model.NameSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No surface may compare a name's source against one constant and treat the
 * rest as an `else`.
 *
 * This is [EventKindsProducedTest] pointed at the fourth vocabulary the app
 * publishes. `NameSource` had two members for most of this app's life, and two
 * places were written as a two-way branch on `== SNI`:
 *
 * - the event detail, which therefore recorded every QUIC-learnt name as
 *   "resolved by DNS" in the permanent log, the events CSV and the alert
 *   webhook — measured on the M10, including names the instrumented suite had
 *   itself sent as QUIC handshakes;
 * - the Domains summary, which counted SNI alone and read "11 names · 1 from
 *   SNI" over a table of ten QUIC rows.
 *
 * Adding `QUIC` broke neither, because an `if/else` over an enum compiles
 * whatever the enum grows into — the thing an exhaustive `when` would have
 * caught. The guard is a source scan for the same reason
 * [BridgeFieldsWrittenTest] is: the fault is legal code.
 */
class NameSourcesReportedTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private fun kotlinFiles(): List<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /**
     * An `else` is only safe over a *binary* question. `fromHandshake` is one,
     * so a comparison against it is allowed; a comparison against a single
     * constant is not, because the answer for every other constant is then
     * whatever the author of the else branch had in mind at the time.
     */
    @Test
    fun `no surface branches on a single name source`() {
        val offenders = mutableListOf<String>()
        val pattern = Regex("""source\s*[=!]=\s*NameSource\.[A-Z]+""")
        for (file in kotlinFiles()) {
            for ((i, line) in file.readLines().withIndex()) {
                if (pattern.containsMatchIn(line)) {
                    offenders += "${file.name}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "a name source is being singled out; use an exhaustive `when`, " +
                "NameSource.provenance, or NameSource.fromHandshake:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** Every constant must carry its own wording, distinct from the others'. */
    @Test
    fun `every name source states its own provenance`() {
        for (source in NameSource.entries) {
            assertTrue("${source.name} has no provenance wording", source.provenance.isNotBlank())
            assertTrue("${source.name} has no badge label", source.label.isNotBlank())
        }
        assertEquals(
            "two name sources share a provenance phrase, so the log cannot tell them apart",
            NameSource.entries.size,
            NameSource.entries.map { it.provenance }.distinct().size,
        )
    }

    /** DNS is the only source that is not read out of a handshake. */
    @Test
    fun `fromHandshake means what the screens assume it means`() {
        assertEquals(
            NameSource.entries.filter { it != NameSource.DNS }.toSet(),
            NameSource.entries.filter { it.fromHandshake }.toSet(),
        )
    }
}
