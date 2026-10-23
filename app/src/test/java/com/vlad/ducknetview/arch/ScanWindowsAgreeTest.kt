package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every caller of `PortScanner.scan` must hand it this device's own known
 * listeners.
 *
 * Two paths run the self-scan — the Services button through `EngineController`
 * and the background `ServiceScanWorker` that raises off-baseline alerts — and
 * a port set that differs between them is the seam failure of DESIGN §8a: the
 * worker would call a listener gone that the screen still lists, or never alert
 * on a port the screen shows. The port that started this is the app's own
 * metrics endpoint. The fast window is a fixed list of guesses, and the moment
 * the user moved the endpoint off its default it was not in that list, so the
 * app served an unauthenticated scrape to the whole LAN while the screen whose
 * whole job is to list this device's listeners said there were none.
 *
 * A source scan, not reflection: both calls compile perfectly with the argument
 * defaulted away, which is exactly how one of them would come to be missing it.
 */
class ScanWindowsAgreeTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    /** Files that start a self-scan, by the call rather than by name. */
    private fun scanCallSites(): List<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.name != "PortScanner.kt" }
        .filter { file ->
            val text = file.readText()
            Regex("""(scanner|PortScanner\(\))\s*\.\s*scan(At)?\s*\(""").containsMatchIn(text)
        }
        .toList()

    @Test
    fun `every self-scan call site is found`() {
        val names = scanCallSites().map { it.name }.sorted()
        assertTrue(
            "no self-scan call site found at all — this guard would pass vacuously: $names",
            names.isNotEmpty(),
        )
    }

    @Test
    fun `every self-scan passes the app's own metrics port`() {
        for (file in scanCallSites()) {
            val text = file.readText()
            assertTrue(
                "${file.name} starts a self-scan without passing the app's own listening " +
                    "port, so a metrics endpoint moved off the default port is invisible " +
                    "to that path",
                text.contains("metricsEnabled") && text.contains("metricsPort"),
            )
        }
    }
}
