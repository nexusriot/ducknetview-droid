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

    /**
     * Read off the tablet: a field holding twenty digits was refused with "not
     * a whole number", which it plainly is. The refusal is right and the reason
     * was not, and this is the one function in the app whose entire job is
     * saying why something was refused.
     */
    @Test
    fun `a run of digits too long to store is refused as too large, not as gibberish`() {
        val problem = numberProblem("99999999999999999999", anyValue)

        assertNotNull(problem)
        assertTrue(
            "a number too big to store was called not a number: $problem",
            !problem!!.contains("not a whole number"),
        )
        assertTrue("the message does not say how large is too large: $problem", problem.contains("above"))
    }

    @Test
    fun `letters are reported`() {
        assertNotNull(numberProblem("abc", anyValue))
        assertTrue(numberProblem("abc", anyValue)!!.contains("not a whole number"))
        assertTrue(numberProblem("12abc", anyValue)!!.contains("not a whole number"))
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
