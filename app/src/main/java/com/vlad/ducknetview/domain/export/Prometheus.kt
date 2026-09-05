package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Scope
import java.util.Locale

/**
 * The TUI's `--metrics` output, as served by [com.vlad.ducknetview.service.MetricsServer].
 *
 * Prometheus text exposition format v0.0.4: `# HELP` and `# TYPE` once per
 * family, then its samples. Metric names are all compile-time literals — every
 * value that comes from the device (interface names, app labels, hostnames)
 * goes into a label, never into a name, because a name has no escaping
 * mechanism at all while a label value does.
 *
 * Pure Kotlin on purpose: no Android types, so the whole format is testable on
 * a plain JVM.
 */
object Prometheus {

    /**
     * Per-app and per-host series are unbounded in principle — a busy phone
     * touches hundreds of hosts — so both lists are cut to the biggest talkers
     * and the number dropped is exported rather than silently discarded.
     */
    const val MAX_TALKER_SERIES = 50

    private const val PREFIX = "ducknetview_"

    fun render(s: NetSnapshot, extra: Map<String, String> = emptyMap()): String {
        val b = StringBuilder(8192)

        b.family("up", "Always 1 while the exporter is answering.", GAUGE)
        b.point("up", num(1L))

        b.family("info", "Static labels describing this device and exporter.", GAUGE)
        b.point("info", num(1L), infoLabels(s, extra))

        b.family("engine_mode", "Capture engine: 0 = API counters, 1 = VPN capture.", GAUGE)
        b.point("engine_mode", num(if (s.engine == EngineMode.VPN) 1L else 0L))

        b.family("paused", "1 while polling is paused, so a stale snapshot is visible as such.", GAUGE)
        b.point("paused", num(if (s.paused) 1L else 0L))

        b.family("poll_interval_seconds", "Configured interval between counter samples.", GAUGE)
        b.point("poll_interval_seconds", num(s.intervalSeconds.toLong()))

        b.family("snapshot_timestamp_seconds", "Wall-clock time the served snapshot was taken.", GAUGE)
        b.point("snapshot_timestamp_seconds", num(s.atMillis / 1000.0))

        b.family("uptime_seconds", "Time this monitoring session has been running.", GAUGE)
        b.point("uptime_seconds", num(s.uptimeMillis / 1000.0))

        b.family("receive_bytes_per_second", "Current device-wide receive rate.", GAUGE)
        b.point("receive_bytes_per_second", num(s.total.rxBps))

        b.family("transmit_bytes_per_second", "Current device-wide transmit rate.", GAUGE)
        b.point("transmit_bytes_per_second", num(s.total.txBps))

        b.family("session_receive_bytes", "Bytes received since this session started.", GAUGE)
        b.point("session_receive_bytes", num(s.sessionRx))

        b.family("session_transmit_bytes", "Bytes transmitted since this session started.", GAUGE)
        b.point("session_transmit_bytes", num(s.sessionTx))

        renderNetworks(b, s)
        renderConnections(b, s)
        renderListeners(b, s)
        renderSecurity(b, s)
        renderEvents(b, s)
        renderLatency(b, s)

        val rankedApps = rankedApps(s)
        val rankedHosts = rankedHosts(s)
        renderApps(b, s, rankedApps)
        truncation(
            b,
            appsDropped = rankedApps.size - MAX_TALKER_SERIES,
            hostsDropped = rankedHosts.size - MAX_TALKER_SERIES,
        )
        renderHosts(b, rankedHosts)

        return b.toString()
    }

    private fun infoLabels(s: NetSnapshot, extra: Map<String, String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(2 + extra.size)
        out += "device" to s.deviceName.ifBlank { UNKNOWN }
        out += "engine" to s.engine.name.lowercase(Locale.ROOT)
        // Caller-supplied keys are the one place a label *name* comes from data,
        // and names cannot be escaped — so they are sanitised, not quoted.
        for ((rawKey, value) in extra.toSortedMap()) {
            val key = sanitizeLabelName(rawKey) ?: continue
            if (key == "device" || key == "engine") continue
            out += key to value.ifEmpty { UNKNOWN }
        }
        return out
    }

