package com.vlad.ducknetview.domain.history

/**
 * One minute of total throughput: the peak rate seen in it and the bytes it
 * moved. The TUI's "Last 24 hours" panel — the one chart that survives a
 * restart, so "when did that spike happen" is still answerable afterwards.
 * On a phone that matters more than in a terminal: Android kills processes
 * far more readily than a person quits a TUI.
 */
data class MinuteSample(
    val minute: Long,
    val rxPeakBps: Long,
    val txPeakBps: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

/**
 * Folds poll-rate readings into whole minutes.
 *
 * A minute is emitted once a reading from a later minute arrives, never
 * while it is still open — a half-minute written to disk and then rewritten
 * is two writes for one fact. Bytes are integrated from the rate over the
 * gap since the previous reading, capped so a process that slept for an hour
 * does not attribute an hour of traffic to the minute it woke in.
 */
class MinuteAccumulator(private val maxGapMillis: Long = MAX_GAP_MILLIS) {
    private var minute = Long.MIN_VALUE
    private var rxPeak = 0L
    private var txPeak = 0L
    private var rxBytes = 0.0
    private var txBytes = 0.0
    private var lastAt = Long.MIN_VALUE

    /** Add a reading; returns the minute it closed, if it closed one. */
    fun add(nowMillis: Long, rxBps: Long, txBps: Long): MinuteSample? {
        val m = nowMillis / MINUTE_MS
        var closed: MinuteSample? = null
        if (minute != Long.MIN_VALUE && m != minute) {
            closed = current()
            reset(m)
        } else if (minute == Long.MIN_VALUE) {
            reset(m)
        }
        val gap = if (lastAt == Long.MIN_VALUE) 0L else (nowMillis - lastAt).coerceIn(0L, maxGapMillis)
        lastAt = nowMillis
        rxPeak = maxOf(rxPeak, rxBps)
        txPeak = maxOf(txPeak, txBps)
        rxBytes += rxBps * gap / 1000.0
        txBytes += txBps * gap / 1000.0
        return closed
    }

    private fun current() = MinuteSample(minute, rxPeak, txPeak, rxBytes.toLong(), txBytes.toLong())

    private fun reset(m: Long) {
        minute = m
        rxPeak = 0L
        txPeak = 0L
        rxBytes = 0.0
        txBytes = 0.0
    }

    companion object {
        const val MINUTE_MS = 60_000L
        const val MAX_GAP_MILLIS = 15_000L

        /** A day of minutes is what is kept. */
        const val KEEP_MINUTES = 24 * 60

        /**
         * Spread stored samples over a fixed 24-hour axis, oldest first, with
         * zero for minutes nobody was watching — a gap in the chart should
         * read as a gap, not be closed up so two distant spikes sit side by
         * side.
         */
        fun series(samples: List<MinuteSample>, nowMillis: Long, buckets: Int = 96): List<Float> {
            val last = nowMillis / MINUTE_MS
            val first = last - KEEP_MINUTES + 1
            val per = KEEP_MINUTES / buckets
            val out = FloatArray(buckets)
            for (s in samples) {
                if (s.minute < first || s.minute > last) continue
                val i = ((s.minute - first) / per).toInt().coerceIn(0, buckets - 1)
                val v = (s.rxPeakBps + s.txPeakBps).toFloat()
                if (v > out[i]) out[i] = v
            }
            return out.toList()
        }
    }
}
