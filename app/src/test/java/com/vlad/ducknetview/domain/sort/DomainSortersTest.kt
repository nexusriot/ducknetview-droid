package com.vlad.ducknetview.domain.sort

import com.vlad.ducknetview.domain.model.DomainRow
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainSortersTest {

    private fun row(name: String, uid: Int = 10100, lookups: Int = 1, lastSeen: Long = 0L, app: String = "") =
        DomainRow(name = name, uid = uid, appLabel = app, lookups = lookups, lastSeen = lastSeen)

    private val rows = listOf(
        row("b.example.com", lookups = 5, lastSeen = 300L, app = "Mail"),
        row("a.example.com", lookups = 9, lastSeen = 100L, app = "Browser"),
        row("c.example.com", lookups = 1, lastSeen = 200L, app = "Store"),
    )

    @Test
    fun `the default column is the most recently seen first`() {
        val sorted = rows.sortedWith(Sorters.domains("seen", desc = true))
        assertEquals(listOf("b.example.com", "c.example.com", "a.example.com"), sorted.map { it.name })
    }

    @Test
    fun `sorting by lookups finds the chattiest name`() {
        assertEquals(
            "a.example.com",
            rows.sortedWith(Sorters.domains("lookups", desc = true)).first().name,
        )
    }

    @Test
    fun `sorting by name and by app both work and reverse`() {
        assertEquals(
            "a.example.com",
            rows.sortedWith(Sorters.domains("name", desc = false)).first().name,
        )
        assertEquals(
            "Browser",
            rows.sortedWith(Sorters.domains("app", desc = false)).first().appLabel,
        )
        assertEquals(
            "Store",
            rows.sortedWith(Sorters.domains("app", desc = true)).first().appLabel,
        )
    }

    @Test
    fun `equal values break the tie on a key that does not change between polls`() {
        // Without it, two equally chatty names swap places on every tick and
        // the table looks like it is vibrating.
        val tied = listOf(
            row("z.example.com", uid = 10200, lookups = 4),
            row("z.example.com", uid = 10100, lookups = 4),
        )
        val once = tied.sortedWith(Sorters.domains("lookups", desc = true))
        val twice = once.reversed().sortedWith(Sorters.domains("lookups", desc = true))
        assertEquals(once.map { it.key }, twice.map { it.key })
    }

    @Test
    fun `an unknown column falls back to the tie-break rather than throwing`() {
        assertEquals(3, rows.sortedWith(Sorters.domains("nonsense", desc = true)).size)
    }

    @Test
    fun `the screen's chips are the registry's columns`() {
        assertEquals(Sorters.DOMAIN_COLUMNS, Sorters.columnsFor("domains"))
    }
}