    private fun renderNetworks(b: StringBuilder, s: NetSnapshot) {
        val rows = s.networks
        b.family("iface_up", "Whether the link is up.", GAUGE)
        for (n in rows) b.point("iface_up", num(if (n.up) 1L else 0L), ifaceLabels(n.ifaceName, n.transport.name))
        b.family("iface_receive_bytes_per_second", "Current receive rate per link.", GAUGE)
        for (n in rows) {
            b.point("iface_receive_bytes_per_second", num(n.rxBps), ifaceLabels(n.ifaceName, n.transport.name))
        }
        b.family("iface_transmit_bytes_per_second", "Current transmit rate per link.", GAUGE)
        for (n in rows) {
            b.point("iface_transmit_bytes_per_second", num(n.txBps), ifaceLabels(n.ifaceName, n.transport.name))
        }
        b.family("iface_receive_bytes", "Bytes received on the link since boot.", GAUGE)
        for (n in rows) b.point("iface_receive_bytes", num(n.rxBytes), ifaceLabels(n.ifaceName, n.transport.name))
        b.family("iface_transmit_bytes", "Bytes transmitted on the link since boot.", GAUGE)
        for (n in rows) b.point("iface_transmit_bytes", num(n.txBytes), ifaceLabels(n.ifaceName, n.transport.name))
    }

    private fun ifaceLabels(iface: String, transport: String): List<Pair<String, String>> = listOf(
        "iface" to iface.ifBlank { UNKNOWN },
        "transport" to transport.lowercase(Locale.ROOT),
    )

    private fun renderConnections(b: StringBuilder, s: NetSnapshot) {
        b.family("connections_active", "Live connections in the current snapshot.", GAUGE)
        b.point("connections_active", num(s.conns.size.toLong()))

        // Every scope is emitted, including the zeroes: a missing series and a
        // series that dropped to zero read very differently on a graph.
        b.family("connections", "Live connections by remote address scope.", GAUGE)
        val byScope = s.conns.groupingBy { it.scope }.eachCount()
        for (scope in Scope.entries) {
            b.point("connections", num((byScope[scope] ?: 0).toLong()), listOf("scope" to scope.toString()))
        }

        b.family("connections_watchlisted", "Live connections matching the watchlist.", GAUGE)
        b.point("connections_watchlisted", num(s.conns.count { it.watchlisted }.toLong()))

        b.family("closed_connections_retained", "Closed connections kept in the history buffer.", GAUGE)
        b.point("closed_connections_retained", num(s.closedConns.size.toLong()))
    }

    private fun renderListeners(b: StringBuilder, s: NetSnapshot) {
        b.family("listeners", "Listening sockets on this device by reachability.", GAUGE)
        val byExposure = s.services.groupingBy { it.exposure }.eachCount()
        for (e in Exposure.entries) {
            b.point(
                "listeners",
                num((byExposure[e] ?: 0).toLong()),
                listOf("exposure" to e.name.lowercase(Locale.ROOT)),
            )
        }
        b.family("listener_scan_timestamp_seconds", "When the listener scan last completed (0 = never).", GAUGE)
        b.point("listener_scan_timestamp_seconds", num(s.serviceScanAt / 1000.0))
    }

    private fun renderSecurity(b: StringBuilder, s: NetSnapshot) {
        b.family("listeners_off_baseline", "Listeners that are not in the saved baseline.", GAUGE)
        b.point("listeners_off_baseline", num(s.security.offBaseline.toLong()))

        b.family("exposed_services", "Listeners reachable from outside this device.", GAUGE)
        b.point("exposed_services", num(s.security.exposedServices.toLong()))

        b.family("watchlist_hits", "Connections to watchlisted remotes.", GAUGE)
        b.point("watchlist_hits", num(s.security.watchlistHits.toLong()))

        b.family("public_connections", "Connections whose remote address is public.", GAUGE)
        b.point("public_connections", num(s.security.publicConns.toLong()))
    }

    private fun renderEvents(b: StringBuilder, s: NetSnapshot) {
        // The event log itself lives outside the snapshot, so what is exported
        // here is the change the current poll saw, by kind.
        b.family("events", "Changes observed in the current poll, by kind.", GAUGE)
        b.point("events", num(s.newConnCount.toLong()), listOf("kind" to "new_connection"))
        b.point("events", num(s.closedConnCount.toLong()), listOf("kind" to "closed_connection"))
        b.point("events", num(s.services.count { it.isNew }.toLong()), listOf("kind" to "new_listener"))
        b.point("events", num(s.conns.count { it.isNew }.toLong()), listOf("kind" to "new_flow"))
    }

