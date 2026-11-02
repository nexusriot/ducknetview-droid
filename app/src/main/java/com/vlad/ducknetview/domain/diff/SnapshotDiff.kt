package com.vlad.ducknetview.domain.diff

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.ServiceRow

/**
 * What changed between two saved snapshots, as the TUI's
 * `--diff old.json new.json` reports it.
 *
 * Only identity-level changes are listed — something appeared, went away or
 * moved. Counters and rates differ between any two snapshots and would bury
 * the handful of lines a person is actually comparing for.
 */
data class SnapshotDiff(
    val listenersGained: List<ServiceRow>,
    val listenersLost: List<ServiceRow>,
    /** Same protocol and port, different bind address: `(old, new)`. */
    val listenersMoved: List<Pair<ServiceRow, ServiceRow>>,
    val appsAppeared: List<AppRow>,
    val appsGone: List<AppRow>,
    /** Remote peers (the resolved name when known) the old snapshot never contacted. */
    val hostsFirstContact: List<String>,
    val networksAppeared: List<NetworkRow>,
    val networksGone: List<NetworkRow>,
) {
    val changeCount: Int
        get() = listenersGained.size + listenersLost.size + listenersMoved.size +
            appsAppeared.size + appsGone.size + hostsFirstContact.size +
            networksAppeared.size + networksGone.size

    val isEmpty: Boolean get() = changeCount == 0
}

object SnapshotDiffer {

    fun diff(old: NetSnapshot, new: NetSnapshot): SnapshotDiff {
        val listeners = diffListeners(old.services, new.services)

        val oldApps = old.apps.associateBy(::appKey)
        val newApps = new.apps.associateBy(::appKey)

        val oldNets = old.networks.associateBy(::networkKey)
        val newNets = new.networks.associateBy(::networkKey)

        return SnapshotDiff(
            listenersGained = listeners.gained,
            listenersLost = listeners.lost,
            listenersMoved = listeners.moved,
            appsAppeared = newApps.filterKeys { it !in oldApps }.values.toList(),
            appsGone = oldApps.filterKeys { it !in newApps }.values.toList(),
            hostsFirstContact = firstContacts(old, new),
            networksAppeared = newNets.filterKeys { it !in oldNets }.values.toList(),
            networksGone = oldNets.filterKeys { it !in newNets }.values.toList(),
        )
    }

    /**
     * An app is its package, not its uid: a reinstall or a work-profile copy
     * gets a new uid while being the same app, and a restart between two
     * snapshots must not read as one app leaving and another arriving. The
     * label is the fallback for rows with no package (the kernel and shared
     * system uids), and the uid only when there is nothing else.
     */
    internal fun appKey(a: AppRow): String = when {
        a.packageName.isNotEmpty() -> "pkg:${a.packageName}"
        a.label.isNotEmpty() -> "label:${a.label}"
        else -> "uid:${a.uid}"
    }

    /**
     * A network is its transport and interface. The platform netId is
     * renumbered on every reconnect (DESIGN §8a), so keying on it would make
     * every Wi-Fi rejoin look like one network vanishing and another
     * appearing.
     */
    internal fun networkKey(n: NetworkRow): String = "${n.transport}|${n.ifaceName}"

    private class Listeners(
        val gained: List<ServiceRow>,
        val lost: List<ServiceRow>,
        val moved: List<Pair<ServiceRow, ServiceRow>>,
    )

    /**
     * A listener that went from `127.0.0.1:8080` to `0.0.0.0:8080` is one
     * event — a rebind, often a widening of exposure — and reporting it as an
     * unrelated loss plus gain hides exactly that. Gains and losses sharing a
     * protocol and port are therefore paired first, one to one, and only the
     * unpaired remainder is reported as gained or lost.
     */
    private fun diffListeners(old: List<ServiceRow>, new: List<ServiceRow>): Listeners {
        val oldKeys = old.mapTo(HashSet()) { it.baselineKey }
        val newKeys = new.mapTo(HashSet()) { it.baselineKey }
        val gained = new.filter { it.baselineKey !in oldKeys }.distinctBy { it.baselineKey }
        val lost = old.filter { it.baselineKey !in newKeys }.distinctBy { it.baselineKey }

        val lostByPort = LinkedHashMap<String, ArrayDeque<ServiceRow>>()
        for (s in lost) lostByPort.getOrPut(portKey(s)) { ArrayDeque() }.addLast(s)

        val moved = ArrayList<Pair<ServiceRow, ServiceRow>>()
        val pairedLost = HashSet<String>()
        val unpairedGained = ArrayList<ServiceRow>()
        for (s in gained) {
            val from = lostByPort[portKey(s)]?.removeFirstOrNull()
            if (from == null) {
                unpairedGained += s
            } else {
                moved += from to s
                pairedLost += from.baselineKey
            }
        }
        return Listeners(
            gained = unpairedGained,
            lost = lost.filter { it.baselineKey !in pairedLost },
            moved = moved,
        )
    }

    private fun portKey(s: ServiceRow): String = "${s.proto}|${s.port}"

    /**
     * Peers in the new snapshot, live or closed, that the old one never
     * reached. A peer counts as known if the old snapshot saw either its
     * address or its name: one snapshot taken before a reverse lookup finished
     * and one after must not report the same host as a first contact.
     */
    private fun firstContacts(old: NetSnapshot, new: NetSnapshot): List<String> {
        val known = HashSet<String>()
        for (c in old.conns + old.closedConns.map { it.row }) {
            if (c.remoteAddr.isNotEmpty()) known += c.remoteAddr
            c.resolvedHost?.takeIf { it.isNotEmpty() }?.let { known += it }
        }
        val out = LinkedHashSet<String>()
        for (c in new.conns + new.closedConns.map { it.row }) {
            if (c.remoteAddr.isEmpty()) continue
            val host = c.resolvedHost?.takeIf { it.isNotEmpty() }
            if (c.remoteAddr in known || (host != null && host in known)) continue
            out += host ?: c.remoteAddr
        }
        return out.toList()
    }
}
