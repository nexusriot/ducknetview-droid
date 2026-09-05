package com.vlad.ducknetview.domain.rdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RdnsCacheTest {

    @Test
    fun unknownAddressReturnsNull() {
        val c = RdnsCache()
        assertNull(c.get("8.8.8.8"))
        assertFalse(c.contains("8.8.8.8"))
        assertEquals(0, c.size)
    }

    @Test
    fun resolvedNameIsRemembered() {
        val c = RdnsCache()
        c.put("8.8.8.8", "dns.google")
        assertEquals("dns.google", c.get("8.8.8.8"))
        assertTrue(c.contains("8.8.8.8"))
    }

    @Test
    fun emptyStringMeansResolvedWithNoPtr() {
        val c = RdnsCache()
        c.put("203.0.113.1", "")
        assertEquals("", c.get("203.0.113.1"))
        assertTrue("a negative answer must still count as cached", c.contains("203.0.113.1"))
    }

    @Test
    fun displayFallsBackToTheAddress() {
        val c = RdnsCache()
        c.put("8.8.8.8", "dns.google")
        c.put("203.0.113.1", "")
        assertEquals("dns.google", c.display("8.8.8.8"))
        assertEquals("203.0.113.1", c.display("203.0.113.1"))
        assertEquals("1.1.1.1", c.display("1.1.1.1"))
    }

    @Test
    fun emptyKeysAreRejected() {
        val c = RdnsCache()
        c.put("", "nowhere")
        assertEquals(0, c.size)
    }

    @Test
    fun capacityIsBounded() {
        val c = RdnsCache(max = 10)
        for (i in 1..50) c.put("10.0.0.$i", "host$i")
        assertEquals(10, c.size)
        assertTrue(c.contains("10.0.0.50"))
        assertFalse(c.contains("10.0.0.1"))
    }

    @Test
    fun recentlyUsedEntriesSurviveEviction() {
        val c = RdnsCache(max = 3)
        c.put("a", "1")
        c.put("b", "2")
        c.put("c", "3")
        c.get("a")
        c.put("d", "4")
        assertTrue("the least recently used entry goes first", c.contains("a"))
        assertFalse(c.contains("b"))
        assertEquals(3, c.size)
    }

    @Test
    fun retainDropsAddressesThatLeftTheTables() {
        val c = RdnsCache()
        c.put("a", "1")
        c.put("b", "2")
        c.put("c", "3")
        c.retain(setOf("b", "zzz"))
        assertEquals(1, c.size)
        assertTrue(c.contains("b"))
        assertFalse(c.contains("a"))
    }

    @Test
    fun retainOnAnEmptyLiveSetClearsTheCache() {
        val c = RdnsCache()
        c.put("a", "1")
        c.retain(emptySet())
        assertEquals(0, c.size)
    }

    @Test
    fun clearAndSnapshot() {
        val c = RdnsCache()
        c.put("a", "1")
        c.put("b", "")
        assertEquals(mapOf("a" to "1", "b" to ""), c.snapshot())
        c.clear()
        assertTrue(c.snapshot().isEmpty())
    }

    @Test
    fun defaultCapacityMatchesTheDocumentedBound() {
        assertEquals(4096, RdnsCache.MAX_ENTRIES)
    }
}
