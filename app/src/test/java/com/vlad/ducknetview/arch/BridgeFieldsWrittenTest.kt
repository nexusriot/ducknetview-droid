package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mutable field on `VpnBridge` must have a writer somewhere outside the
 * bridge itself.
 *
 * `currentNetworkLabel` did not. The service stamped it onto every new flow in
 * three places and nothing ever assigned it, so `ConnRow.network` was the empty
 * string for the whole life of the app: the detail pane said "Network: -", the
 * CSV export's column came out blank, and — the visible part — the `wlan0` and
 * `tun0` filter chips compared a real interface name against "" and emptied the
 * table whichever one was tapped. Two controls that could only ever produce
 * "No connections".
 *
 * This is the same failure [UiActionsWiredTest] guards for actions, one layer
 * down and on data rather than behaviour, which is why that test did not catch
 * it. A compiler cannot: a `var` with an initialiser and no assignment is
 * perfectly legal code.
 */
class BridgeFieldsWrittenTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val serviceFile: File get() = File(sourceRoot, "engine/vpn/DuckVpnService.kt")

    /** The bridge's public mutable fields, in declaration order. */
    private fun bridgeVars(): List<String> {
        val body = serviceFile.readText().substringAfter("object VpnBridge {")
        return Regex("""^\s*(?:@Volatile\s+)?var\s+([a-zA-Z][A-Za-z0-9_]*)""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .filter { it.first().isLowerCase() }
            .toList()
    }

    /**
     * Everything that could assign one, the bridge itself excluded. The
     * instrumented tests count: `captureOwnTraffic` exists only for them, and
     * routing the test process's own traffic through the TUN is the only way to
     * assert capture end to end.
     */
    private fun writerSources(): String = buildString {
        val roots = listOfNotNull(
            sourceRoot,
            sequenceOf(
                File("src/androidTest/java/com/vlad/ducknetview"),
                File("app/src/androidTest/java/com/vlad/ducknetview"),
                File("../app/src/androidTest/java/com/vlad/ducknetview"),
            ).firstOrNull { it.isDirectory },
        )
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.name != "DuckVpnService.kt" }
                .forEach { append(it.readText()).append('\n') }
        }
    }

    @Test
    fun `the bridge declares the fields this test expects to find`() {
        val vars = bridgeVars()
        assertTrue("found no mutable fields on VpnBridge at all: $vars", vars.size >= 5)
        assertTrue("currentNetworkLabel is gone from VpnBridge", vars.contains("currentNetworkLabel"))
    }

    @Test
    fun `every mutable bridge field is assigned by someone`() {
        val writers = writerSources()
        // A setter function counts: `blockedUids` is private and is written
        // through setBlocked(), which the ViewModel calls.
        val service = serviceFile.readText()

        val unwritten = bridgeVars().filter { name ->
            val assignedOutside = Regex("""VpnBridge\.$name\s*=""").containsMatchIn(writers)
            val assignedThroughSetter = Regex("""\b$name\s*=\s*\w""")
                .findAll(service.substringAfter("object VpnBridge {"))
                .any()
            !assignedOutside && !assignedThroughSetter
        }

        assertTrue(
            "VpnBridge fields that are read but never written, so they hold their " +
                "initialiser forever: $unwritten",
            unwritten.isEmpty(),
        )
    }

    @Test
    fun `the engine assigns the network label every poll`() {
        val controller = File(sourceRoot, "engine/EngineController.kt").readText()
        assertTrue(
            "nothing in EngineController sets VpnBridge.currentNetworkLabel, so every " +
                "flow will be stamped with an empty network name again",
            controller.contains("VpnBridge.currentNetworkLabel ="),
        )
    }
}
