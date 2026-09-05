package com.vlad.ducknetview.domain.export

import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.CellularState
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.RouteRow
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.SecurityCounts
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.model.WifiState

/**
 * The TUI's `--json` / `--from snapshot` interchange format.
 *
 * Hand-rolled on purpose: org.json is an Android framework class (and a stub
 * that throws under a plain JVM test), and pulling in a serialization runtime
 * for one file would put a compiler plugin between the domain layer and its
 * tests. The writer and the recursive-descent reader below are symmetric and
 * round-trip tested.
 */
object SnapshotJson {

    fun encode(s: NetSnapshot): String {
        val sb = StringBuilder(4096)
        writeValue(snapshotToMap(s), sb)
        return sb.toString()
    }

    fun decode(json: String): NetSnapshot {
        val root = Parser(json).parseDocument()
        val map = asObject(root) ?: throw IllegalArgumentException("expected a JSON object")
        return snapshotFromMap(map)
    }

    private fun snapshotToMap(s: NetSnapshot): Map<String, Any?> = linkedMapOf(
        "atMillis" to s.atMillis,
        "engine" to s.engine.name,
        "paused" to s.paused,
        "intervalSeconds" to s.intervalSeconds,
        "deviceName" to s.deviceName,
        "uptimeMillis" to s.uptimeMillis,
        "networks" to s.networks.map(::networkToMap),
        "selectedNetworkId" to s.selectedNetworkId,
        "conns" to s.conns.map(::connToMap),
        "closedConns" to s.closedConns.map(::closedToMap),
        "apps" to s.apps.map(::appToMap),
        "services" to s.services.map(::serviceToMap),
        "serviceScanAt" to s.serviceScanAt,
        "serviceScanRunning" to s.serviceScanRunning,
        "total" to linkedMapOf<String, Any?>("rxBps" to s.total.rxBps, "txBps" to s.total.txBps),
        "totalPeakRx" to s.totalPeakRx,
        "totalPeakTx" to s.totalPeakTx,
        "sessionRx" to s.sessionRx,
        "sessionTx" to s.sessionTx,
        "rxHistory" to s.rxHistory,
        "txHistory" to s.txHistory,
        "churnHistory" to s.churnHistory,
        "newConnCount" to s.newConnCount,
        "closedConnCount" to s.closedConnCount,
        "topApps" to s.topApps.map(::talkerToMap),
        "topHosts" to s.topHosts.map(::talkerToMap),
        "latency" to s.latency.map(::latencyToMap),
        "externalIp" to s.externalIp,
        "externalIpAt" to s.externalIpAt,
        "security" to linkedMapOf<String, Any?>(
            "exposedServices" to s.security.exposedServices,
            "publicConns" to s.security.publicConns,
            "watchlistHits" to s.security.watchlistHits,
            "offBaseline" to s.security.offBaseline,
        ),
        "frozen" to s.frozen,
        "frozenLabel" to s.frozenLabel,
        "error" to s.error,
    )

