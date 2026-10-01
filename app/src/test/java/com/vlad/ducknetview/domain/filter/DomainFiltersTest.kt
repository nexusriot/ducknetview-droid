package com.vlad.ducknetview.domain.filter

import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.ui.QuickFilters
import org.junit.Assert.assertEquals
import org.junit.Test

class DomainFiltersTest {

    private fun row(name: String, uid: Int) = DomainRow(name = name, uid = uid)

    private val rows = listOf(
        row("user.example.com", 10100),
        row("system.example.com", 1000),
    )

    private val userUids = setOf(10100)

    @Test
    fun `no filters means no copying`() {
        assertEquals(rows, Filters.domains(rows, QuickFilters(), userUids))
    }

    @Test
    fun `the user-apps chip drops system owners`() {
        val filtered = Filters.domains(rows, QuickFilters(userAppsOnly = true), userUids)
        assertEquals(listOf("user.example.com"), filtered.map { it.name })
    }

    @Test
    fun `a uid filter narrows to one app, which is how the detail pane drills through`() {
        val filtered = Filters.domains(rows, QuickFilters(uid = 1000), userUids)
        assertEquals(listOf("system.example.com"), filtered.map { it.name })
    }

    @Test
    fun `a chip that cannot mean anything for a name does not silently drop rows`() {
        // A name has no protocol. Applying the protocol chip here would empty
        // the table for a reason nothing on screen explains.
        val filtered = Filters.domains(rows, QuickFilters(proto = ProtoFilter.UDP), userUids)
        assertEquals(rows, filtered)
    }
}
