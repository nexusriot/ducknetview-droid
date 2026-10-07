package com.vlad.ducknetview.domain.filter

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.domain.search.Search
import com.vlad.ducknetview.domain.sort.Sorters
import com.vlad.ducknetview.ui.QuickFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The closed-connection history used to be handed to the screen straight off
 * the snapshot, so the chips, the search box and the sort header were all inert
 * over it: on a real device, searching "Chrome" above a closed table full of
 * Chrome rows answered "0 matches" and removed nothing.
 */
class ClosedFiltersTest {

    private val chrome = Fixtures.closed(
        row = Fixtures.conn(
            key = "a", proto = Proto.TCP, remoteAddr = "172.217.115.4",
            appLabel = "Chrome", uid = 10135, state = ConnState.CLOSED, network = "wlan0",
            rttMillis = 30,
        ),
        closedAt = 300L,
        lifetimeMillis = 50L,
        finalRx = 10L,
        finalTx = 1L,
    )
    private val quic = Fixtures.closed(
        row = Fixtures.conn(
            key = "b", proto = Proto.UDP, remoteAddr = "2a00:1450::1",
            appLabel = "Chrome", uid = 10135, state = ConnState.CLOSED, network = "wlan0",
            rttMillis = 90,
        ),
        closedAt = 200L,
        lifetimeMillis = 500L,
        finalRx = 5L,
        finalTx = 2L,
    )
    private val telegram = Fixtures.closed(
        row = Fixtures.conn(
            key = "c", proto = Proto.TCP, remoteAddr = "149.154.167.51",
            appLabel = "Telegram", uid = 10200, state = ConnState.CLOSED, network = "rmnet0",
            rttMillis = 150,
        ),
        closedAt = 100L,
        lifetimeMillis = 5_000L,
        finalRx = 1L,
        finalTx = 3L,
    )
    private val rows = listOf(chrome, quic, telegram)

    private fun keys(list: List<com.vlad.ducknetview.domain.model.ClosedConn>) = list.map { it.row.key }

    @Test
    fun `no chip set returns the history untouched`() {
        assertEquals(rows, Filters.closed(rows, QuickFilters(), emptySet()))
    }

    @Test
    fun `the proto chip reaches the closed table`() {
        assertEquals(
            listOf("a", "c"),
            keys(Filters.closed(rows, QuickFilters(proto = ProtoFilter.TCP), emptySet())),
        )
    }

    @Test
    fun `the address-family chip reaches the closed table`() {
        assertEquals(
            listOf("b"),
            keys(Filters.closed(rows, QuickFilters(ipVersion = IpVersionFilter.V6), emptySet())),
        )
    }

    @Test
    fun `the network chip reaches the closed table`() {
        assertEquals(
            listOf("a", "b"),
            keys(Filters.closed(rows, QuickFilters(network = "wlan0"), emptySet())),
        )
    }

    @Test
    fun `the uid chip reaches the closed table`() {
        assertEquals(
            listOf("c"),
            keys(Filters.closed(rows, QuickFilters(uid = 10200), emptySet())),
        )
    }

    @Test
    fun `a state chip cannot empty a table in which every row is closed`() {
        // "established" over the closed history would otherwise match nothing
        // at all, which reads as a bug rather than as a filter.
        assertEquals(
            rows,
            Filters.closed(rows, QuickFilters(state = StateFilter.ESTABLISHED), emptySet()),
        )
    }

    @Test
    fun `search matches closed rows by app label`() {
        val matcher = Search.compile("chrome").matcher!!
        val result = Search.apply(rows, matcher, SearchMode.FILTER) { c ->
            listOf(c.row.local, c.row.remote, c.row.appLabel, c.row.service, c.row.proto.toString())
        }
        assertEquals(listOf("a", "b"), keys(result.items))
        assertEquals(2, result.matchCount)
    }

    @Test
    fun `search matches closed rows by remote address`() {
        val matcher = Search.compile("149.154").matcher!!
        val result = Search.apply(rows, matcher, SearchMode.FILTER) { c ->
            listOf(c.row.local, c.row.remote, c.row.appLabel, c.row.service, c.row.proto.toString())
        }
        assertEquals(listOf("c"), keys(result.items))
    }

    // ---------------- sorting under the live table's chips ----------------

    @Test
    fun `age sorts closed rows by lifetime, not by time since the last packet`() {
        // Every row's lastSeen is its close time, so an ageMillis sort would
        // just reproduce close order; lifetime is the figure the table prints.
        assertEquals(
            listOf("c", "b", "a"),
            keys(
                rows.sortedWith(
                    Sorters.closedByConnColumn("age", desc = true, mode = ThroughputMode.RATE)
                )
            ),
        )
    }

    @Test
    fun `rx and tx sort by the final totals`() {
        assertEquals(
            listOf("a", "b", "c"),
            keys(
                rows.sortedWith(
                    Sorters.closedByConnColumn("rx", desc = true, mode = ThroughputMode.RATE)
                )
            ),
        )
        assertEquals(
            listOf("c", "b", "a"),
            keys(
                rows.sortedWith(
                    Sorters.closedByConnColumn("tx", desc = true, mode = ThroughputMode.RATE)
                )
            ),
        )
    }

    @Test
    fun `a conn column delegates to the live comparator`() {
        assertEquals(
            listOf("c", "a", "b"),
            keys(
                rows.sortedWith(
                    Sorters.closedByConnColumn("app", desc = true, mode = ThroughputMode.RATE)
                )
            ),
        )
    }

    @Test
    fun `every live sort chip orders the closed table too`() {
        // "state" is the one exception, and not a gap: FlowTable.close stamps
        // every retired flow CLOSED, so the column holds one value for the
        // whole table and no comparator can reorder it. The state filter chip
        // is skipped over this table for the same reason.
        assertEquals(1, rows.map { it.row.state }.distinct().size)
        val sortable = com.vlad.ducknetview.ui.screens.CONN_SORT_COLUMNS - "state"
        for (col in sortable) {
            assertTrue(
                "closed table ignores the \"$col\" chip the screen is showing",
                rows.sortedWith(Sorters.closedByConnColumn(col, false, ThroughputMode.RATE)) !=
                    rows.sortedWith(Sorters.closedByConnColumn(col, true, ThroughputMode.RATE)),
            )
        }
    }
}
