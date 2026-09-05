package com.vlad.ducknetview.data.work

import com.vlad.ducknetview.domain.usage.UsageRollup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageRollupLogicTest {

    private val day = 1_700_000_000_000L

    private fun label(uid: Int) = "app$uid"

    @Test
    fun `counters are the sum over every uid`() {
        val usage = UsageRollupLogic.dayUsage(
            day,
            mapOf(1 to Pair(10L, 1L), 2 to Pair(5L, 4L)),
            ::label,
        )
        assertEquals(15L, usage.rx)
        assertEquals(5L, usage.tx)
        assertEquals(20L, usage.total)
    }

    @Test
    fun `the day epoch is carried through untouched`() {
        val usage = UsageRollupLogic.dayUsage(day, mapOf(1 to Pair(1L, 1L)), ::label)
        assertEquals(day, usage.dayEpoch)
    }

    @Test
    fun `each app is keyed by its label and holds rx plus tx`() {
        val usage = UsageRollupLogic.dayUsage(day, mapOf(7 to Pair(30L, 12L)), ::label)
        assertEquals(mapOf("app7" to 42L), usage.apps)
    }

    @Test
    fun `uids that share a label are folded together`() {
        val usage = UsageRollupLogic.dayUsage(
            day,
            mapOf(1 to Pair(10L, 0L), 2 to Pair(0L, 5L)),
        ) { "Shared" }
        assertEquals(mapOf("Shared" to 15L), usage.apps)
    }

    @Test
    fun `a uid with no traffic is not listed`() {
        val usage = UsageRollupLogic.dayUsage(
            day,
            mapOf(1 to Pair(0L, 0L), 2 to Pair(3L, 0L)),
            ::label,
        )
        assertEquals(setOf("app2"), usage.apps.keys)
    }

    @Test
    fun `an empty label falls back to the uid`() {
        val usage = UsageRollupLogic.dayUsage(day, mapOf(1000 to Pair(1L, 1L))) { "" }
        assertEquals(mapOf("uid 1000" to 2L), usage.apps)
    }

    @Test
    fun `a label lookup that throws falls back to the uid`() {
        val usage = UsageRollupLogic.dayUsage(day, mapOf(1000 to Pair(1L, 1L))) {
            throw IllegalStateException("package manager is gone")
        }
        assertEquals(mapOf("uid 1000" to 2L), usage.apps)
    }

    @Test
    fun `negative counters from a truncated bucket stream are floored at zero`() {
        val usage = UsageRollupLogic.dayUsage(day, mapOf(1 to Pair(-5L, 4L)), ::label)
        assertEquals(0L, usage.rx)
        assertEquals(4L, usage.tx)
    }

    @Test
    fun `hosts stay empty because the source cannot attribute them`() {
        val usage = UsageRollupLogic.dayUsage(day, mapOf(1 to Pair(9L, 9L)), ::label)
        assertTrue(usage.hosts.isEmpty())
    }

    @Test
    fun `the apps map is capped at the fifty heaviest`() {
        val perUid = (1..120).associate { it to Pair(it.toLong(), 0L) }
        val usage = UsageRollupLogic.dayUsage(day, perUid, ::label)
        assertEquals(UsageRollup.TOP_N, usage.apps.size)
        assertTrue(usage.apps.containsKey("app120"))
        assertFalse(usage.apps.containsKey("app1"))
    }

    @Test
    fun `the capped map keeps the heaviest values intact`() {
        val perUid = (1..120).associate { it to Pair(it.toLong(), 0L) }
        val usage = UsageRollupLogic.dayUsage(day, perUid, ::label)
        assertEquals(120L, usage.apps["app120"])
    }

    @Test
    fun `capping the apps map does not change the day counters`() {
        val perUid = (1..120).associate { it to Pair(1L, 1L) }
        val usage = UsageRollupLogic.dayUsage(day, perUid, ::label)
        assertEquals(120L, usage.rx)
        assertEquals(120L, usage.tx)
    }

    @Test
    fun `an empty source yields an empty day rather than nothing`() {
        val usage = UsageRollupLogic.dayUsage(day, emptyMap(), ::label)
        assertEquals(day, usage.dayEpoch)
        assertEquals(0L, usage.total)
        assertTrue(usage.apps.isEmpty())
    }

    @Test
    fun `the same input twice yields identical records`() {
        val perUid = mapOf(1 to Pair(10L, 2L), 2 to Pair(3L, 4L))
        val first = UsageRollupLogic.dayUsage(day, perUid, ::label)
        val second = UsageRollupLogic.dayUsage(day, perUid, ::label)
        assertEquals(first, second)
    }
}
