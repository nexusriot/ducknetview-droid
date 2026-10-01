package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A narrower cousin of [UiActionsWiredTest], guarding the other half of this
 * project's recurring defect: not a control with no handler, but a field the
 * output renders and nothing produces.
 *
 * The name store keeps a uid and resolves the app's label on read, because an
 * app can rename itself and a label frozen into the database goes stale. The
 * consequence is that the rows [com.vlad.ducknetview.data.DomainRepository]
 * hands back are unlabelled, and an event line reading "first contact with
 * ads.example.com" with no app beside it is most of the value missing — while
 * compiling perfectly and failing no test, because the field has a default.
 *
 * Source-level on purpose: both layers are individually correct here, and the
 * defect lives in the seam between them. That is the class of bug a device run
 * found three times before `SnapshotAssemblerTest` existed.
 */
class DomainEventsLabelledTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    @Test
    fun `the rows handed to the event producer have been through the app catalog`() {
        val text = File(sourceRoot, "engine/EngineController.kt").readText()

        val recordAt = text.indexOf("domains.record(")
        val eventsAt = text.indexOf("DomainEvents.of(")
        assertTrue("EngineController no longer records domain observations", recordAt >= 0)
        assertTrue("EngineController no longer produces new_domain events", eventsAt >= 0)
        assertTrue("events are produced before the observations are recorded", recordAt < eventsAt)

        val between = text.substring(recordAt, eventsAt)
        assertTrue(
            "the rows from DomainRepository.record go straight into DomainEvents.of " +
                "without passing through the app catalog, so every new_domain event " +
                "will name no app",
            between.contains("catalog.row("),
        )
    }
}