    private fun renderLatency(b: StringBuilder, s: NetSnapshot) {
        val samples = s.latency
        b.family("latency_seconds", "Round-trip time to a probe target (only successful probes).", GAUGE)
        for (p in samples) {
            if (!p.ok || p.millis < 0) continue
            b.point("latency_seconds", num(p.millis / 1000.0), latencyLabels(p.target, p.label))
        }
        b.family("latency_up", "1 when the last probe to the target succeeded.", GAUGE)
        for (p in samples) {
            b.point("latency_up", num(if (p.ok) 1L else 0L), latencyLabels(p.target, p.label))
        }
    }

    private fun latencyLabels(target: String, label: String): List<Pair<String, String>> = listOf(
        "target" to target.ifBlank { UNKNOWN },
        "name" to label.ifBlank { target.ifBlank { UNKNOWN } },
    )

    private fun rankedApps(s: NetSnapshot): List<AppRow> = s.apps.sortedWith(
        compareByDescending<AppRow> { it.sessionRx + it.sessionTx }
            .thenByDescending { it.rxBps + it.txBps }
            .thenBy { it.uid },
    )

    private fun renderApps(b: StringBuilder, s: NetSnapshot, ranked: List<AppRow>) {
        val shown = ranked.take(MAX_TALKER_SERIES)

        b.family("apps", "Apps present in the current snapshot.", GAUGE)
        b.point("apps", num(s.apps.size.toLong()))

        b.family("apps_with_sockets", "Apps holding at least one connection.", GAUGE)
        b.point("apps_with_sockets", num(s.apps.count { it.connCount > 0 }.toLong()))

        b.family("app_connections", "Live connections held by the app.", GAUGE)
        for (a in shown) b.point("app_connections", num(a.connCount.toLong()), appLabels(a))

        b.family("app_receive_bytes_per_second", "Current receive rate per app.", GAUGE)
        for (a in shown) b.point("app_receive_bytes_per_second", num(a.rxBps), appLabels(a))

        b.family("app_transmit_bytes_per_second", "Current transmit rate per app.", GAUGE)
        for (a in shown) b.point("app_transmit_bytes_per_second", num(a.txBps), appLabels(a))

        b.family("app_session_receive_bytes", "Bytes received by the app this session.", GAUGE)
        for (a in shown) b.point("app_session_receive_bytes", num(a.sessionRx), appLabels(a))

        b.family("app_session_transmit_bytes", "Bytes transmitted by the app this session.", GAUGE)
        for (a in shown) b.point("app_session_transmit_bytes", num(a.sessionTx), appLabels(a))
    }

    private fun appLabels(a: AppRow): List<Pair<String, String>> = listOf(
        "app" to a.label.ifBlank { a.packageName.ifBlank { "uid ${a.uid}" } },
        "package" to a.packageName.ifBlank { UNKNOWN },
        "uid" to a.uid.toString(),
    )

    private fun renderHosts(b: StringBuilder, ranked: List<HostAgg>) {
        val shown = ranked.take(MAX_TALKER_SERIES)

        b.family("hosts", "Distinct remote hosts in the current snapshot.", GAUGE)
        b.point("hosts", num(ranked.size.toLong()))

        b.family("host_connections", "Live connections to the remote host.", GAUGE)
        for (h in shown) b.point("host_connections", num(h.conns), hostLabels(h))

        b.family("host_receive_bytes_per_second", "Current receive rate per remote host.", GAUGE)
        for (h in shown) b.point("host_receive_bytes_per_second", num(h.rxBps), hostLabels(h))

        b.family("host_transmit_bytes_per_second", "Current transmit rate per remote host.", GAUGE)
        for (h in shown) b.point("host_transmit_bytes_per_second", num(h.txBps), hostLabels(h))

        b.family("host_receive_bytes", "Bytes received from the remote host.", GAUGE)
        for (h in shown) b.point("host_receive_bytes", num(h.rx), hostLabels(h))

        b.family("host_transmit_bytes", "Bytes transmitted to the remote host.", GAUGE)
        for (h in shown) b.point("host_transmit_bytes", num(h.tx), hostLabels(h))
    }

