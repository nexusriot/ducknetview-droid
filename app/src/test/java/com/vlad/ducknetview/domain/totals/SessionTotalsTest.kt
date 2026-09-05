package com.vlad.ducknetview.domain.totals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTotalsTest {

    @Test
    fun perAppDeltasAccumulate() {
        val t = SessionTotals()
        t.add(10, "Browser", 100, 50)
        t.add(10, "Browser", 400, 50)
        val app = t.app(10)!!
        assertEquals(500, app.rx)
        assertEquals(100, app.tx)
        assertEquals(600, app.total)
    }

    @Test
    fun grandTotalsFollowTheAppPath() {
        val t = SessionTotals()
        t.add(10, "A", 100, 20)
        t.add(11, "B", 5, 5)
        assertEquals(105, t.sessionRx)
        assertEquals(25, t.sessionTx)
        assertEquals(130, t.sessionTotal)
    }

    @Test
    fun hostTotalsDoNotDoubleCountTheGrandTotal() {
        val t = SessionTotals()
        t.addConn(10, "A", "8.8.8.8", 100, 20)
        assertEquals(100, t.sessionRx)
        assertEquals(20, t.sessionTx)
        assertEquals(120, t.host("8.8.8.8")!!.total)
        assertEquals(120, t.app(10)!!.total)
    }

    @Test
    fun zeroDeltasAreIgnored() {
        val t = SessionTotals()
        t.add(10, "A", 0, 0)
        t.addHost("8.8.8.8", 0, 0)
        assertEquals(0, t.appCount)
        assertEquals(0, t.hostCount)
        assertEquals(0, t.sessionTotal)
    }

    @Test
    fun emptyHostKeyIsIgnored() {
        val t = SessionTotals()
        t.addHost("", 10, 10)
        assertEquals(0, t.hostCount)
    }

    @Test
    fun aLabelArrivingLaterIsFilledIn() {
        val t = SessionTotals()
        t.add(10, "", 1, 1)
        t.add(10, "Browser", 1, 1)
        assertEquals("Browser", t.app(10)!!.label)
    }

    @Test
    fun topTalkersAreRankedByTotalBytes() {
        val t = SessionTotals()
        t.add(1, "small", 10, 0)
        t.add(2, "big", 1_000, 500)
        t.add(3, "medium", 100, 100)
        assertEquals(listOf("big", "medium", "small"), t.topApps(5).map { it.label })
        assertEquals(listOf("big"), t.topApps(1).map { it.label })
        assertTrue(t.topApps(0).isEmpty())
    }

    @Test
    fun topHostsAreRankedTheSameWay() {
        val t = SessionTotals()
        t.addHost("a", 1, 0)
        t.addHost("b", 9, 0)
        assertEquals(listOf("b", "a"), t.topHosts(5).map { it.label })
    }

    @Test
    fun evictionKeepsTheLoudestEntries() {
        val t = SessionTotals(max = 4, evictTo = 2)
        for (i in 1..5) t.addHost("host-$i", i.toLong(), 0)
        assertEquals(2, t.hostCount)
        assertEquals(listOf("host-5", "host-4"), t.topHosts(5).map { it.label })
        assertNull(t.host("host-1"))
        assertNotNull(t.host("host-5"))
    }

    @Test
    fun evictionAlsoAppliesToApps() {
        val t = SessionTotals(max = 3, evictTo = 2)
        for (i in 1..4) t.add(i, "app-$i", i.toLong(), 0)
        assertEquals(2, t.appCount)
        assertEquals(listOf("app-4", "app-3"), t.topApps(5).map { it.label })
        // The grand total keeps everything that was ever booked.
        assertEquals(10, t.sessionRx)
    }

    @Test
    fun clearResetsEverything() {
        val t = SessionTotals()
        t.addConn(10, "A", "8.8.8.8", 100, 100)
        t.clear()
        assertEquals(0, t.sessionTotal)
        assertEquals(0, t.appCount)
        assertEquals(0, t.hostCount)
    }
}
