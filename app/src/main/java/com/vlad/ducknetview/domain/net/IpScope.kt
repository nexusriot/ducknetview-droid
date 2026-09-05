package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Scope

/**
 * Address parsing without java.net: [java.net.InetAddress] resolves names on
 * some paths, which would turn a pure classification call into a blocking DNS
 * lookup. Everything here is dotted-quad / hex-group parsing and bit masks.
 */
object IpBytes {

    /** 4 bytes for IPv4, 16 for IPv6, null when the literal is not an address. */
    fun parse(s: String): ByteArray? {
        val t = trimLiteral(s)
        if (t.isEmpty()) return null
        return if (t.indexOf(':') >= 0) parseV6(t) else parseV4(t)
    }

    /** [parse] with IPv4-mapped IPv6 folded down to its 4 IPv4 bytes. */
    fun parseNormalized(s: String): ByteArray? = parse(s)?.let(::normalize)

    /** Strips `[...]` brackets and any `%zone` suffix. */
    fun trimLiteral(s: String): String {
        var t = s.trim()
        if (t.length >= 2 && t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length - 1)
        val zone = t.indexOf('%')
        if (zone >= 0) t = t.substring(0, zone)
        return t
    }

    fun parseV4(s: String): ByteArray? {
        val out = ByteArray(4)
        var octet = 0
        var value = -1
        var digits = 0
        for (c in s) {
            if (c == '.') {
                if (digits == 0 || octet == 3) return null
                out[octet++] = value.toByte()
                value = -1
                digits = 0
                continue
            }
            if (c < '0' || c > '9') return null
            // A leading zero would be octal to some resolvers and decimal to
            // others; refusing it keeps this parser unambiguous.
            if (digits == 1 && value == 0) return null
            value = (if (value < 0) 0 else value) * 10 + (c - '0')
            digits++
            if (digits > 3 || value > 255) return null
        }
        if (digits == 0 || octet != 3) return null
        out[3] = value.toByte()
        return out
    }

    fun parseV6(s: String): ByteArray? {
        var text = s
        var embedded: ByteArray? = null

        val dot = text.indexOf('.')
        if (dot >= 0) {
            val colon = text.lastIndexOf(':', dot)
            if (colon < 0) return null
            embedded = parseV4(text.substring(colon + 1)) ?: return null
            // Keep the second colon of a "::" that immediately precedes the
            // embedded IPv4 part, drop a plain group separator.
            text = if (colon > 0 && text[colon - 1] == ':') text.substring(0, colon + 1)
            else text.substring(0, colon)
        }

        val out = ByteArray(16)
        var span = 16
        if (embedded != null) {
            out[12] = embedded[0]
            out[13] = embedded[1]
            out[14] = embedded[2]
            out[15] = embedded[3]
            span = 12
        }
        val capacity = span / 2

        val dc = text.indexOf("::")
        if (dc >= 0 && text.indexOf("::", dc + 2) >= 0) return null

        val head: List<String>
        val tail: List<String>
        if (dc >= 0) {
            head = groups(text.substring(0, dc)) ?: return null
            tail = groups(text.substring(dc + 2)) ?: return null
            if (head.size + tail.size > capacity - 1) return null
        } else {
            head = groups(text) ?: return null
            tail = emptyList()
            if (head.size != capacity) return null
        }

        for ((i, g) in head.withIndex()) {
            val v = hex(g) ?: return null
            out[i * 2] = (v ushr 8).toByte()
            out[i * 2 + 1] = (v and 0xFF).toByte()
        }
        val base = span - tail.size * 2
        for ((i, g) in tail.withIndex()) {
            val v = hex(g) ?: return null
            out[base + i * 2] = (v ushr 8).toByte()
            out[base + i * 2 + 1] = (v and 0xFF).toByte()
        }
        return out
    }

    /** Folds `::ffff:a.b.c.d` to 4 bytes; leaves `::1` and `::` alone. */
    fun normalize(b: ByteArray): ByteArray {
        if (b.size != 16) return b
        for (i in 0 until 10) if (b[i].toInt() != 0) return b
        if (b[10].toInt() and 0xFF != 0xFF || b[11].toInt() and 0xFF != 0xFF) return b
        return b.copyOfRange(12, 16)
    }

