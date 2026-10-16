package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `EventKind` must be constructed somewhere in production code.
 *
 * `CAPTIVE_PORTAL` was not. It was declared in the enum, rendered in the
 * Events screen's filter vocabulary, carried in the CSV export's `kind`
 * column, and no line anywhere in `main/` ever built an event with it — so the
 * one thing the app could say about the one link condition that invalidates
 * every other reading on the screen could never be said.
 *
 * This is the same failure [UiActionsWiredTest] guards for actions and
 * [BridgeFieldsWrittenTest] guards for data, applied to the third vocabulary
 * the app publishes to the user. A compiler cannot catch it: an enum constant
 * nothing constructs is perfectly legal, and an exhaustive `when` over it in
 * the renderer makes it look thoroughly wired.
 *
 * Declaring a kind is a promise that the app can report it. If a kind has no
 * source on Android — the way retransmit counts have none — the honest move is
 * the one the rest of the project makes: leave it out rather than ship a
 * filter chip that can only ever match nothing.
 */
class EventKindsProducedTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val eventsFile: File get() = File(sourceRoot, "domain/model/Events.kt")

    /** The enum constants, read out of the declaration rather than reflected. */
    private fun declaredKinds(): List<String> {
        val body = eventsFile.readText()
            .substringAfter("enum class EventKind(")
            .substringBefore("companion object")
        return Regex("""^\s{4}([A-Z][A-Z0-9_]*)\(""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * Everything that could name one, the declaration itself excluded. A
     * mention inside `Events.kt` proves only that the constant exists; what
     * this test is about is whether anything else reaches for it.
     */
    private fun producerSources(): String = buildString {
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "Events.kt" }
            .forEach { append(it.readText()).append('\n') }
    }

    @Test
    fun `the enum declares the kinds this test expects to find`() {
        val kinds = declaredKinds()
        assertTrue("found no EventKind constants at all: $kinds", kinds.size >= 15)
        assertTrue("CAPTIVE_PORTAL is gone from EventKind", kinds.contains("CAPTIVE_PORTAL"))
        assertTrue("NEW_PUBLIC_HOST is gone from EventKind", kinds.contains("NEW_PUBLIC_HOST"))
    }

    @Test
    fun `every event kind has a producer`() {
        val sources = producerSources()
        val orphans = declaredKinds().filter { kind ->
            !Regex("""EventKind\.$kind\b""").containsMatchIn(sources)
        }
        assertTrue(
            "EventKind constants the app renders and filters on but can never " +
                "emit, so their filter chip can only ever match nothing: $orphans",
            orphans.isEmpty(),
        )
    }

    /**
     * A kind mentioned only by the renderer is still an orphan — the Events
     * screen colours and labels every kind, so a `when` branch there would
     * satisfy the check above on its own.
     */
    @Test
    fun `every event kind is produced outside the ui layer`() {
        val producers = buildString {
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.name != "Events.kt" }
                .filterNot { it.path.contains("/ui/") }
                .forEach { append(it.readText()).append('\n') }
        }
        val orphans = declaredKinds().filter { kind ->
            !Regex("""EventKind\.$kind\b""").containsMatchIn(producers)
        }
        assertTrue(
            "EventKind constants named only by the UI that renders them, which " +
                "means nothing in the engine, the domain or the services can " +
                "actually emit one: $orphans",
            orphans.isEmpty(),
        )
    }
}
