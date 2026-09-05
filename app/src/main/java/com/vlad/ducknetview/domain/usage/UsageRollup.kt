package com.vlad.ducknetview.domain.usage

/**
 * One day's accounted traffic. [dayEpoch] is the day number (local midnights
 * divided out by the caller), not a millisecond timestamp, so merging two
 * sessions from the same day is an equality test rather than a range test.
 */
data class DailyUsage(
    val dayEpoch: Long,
    val rx: Long = 0L,
    val tx: Long = 0L,
    val apps: Map<String, Long> = emptyMap(),
    val hosts: Map<String, Long> = emptyMap(),
) {
    val total: Long get() = rx + tx
}

/**
 * Session totals die with the session, which is no use for the question people
 * actually ask on a metered link: where did this month's data go? Folding each
 * session into a per-day record answers it without keeping a database.
 */
object UsageRollup {

    /** A month is the billing period that matters; a little more gives context. */
    const val MAX_DAYS = 40

    /** Per day. Keeping every app and host would grow the file without improving the answer. */
    const val TOP_N = 50

    fun merge(existing: List<DailyUsage>, day: DailyUsage): List<DailyUsage> {
        val out = ArrayList<DailyUsage>(existing.size + 1)
        var merged = false
        for (d in existing) {
            if (d.dayEpoch != day.dayEpoch) {
                out += d
                continue
            }
            out += DailyUsage(
                dayEpoch = d.dayEpoch,
                rx = d.rx + day.rx,
                tx = d.tx + day.tx,
                apps = mergeCounts(d.apps, day.apps, TOP_N),
                hosts = mergeCounts(d.hosts, day.hosts, TOP_N),
            )
            merged = true
        }
        if (!merged) {
            out += DailyUsage(
                dayEpoch = day.dayEpoch,
                rx = day.rx,
                tx = day.tx,
                apps = mergeCounts(emptyMap(), day.apps, TOP_N),
                hosts = mergeCounts(emptyMap(), day.hosts, TOP_N),
            )
        }

        out.sortBy { it.dayEpoch }
        return if (out.size > MAX_DAYS) out.subList(out.size - MAX_DAYS, out.size).toList() else out
    }

    /**
     * Adds one session's per-name totals into a day's, keeping only the
     * biggest [keep] so the record cannot grow without bound.
     */
    fun mergeCounts(into: Map<String, Long>, add: Map<String, Long>, keep: Int): Map<String, Long> {
        val sum = LinkedHashMap<String, Long>(into)
        for ((name, n) in add) sum[name] = (sum[name] ?: 0L) + n
        if (sum.size <= keep) return sum

        val kept = sum.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(keep)
        val out = LinkedHashMap<String, Long>(kept.size)
        for (e in kept) out[e.key] = e.value
        return out
    }

    fun totalRx(days: List<DailyUsage>): Long = days.sumOf { it.rx }

    fun totalTx(days: List<DailyUsage>): Long = days.sumOf { it.tx }

    /** The last [n] days present, oldest first — what the usage chart draws. */
    fun recent(days: List<DailyUsage>, n: Int): List<DailyUsage> {
        if (n <= 0 || days.isEmpty()) return emptyList()
        val sorted = days.sortedBy { it.dayEpoch }
        return if (sorted.size <= n) sorted else sorted.subList(sorted.size - n, sorted.size)
    }
}
