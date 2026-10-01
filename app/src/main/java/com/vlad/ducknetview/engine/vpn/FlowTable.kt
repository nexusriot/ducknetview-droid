package com.vlad.ducknetview.engine.vpn

import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.fmtAddr
import com.vlad.ducknetview.domain.net.IpScope
import com.vlad.ducknetview.domain.net.ServiceNames
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class FlowKey(
    val proto: Proto,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
) {
    override fun toString() =
        "$proto|${fmtAddr(srcIp, srcPort)}|${fmtAddr(dstIp, dstPort)}"
}

/**
 * Live accounting for one proxied flow. Because every byte passes through the
 * proxy, these counters are exact rather than sampled — which is what lets the
 * closed-connection history report true lifetime totals.
 */
class Flow(
    val key: FlowKey,
    val ipVersion: Int,
    val firstSeen: Long,
) {
    val rx = AtomicLong(0)
    val tx = AtomicLong(0)

    @Volatile var state: ConnState = ConnState.NEW
    @Volatile var uid: Int = -1
    @Volatile var lastSeen: Long = firstSeen
    @Volatile var rttMillis: Int = -1
    @Volatile var network: String = ""
    @Volatile var blocked: Boolean = false

    fun touch(at: Long) {
        lastSeen = at
    }
}

/**
 * Owns the live flows and the bounded history of the ones that went away.
 * Snapshot production is the only thing the UI side calls.
 */
class FlowTable(private val closedLimit: Int = 200) {

    private val flows = ConcurrentHashMap<FlowKey, Flow>()
    private val closed = ArrayDeque<ClosedConn>()
    private val closedLock = Any()

    @Volatile private var openedSinceLastSnapshot = 0
    @Volatile private var closedSinceLastSnapshot = 0

    fun get(key: FlowKey): Flow? = flows[key]

    fun open(key: FlowKey, ipVersion: Int, now: Long): Flow {
        val existing = flows[key]
        if (existing != null) return existing
        val f = Flow(key, ipVersion, now)
        flows[key] = f
        synchronized(closedLock) { openedSinceLastSnapshot++ }
        return f
    }

    fun close(key: FlowKey, now: Long, appLabel: String, packageName: String) {
        val f = flows.remove(key) ?: return
        f.state = ConnState.CLOSED
        val row = toRow(f, now, appLabel, packageName, rxBps = 0, txBps = 0, isNew = false)
        synchronized(closedLock) {
            closed.addLast(
                ClosedConn(
                    row = row,
                    closedAt = now,
                    lifetimeMillis = now - f.firstSeen,
                    finalRx = f.rx.get(),
                    finalTx = f.tx.get(),
                )
            )
            while (closed.size > closedLimit) closed.removeFirst()
            closedSinceLastSnapshot++
        }
    }

    fun live(): Collection<Flow> = flows.values

    fun closedSnapshot(): List<ClosedConn> = synchronized(closedLock) { closed.toList().asReversed() }

    /** Reads and resets the per-poll churn counters. */
    fun drainChurn(): Pair<Int, Int> = synchronized(closedLock) {
        val r = openedSinceLastSnapshot to closedSinceLastSnapshot
        openedSinceLastSnapshot = 0
        closedSinceLastSnapshot = 0
        r
    }

    /**
     * Expire idle flows: TCP connections whose peer vanished without a FIN get
     * the long window, and the connectionless protocols — UDP and ICMP echo,
     * neither of which has a teardown to wait for — get the short one.
     */
    fun expire(now: Long, udpIdleMs: Long, tcpIdleMs: Long, onExpire: (Flow) -> Unit) {
        for (f in flows.values) {
            val idle = now - f.lastSeen
            val limit = if (f.key.proto == Proto.TCP) tcpIdleMs else udpIdleMs
            if (idle > limit) onExpire(f)
        }
    }

    fun clear() {
        flows.clear()
        synchronized(closedLock) { closed.clear() }
    }

    fun toRow(
        f: Flow,
        now: Long,
        appLabel: String,
        packageName: String,
        rxBps: Long,
        txBps: Long,
        isNew: Boolean,
        resolvedHost: String? = null,
        watchlisted: Boolean = false,
    ): ConnRow = ConnRow(
        key = f.key.toString(),
        proto = f.key.proto,
        localAddr = f.key.srcIp,
        localPort = f.key.srcPort,
        remoteAddr = f.key.dstIp,
        remotePort = f.key.dstPort,
        state = f.state,
        uid = f.uid,
        appLabel = appLabel,
        packageName = packageName,
        // ICMP echo addresses a host rather than a service, and its "port"
        // is the echo identifier, so a port lookup here would invent a name.
        service = if (f.key.proto == Proto.ICMP) "echo" else ServiceNames.of(f.key.dstPort, f.key.proto),
        scope = IpScope.of(f.key.dstIp),
        rxBytes = f.rx.get(),
        txBytes = f.tx.get(),
        rxBps = rxBps,
        txBps = txBps,
        rttMillis = f.rttMillis,
        firstSeen = f.firstSeen,
        lastSeen = f.lastSeen,
        network = f.network,
        resolvedHost = resolvedHost,
        isNew = isNew,
        watchlisted = watchlisted,
        blocked = f.blocked,
    )

    val size: Int get() = flows.size

    companion object {
        fun scopeOf(ip: String): Scope = IpScope.of(ip)
    }
}