    fun isUnspecified(b: ByteArray): Boolean = b.all { it.toInt() == 0 }

    fun isLoopback(b: ByteArray): Boolean = when (b.size) {
        4 -> (b[0].toInt() and 0xFF) == 127
        16 -> {
            var only1 = true
            for (i in 0 until 15) if (b[i].toInt() != 0) only1 = false
            only1 && b[15].toInt() == 1
        }
        else -> false
    }

    private fun groups(s: String): List<String>? {
        if (s.isEmpty()) return emptyList()
        val parts = s.split(':')
        for (p in parts) if (p.isEmpty() || p.length > 4) return null
        return parts
    }

    private fun hex(s: String): Int? {
        var v = 0
        for (c in s) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> return null
            }
            v = v * 16 + d
        }
        return v
    }
}

/**
 * Coarse reachability classification of a bare address, ported from
 * probe.IPScope / probe.ListenerExposure.
 */
object IpScope {

    /**
     * An unparseable address (a hostname, a truncated literal) is reported as
     * PUBLIC: this feeds the "public connections" security count, where an
     * unknown peer is the one worth showing, not the one worth hiding.
     */
    fun of(addr: String): Scope {
        val b = IpBytes.parseNormalized(addr) ?: return Scope.PUBLIC
        return of(b)
    }

    fun of(bytes: ByteArray): Scope {
        val b = IpBytes.normalize(bytes)
        if (IpBytes.isUnspecified(b) || IpBytes.isLoopback(b)) return Scope.LOOPBACK
        return if (b.size == 4) v4(b) else v6(b)
    }

    /**
     * IPv4-mapped literals (`::ffff:10.0.0.1`) report false: they carry IPv4
     * traffic, which is what the version filter is asking about.
     */
    fun isIpv6(addr: String): Boolean {
        val raw = IpBytes.parse(addr) ?: return false
        return raw.size == 16 && IpBytes.normalize(raw).size == 16
    }

    fun isIpv4(addr: String): Boolean {
        val raw = IpBytes.parse(addr) ?: return false
        return IpBytes.normalize(raw).size == 4
    }

    /**
     * Where a listening socket can be reached from. A wildcard bind is EXPOSED
     * rather than LOCAL — it accepts on every interface — and an address that
     * cannot be parsed is EXPOSED too, because a security summary that fails
     * quiet is worse than one that fails loud.
     */
    fun exposureOf(bindAddr: String): Exposure {
        val raw = IpBytes.parse(bindAddr) ?: return Exposure.EXPOSED
        val b = IpBytes.normalize(raw)
        if (IpBytes.isUnspecified(b)) return Exposure.EXPOSED
        if (IpBytes.isLoopback(b)) return Exposure.LOCAL
        return if (of(b) == Scope.PRIVATE) Exposure.LAN else Exposure.EXPOSED
    }

    /** LOCAL < LAN < EXPOSED, so a rebind can be judged widening or narrowing. */
    fun exposureRank(e: Exposure): Int = e.ordinal

    private fun v4(b: ByteArray): Scope {
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return when {
            a0 in 224..239 -> Scope.MULTICAST
            a0 == 255 && a1 == 255 && (b[2].toInt() and 0xFF) == 255 &&
                (b[3].toInt() and 0xFF) == 255 -> Scope.MULTICAST
            a0 == 10 -> Scope.PRIVATE
            a0 == 172 && a1 in 16..31 -> Scope.PRIVATE
            a0 == 192 && a1 == 168 -> Scope.PRIVATE
            a0 == 100 && a1 in 64..127 -> Scope.PRIVATE
            a0 == 169 && a1 == 254 -> Scope.PRIVATE
            else -> Scope.PUBLIC
        }
    }

    private fun v6(b: ByteArray): Scope {
        val a0 = b[0].toInt() and 0xFF
        val a1 = b[1].toInt() and 0xFF
        return when {
            a0 == 0xFF -> Scope.MULTICAST
            a0 and 0xFE == 0xFC -> Scope.PRIVATE
            a0 == 0xFE && (a1 and 0xC0) == 0x80 -> Scope.PRIVATE
            else -> Scope.PUBLIC
        }
    }
}
