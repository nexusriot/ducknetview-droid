package com.vlad.ducknetview.engine.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalIpTest {

    @Test
    fun `a plain IPv4 body is accepted`() {
        assertEquals("203.0.113.7", ExternalIpFetcher.parseIp("203.0.113.7"))
    }

    @Test
    fun `surrounding whitespace and newlines are trimmed`() {
        assertEquals("203.0.113.7", ExternalIpFetcher.parseIp("  203.0.113.7\n"))
    }

    @Test
    fun `an IPv6 body is accepted`() {
        assertEquals("2001:db8::1", ExternalIpFetcher.parseIp("2001:db8::1"))
    }

    @Test
    fun `a captive-portal HTML page is rejected instead of being shown as an IP`() {
        assertNull(ExternalIpFetcher.parseIp("<html><body>Sign in</body></html>"))
        assertNull(ExternalIpFetcher.parseIp("Please log in to continue"))
    }

    @Test
    fun `an out-of-range octet is rejected`() {
        assertNull(ExternalIpFetcher.parseIp("999.1.1.1"))
        assertNull(ExternalIpFetcher.parseIp("1.2.3"))
    }

    @Test
    fun `an empty or oversized body is rejected`() {
        assertNull(ExternalIpFetcher.parseIp(""))
        assertNull(ExternalIpFetcher.parseIp("   "))
        assertNull(ExternalIpFetcher.parseIp("1".repeat(500)))
    }

    @Test
    fun `a successful fetch caches the value and its timestamp`() = runBlocking {
        val f = ExternalIpFetcher(transport = { _, _ -> "198.51.100.9" })
        assertEquals("198.51.100.9", f.fetch())
        assertEquals("198.51.100.9", f.value)
        assertTrue(f.fetchedAt > 0L)
    }

    @Test
    fun `a fresh cached value is not re-fetched`() = runBlocking {
        var calls = 0
        val f = ExternalIpFetcher(transport = { _, _ -> calls++; "198.51.100.9" })
        f.fetch()
        f.refreshIfStale(maxAgeMs = 30_000L, now = f.fetchedAt + 1_000L)
        assertEquals(1, calls)
    }

    @Test
    fun `a stale cached value is re-fetched`() = runBlocking {
        var calls = 0
        val f = ExternalIpFetcher(transport = { _, _ -> calls++; "198.51.100.9" })
        f.fetch()
        f.refreshIfStale(maxAgeMs = 30_000L, now = f.fetchedAt + 60_000L)
        assertEquals(2, calls)
    }

    @Test
    fun `a failed refresh keeps the last known value rather than blanking the card`() =
        runBlocking {
            var first = true
            val f = ExternalIpFetcher(
                transport = { _, _ -> if (first) { first = false; "198.51.100.9" } else null },
            )
            f.fetch()
            val out = f.refreshIfStale(maxAgeMs = 0L)
            assertEquals("198.51.100.9", out)
        }

    @Test
    fun `a transport that throws yields null rather than propagating`() = runBlocking {
        val f = ExternalIpFetcher(transport = { _, _ -> throw IllegalStateException("boom") })
        assertNull(f.fetch())
        assertNull(f.value)
    }

    @Test
    fun `invalidate forgets the cached value`() = runBlocking {
        val f = ExternalIpFetcher(transport = { _, _ -> "198.51.100.9" })
        f.fetch()
        f.invalidate()
        assertNull(f.value)
        assertEquals(0L, f.fetchedAt)
    }
}
