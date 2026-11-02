package com.vlad.ducknetview.domain.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBudgetTest {

    private val video = AppBudget("com.example.video", 500_000_000L, block = true)
    private val maps = AppBudget("com.example.maps", 50_000_000L, block = false)

    @Test
    fun `encoding is pkg, cap and a block flag`() {
        assertEquals(
            setOf("com.example.maps|50000000|0", "com.example.video|500000000|1"),
            AppBudgets.encode(listOf(video, maps)),
        )
    }

    @Test
    fun `budgets round trip`() {
        assertEquals(listOf(maps, video), AppBudgets.decode(AppBudgets.encode(listOf(video, maps))))
        assertTrue(AppBudgets.decode(AppBudgets.encode(emptyList())).isEmpty())
    }

    @Test
    fun `decoding is sorted by package for a stable list`() {
        val decoded = AppBudgets.decode(setOf("z.app|10|0", "a.app|10|0", "m.app|10|1"))
        assertEquals(listOf("a.app", "m.app", "z.app"), decoded.map { it.packageName })
    }

    @Test
    fun `garbage entries cost only themselves`() {
        val set = setOf(
            "com.example.maps|50000000|0",
            "",
            "nonsense",
            "a|b",
            "a.b|12|0|extra",
            "a.b|twelve|0",
            "a.b|12|yes",
            "a.b|12|2",
            "|12|0",
            "a.b|-5|1",
            "a.b|0|1",
            "a b|12|0",
            "a.b|99999999999999999999|0",
        )
        assertEquals(listOf(maps), AppBudgets.decode(set))
    }

    @Test
    fun `entries that would not round trip are not written`() {
        val bad = listOf(
            AppBudget("has|pipe", 10, false),
            AppBudget("", 10, false),
            AppBudget("zero.cap", 0, true),
            AppBudget("neg.cap", -1, true),
        )
        assertTrue(AppBudgets.encode(bad).isEmpty())
        assertEquals(setOf("com.example.maps|50000000|0"), AppBudgets.encode(bad + maps))
    }

    @Test
    fun `a duplicate package keeps the tighter cap`() {
        val loose = AppBudget("dup.app", 1_000, block = false)
        val tight = AppBudget("dup.app", 100, block = false)
        assertEquals(listOf(tight), AppBudgets.decode(setOf("dup.app|1000|0", "dup.app|100|0")))
        assertEquals(setOf("dup.app|100|0"), AppBudgets.encode(listOf(loose, tight)))
    }

    @Test
    fun `on an equal cap the blocking budget wins`() {
        assertEquals(
            listOf(AppBudget("dup.app", 100, block = true)),
            AppBudgets.decode(setOf("dup.app|100|0", "dup.app|100|1")),
        )
    }

    @Test
    fun `breaches are budgets at or over their cap`() {
        val used = mapOf(
            "com.example.video" to 500_000_000L, // exactly at cap
            "com.example.maps" to 49_999_999L, // one byte under
        )
        assertEquals(listOf(video to 500_000_000L), AppBudgets.breaches(listOf(video, maps), used))
    }

    @Test
    fun `breaches report the bytes used, in budget order`() {
        val used = mapOf("com.example.video" to 900_000_000L, "com.example.maps" to 60_000_000L)
        assertEquals(
            listOf(maps to 60_000_000L, video to 900_000_000L),
            AppBudgets.breaches(listOf(maps, video), used),
        )
    }

    @Test
    fun `an app with no usage has not breached`() {
        assertTrue(AppBudgets.breaches(listOf(video, maps), emptyMap()).isEmpty())
        assertTrue(AppBudgets.breaches(emptyList(), mapOf("com.example.video" to Long.MAX_VALUE)).isEmpty())
    }
}