    private fun hostLabels(h: HostAgg): List<Pair<String, String>> = listOf(
        "host" to h.host,
        "scope" to h.scope.toString(),
    )

    private class HostAgg(val host: String, val scope: Scope) {
        var conns = 0L
        var rx = 0L
        var tx = 0L
        var rxBps = 0L
        var txBps = 0L
        val bytes: Long get() = rx + tx
    }

    private fun rankedHosts(s: NetSnapshot): List<HostAgg> {
        val byHost = LinkedHashMap<String, HostAgg>()
        for (c in s.conns) {
            val key = hostKey(c)
            val agg = byHost.getOrPut(key) { HostAgg(key, c.scope) }
            agg.conns++
            agg.rx += c.rxBytes
            agg.tx += c.txBytes
            agg.rxBps += c.rxBps
            agg.txBps += c.txBps
        }
        return byHost.values.sortedWith(
            compareByDescending<HostAgg> { it.bytes }
                .thenByDescending { it.rxBps + it.txBps }
                .thenByDescending { it.conns }
                .thenBy { it.host },
        )
    }

    private fun hostKey(c: ConnRow): String =
        c.resolvedHost?.takeIf { it.isNotBlank() } ?: c.remoteAddr.ifBlank { UNKNOWN }

    /**
     * Emitted from one place so the family appears exactly once even though the
     * app and host sections are rendered separately.
     */
    private fun truncation(b: StringBuilder, appsDropped: Int, hostsDropped: Int) {
        b.family(
            "series_truncated",
            "Talkers dropped by the top-$MAX_TALKER_SERIES series cap, by family.",
            GAUGE,
        )
        b.point("series_truncated", num(appsDropped.coerceAtLeast(0).toLong()), listOf("family" to "app"))
        b.point("series_truncated", num(hostsDropped.coerceAtLeast(0).toLong()), listOf("family" to "host"))
    }

    private fun StringBuilder.family(name: String, help: String, type: String) {
        append("# HELP ").append(PREFIX).append(name).append(' ').append(escapeHelp(help)).append('\n')
        append("# TYPE ").append(PREFIX).append(name).append(' ').append(type).append('\n')
    }

    private fun StringBuilder.point(
        name: String,
        value: String,
        labels: List<Pair<String, String>> = emptyList(),
    ) {
        append(PREFIX).append(name)
        if (labels.isNotEmpty()) {
            append('{')
            for ((i, l) in labels.withIndex()) {
                if (i > 0) append(',')
                append(l.first).append('=').append('"').append(escapeLabel(l.second)).append('"')
            }
            append('}')
        }
        append(' ').append(value).append('\n')
    }

    private fun num(v: Long): String = v.toString()

    /** Locale-independent on purpose: a comma decimal separator is not parseable. */
    private fun num(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "0" else String.format(Locale.ROOT, "%.3f", v)

    /**
     * The three escapes the exposition format defines for a label value. A bare
     * carriage return is dropped rather than escaped: there is no `\r` escape to
     * unescape it with, and left raw it would truncate the line for some readers.
     */
    fun escapeLabel(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (ch in v) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** HELP text escapes only the backslash and the newline; a quote is literal. */
    fun escapeHelp(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (ch in v) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Label names have no escaping mechanism, so anything that cannot be spelled
     * as `[a-zA-Z_][a-zA-Z0-9_]*` is rewritten, and a name with nothing usable
     * left in it is dropped rather than emitted broken.
     */
    fun sanitizeLabelName(raw: String): String? {
        if (raw.isEmpty()) return null
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(if (ch.isDigit() || ch in 'a'..'z' || ch in 'A'..'Z' || ch == '_') ch else '_')
        }
        if (sb.first().isDigit()) sb.insert(0, '_')
        val out = sb.toString()
        // "__" is the reserved prefix; an all-underscore name carries nothing.
        return if (out.all { it == '_' }) null else out
    }

    private const val GAUGE = "gauge"
    private const val UNKNOWN = "unknown"
}
