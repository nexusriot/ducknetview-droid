package com.vlad.ducknetview.domain.events

import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.domain.usage.AppBudget
import com.vlad.ducknetview.domain.usage.AppBudgets

/**
 * Things worth knowing about how an app talks, as opposed to how much: plain
 * text where it could have been encrypted, a TLS version the IETF retired,
 * data moved while nobody was looking, an app past its own allowance.
 *
 * Each is reported once per subject per session (or per day / cycle for the
 * two that are about totals) — they are properties of an app's behaviour,
 * and repeating them every poll would bury the log under facts the user has
 * already read.
 */
class FindingEvents {

    private val cleartextSeen = HashSet<String>()
    private val tlsSeen = HashSet<String>()
    private val backgroundReported = HashSet<Int>()
    private val budgetReported = HashSet<String>()
    private var budgetCycle = Long.MIN_VALUE

    /**
     * [cycleUsedByPackage] is each app's usage since the budget cycle began,
     * and [cycleStart] identifies the cycle so a new month can speak again.
     */
    fun of(
        snap: NetSnapshot,
        s: AppSettings,
        now: Long,
        cycleUsedByPackage: Map<String, Long> = emptyMap(),
        cycleStart: Long = 0L,
    ): List<Event> {
        val out = ArrayList<Event>()
        // Closed rows too: a plain-HTTP request is usually over before the
        // next poll sees it live.
        for (c in snap.conns + snap.closedConns.map { it.row }) {
            if (c.cleartext.isNotEmpty() && cleartextSeen.add("${c.uid}|${c.cleartext}")) {
                out += Event(
                    at = now,
                    level = EventLevel.INFO,
                    kind = EventKind.CLEARTEXT_FLOW,
                    subject = appOf(c.appLabel, c.uid),
                    detail = "speaks ${c.cleartext} to ${c.remoteDisplay(revDns = true)} " +
                        "without encryption",
                )
            }
            if (c.tlsDeprecated && tlsSeen.add("${c.uid}|${c.remoteAddr}")) {
                out += Event(
                    at = now,
                    level = EventLevel.WARN,
                    kind = EventKind.TLS_DEPRECATED,
                    subject = appOf(c.appLabel, c.uid),
                    detail = "negotiated ${c.tls} with ${c.remoteDisplay(revDns = true)} " +
                        "— deprecated by RFC 8996",
                )
            }
        }

        val bgLimit = s.alertBackgroundBytes
        if (bgLimit > 0) {
            for (a in snap.apps) {
                val bg = a.todayBackground
                if (bg < 0) continue
                // Below the line re-arms: the counter falls back at midnight,
                // so the next day's breach is reported without a clock here.
                if (bg < bgLimit) {
                    backgroundReported -= a.uid
                    continue
                }
                if (!backgroundReported.add(a.uid)) continue
                out += Event(
                    at = now,
                    level = EventLevel.WARN,
                    kind = EventKind.BACKGROUND_DATA,
                    subject = appOf(a.label, a.uid),
                    detail = "${Units.bytes(bg)} in the background today, over " +
                        Units.bytes(bgLimit),
                )
            }
        } else {
            backgroundReported.clear()
        }

        if (cycleStart != budgetCycle) {
            budgetCycle = cycleStart
            budgetReported.clear()
        }
        val budgets = AppBudgets.decode(s.appBudgets)
        for ((b, used) in AppBudgets.breaches(budgets, cycleUsedByPackage)) {
            if (!budgetReported.add(b.packageName)) continue
            out += Event(
                at = now,
                level = EventLevel.WARN,
                kind = EventKind.APP_BUDGET,
                subject = labelOf(snap, b),
                detail = "${Units.bytes(used)} this cycle, over its ${Units.bytes(b.capBytes)} " +
                    "allowance" + if (b.block) " — blocked until the cycle resets" else "",
            )
        }
        return out
    }

    fun reset() {
        cleartextSeen.clear()
        tlsSeen.clear()
        backgroundReported.clear()
        budgetReported.clear()
        budgetCycle = Long.MIN_VALUE
    }

    private fun appOf(label: String, uid: Int) = if (label.isNotEmpty()) "$label (uid $uid)" else "uid $uid"

    private fun labelOf(snap: NetSnapshot, b: AppBudget): String =
        snap.apps.firstOrNull { it.packageName == b.packageName }?.let { appOf(it.label, it.uid) }
            ?: b.packageName
}