    private fun snapshotFromMap(m: Map<String, Any?>): NetSnapshot {
        val total = obj(m, "total")
        val security = obj(m, "security")
        return NetSnapshot(
            atMillis = long(m, "atMillis"),
            engine = enum(m, "engine", EngineMode.API),
            paused = bool(m, "paused"),
            intervalSeconds = int(m, "intervalSeconds", 2),
            deviceName = str(m, "deviceName"),
            uptimeMillis = long(m, "uptimeMillis"),
            networks = objects(m, "networks").map(::networkFromMap),
            selectedNetworkId = strOrNull(m, "selectedNetworkId"),
            conns = objects(m, "conns").map(::connFromMap),
            closedConns = objects(m, "closedConns").map(::closedFromMap),
            apps = objects(m, "apps").map(::appFromMap),
            services = objects(m, "services").map(::serviceFromMap),
            serviceScanAt = long(m, "serviceScanAt"),
            serviceScanRunning = bool(m, "serviceScanRunning"),
            total = Throughput(long(total, "rxBps"), long(total, "txBps")),
            totalPeakRx = long(m, "totalPeakRx"),
            totalPeakTx = long(m, "totalPeakTx"),
            sessionRx = long(m, "sessionRx"),
            sessionTx = long(m, "sessionTx"),
            rxHistory = floats(m, "rxHistory"),
            txHistory = floats(m, "txHistory"),
            churnHistory = floats(m, "churnHistory"),
            newConnCount = int(m, "newConnCount"),
            closedConnCount = int(m, "closedConnCount"),
            topApps = objects(m, "topApps").map(::talkerFromMap),
            topHosts = objects(m, "topHosts").map(::talkerFromMap),
            latency = objects(m, "latency").map(::latencyFromMap),
            externalIp = strOrNull(m, "externalIp"),
            externalIpAt = long(m, "externalIpAt"),
            security = SecurityCounts(
                exposedServices = int(security, "exposedServices"),
                publicConns = int(security, "publicConns"),
                watchlistHits = int(security, "watchlistHits"),
                offBaseline = int(security, "offBaseline"),
            ),
            frozen = bool(m, "frozen"),
            frozenLabel = strOrNull(m, "frozenLabel"),
            error = strOrNull(m, "error"),
        )
    }

    private fun networkToMap(n: NetworkRow): Map<String, Any?> = linkedMapOf(
        "id" to n.id,
        "ifaceName" to n.ifaceName,
        "transport" to n.transport.name,
        "up" to n.up,
        "addresses" to n.addresses,
        "mtu" to n.mtu,
        "metered" to n.metered,
        "validated" to n.validated,
        "isDefault" to n.isDefault,
        "gateway" to n.gateway,
        "dnsServers" to n.dnsServers,
        "domains" to n.domains,
        "privateDns" to n.privateDns,
        "routes" to n.routes.map { r ->
            linkedMapOf<String, Any?>(
                "destination" to r.destination,
                "gateway" to r.gateway,
                "iface" to r.iface,
                "isDefault" to r.isDefault,
            )
        },
        "rxBytes" to n.rxBytes,
        "txBytes" to n.txBytes,
        "rxBps" to n.rxBps,
        "txBps" to n.txBps,
        "peakRxBps" to n.peakRxBps,
        "peakTxBps" to n.peakTxBps,
        "rxHistory" to n.rxHistory,
        "txHistory" to n.txHistory,
        "wifi" to n.wifi?.let { w ->
            linkedMapOf<String, Any?>(
                "ssid" to w.ssid,
                "bssid" to w.bssid,
                "rssiDbm" to w.rssiDbm,
                "linkSpeedMbps" to w.linkSpeedMbps,
                "txLinkSpeedMbps" to w.txLinkSpeedMbps,
                "rxLinkSpeedMbps" to w.rxLinkSpeedMbps,
                "frequencyMhz" to w.frequencyMhz,
                "standard" to w.standard,
            )
        },
        "cellular" to n.cellular?.let { c ->
            linkedMapOf<String, Any?>(
                "networkType" to c.networkType,
                "operator" to c.operator,
                "signalLevel" to c.signalLevel,
            )
        },
    )

