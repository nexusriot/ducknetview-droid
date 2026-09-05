package com.vlad.ducknetview.domain.watchlist

import com.vlad.ducknetview.domain.net.IpBytes

/**
 * Remote-address rules from settings. Each entry is read as a CIDR, then as a
 * bare IP, and only failing both as a regular expression — which is tested
 * against the raw address *and* the resolved hostname, since "the malware CDN"
 * is a name pattern far more often than it is a prefix.
 *
 * CIDR containment is integer bit masking on bytes parsed here; nothing in this
 * class can touch the network.
 */
class Watchlist(entries: List<String>) {

    internal class Cidr(val base: ByteArray, val bits: Int)

    private val ips = ArrayList<ByteArray>()
    private val nets = ArrayList<Cidr>()
    private val regexes = ArrayList<Regex>()
    private val raw = ArrayList<String>()

    /** Entries that parsed as neither address, CIDR, nor valid regex. */
    val invalid: List<String>

    init {
        val bad = ArrayList<String>()
        for (entry in entries) {
            val e = entry.trim()
            if (e.isEmpty()) continue
            raw += e

            val slash = e.lastIndexOf('/')
            if (slash > 0 && IpBytes.parse(e.substring(0, slash)) != null) {
                // It is meant as a CIDR, so a bad prefix length is an error
                // rather than an excuse to reinterpret it as a regex that
                // would quietly never match.
                val cidr = parseCidr(e)
                if (cidr != null) nets += cidr else bad += e
                continue
            }

            val ip = IpBytes.parseNormalized(e)
            if (ip != null) {
                ips += ip
                continue
            }

            try {
                regexes += Regex(e, RegexOption.IGNORE_CASE)
            } catch (_: IllegalArgumentException) {
                bad += e
            }
        }
        invalid = bad
    }

    val entries: List<String> get() = raw

    val isEmpty: Boolean get() = ips.isEmpty() && nets.isEmpty() && regexes.isEmpty()

    fun matches(ip: String, host: String?): Boolean {
        val addr = IpBytes.parseNormalized(ip)
        if (addr != null) {
            for (x in ips) if (x.contentEquals(addr)) return true
            for (n in nets) if (contains(n, addr)) return true
        }
        if (regexes.isEmpty()) return false
        for (re in regexes) {
            if (re.containsMatchIn(ip)) return true
            if (!host.isNullOrEmpty() && re.containsMatchIn(host)) return true
        }
        return false
    }

    fun matches(ip: String): Boolean = matches(ip, null)

    companion object {

        val EMPTY = Watchlist(emptyList())

        /** Exposed for the "add this remote" action, which only accepts IPs. */
        fun isAddress(entry: String): Boolean = IpBytes.parse(entry) != null

        internal fun parseCidr(entry: String): Cidr? {
            val slash = entry.lastIndexOf('/')
            if (slash <= 0 || slash == entry.length - 1) return null
            val base = IpBytes.parseNormalized(entry.substring(0, slash)) ?: return null
            val bitsText = entry.substring(slash + 1)
            if (bitsText.any { it < '0' || it > '9' } || bitsText.length > 3) return null
            val bits = bitsText.toIntOrNull() ?: return null
            if (bits < 0 || bits > base.size * 8) return null
            return Cidr(base, bits)
        }

        internal fun contains(net: Cidr, addr: ByteArray): Boolean {
            if (net.base.size != addr.size) return false
            var bits = net.bits
            var i = 0
            while (bits >= 8) {
                if (net.base[i] != addr[i]) return false
                i++
                bits -= 8
            }
            if (bits == 0) return true
            val mask = (0xFF shl (8 - bits)) and 0xFF
            return (net.base[i].toInt() and mask) == (addr[i].toInt() and mask)
        }
    }
}
