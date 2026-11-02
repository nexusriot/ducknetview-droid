package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.model.BlockedAttempt
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel

/**
 * Turns refused connections into log lines, summarised per app.
 *
 * The per-app firewall is the one thing this app *does* to traffic rather than
 * merely watching, and it used to do it in total silence: the flow was marked,
 * retired and gone. A blocked app has no live connections by construction, so
 * the screen showed exactly what it shows for an app that is not running —
 * which left the user with no way to tell a working block from a broken one.
 *
 * Summarised rather than reported one by one because a blocked app does not
 * give up. A browser denied the network retries every few hundred milliseconds
 * and would own the entire log within a minute; what is worth knowing is that
 * it is still trying and roughly how hard. So one line per app per
 * [REFIRE_MILLIS], carrying the count accumulated since the last one.
 *
 * INFO, deliberately. The user asked for this block, so a refusal is the
 * feature working, not an incident — and [EventLevel.WARN] would put a
 * notification and a webhook POST behind every retry of an app the user has
 * already dealt with.
 *
 * Stateful, like [com.vlad.ducknetview.domain.alerts.AlertRules] and for the
 * same reason: the suppression window has to outlive one tick.
 */
class BlockedEvents(private val refireMillis: Long = REFIRE_MILLIS) {

    private class Pending(var count: Long, var last: BlockedAttempt)

    private val pending = LinkedHashMap<Int, Pending>()
    private val firedAt = LinkedHashMap<Int, Long>()

    /**
     * Accumulate a tick's refusals and emit for the apps whose window is up.
     *
     * Attempts are never dropped on the floor by the window: they are held in
     * [pending] and counted into the next line, so the totals in the log add
     * up to what actually happened.
     */
    fun of(attempts: List<BlockedAttempt>, now: Long): List<Event> {
        for (a in attempts) {
            val p = pending[a.uid]
            if (p == null) {
                pending[a.uid] = Pending(1L, a)
            } else {
                p.count++
                p.last = a
            }
        }
        if (pending.isEmpty()) return emptyList()

        val out = ArrayList<Event>(pending.size)
        val uids = pending.keys.toList()
        for (uid in uids) {
            val last = firedAt[uid]
            // A first refusal is reported at once; the window only governs
            // what follows, so a block the user has just applied gives
            // immediate feedback that it is biting.
            if (last != null && now - last < refireMillis) continue
            val p = pending.remove(uid) ?: continue
            firedAt[uid] = now
            out += Event(
                at = now,
                level = EventLevel.INFO,
                kind = EventKind.BLOCKED_ATTEMPT,
                subject = subject(p.last),
                detail = detail(p),
            )
        }
        pruneFired(now)
        return out
    }

    fun reset() {
        pending.clear()
        firedAt.clear()
    }

    private fun subject(a: BlockedAttempt): String =
        if (a.appLabel.isNotEmpty()) "${a.appLabel} (uid ${a.uid})" else "uid ${a.uid}"

    private fun detail(p: Pending): String {
        val what = if (p.count == 1L) "1 connection refused" else "${p.count} connections refused"
        // The reason is what tells a refusal by a policy ("while the screen is
        // off") from one by a rule or the app switch — without it the log
        // says only that something refused.
        val why = p.last.reason.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""
        return "$what · last ${p.last.proto} to ${p.last.remote}$why"
    }

    private fun pruneFired(now: Long) {
        val cutoff = 2 * refireMillis
        val it = firedAt.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > cutoff && e.key !in pending) it.remove()
        }
    }

    companion object {
        /**
         * How long one app stays quiet after a line. Matches
         * [com.vlad.ducknetview.domain.alerts.AlertRules.REFIRE_MILLIS]: the
         * two solve the same problem and a user reading the log should not
         * have to learn two cadences.
         */
        const val REFIRE_MILLIS = 60_000L
    }
}