    private fun networkFromMap(m: Map<String, Any?>): NetworkRow {
        val wifi = obj(m, "wifi")
        val cell = obj(m, "cellular")
        return NetworkRow(
            id = str(m, "id"),
            ifaceName = str(m, "ifaceName"),
            transport = enum(m, "transport", Transport.OTHER),
            up = bool(m, "up"),
            addresses = strings(m, "addresses"),
            mtu = int(m, "mtu"),
            metered = bool(m, "metered"),
            validated = bool(m, "validated"),
            isDefault = bool(m, "isDefault"),
            gateway = strOrNull(m, "gateway"),
            dnsServers = strings(m, "dnsServers"),
            domains = strOrNull(m, "domains"),
            privateDns = strOrNull(m, "privateDns"),
            routes = objects(m, "routes").map { r ->
                RouteRow(
                    destination = str(r, "destination"),
                    gateway = strOrNull(r, "gateway"),
                    iface = str(r, "iface"),
                    isDefault = bool(r, "isDefault"),
                )
            },
            rxBytes = long(m, "rxBytes"),
            txBytes = long(m, "txBytes"),
            rxBps = long(m, "rxBps"),
            txBps = long(m, "txBps"),
            peakRxBps = long(m, "peakRxBps"),
            peakTxBps = long(m, "peakTxBps"),
            rxHistory = floats(m, "rxHistory"),
            txHistory = floats(m, "txHistory"),
            wifi = if (wifi.isEmpty()) null else WifiState(
                ssid = strOrNull(wifi, "ssid"),
                bssid = strOrNull(wifi, "bssid"),
                rssiDbm = int(wifi, "rssiDbm", WifiState.UNKNOWN_RSSI),
                linkSpeedMbps = int(wifi, "linkSpeedMbps"),
                txLinkSpeedMbps = int(wifi, "txLinkSpeedMbps", -1),
                rxLinkSpeedMbps = int(wifi, "rxLinkSpeedMbps", -1),
                frequencyMhz = int(wifi, "frequencyMhz"),
                standard = strOrNull(wifi, "standard"),
            ),
            cellular = if (cell.isEmpty()) null else CellularState(
                networkType = str(cell, "networkType"),
                operator = strOrNull(cell, "operator"),
                signalLevel = int(cell, "signalLevel"),
            ),
        )
    }

    private fun connToMap(c: ConnRow): Map<String, Any?> = linkedMapOf(
        "key" to c.key,
        "proto" to c.proto.name,
        "localAddr" to c.localAddr,
        "localPort" to c.localPort,
        "remoteAddr" to c.remoteAddr,
        "remotePort" to c.remotePort,
        "state" to c.state.name,
        "uid" to c.uid,
        "appLabel" to c.appLabel,
        "packageName" to c.packageName,
        "service" to c.service,
        "scope" to c.scope.name,
        "rxBytes" to c.rxBytes,
        "txBytes" to c.txBytes,
        "rxBps" to c.rxBps,
        "txBps" to c.txBps,
        "rttMillis" to c.rttMillis,
        "firstSeen" to c.firstSeen,
        "lastSeen" to c.lastSeen,
        "network" to c.network,
        "resolvedHost" to c.resolvedHost,
        "isNew" to c.isNew,
        "watchlisted" to c.watchlisted,
        "blocked" to c.blocked,
    )

    private fun connFromMap(m: Map<String, Any?>): ConnRow = ConnRow(
        key = str(m, "key"),
        proto = enum(m, "proto", Proto.OTHER),
        localAddr = str(m, "localAddr"),
        localPort = int(m, "localPort"),
        remoteAddr = str(m, "remoteAddr"),
        remotePort = int(m, "remotePort"),
        state = enum(m, "state", ConnState.ACTIVE),
        uid = int(m, "uid", -1),
        appLabel = str(m, "appLabel"),
        packageName = str(m, "packageName"),
        service = str(m, "service"),
        scope = enum(m, "scope", Scope.PUBLIC),
        rxBytes = long(m, "rxBytes"),
        txBytes = long(m, "txBytes"),
        rxBps = long(m, "rxBps"),
        txBps = long(m, "txBps"),
        rttMillis = int(m, "rttMillis", -1),
        firstSeen = long(m, "firstSeen"),
        lastSeen = long(m, "lastSeen"),
        network = str(m, "network"),
        resolvedHost = strOrNull(m, "resolvedHost"),
        isNew = bool(m, "isNew"),
        watchlisted = bool(m, "watchlisted"),
        blocked = bool(m, "blocked"),
    )

