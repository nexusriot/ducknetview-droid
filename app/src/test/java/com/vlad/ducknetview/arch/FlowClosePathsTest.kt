package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every path that retires a flow has to name its app and mark a block.
 *
 * The three ordinary teardowns (`closeTcp`, `closeUdp`, `closeIcmp`) resolve a
 * label through `VpnBridge.labelFor` before calling `FlowTable.close`. The
 * blocked-UDP path did not: it called `table.close(key, now, "", "")`
 * directly, so a QUIC flow dropped because its app was blocked landed in
 * closed history as "uid 10135" with `blocked = false` — no app name, and the
 * Conns sheet's own toggle would re-block it rather than unblock. It was
 * visible on a device the moment a browser was blocked, because a browser's
 * traffic is almost all QUIC.
 *
 * A source check rather than a behavioural one: the call lives inside
 * VpnService packet dispatch, which a JVM test cannot drive, and the mistake is
 * exactly "a caller passed the wrong arguments".
 */
class FlowClosePathsTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val service: File get() = File(sourceRoot, "engine/vpn/DuckVpnService.kt")

    @Test
    fun `the service file is where this test thinks it is`() {
        assertTrue(service.isFile)
    }

    @Test
    fun `no close call in the service discards the app label`() {
        val offenders = service.readText()
            .lineSequence()
            .withIndex()
            .filter { (_, line) -> line.contains("table.close(") }
            .filter { (_, line) -> Regex(""""\s*"\s*,\s*"\s*"""").containsMatchIn(line) }
            .map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
            .toList()

        assertTrue(
            "a flow is retired with empty label and package, so its row will name " +
                "no app: ${offenders.joinToString("; ")}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the blocked udp path marks the flow before closing it`() {
        val text = service.readText()
        // The UDP dispatch block that handles a blocked owner, up to its return.
        val blockedBranch = text
            .substringAfter("private fun dispatchUdp")
            .substringAfter("if (VpnBridge.isBlocked(flow.uid)) {")
            .substringBefore("return@synchronized null")

        assertTrue(
            "the blocked-UDP branch does not set flow.blocked, so the row it retires " +
                "will not say why it died",
            blockedBranch.contains("flow.blocked = true"),
        )
        assertTrue(
            "the blocked-UDP branch does not retire the flow through the labelling " +
                "helper, so its row will name no app",
            blockedBranch.contains("retire("),
        )
    }

    @Test
    fun `the blocked tcp path still goes through reject`() {
        val text = service.readText()
        val blockedBranch = text
            .substringAfter("private fun dispatchTcp")
            .substringAfter("if (VpnBridge.isBlocked(flow.uid)) {")
            .substringBefore("return@synchronized null")

        assertTrue(
            "the blocked-TCP branch no longer calls reject(), which is what sets " +
                "flow.blocked and sends the RST",
            blockedBranch.contains("reject()"),
        )
    }
}
