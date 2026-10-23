package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.group.Grouping
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {

    @Test
    fun plainValuesAreNotQuoted() {
        assertEquals("tcp", Csv.field("tcp"))
        assertEquals("192.168.1.5:443", Csv.field("192.168.1.5:443"))
        assertEquals("", Csv.field(""))
    }

    @Test
    fun embeddedCommasAreQuoted() {
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("a,\"b,c\",d", Csv.line(listOf("a", "b,c", "d")))
    }

    @Test
    fun embeddedQuotesAreDoubled() {
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"\"\"\"", Csv.field("\""))
    }

    @Test
    fun embeddedNewlinesAreQuoted() {
        assertEquals("\"line1\nline2\"", Csv.field("line1\nline2"))
        assertEquals("\"cr\rlf\"", Csv.field("cr\rlf"))
    }

    @Test
    fun surroundingWhitespaceIsPreservedByQuoting() {
        assertEquals("\" padded \"", Csv.field(" padded "))
        assertEquals("\"trailing \"", Csv.field("trailing "))
    }

    @Test
    fun allThreeSpecialCharactersAtOnce() {
        assertEquals("\"a,\"\"b\"\"\nc\"", Csv.field("a,\"b\"\nc"))
    }

    @Test
    fun documentUsesCrLfAndKeepsHeaderFirst() {
        val out = Csv.of(listOf("a", "b"), listOf(listOf("1", "2"), listOf("3", "4")))
        assertEquals("a,b\r\n1,2\r\n3,4\r\n", out)
    }

    @Test
    fun headersOnlyAndRowsOnly() {
        assertEquals("a,b\r\n", Csv.of(listOf("a", "b"), emptyList()))
        assertEquals("1,2\r\n", Csv.of(emptyList(), listOf(listOf("1", "2"))))
        assertEquals("", Csv.of(emptyList(), emptyList()))
    }

    @Test
    fun connsExportHasOneRowPerConnectionAndMatchingWidths() {
        val out = Csv.conns(
            listOf(Fixtures.conn(key = "a"), Fixtures.conn(key = "b", remoteAddr = "1.1.1.1")),
            now = 5_000,
        )
        val lines = out.trimEnd('\r', '\n').split("\r\n")
        assertEquals(3, lines.size)
        val width = lines[0].split(",").size
        assertTrue(lines.drop(1).all { it.split(",").size == width })
        assertTrue(lines[0].startsWith("proto,local,remote"))
    }

    @Test
    fun appsExportQuotesLabelsWithCommas() {
        val out = Csv.apps(listOf(Fixtures.app(label = "Maps, Navigation")))
        assertTrue(out.contains("\"Maps, Navigation\""))
    }

    @Test
    fun servicesExportRendersExposureLowercase() {
        val out = Csv.services(listOf(Fixtures.service(bindAddr = "0.0.0.0", port = 22)))
        assertTrue(out.contains("tcp,0.0.0.0,22"))
        assertTrue(out.contains("exposed"))
    }

    /**
     * The closed table has columns no live row can fill, and the export used to
     * have no function for it at all: the Share button on a screen reading
     * "8 closed" wrote fourteen `established` rows, measured on the M10.
     */
    @Test
    fun closedExportCarriesTheNumbersOnlyAClosedSocketHas() {
        val out = Csv.closed(
            listOf(
                Fixtures.closed(
                    row = Fixtures.conn(state = ConnState.CLOSED, rttMillis = 42),
                    closedAt = 1_700_000_000_000L,
                    lifetimeMillis = 12_345L,
                    finalRx = 98_765L,
                    finalTx = 4_321L,
                )
            )
        )

        val lines = out.trimEnd().split(Csv.EOL)
        val header = lines.first().split(",")
        val row = lines[1].split(",")
        assertEquals(header.size, row.size)
        assertTrue(header.contains("closed_at"))
        assertTrue(header.contains("lifetime_ms"))
        assertEquals("1700000000000", row[header.indexOf("closed_at")])
        assertEquals("12345", row[header.indexOf("lifetime_ms")])
        // Lifetime totals, which is the point of the closed table.
        assertEquals("98765", row[header.indexOf("rx_bytes")])
        assertEquals("4321", row[header.indexOf("tx_bytes")])
        assertEquals("closed", row[header.indexOf("state")])
    }

    @Test
    fun closedExportIsHeaderOnlyWhenNothingHasClosed() {
        val out = Csv.closed(emptyList())

        assertEquals(1, out.trimEnd().split(Csv.EOL).size)
    }

    /** The grouped view had no export function either. */
    @Test
    fun groupsExportHasOneRowPerHostWithItsFoldedTotals() {
        val groups = Grouping.of(
            listOf(
                Fixtures.conn(key = "a", remoteAddr = "93.184.216.34", rxBytes = 10, txBytes = 1),
                Fixtures.conn(key = "b", remoteAddr = "93.184.216.34", rxBytes = 20, txBytes = 2),
                Fixtures.conn(key = "c", remoteAddr = "1.1.1.1", rxBytes = 5, txBytes = 5),
            )
        )

        val out = Csv.groups(groups)

        val lines = out.trimEnd().split(Csv.EOL)
        val header = lines.first().split(",")
        assertEquals(3, lines.size)
        assertTrue(header.contains("conns"))
        val folded = lines.drop(1).first { it.startsWith("93.184.216.34,") }.split(",")
        assertEquals("2", folded[header.indexOf("conns")])
        assertEquals("30", folded[header.indexOf("rx_bytes")])
        assertEquals("3", folded[header.indexOf("tx_bytes")])
    }

    @Test
    fun groupsExportQuotesTheAppListItJoins() {
        val out = Csv.groups(
            Grouping.of(listOf(Fixtures.conn(appLabel = "Maps, Navigation")))
        )

        assertTrue(out.contains("\"Maps, Navigation\""))
    }

    @Test
    fun eventsExportUsesTheModelRowShape() {
        val out = Csv.events(
            listOf(
                Event(
                    at = 12,
                    level = EventLevel.ALERT,
                    kind = EventKind.WATCHLIST_HIT,
                    subject = "8.8.8.8:443",
                    detail = "Browser, background",
                ),
            ),
        )
        assertEquals(
            "at,level,kind,subject,detail\r\n12,alert,watchlist_hit,8.8.8.8:443,\"Browser, background\"\r\n",
            out,
        )
    }
}