    private fun closedToMap(c: ClosedConn): Map<String, Any?> = linkedMapOf(
        "row" to connToMap(c.row),
        "closedAt" to c.closedAt,
        "lifetimeMillis" to c.lifetimeMillis,
        "finalRx" to c.finalRx,
        "finalTx" to c.finalTx,
    )

    private fun closedFromMap(m: Map<String, Any?>): ClosedConn = ClosedConn(
        row = connFromMap(obj(m, "row")),
        closedAt = long(m, "closedAt"),
        lifetimeMillis = long(m, "lifetimeMillis"),
        finalRx = long(m, "finalRx"),
        finalTx = long(m, "finalTx"),
    )

    private fun appToMap(a: AppRow): Map<String, Any?> = linkedMapOf(
        "uid" to a.uid,
        "packageName" to a.packageName,
        "label" to a.label,
        "isSystem" to a.isSystem,
        "connCount" to a.connCount,
        "rxBps" to a.rxBps,
        "txBps" to a.txBps,
        "sessionRx" to a.sessionRx,
        "sessionTx" to a.sessionTx,
        "todayRx" to a.todayRx,
        "todayTx" to a.todayTx,
        "blocked" to a.blocked,
        "excludedFromVpn" to a.excludedFromVpn,
        "remoteHosts" to a.remoteHosts,
    )

    private fun appFromMap(m: Map<String, Any?>): AppRow = AppRow(
        uid = int(m, "uid", -1),
        packageName = str(m, "packageName"),
        label = str(m, "label"),
        isSystem = bool(m, "isSystem"),
        connCount = int(m, "connCount"),
        rxBps = long(m, "rxBps"),
        txBps = long(m, "txBps"),
        sessionRx = long(m, "sessionRx"),
        sessionTx = long(m, "sessionTx"),
        todayRx = long(m, "todayRx"),
        todayTx = long(m, "todayTx"),
        blocked = bool(m, "blocked"),
        excludedFromVpn = bool(m, "excludedFromVpn"),
        remoteHosts = int(m, "remoteHosts"),
    )

    private fun serviceToMap(s: ServiceRow): Map<String, Any?> = linkedMapOf(
        "proto" to s.proto.name,
        "bindAddr" to s.bindAddr,
        "port" to s.port,
        "service" to s.service,
        "exposure" to s.exposure.name,
        "firstSeen" to s.firstSeen,
        "lastSeen" to s.lastSeen,
        "isNew" to s.isNew,
        "offBaseline" to s.offBaseline,
        "uid" to s.uid,
        "appLabel" to s.appLabel,
    )

    private fun serviceFromMap(m: Map<String, Any?>): ServiceRow = ServiceRow(
        proto = enum(m, "proto", Proto.OTHER),
        bindAddr = str(m, "bindAddr"),
        port = int(m, "port"),
        service = str(m, "service"),
        exposure = enum(m, "exposure", Exposure.LOCAL),
        firstSeen = long(m, "firstSeen"),
        lastSeen = long(m, "lastSeen"),
        isNew = bool(m, "isNew"),
        offBaseline = bool(m, "offBaseline"),
        uid = int(m, "uid", -1),
        appLabel = str(m, "appLabel"),
    )

    private fun talkerToMap(t: Talker): Map<String, Any?> =
        linkedMapOf("key" to t.key, "label" to t.label, "rx" to t.rx, "tx" to t.tx)

    private fun talkerFromMap(m: Map<String, Any?>): Talker =
        Talker(key = str(m, "key"), label = str(m, "label"), rx = long(m, "rx"), tx = long(m, "tx"))

    private fun latencyToMap(l: LatencySample): Map<String, Any?> = linkedMapOf(
        "target" to l.target,
        "label" to l.label,
        "millis" to l.millis,
        "ok" to l.ok,
        "history" to l.history,
        "note" to l.note,
    )

