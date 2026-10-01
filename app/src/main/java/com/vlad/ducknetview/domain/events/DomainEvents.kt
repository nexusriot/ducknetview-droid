package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.domain.watchlist.Watchlist

/**
 * Turns first sightings of a name into log lines.
 *
 * Kept apart from [EventEngine] on purpose. That engine derives everything from
 * diffing two snapshots and owns `new_public_host`; names are not on a snapshot
 * at all, they arrive from the capture engine's DNS and SNI observations. Two
 * producers reading the same source is what put every new host in the log
 * twice once before, so the rule is one producer per source, and these two read
 * different ones: an address and a name are different subjects even when they
 * describe the same server.
 */
object DomainEvents {

    /** Matches the event engine's own per-kind cap on one tick. */
    const val PER_TICK_CAP = 20

    fun of(newNames: List<DomainRow>, watchlist: Watchlist, at: Long): List<Event> {
        if (newNames.isEmpty()) return emptyList()
        val out = ArrayList<Event>(minOf(newNames.size, PER_TICK_CAP) + 1)
        for (row in newNames.take(PER_TICK_CAP)) {
            val watched = !watchlist.isEmpty &&
                (watchlist.matches(row.name, row.name) ||
                    row.addresses.any { watchlist.matches(it, row.name) })
            out += Event(
                at = at,
                level = if (watched) EventLevel.ALERT else EventLevel.INFO,
                kind = EventKind.NEW_DOMAIN,
                subject = row.name,
                detail = detail(row, watched),
            )
        }
        val suppressed = newNames.size - PER_TICK_CAP
        if (suppressed > 0) {
            out += Event(
                at = at,
                level = EventLevel.INFO,
                kind = EventKind.SUPPRESSED,
                subject = EventKind.NEW_DOMAIN.label,
                detail = "$suppressed more suppressed",
            )
        }
        return out
    }

    private fun detail(row: DomainRow, watched: Boolean): String {
        val parts = ArrayList<String>(4)
        parts += if (row.source == NameSource.SNI) "seen in TLS SNI" else "resolved by DNS"
        if (row.appLabel.isNotEmpty()) parts += row.appLabel
        if (row.addresses.isNotEmpty()) parts += row.addresses.take(3).joinToString(" ")
        if (watched) parts += "on the watchlist"
        return parts.joinToString(" · ")
    }
}
