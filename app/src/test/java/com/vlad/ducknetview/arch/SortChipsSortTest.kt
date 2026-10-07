package com.vlad.ducknetview.arch

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.domain.sort.Sorters
import com.vlad.ducknetview.ui.screens.APP_SORT_COLUMNS
import com.vlad.ducknetview.ui.screens.CONN_SORT_COLUMNS
import com.vlad.ducknetview.ui.screens.SERVICE_SORT_COLUMNS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every sort chip a screen renders must reach a comparator.
 *
 * [Sorters] answers an unknown column with its tie-break alone, so a chip whose
 * name the comparator does not recognise still highlights, still flips its
 * arrow and still re-sorts the table — by row key, which looks like an
 * arbitrary order rather than a broken control. Two chips shipped that way: the
 * Conns table's "app" (the comparator only knew the TUI's name for it,
 * "process") and the Services table's "seen".
 *
 * The check is behavioural rather than a list comparison, because the
 * `*_COLUMNS` registries are hand-written too and could drift from the `when`
 * they are supposed to describe.
 */
class SortChipsSortTest {

    private val mode = ThroughputMode.RATE

    /**
     * Rows that differ in every sortable field.
     *
     * The check is that flipping the sort direction changes the order: an
     * unrecognised column makes [Sorters] hand back its tie-break alone, which
     * ignores `desc` entirely, so ascending and descending come back identical.
     * Comparing against a crafted "key order" instead would pass by coincidence
     * whenever a column happened to agree with the row keys — which is how
     * "app" went unnoticed.
     */
    private val conns = listOf(
        Fixtures.conn(
            key = "c3", proto = Proto.TCP, localPort = 3, remoteAddr = "10.0.0.1",
            state = ConnState.CLOSING, uid = 3, appLabel = "aaa", rxBps = 1, txBps = 1,
            rxBytes = 1, txBytes = 1, rttMillis = 1, firstSeen = 300L,
        ),
        Fixtures.conn(
            key = "c2", proto = Proto.UDP, localPort = 2, remoteAddr = "20.0.0.1",
            state = ConnState.ESTABLISHED, uid = 2, appLabel = "mmm", rxBps = 2, txBps = 2,
            rxBytes = 2, txBytes = 2, rttMillis = 2, firstSeen = 200L,
        ),
        Fixtures.conn(
            key = "c1", proto = Proto.ICMP, localPort = 1, remoteAddr = "30.0.0.1",
            state = ConnState.SYN_SENT, uid = 1, appLabel = "zzz", rxBps = 3, txBps = 3,
            rxBytes = 3, txBytes = 3, rttMillis = 3, firstSeen = 100L,
        ),
    )

    private val apps = listOf(
        Fixtures.app(uid = 3, label = "aaa", connCount = 1, rxBps = 1, txBps = 1, todayRx = 1),
        Fixtures.app(uid = 2, label = "mmm", connCount = 2, rxBps = 2, txBps = 2, todayRx = 2),
        Fixtures.app(uid = 1, label = "zzz", connCount = 3, rxBps = 3, txBps = 3, todayRx = 3),
    )

    private val services = listOf(
        Fixtures.service(
            proto = Proto.TCP, port = 9, service = "aaa",
            exposure = Exposure.EXPOSED, lastSeen = 100L,
        ),
        Fixtures.service(
            proto = Proto.UDP, port = 5, service = "mmm",
            exposure = Exposure.LAN, lastSeen = 200L,
        ),
    )

    @Test
    fun `every conns sort chip actually orders the table`() {
        for (col in CONN_SORT_COLUMNS) {
            assertTrue(
                "conns sort chip \"$col\" reaches no comparator: ascending and " +
                    "descending give the same order, so the chip only flips its arrow",
                conns.sortedWith(Sorters.conns(col, false, mode, 1_000L)) !=
                    conns.sortedWith(Sorters.conns(col, true, mode, 1_000L)),
            )
        }
        assertEquals(
            conns.sortedWith(Sorters.conns("no-such-column", false, mode, 1_000L)),
            conns.sortedWith(Sorters.conns("no-such-column", true, mode, 1_000L)),
        )
    }

    @Test
    fun `every apps sort chip actually orders the table`() {
        for (col in APP_SORT_COLUMNS) {
            assertTrue(
                "apps sort chip \"$col\" reaches no comparator",
                apps.sortedWith(Sorters.apps(col, false, mode)) !=
                    apps.sortedWith(Sorters.apps(col, true, mode)),
            )
        }
    }

    @Test
    fun `every services sort chip actually orders the table`() {
        for (col in SERVICE_SORT_COLUMNS) {
            assertTrue(
                "services sort chip \"$col\" reaches no comparator",
                services.sortedWith(Sorters.services(col, false)) !=
                    services.sortedWith(Sorters.services(col, true)),
            )
        }
    }

    @Test
    fun `every chip a screen shows is also declared in the column registry`() {
        assertTrue(
            "conns chips missing from Sorters.CONN_COLUMNS: " +
                (CONN_SORT_COLUMNS - Sorters.CONN_COLUMNS.toSet()),
            Sorters.CONN_COLUMNS.containsAll(CONN_SORT_COLUMNS),
        )
        assertTrue(
            "apps chips missing from Sorters.APP_COLUMNS: " +
                (APP_SORT_COLUMNS - Sorters.APP_COLUMNS.toSet()),
            Sorters.APP_COLUMNS.containsAll(APP_SORT_COLUMNS),
        )
        assertTrue(
            "services chips missing from Sorters.SERVICE_COLUMNS: " +
                (SERVICE_SORT_COLUMNS - Sorters.SERVICE_COLUMNS.toSet()),
            Sorters.SERVICE_COLUMNS.containsAll(SERVICE_SORT_COLUMNS),
        )
    }

    @Test
    fun `app is the same order as the TUI's process column`() {
        assertEquals(
            conns.sortedWith(Sorters.conns("process", desc = false, mode = mode, now = 0L)),
            conns.sortedWith(Sorters.conns("app", desc = false, mode = mode, now = 0L)),
        )
    }

    @Test
    fun `seen orders services by when they were last answered`() {
        assertEquals(
            listOf(5, 9),
            services.sortedWith(Sorters.services("seen", desc = true)).map { it.port },
        )
    }
}
