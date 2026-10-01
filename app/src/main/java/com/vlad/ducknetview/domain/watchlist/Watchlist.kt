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

            // One decision point: whatever [problem] calls broken is exactly
            // what is left out of the structures below, so the explanation the
            // user is shown can never disagree with what actually matches.
            if (problem(e) != null) {
                bad += e
                continue
            }

            val slash = e.lastIndexOf('/')
            if (slash > 0 && IpBytes.parse(e.substring(0, slash)) != null) {
                parseCidr(e)?.let { nets += it }
                continue
            }

            val ip = IpBytes.parseNormalized(e)
            if (ip != null) {
                ips += ip
                continue
            }

            regexes += Regex(e, RegexOption.IGNORE_CASE)
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

        /**
         * Why this entry can never match, or null when it is usable.
         *
         * The watchlist is a security feature: an entry with a typo in it used
         * to be stored, listed and rendered exactly like a working rule, and
         * the alert it was added for simply never fired. [invalid] knew which
         * entries those were but nothing could ask it *what* was wrong, so
         * nothing told the user.
         */
        fun problem(entry: String): String? {
            val e = entry.trim()
            if (e.isEmpty()) return null

            val slash = e.lastIndexOf('/')
            if (slash > 0 && IpBytes.parse(e.substring(0, slash)) != null) {
                // It is meant as a CIDR, so a bad prefix length is an error
                // rather than an excuse to reinterpret it as a regex that
                // would quietly never match.
                return if (parseCidr(e) == null) {
                    "not a valid CIDR: the prefix length is out of range"
                } else {
                    null
                }
            }

            if (IpBytes.parseNormalized(e) != null) return null

            return try {
                Regex(e, RegexOption.IGNORE_CASE)
                null
            } catch (ex: IllegalArgumentException) {
                "invalid regex: " + (ex.message?.lineSequence()?.firstOrNull()?.trim()
                    ?: "it cannot be compiled")
            }
        }

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
