package com.vlad.ducknetview.domain.alerts

import com.vlad.ducknetview.domain.Fixtures
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.usage.QuotaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget rules, which differ from every other threshold on that screen in
 * one way worth testing: the subject does not go away. A connection breaching
 * a rate threshold closes; a cap sat on top of stays breached for the rest of
 * the month, so a plain per-subject cooldown would produce a notification a
 * minute until the cycle rolled over.
 */
class QuotaAlertTest {

    private val rules = AlertRules()

    private fun quota(usedPercent: Int, dayOfCycle: Int = 11, cycleDays: Int = 31) = QuotaStatus(
        capBytes = 10_000L,
        usedBytes = 100L * usedPercent,
        cycleStartDay = 20_000L,
        cycleEndDay = 20_000L + cycleDays,
        todayDay = 20_000L + dayOfCycle - 1,
    )

    private fun settings(percent: Int = 80, projected: Boolean = false) =
        AppSettings(alertQuotaPercent = percent, alertQuotaProjected = projected)

    private fun evaluate(q: QuotaStatus, s: AppSettings, now: Long): List<Event> =
        rules.evaluate(Fixtures.snapshot(atMillis = now, quota = q), s, now)

    private fun of(events: List<Event>, kind: EventKind) = events.filter { it.kind == kind }

    @Test
    fun `nothing fires without a cap`() {
        val out = evaluate(QuotaStatus(), settings(), now = 1_000L)
        assertTrue("an unconfigured budget produced $out", out.isEmpty())
    }

    @Test
    fun `nothing fires below the threshold`() {
        val out = of(evaluate(quota(70), settings(80), 1_000L), EventKind.QUOTA_PERCENT)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `crossing the threshold is reported`() {
        val out = of(evaluate(quota(85), settings(80), 1_000L), EventKind.QUOTA_PERCENT)
        assertEquals(1, out.size)
        assertEquals(EventLevel.WARN, out[0].level)
        assertTrue("no percentage in: ${out[0].detail}", out[0].detail.contains("85%"))
    }

    @Test
    fun `being over the cap is an alert, not a warning`() {
        val out = of(evaluate(quota(120), settings(80), 1_000L), EventKind.QUOTA_PERCENT)
        assertEquals(EventLevel.ALERT, out[0].level)
    }

    @Test
    fun `sitting on the cap does not report every poll`() {
        evaluate(quota(85), settings(80), 1_000L)
        val out = of(evaluate(quota(86), settings(80), 2_000L), EventKind.QUOTA_PERCENT)
        assertTrue("a second poll in the same band reported again: $out", out.isEmpty())
    }

    @Test
    fun `a standing breach stays quiet long past the refire window`() {
        // The property that matters: a cap crossed on the 10th must not
        // produce a line every minute until the cycle rolls over.
        evaluate(quota(85), settings(80), 1_000L)
        var fired = 0
        for (i in 1..200) {
            val now = 1_000L + i * AlertRules.REFIRE_MILLIS
            fired += of(evaluate(quota(85), settings(80), now), EventKind.QUOTA_PERCENT).size
        }
        assertEquals("a standing breach reported $fired times", 0, fired)
    }

    @Test
    fun `each ten percent band is reported once`() {
        evaluate(quota(85), settings(80), 1_000L)
        val next = of(
            evaluate(quota(95), settings(80), 1_000L + AlertRules.REFIRE_MILLIS * 2),
            EventKind.QUOTA_PERCENT,
        )
        assertEquals("climbing into a new band said nothing: $next", 1, next.size)
        assertTrue(next[0].detail.contains("95%"))
    }

    @Test
    fun `the projection rule warns before the cap is reached`() {
        // Day 11 of 31 with 55% gone projects to 155%: still inside the cap
        // today, certain to overrun, and that is the only moment the warning
        // is any use.
        val q = quota(55)
        val out = of(evaluate(q, settings(percent = 0, projected = true), 1_000L), EventKind.QUOTA_PROJECTED)
        assertEquals(1, out.size)
        assertEquals(EventLevel.WARN, out[0].level)
        assertTrue("no advice in: ${out[0].detail}", out[0].detail.contains("would stay inside"))
    }

    @Test
    fun `the projection rule is silent once the cap is actually breached`() {
        // The percentage rule owns that case; two lines saying the same thing
        // is what the one-producer-per-fact rule exists to prevent.
        val out = of(
            evaluate(quota(120), settings(percent = 0, projected = true), 1_000L),
            EventKind.QUOTA_PROJECTED,
        )
        assertTrue("the projection duplicated the breach: $out", out.isEmpty())
    }

    @Test
    fun `the projection rule stays off when it is not enabled`() {
        val out = of(
            evaluate(quota(55), settings(percent = 0, projected = false), 1_000L),
            EventKind.QUOTA_PROJECTED,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a cycle on course says nothing`() {
        // Day 11 of 31 with 30% gone projects to about 85%.
        val out = evaluate(quota(30), settings(percent = 80, projected = true), 1_000L)
        assertTrue("a healthy cycle produced $out", out.isEmpty())
    }

    @Test
    fun `a new cycle may speak again`() {
        // The other half of scoping the state to the cycle: suppression that
        // outlives the month it was set in would silence the next one.
        evaluate(quota(120), settings(80), 1_000L)
        val nextCycle = quota(85).copy(
            cycleStartDay = 20_031L,
            cycleEndDay = 20_061L,
            todayDay = 20_041L,
        )
        val out = of(evaluate(nextCycle, settings(80), 2_000L), EventKind.QUOTA_PERCENT)
        assertEquals("the new cycle was silenced by the old one: $out", 1, out.size)
    }

    @Test
    fun `turning the cap off and on again starts clean`() {
        evaluate(quota(85), settings(80), 1_000L)
        evaluate(QuotaStatus(), settings(80), 2_000L)
        val out = of(evaluate(quota(85), settings(80), 3_000L), EventKind.QUOTA_PERCENT)
        assertEquals(1, out.size)
    }

    @Test
    fun `the subject says which transports are counted`() {
        val mobile = of(
            evaluate(quota(85).copy(mobileOnly = true), settings(80), 1_000L),
            EventKind.QUOTA_PERCENT,
        )
        assertTrue(mobile[0].subject.contains("mobile"))
    }
}
