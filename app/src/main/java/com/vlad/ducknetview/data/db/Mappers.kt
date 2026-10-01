package com.vlad.ducknetview.data.db

import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.model.fmtAddr
import com.vlad.ducknetview.domain.usage.DailyUsage

/**
 * A self-contained string codec for the collections this layer has to squeeze
 * into a single SQLite / DataStore column. It exists instead of a JSON
 * dependency so the whole thing stays a plain-JVM unit test.
 *
 * Grammar: every element is escaped and *terminated* by `;` (terminated, not
 * separated, so `listOf("")` and `emptyList()` do not collapse onto the same
 * encoding); map entries are `key=value`. `\` escapes `\`, `;` and `=`.
 *
 * Decoding is deliberately forgiving — a truncated or hand-edited value yields
 * whatever entries are still readable rather than an exception. The one place
 * that reports failure is [decodeIntSet], where a non-numeric token means the
 * value cannot be honoured at all and the caller must fall back to its default.
 */
object Codec {

    private const val ESC = '\\'
    private const val ENTRY = ';'
    private const val KV = '='

    fun encodeList(values: List<String>): String {
        if (values.isEmpty()) return ""
        val sb = StringBuilder()
        for (v in values) sb.append(escape(v)).append(ENTRY)
        return sb.toString()
    }

    fun decodeList(s: String): List<String> = splitRaw(s, ENTRY).map(::unescape)

    fun encodeIntSet(values: Set<Int>): String = encodeList(values.map { it.toString() })

    /** Returns null when any token is not an integer, i.e. the value is unusable. */
    fun decodeIntSet(s: String): Set<Int>? {
        val out = LinkedHashSet<Int>()
        for (raw in decodeList(s)) {
            val n = raw.trim().toIntOrNull() ?: return null
            out.add(n)
        }
        return out
    }

    fun encodeMap(map: Map<String, Long>): String {
        if (map.isEmpty()) return ""
        val sb = StringBuilder()
        for ((k, v) in map) sb.append(escape(k)).append(KV).append(v).append(ENTRY)
        return sb.toString()
    }

    fun decodeMap(s: String): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (entry in splitRaw(s, ENTRY)) {
            val parts = splitRaw(entry, KV)
            if (parts.size != 2) continue
            val value = unescape(parts[1]).trim().toLongOrNull() ?: continue
            out[unescape(parts[0])] = value
        }
        return out
    }

    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            if (c == ESC || c == ENTRY || c == KV) sb.append(ESC)
            sb.append(c)
        }
        return sb.toString()
    }

    /** Splits on unescaped `sep` while leaving escape sequences intact. */
    private fun splitRaw(s: String, sep: Char): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var pending = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == ESC && i + 1 < s.length -> {
                    sb.append(c).append(s[i + 1])
                    i += 2
                    pending = true
                }
                c == sep -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                    i++
                    pending = false
                }
                else -> {
                    sb.append(c)
                    i++
                    pending = true
                }
            }
        }
        if (pending) out.add(sb.toString())
        return out
    }

    private fun unescape(s: String): String {
        if (s.indexOf(ESC) < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ESC && i + 1 < s.length -> {
                    sb.append(s[i + 1])
                    i += 2
                }
                c == ESC -> i++
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}

fun Event.toEntity(): EventEntity = EventEntity(
    id = id,
    at = at,
    level = level.name,
    kind = kind.label,
    subject = subject,
    detail = detail,
)

fun EventEntity.toDomain(): Event = Event(
    id = id,
    at = at,
    level = decodeLevel(level),
    kind = decodeKind(kind),
    subject = subject,
    detail = detail,
)

/** An unrecognised level is informational; it must never sink a whole read. */
private fun decodeLevel(s: String): EventLevel =
    EventLevel.entries.firstOrNull { it.name == s } ?: EventLevel.INFO

/**
 * A row written by a newer build can name a kind this build has no constant
 * for. It lands in SUPPRESSED — the bucket already meaning "an event happened
 * that is not being shown in full".
 */
private fun decodeKind(s: String): EventKind =
    EventKind.fromLabel(s) ?: EventKind.SUPPRESSED

fun ClosedConn.toEntity(): ClosedConnEntity = ClosedConnEntity(
    closedAt = closedAt,
    proto = row.proto.name,
    localAddr = row.localAddr,
    localPort = row.localPort,
    remoteAddr = row.remoteAddr,
    remotePort = row.remotePort,
    uid = row.uid,
    appLabel = row.appLabel,
    service = row.service,
    scope = row.scope.name,
    finalRx = finalRx,
    finalTx = finalTx,
    lifetimeMillis = lifetimeMillis,
    network = row.network,
    resolvedHost = row.resolvedHost,
)

fun ClosedConnEntity.toDomain(): ClosedConn {
    val p = decodeProto(proto)
    val row = ConnRow(
        key = connKey(p, localAddr, localPort, remoteAddr, remotePort),
        proto = p,
        localAddr = localAddr,
        localPort = localPort,
        remoteAddr = remoteAddr,
        remotePort = remotePort,
        state = ConnState.CLOSED,
        uid = uid,
        appLabel = appLabel,
        service = service,
        scope = decodeScope(scope),
        // For a torn-down flow the final counters are its lifetime totals, so
        // they are what the byte columns must show; rates are meaningless now.
        rxBytes = finalRx,
        txBytes = finalTx,
        firstSeen = closedAt - lifetimeMillis,
        lastSeen = closedAt,
        network = network,
        resolvedHost = resolvedHost,
    )
    return ClosedConn(
        row = row,
        closedAt = closedAt,
        lifetimeMillis = lifetimeMillis,
        finalRx = finalRx,
        finalTx = finalTx,
    )
}

/**
 * The live flow table's key is not persisted (it is only meaningful while the
 * flow exists), so history rows get a deterministic key rebuilt from the tuple.
 */
fun connKey(proto: Proto, localAddr: String, localPort: Int, remoteAddr: String, remotePort: Int): String =
    "$proto|${fmtAddr(localAddr, localPort)}|${fmtAddr(remoteAddr, remotePort)}"

private fun decodeProto(s: String): Proto =
    Proto.entries.firstOrNull { it.name == s } ?: Proto.OTHER

private fun decodeScope(s: String): Scope =
    Scope.entries.firstOrNull { it.name == s } ?: Scope.PUBLIC

fun DailyUsage.toEntity(): DailyUsageEntity = DailyUsageEntity(
    dayEpoch = dayEpoch,
    rx = rx,
    tx = tx,
    appsJson = Codec.encodeMap(apps),
    hostsJson = Codec.encodeMap(hosts),
)

fun DailyUsageEntity.toDomain(): DailyUsage = DailyUsage(
    dayEpoch = dayEpoch,
    rx = rx,
    tx = tx,
    apps = Codec.decodeMap(appsJson),
    hosts = Codec.decodeMap(hostsJson),
)

fun DomainRow.toEntity(): DomainEntity = DomainEntity(
    name = name,
    uid = uid,
    source = source.name,
    lookups = lookups,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    addresses = Codec.encodeList(addresses),
)

fun DomainEntity.toDomain(): DomainRow = DomainRow(
    name = name,
    uid = uid,
    source = decodeNameSource(source),
    lookups = lookups,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    addresses = Codec.decodeList(addresses),
)

private fun decodeNameSource(s: String): NameSource =
    NameSource.entries.firstOrNull { it.name == s } ?: NameSource.DNS