    private fun latencyFromMap(m: Map<String, Any?>): LatencySample = LatencySample(
        target = str(m, "target"),
        label = str(m, "label"),
        millis = int(m, "millis"),
        ok = bool(m, "ok"),
        history = floats(m, "history"),
        note = strOrNull(m, "note"),
    )

    private fun writeValue(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(v, sb)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> writeDouble(v.toDouble(), sb)
            is Double -> writeDouble(v, sb)
            is Enum<*> -> writeString(v.name, sb)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeValue(value, sb)
                }
                sb.append('}')
            }

            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(item, sb)
                }
                sb.append(']')
            }

            else -> writeString(v.toString(), sb)
        }
    }

    /** JSON has no NaN or Infinity, and a snapshot must never fail to encode. */
    private fun writeDouble(d: Double, sb: StringBuilder) {
        if (d.isNaN() || d.isInfinite()) {
            sb.append('0')
            return
        }
        sb.append(d.toString())
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> {
                    sb.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                }

                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            val v = parseValue()
            skipWhitespace()
            if (pos != src.length) fail("trailing content")
            return v
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= src.length) fail("unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("unexpected '$c'")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected an object key")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("expected ':' after key '$key'")
                pos++
                out[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }

                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }

                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("unterminated string")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) fail("unterminated escape")
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) fail("truncated \\u escape")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }

                            else -> fail("unknown escape '\\$e'")
                        }
                    }

                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            var isFloat = false
            while (pos < src.length) {
                val c = src[pos]
                when {
                    c in '0'..'9' -> pos++
                    c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' -> {
                        isFloat = true
                        pos++
                    }

                    else -> break
                }
            }
            val text = src.substring(start, pos)
            if (text.isEmpty() || text == "-") fail("bad number")
            return if (isFloat) {
                text.toDoubleOrNull() ?: fail("bad number '$text'")
            } else {
                text.toLongOrNull() ?: text.toDoubleOrNull() ?: fail("bad number '$text'")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!src.startsWith(word, pos)) fail("expected '$word'")
            pos += word.length
            return value
        }

        private fun peek(): Char {
            if (pos >= src.length) fail("unexpected end of input")
            return src[pos]
        }

        private fun skipWhitespace() {
            while (pos < src.length && (src[pos] == ' ' || src[pos] == '\t' ||
                    src[pos] == '\n' || src[pos] == '\r')
            ) {
                pos++
            }
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("$message at offset $pos")
    }

    @Suppress("UNCHECKED_CAST")
    private fun asObject(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    private fun obj(m: Map<String, Any?>, key: String): Map<String, Any?> =
        asObject(m[key]) ?: emptyMap()

    private fun objects(m: Map<String, Any?>, key: String): List<Map<String, Any?>> =
        (m[key] as? List<*>)?.mapNotNull(::asObject) ?: emptyList()

    private fun str(m: Map<String, Any?>, key: String, default: String = ""): String =
        m[key] as? String ?: default

    private fun strOrNull(m: Map<String, Any?>, key: String): String? = m[key] as? String

    private fun strings(m: Map<String, Any?>, key: String): List<String> =
        (m[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun floats(m: Map<String, Any?>, key: String): List<Float> =
        (m[key] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() } ?: emptyList()

    private fun long(m: Map<String, Any?>, key: String, default: Long = 0L): Long =
        (m[key] as? Number)?.toLong() ?: default

    private fun int(m: Map<String, Any?>, key: String, default: Int = 0): Int =
        (m[key] as? Number)?.toInt() ?: default

    private fun bool(m: Map<String, Any?>, key: String, default: Boolean = false): Boolean =
        m[key] as? Boolean ?: default

    /** An unknown or missing enum name falls back rather than failing the decode. */
    private inline fun <reified E : Enum<E>> enum(
        m: Map<String, Any?>,
        key: String,
        default: E,
    ): E {
        val name = m[key] as? String ?: return default
        return try {
            enumValueOf<E>(name.uppercase())
        } catch (_: IllegalArgumentException) {
            default
        }
    }
}
