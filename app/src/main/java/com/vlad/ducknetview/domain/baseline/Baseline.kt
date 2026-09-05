package com.vlad.ducknetview.domain.baseline

import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ServiceRow

/**
 * The lowest port the kernel hands out to client sockets. Android's
 * ip_local_port_range starts at 32768 and is not readable from an unprivileged
 * app, so the conservative default is the only value available.
 */
const val EPHEMERAL_PORT_FLOOR = 32768

/**
 * Whether a listening socket belongs in the host's expected service surface.
 *
 * UDP has no LISTEN state, so an outbound DNS query looks exactly like a UDP
 * service. Baselining those would mean a baseline that is dirty again one
 * second after it is saved, which is why every path — save, compare, accept —
 * has to agree to skip them.
 */
fun baselineEligible(row: ServiceRow): Boolean =
    row.proto != Proto.UDP || row.port < EPHEMERAL_PORT_FLOOR

/**
 * The set of listeners the device is expected to have. Saving one turns the
 * Services table into a change detector that survives a restart.
 */
class Baseline(val keys: Set<String>, val savedAt: Long) {

    val isEmpty: Boolean get() = keys.isEmpty()

    val size: Int get() = keys.size

    fun contains(row: ServiceRow): Boolean = row.baselineKey in keys

    /**
     * False while no baseline is saved, so the alert styling stays off until
     * the user opts in.
     */
    fun isOffBaseline(row: ServiceRow): Boolean {
        if (isEmpty || !baselineEligible(row)) return false
        return row.baselineKey !in keys
    }

    /**
     * Blesses one listener. Re-saving the whole set to accept a single new
     * service would also bless everything else that appeared since — the
     * opposite of what a baseline is for.
     */
    fun accept(row: ServiceRow): Baseline {
        if (!baselineEligible(row)) return this
        if (contains(row)) return this
        return Baseline(keys + row.baselineKey, savedAt)
    }

    fun remove(row: ServiceRow): Baseline {
        if (!contains(row)) return this
        return Baseline(keys - row.baselineKey, savedAt)
    }

    /** Listeners in [rows] that the baseline does not cover. */
    fun offBaseline(rows: List<ServiceRow>): List<ServiceRow> =
        if (isEmpty) emptyList() else rows.filter { isOffBaseline(it) }

    /** Sorted so a persisted baseline stays diffable between saves. */
    fun sortedKeys(): List<String> = keys.sorted()

    companion object {

        val EMPTY = Baseline(emptySet(), 0L)

        fun from(rows: List<ServiceRow>, at: Long): Baseline =
            Baseline(rows.filter(::baselineEligible).map { it.baselineKey }.toSet(), at)

        fun of(keys: Collection<String>, at: Long): Baseline = Baseline(keys.toSet(), at)

        /**
         * How many listeners a check actually looks at, so an "all clear"
         * never quotes a total that includes the sockets it skipped.
         */
        fun comparable(rows: List<ServiceRow>): Int = rows.count(::baselineEligible)
    }
}
