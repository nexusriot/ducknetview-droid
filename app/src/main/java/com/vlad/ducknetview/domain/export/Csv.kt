package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.ServiceRow

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
        ),
        rows.map { a ->
            listOf(
                a.uid.toString(), a.packageName, a.label, a.isSystem.toString(),
                a.connCount.toString(), a.rxBps.toString(), a.txBps.toString(),
                a.sessionRx.toString(), a.sessionTx.toString(),
                a.todayRx.toString(), a.todayTx.toString(),
                a.remoteHosts.toString(), a.blocked.toString(),
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
}
