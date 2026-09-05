package com.vlad.ducknetview.engine

/**
 * How often the poll loop should wake.
 *
 * Nothing renders while the screen is off, so the configured cadence is a
 * waste of wakeups there — the loop backs off instead and snaps straight back
 * when the screen comes on. Frozen means a snapshot file is being browsed and
 * the live host is irrelevant, so it idles at the cap.
 */
object PollCadence {

    const val SCREEN_OFF_FACTOR = 5
    const val MAX_INTERVAL_SECONDS = 30
    const val MIN_INTERVAL_SECONDS = 1
    const val MAX_CONFIGURED_SECONDS = 10

    fun effectiveIntervalSeconds(configured: Int, interactive: Boolean, frozen: Boolean): Int {
        val base = configured.coerceIn(MIN_INTERVAL_SECONDS, MAX_CONFIGURED_SECONDS)
        if (frozen) return MAX_INTERVAL_SECONDS
        if (interactive) return base
        return (base * SCREEN_OFF_FACTOR).coerceAtMost(MAX_INTERVAL_SECONDS)
    }
}
