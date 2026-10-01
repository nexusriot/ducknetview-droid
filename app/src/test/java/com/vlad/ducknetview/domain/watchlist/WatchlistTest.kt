package com.vlad.ducknetview.domain.watchlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistTest {

    @Test
    fun ipv4CidrBoundaries() {
        val w = Watchlist(listOf("45.9.0.0/16"))
        assertTrue(w.matches("45.9.0.0", null))
        assertTrue(w.matches("45.9.0.1", null))
        assertTrue(w.matches("45.9.255.255", null))
        assertFalse(w.matches("45.8.255.255", null))
        assertFalse(w.matches("45.10.0.0", null))
    }

    @Test
    fun nonByteAlignedPrefix() {
        val w = Watchlist(listOf("192.168.1.128/25"))
        assertFalse(w.matches("192.168.1.127", null))
        assertTrue(w.matches("192.168.1.128", null))
        assertTrue(w.matches("192.168.1.255", null))
        assertFalse(w.matches("192.168.2.128", null))
    }

    @Test
    fun singleHostPrefix() {
        val w = Watchlist(listOf("10.1.2.3/32"))
        assertTrue(w.matches("10.1.2.3", null))
        assertFalse(w.matches("10.1.2.4", null))
    }

    @Test
    fun zeroPrefixMatchesEveryAddressOfThatFamily() {
        val w = Watchlist(listOf("0.0.0.0/0"))
        assertTrue(w.matches("8.8.8.8", null))
        assertTrue(w.matches("192.168.1.1", null))
        assertFalse("a v4 CIDR must not swallow v6", w.matches("2001:db8::1", null))
    }

    @Test
    fun thirtyOneBitPrefix() {
        val w = Watchlist(listOf("10.0.0.2/31"))
        assertTrue(w.matches("10.0.0.2", null))
        assertTrue(w.matches("10.0.0.3", null))
        assertFalse(w.matches("10.0.0.1", null))
        assertFalse(w.matches("10.0.0.4", null))
    }

    @Test
    fun ipv6CidrBoundaries() {
        val w = Watchlist(listOf("2001:db8::/32"))
        assertTrue(w.matches("2001:db8::1", null))
        assertTrue(w.matches("2001:db8:ffff:ffff::1", null))
        assertFalse(w.matches("2001:db9::1", null))
        assertFalse(w.matches("2001:db7:ffff::1", null))
    }

    @Test
    fun ipv6NonByteAlignedPrefix() {
        val w = Watchlist(listOf("fe80::/10"))
        assertTrue(w.matches("fe80::1", null))
        assertTrue(w.matches("febf:ffff::1", null))
        assertFalse(w.matches("fec0::1", null))
        assertFalse(w.matches("fe7f::1", null))
    }

    @Test
    fun ipv6FullLengthPrefix() {
        val w = Watchlist(listOf("2606:4700::1111/128"))
        assertTrue(w.matches("2606:4700::1111", null))
        assertFalse(w.matches("2606:4700::1112", null))
    }

    @Test
    fun bareAddressEntries() {
        val w = Watchlist(listOf("8.8.8.8", "2001:db8::1"))
        assertTrue(w.matches("8.8.8.8", null))
        assertFalse(w.matches("8.8.4.4", null))
        assertTrue(w.matches("2001:0db8:0000:0000:0000:0000:0000:0001", null))
    }

    @Test
    fun ipv4MappedFormsAreTheSameAddress() {
        val w = Watchlist(listOf("10.0.0.0/8"))
        assertTrue(w.matches("::ffff:10.1.2.3", null))
    }

    @Test
    fun regexEntryTestsBothTheAddressAndTheHostname() {
        val w = Watchlist(listOf("""\.evil\.example$"""))
        assertTrue(w.matches("203.0.113.9", "host.evil.example"))
        assertFalse(w.matches("203.0.113.9", "host.good.example"))
        assertFalse(w.matches("203.0.113.9", null))
    }

    @Test
    fun regexIsCaseInsensitiveAndCanMatchTheAddressText() {
        val w = Watchlist(listOf("EVIL", "^203\\.0\\."))
        assertTrue(w.matches("198.51.100.1", "an-evil-host"))
        assertTrue(w.matches("203.0.113.9", null))
        assertFalse(w.matches("198.51.100.1", "fine"))
    }

    @Test
    fun invalidRegexIsReportedNotSwallowed() {
        val w = Watchlist(listOf("[unclosed", "8.8.8.8"))
        assertEquals(listOf("[unclosed"), w.invalid)
        assertTrue(w.matches("8.8.8.8", null))
        assertFalse(w.matches("1.1.1.1", null))
    }

    @Test
    fun badPrefixLengthIsInvalidRatherThanAUselessRegex() {
        val w = Watchlist(listOf("10.0.0.0/33", "2001:db8::/129"))
        assertEquals(listOf("10.0.0.0/33", "2001:db8::/129"), w.invalid)
        assertTrue(w.isEmpty)
    }

    /**
     * The watchlist is a security feature, so an entry that can never match has
     * to be able to say why. [Watchlist.invalid] already knew which entries
     * those were; nothing could ask it what was wrong with them.
     */
    @Test
    fun problemExplainsWhyAnEntryCannotBeUsed() {
        assertTrue(Watchlist.problem("[unclosed")!!.contains("regex"))
        assertTrue(Watchlist.problem("10.0.0.0/33")!!.contains("CIDR"))
        assertTrue(Watchlist.problem("2001:db8::/129")!!.contains("CIDR"))
    }

    @Test
    fun problemPassesEveryFormTheWatchlistAccepts() {
        assertNull(Watchlist.problem("10.0.0.0/8"))
        assertNull(Watchlist.problem("1.1.1.1"))
        assertNull(Watchlist.problem("2001:db8::/32"))
        assertNull(Watchlist.problem("^cdn.*\\.example\\.com$"))
        // Blank is not an error; it is simply not an entry.
        assertNull(Watchlist.problem("   "))
    }

    /**
     * The two must not drift: whatever `problem` calls broken is exactly what
     * the parse refuses to use.
     */
    @Test
    fun problemAgreesWithInvalid() {
        val entries = listOf(
            "[unclosed", "10.0.0.0/33", "10.0.0.0/8", "1.1.1.1",
            "2001:db8::/129", "^cdn", "a(b",
        )
        val w = Watchlist(entries)
        assertEquals(entries.filter { Watchlist.problem(it) != null }, w.invalid)
    }

    @Test
    fun blankEntriesAreIgnored() {
        val w = Watchlist(listOf("", "   ", "\t"))
        assertTrue(w.isEmpty)
        assertTrue(w.invalid.isEmpty())
        assertFalse(w.matches("8.8.8.8", null))
    }

    @Test
    fun emptyWatchlistNeverMatches() {
        assertTrue(Watchlist.EMPTY.isEmpty)
        assertFalse(Watchlist.EMPTY.matches("8.8.8.8", "anything"))
    }

    @Test
    fun entriesAreKeptForRoundTripping() {
        val w = Watchlist(listOf(" 8.8.8.8 ", "45.9.0.0/16"))
        assertEquals(listOf("8.8.8.8", "45.9.0.0/16"), w.entries)
    }

    @Test
    fun addressDetectionForTheToggleAction() {
        assertTrue(Watchlist.isAddress("8.8.8.8"))
        assertTrue(Watchlist.isAddress("2001:db8::1"))
        assertFalse(Watchlist.isAddress("example.com"))
        assertFalse(Watchlist.isAddress("8.8.8.8/32"))
    }

    @Test
    fun unparseableRemoteFallsThroughToRegexOnly() {
        val w = Watchlist(listOf("10.0.0.0/8"))
        assertFalse(w.matches("", null))
        assertFalse(w.matches("not-an-ip", null))
    }
}
