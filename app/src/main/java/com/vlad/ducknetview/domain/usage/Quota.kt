package com.vlad.ducknetview.domain.usage

import java.time.LocalDate

/**
 * A billing cycle's worth of data, measured against a cap.
 *
 * Every other number in this app answers "what is happening"; this one answers
 * the question a person on a metered plan actually has, which is "am I going
 * to run out". That needs three things the daily history alone does not give:
 * where the cycle starts, how much of it has gone, and what the rate so far
 * implies for the end of it.
 *
 * Pure, so the arithmetic that decides whether to warn somebody is a plain JVM
 * test rather than something only a device can check.
 */
object Quota {

    /**
     * The cycle containing [today] for a plan that resets on [startDay].
     *
     * A start day past the end of a short month lands on that month's last
     * day. Plans phrased "the 31st" exist and February does not have one; the
     * alternative — skipping the month, or rolling into March — would either
     * drop a cycle or silently move everyone's reset date.
     */
    fun cycleStart(today: LocalDate, startDay: Int): LocalDate {
        val day = startDay.coerceIn(1, 31)
        val thisMonth = clampToMonth(today.withDayOfMonth(1), day)
        return if (!today.isBefore(thisMonth)) {
            thisMonth
        } else {
            clampToMonth(today.minusMonths(1).withDayOfMonth(1), day)
        }
    }

    /** The first day of the cycle *after* the one containing [today]. */
    fun cycleEnd(today: LocalDate, startDay: Int): LocalDate {
        val start = cycleStart(today, startDay)
        return clampToMonth(start.withDayOfMonth(1).plusMonths(1), startDay.coerceIn(1, 31))
    }

    private fun clampToMonth(firstOfMonth: LocalDate, day: Int): LocalDate =
        firstOfMonth.withDayOfMonth(minOf(day, firstOfMonth.lengthOfMonth()))

    /**
     * @param capBytes the plan's allowance; zero means no cap is configured
     *   and [QuotaStatus.configured] is false.
     * @param usedBytes what the cycle has consumed so far.
     */
    fun status(
        today: LocalDate,
        startDay: Int,
        capBytes: Long,
        usedBytes: Long,
        mobileOnly: Boolean = true,
    ): QuotaStatus {
        val start = cycleStart(today, startDay)
        val end = cycleEnd(today, startDay)
        return QuotaStatus(
            capBytes = capBytes.coerceAtLeast(0L),
            usedBytes = usedBytes.coerceAtLeast(0L),
            cycleStartDay = start.toEpochDay(),
            cycleEndDay = end.toEpochDay(),
            todayDay = today.toEpochDay(),
            mobileOnly = mobileOnly,
        )
    }
}

data class QuotaStatus(
    val capBytes: Long = 0L,
    val usedBytes: Long = 0L,
    val cycleStartDay: Long = 0L,
    val cycleEndDay: Long = 0L,
    val todayDay: Long = 0L,
    val mobileOnly: Boolean = true,
) {

    val configured: Boolean get() = capBytes > 0L

    /** Days in the whole cycle; at least one, so nothing here divides by zero. */
    val cycleDays: Int get() = (cycleEndDay - cycleStartDay).toInt().coerceAtLeast(1)

    /**
     * Days of the cycle that have begun, today included.
     *
     * Today counts as elapsed even though it is not over. The alternative
     * makes day one of a cycle "zero days elapsed", which turns the projection
     * below into a division by zero on exactly the day a cap is most often
     * set.
     */
    val daysElapsed: Int get() = (todayDay - cycleStartDay + 1).toInt().coerceIn(1, cycleDays)

    val daysRemaining: Int get() = (cycleDays - daysElapsed).coerceAtLeast(0)

    val remainingBytes: Long get() = (capBytes - usedBytes).coerceAtLeast(0L)

    val overBudget: Boolean get() = configured && usedBytes >= capBytes

    /** 0..100, clamped: a cycle 140% spent reports 100 on a progress bar. */
    val percentUsed: Int
        get() = if (!configured) 0 else ((usedBytes * 100) / capBytes).toInt().coerceIn(0, 100)

    /** Unclamped, for the text beside the bar, where "140%" is the useful figure. */
    val percentUsedExact: Int
        get() = if (!configured) 0 else ((usedBytes * 100) / capBytes).toInt().coerceAtLeast(0)

    val percentOfCycleElapsed: Int get() = (daysElapsed * 100 / cycleDays).coerceIn(0, 100)

    /** Mean daily consumption so far — the basis of the projection. */
    val bytesPerDay: Long get() = usedBytes / daysElapsed

    /**
     * What the cycle ends at if the rate so far continues.
     *
     * Deliberately the simplest possible model. A weekday/weekend split or a
     * trend fit would be a better forecast on some devices and a worse one on
     * others, and neither can be explained in the one line the card has room
     * for. "At this rate" is a claim a reader can check.
     */
    val projectedBytes: Long get() = bytesPerDay * cycleDays

    val projectedOver: Boolean get() = configured && projectedBytes > capBytes

    /** What may be spent per remaining day to finish exactly on the cap. */
    val safeDailyRemaining: Long
        get() = if (daysRemaining <= 0) remainingBytes else remainingBytes / daysRemaining

    /** The day the cap runs out at this rate, or null when it does not. */
    val exhaustedOnDay: Long?
        get() {
            if (!configured) return null
            if (overBudget) return todayDay
            val perDay = bytesPerDay
            if (perDay <= 0L) return null
            val daysLeftAtRate = remainingBytes / perDay
            val day = todayDay + daysLeftAtRate + 1
            return if (day < cycleEndDay) day else null
        }
}
