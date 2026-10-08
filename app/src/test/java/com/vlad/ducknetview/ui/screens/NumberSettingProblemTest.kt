package com.vlad.ducknetview.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert thresholds used to store `toLongOrNull() ?: 0L`, and 0 is
 * documented on that screen as "disables the rule". So a value too large to
 * parse switched an alert off while the field went on showing what was typed —
 * no error, and no way to tell, because the field never re-seeds. On the test
 * tablet a box reading 999999999999999999999 was backed by a stored 0, which
 * only became visible after leaving the screen and coming back.
 */
class NumberSettingProblemTest {

    private val anyValue = Long.MAX_VALUE

    @Test
    fun `an ordinary number is accepted`() {
        assertNull(numberProblem("4096", anyValue))
        assertNull(numberProblem("0", anyValue))
        assertNull(numberProblem("  512  ", anyValue))
    }

    @Test
    fun `an empty field is not an error because clearing it means zero`() {
        assertNull(numberProblem("", anyValue))
        assertNull(numberProblem("   ", anyValue))
    }

    @Test
    fun `a value too large for Long is reported rather than silently becoming zero`() {
        val problem = numberProblem("999999999999999999999", anyValue)
        assertNotNull("the overflowing value the tablet accepted was not reported", problem)
        assertTrue(
            "the message does not say the setting is unchanged: $problem",
            problem!!.contains("previous value"),
        )
    }

    @Test
    fun `letters are reported`() {
        assertNotNull(numberProblem("abc", anyValue))
    }

    /**
     * `alertRttMs`, `alertConnGrowthPolls` and `alertFanoutHosts` are Int-typed
     * and were assigned `v.toInt()`, which truncates the low 32 bits instead of
     * clamping: 4294967296 became 0 (rule off) and 2147483648 became negative
     * (also off, since every rule is gated on `> 0`). Both are silent.
     */
    @Test
    fun `a value past Int range is reported for an Int-typed setting`() {
        val max = Int.MAX_VALUE.toLong()
        assertNull(numberProblem("2147483647", max))
        assertNotNull("2^31 would have truncated to a negative", numberProblem("2147483648", max))
        assertNotNull("2^32 would have truncated to zero", numberProblem("4294967296", max))
    }

    @Test
    fun `the truncation these bounds prevent really is silent`() {
        // The arithmetic the bound exists to stop, stated so the reason the
        // bound matters does not have to be taken on trust.
        assertEquals(0, 4294967296L.toInt())
        assertEquals(Int.MIN_VALUE, 2147483648L.toInt())
        assertTrue("a truncated threshold is <= 0, which every rule reads as off", 4294967296L.toInt() <= 0)
    }

    @Test
    fun `a port past the port range is reported`() {
        assertNull(numberProblem("9187", 65535L))
        assertNull(numberProblem("65535", 65535L))
        assertNotNull(numberProblem("65536", 65535L))
    }

    @Test
    fun `a negative value is reported`() {
        assertNotNull(numberProblem("-1", anyValue))
    }
}
