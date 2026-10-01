package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.NameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvDomainsTest {

    private val row = DomainRow(
        name = "example.com",
        uid = 10100,
        appLabel = "Browser, Beta",
        packageName = "com.example.browser",
        source = NameSource.SNI,
        lookups = 4,
        firstSeen = 1000L,
        lastSeen = 2000L,
        addresses = listOf("1.1.1.1", "2.2.2.2"),
    )

    @Test
    fun `the header names every stored field`() {
        val lines = Csv.domains(listOf(row)).split(Csv.EOL)
        assertEquals(
            "name,uid,app,package,source,lookups,first_seen,last_seen,addresses",
            lines.first(),
        )
    }

    @Test
    fun `a label containing a comma is quoted rather than splitting the row`() {
        val line = Csv.domains(listOf(row)).split(Csv.EOL)[1]
        assertTrue(line.contains("\"Browser, Beta\""))
        assertTrue(line.contains("example.com,10100,"))
    }

    @Test
    fun `addresses travel as one space-separated cell`() {
        assertTrue(Csv.domains(listOf(row)).contains("1.1.1.1 2.2.2.2"))
    }

    @Test
    fun `the source is spelt the way the screen spells it`() {
        assertTrue(Csv.domains(listOf(row)).contains("SNI"))
    }

    @Test
    fun `an empty table is still a valid file with its header`() {
        assertEquals(2, Csv.domains(emptyList()).split(Csv.EOL).size)
    }
}
