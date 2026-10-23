package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.ui.HostGroup

/** RFC 4180 CSV. Every table exports the view the user is looking at. */
object Csv {

    /** RFC 4180 says CRLF; spreadsheet importers on every platform accept it. */
    const val EOL = "\r\n"

    fun of(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        if (headers.isNotEmpty()) {
            sb.append(line(headers))
            sb.append(EOL)
        }
        for (row in rows) {
            sb.append(line(row))
            sb.append(EOL)
        }
        return sb.toString()
    }

    fun line(fields: List<String>): String = fields.joinToString(",") { field(it) }

    /**
     * Quotes when the value contains a delimiter, a quote, a newline, or
     * leading/trailing whitespace that an importer would otherwise eat.
     */
    fun field(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            value != value.trim()
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun conns(rows: List<ConnRow>, now: Long): String = of(
        listOf(
            "proto", "local", "remote", "resolved", "service", "scope", "state",
            "uid", "app", "package", "rx_bps", "tx_bps", "rx_bytes", "tx_bytes",
            "rtt_ms", "age_ms", "network", "watchlisted",
        ),
        rows.map { c ->
            listOf(
                c.proto.toString(), c.local, c.remote, c.resolvedHost ?: "", c.service,
                c.scope.toString(), c.state.toString(), c.uid.toString(), c.appLabel,
                c.packageName, c.rxBps.toString(), c.txBps.toString(),
                c.rxBytes.toString(), c.txBytes.toString(), c.rttMillis.toString(),
                c.ageMillis(now).toString(), c.network, c.watchlisted.toString(),
            )
        },
    )

    fun apps(rows: List<AppRow>): String = of(
        listOf(
            "uid", "package", "label", "system", "conns", "rx_bps", "tx_bps",
            "session_rx", "session_tx", "today_rx", "today_tx", "remote_hosts", "blocked",
            "blocked_attempts",
        ),
        rows.map { a ->
            listOf(
                a.uid.toString(), a.packageName, a.label, a.isSystem.toString(),
                a.connCount.toString(), a.rxBps.toString(), a.txBps.toString(),
                a.sessionRx.toString(), a.sessionTx.toString(),
                a.todayRx.toString(), a.todayTx.toString(),
                a.remoteHosts.toString(), a.blocked.toString(),
                a.blockedAttempts.toString(),
            )
        },
    )

    fun neighbours(rows: List<NeighbourRow>): String = of(
        listOf("address", "hostname", "source", "rtt_ms", "services", "gateway", "self", "last_seen"),
        rows.map { n ->
            listOf(
                n.address, n.hostname, n.source.label,
                if (n.rttMillis >= 0) n.rttMillis.toString() else "",
                n.services.joinToString(" "),
                n.isGateway.toString(), n.isSelf.toString(), n.lastSeen.toString(),
            )
        },
    )

    fun services(rows: List<ServiceRow>): String = of(
        listOf(
            "proto", "bind", "port", "service", "exposure", "uid", "app",
            "first_seen", "last_seen", "off_baseline",
        ),
        rows.map { s ->
            listOf(
                s.proto.toString(), s.bindAddr, s.port.toString(), s.service,
                s.exposure.name.lowercase(), s.uid.toString(), s.appLabel,
                s.firstSeen.toString(), s.lastSeen.toString(), s.offBaseline.toString(),
            )
        },
    )

    fun domains(rows: List<DomainRow>): String = of(
        listOf(
            "name", "uid", "app", "package", "source", "lookups",
            "first_seen", "last_seen", "addresses",
        ),
        rows.map { d ->
            listOf(
                d.name, d.uid.toString(), d.appLabel, d.packageName,
                d.source.label, d.lookups.toString(),
                d.firstSeen.toString(), d.lastSeen.toString(),
                d.addresses.joinToString(" "),
            )
        },
    )

    fun events(rows: List<Event>): String =
        of(listOf("at", "level", "kind", "subject", "detail"), rows.map { it.toCsvRow() })

    /**
     * Retired flows, with the numbers only a closed socket has.
     *
     * The Connections screen has three modes and the Share button exported the
     * live table in all of them: measured on the M10, a screen reading "8
     * closed" exported fourteen rows every one of which said `established`,
     * with no close time, no lifetime and no final totals anywhere in the file.
     * The counters here are the lifetime ones — for a closed socket those are
     * the true totals, which is the whole reason the closed table exists.
     */
    fun closed(rows: List<ClosedConn>): String = of(
        listOf(
            "proto", "local", "remote", "resolved", "service", "scope", "state",
            "uid", "app", "package", "rx_bytes", "tx_bytes", "rtt_ms",
            "closed_at", "lifetime_ms", "network", "watchlisted",
        ),
        rows.map { c ->
            val r = c.row
            listOf(
                r.proto.toString(), r.local, r.remote, r.resolvedHost ?: "", r.service,
                r.scope.toString(), r.state.toString(), r.uid.toString(), r.appLabel,
                r.packageName, c.finalRx.toString(), c.finalTx.toString(),
                r.rttMillis.toString(), c.closedAt.toString(), c.lifetimeMillis.toString(),
                r.network, r.watchlisted.toString(),
            )
        },
    )

    /** One row per remote host, as the grouped view folds them. */
    fun groups(rows: List<HostGroup>): String = of(
        listOf(
            "host", "display", "conns", "scope", "rx_bps", "tx_bps",
            "rx_bytes", "tx_bytes", "states", "apps", "watchlisted",
        ),
        rows.map { g ->
            listOf(
                g.host, g.display, g.connCount.toString(), g.scope.toString(),
                g.rxBps.toString(), g.txBps.toString(),
                g.rxTotal.toString(), g.txTotal.toString(),
                g.states.entries.joinToString(" ") { "${it.key}=${it.value}" },
                g.apps.joinToString(" "),
                g.watchlisted.toString(),
            )
        },
    )
}
