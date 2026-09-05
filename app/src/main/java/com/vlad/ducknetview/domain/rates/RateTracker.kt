package com.vlad.ducknetview.domain.rates

/**
 * Turns cumulative counters into rates by diffing consecutive samples, and
 * keeps a bounded history for sparklines.
 *
 * The counter-reset guard is the TUI's probe.rate() lesson: an interface (or a
 * flow table entry) whose counter goes backwards must contribute 0, not a
 * wrapped-around spike of several exabytes per second.
 */
class RateTracker(private val historySize: Int = 60) {

    private data class Sample(val value: Long, val atMillis: Long)

    private val last = HashMap<String, Sample>()
    private val history = HashMap<String, ArrayDeque<Float>>()
    private val lastDelta = HashMap<String, Long>()

    /**
     * Feed a cumulative counter; returns the rate in units/second. The first
     * sighting of a key yields 0 — a rate needs two samples, so values appear
     * one poll after a flow or link is first seen.
     */
    fun update(key: String, cumulative: Long, atMillis: Long): Long {
        val prev = last[key]
        last[key] = Sample(cumulative, atMillis)
        if (prev == null) {
            lastDelta[key] = 0L
            return 0L
        }
        val dt = atMillis - prev.atMillis
        if (dt <= 0L || cumulative < prev.value) {
            lastDelta[key] = 0L // counter reset, or no time passed
            return 0L
        }
        val delta = cumulative - prev.value
        lastDelta[key] = delta
        return (delta * 1000L) / dt
    }

    /**
     * The raw byte delta behind the most recent [update]. Session totals are
     * accumulated from these rather than from rates, so a slow poll cannot
     * quietly lose or double-count traffic.
     */
    fun deltaOf(key: String): Long = lastDelta[key] ?: 0L

    /** Push a value onto a named sparkline history, evicting the oldest. */
    fun pushHistory(key: String, value: Float) {
        val q = history.getOrPut(key) { ArrayDeque() }
        q.addLast(value)
        while (q.size > historySize) q.removeFirst()
    }

    fun history(key: String): List<Float> = history[key]?.toList() ?: emptyList()

    /** Drop bookkeeping for keys that are no longer present, bounding memory. */
    fun retain(keys: Set<String>) {
        last.keys.retainAll(keys)
        history.keys.retainAll(keys)
        lastDelta.keys.retainAll(keys)
    }

    fun forget(key: String) {
        last.remove(key)
        history.remove(key)
        lastDelta.remove(key)
    }

    fun clear() {
        last.clear()
        history.clear()
        lastDelta.clear()
    }
}
