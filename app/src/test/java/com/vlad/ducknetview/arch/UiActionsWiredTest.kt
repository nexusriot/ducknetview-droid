package com.vlad.ducknetview.arch

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the failure this project keeps hitting: an action is
 * implemented in the ViewModel, the docs describe the feature, and no control
 * anywhere in the UI ever calls it, so the feature is unreachable and nothing
 * fails. It happened to `openSnapshot`, `requestWifiPermission` and the
 * highlight-mode plumbing before this test existed.
 *
 * This is a source-level check on purpose: reflection can prove a method
 * exists, not that anything invokes it.
 */
class UiActionsWiredTest {

    private val sourceRoot: File = sequenceOf(
        File("src/main/java/com/vlad/ducknetview"),
        File("app/src/main/java/com/vlad/ducknetview"),
        File("../app/src/main/java/com/vlad/ducknetview"),
    ).firstOrNull { it.isDirectory }
        ?: error("could not locate the main source root from ${File(".").absolutePath}")

    private val contract: File get() = File(sourceRoot, "ui/UiContract.kt")

    /** Members declared on the UiActions interface, in declaration order. */
    private fun declaredActions(): List<String> {
        val text = contract.readText()
        val body = text.substringAfter("interface UiActions {").substringBeforeLast("}")
        return Regex("""^\s*fun\s+([a-zA-Z][A-Za-z0-9_]*)\s*\(""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
    }

    /** Everything a screen, component or the activity could call an action from. */
    private fun callSites(): String = buildString {
        val roots = listOf(File(sourceRoot, "ui"), File(sourceRoot, "MainActivity.kt"))
        for (root in roots) {
            if (root.isFile) {
                append(root.readText())
                continue
            }
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                // The contract itself only declares; it never calls.
                .filter { it.name != "UiContract.kt" }
                .forEach { append(it.readText()).append('\n') }
        }
    }

    @Test
    fun theContractIsFoundAndNonTrivial() {
        assertTrue("UiContract.kt not found at ${contract.absolutePath}", contract.isFile)
        assertTrue("expected a populated UiActions interface", declaredActions().size > 20)
    }

    @Test
    fun everyUiActionHasACallerInTheUi() {
        val haystack = callSites()
        // Matches actions.foo(, actions.foo { (trailing lambda), actions::foo
        // and vm::foo, with a word boundary so setSort does not satisfy
        // setSortColumn.
        val unwired = declaredActions().filter { name ->
            val pattern = "(actions|vm)(\\.|::)" + Regex.escape(name) + "\\b"
            !Regex(pattern).containsMatchIn(haystack)
        }
        assertTrue(
            "These UiActions members are declared but nothing in ui/ or MainActivity calls " +
                "them, so the features behind them are unreachable: $unwired",
            unwired.isEmpty(),
        )
    }

    @Test
    fun theViewModelImplementsEveryDeclaredAction() {
        val vm = File(sourceRoot, "ui/MainViewModel.kt").readText()
        val missing = declaredActions().filter { !vm.contains("override fun $it(") }
        assertTrue("MainViewModel does not override: $missing", missing.isEmpty())
    }
}
