package com.vlad.ducknetview.domain.firewall

import com.vlad.ducknetview.domain.net.IpBytes
import com.vlad.ducknetview.domain.watchlist.Watchlist

/**
 * One user-written block: a domain, an address or a CIDR, for every app or
 * for one package.
 *
 * Kept by package name rather than uid, like every other per-app choice a
 * person makes: a uid is reassigned when an app is reinstalled, and a rule
 * that silently moved to whichever app inherited the number would be worse
 * than one that stopped matching.
 */
data class FirewallRule(val pattern: String, val packageName: String = "") {

    val global: Boolean get() = packageName.isEmpty()

    fun encode(): String = "$packageName|$pattern"

    companion object {
        fun decode(s: String): FirewallRule? {
            val bar = s.indexOf('|')
            if (bar < 0) return null
            val pattern = normalize(s.substring(bar + 1))
            if (problem(pattern) != null) return null
            return FirewallRule(pattern, s.substring(0, bar))
        }

        fun normalize(pattern: String): String =
            pattern.trim().lowercase().removePrefix("*.").trimEnd('.')

        /**
         * Why a pattern cannot be used, or null when it can. A rule that is
         * stored and then matches nothing is the failure this exists to stop.
         */
        fun problem(pattern: String): String? {
            val p = normalize(pattern)
            return when {
                p.isEmpty() -> "empty"
                p.contains('/') ->
                    if (Watchlist.parseCidr(p) == null) "not a valid CIDR" else null
                IpBytes.parse(p) != null -> null
                p.any { it.isWhitespace() || it == '|' } -> "contains a space or '|'"
                !p.contains('.') -> "a domain needs at least one dot"
                p.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') } ->
                    "not a domain, an address or a CIDR"
                else -> null
            }
        }
    }
}

/**
 * The conditions an app can be refused under, beyond "always".
 *
 * Each one names the link a connection would leave by — or, for the screen,
 * the moment — because the question these answer is "is this app allowed to
 * use data *now*", which is what NetGuard-style firewalls taught people to
 * expect.
 */
data class AppPolicy(
    val uid: Int,
    val blockMobile: Boolean = false,
    val blockMetered: Boolean = false,
    val blockUntrusted: Boolean = false,
    val blockScreenOff: Boolean = false,
) {
    val isEmpty: Boolean get() = !(blockMobile || blockMetered || blockUntrusted || blockScreenOff)

    fun encode(): String = buildString {
        append(uid).append(':')
        if (blockMobile) append('m')
        if (blockMetered) append('$')
        if (blockUntrusted) append('u')
        if (blockScreenOff) append('s')
    }

    /** Why this policy refuses a connection on [link], or null when it does not. */
    fun reasonOn(link: LinkContext): String? = when {
        blockMobile && link.mobile -> "blocked on mobile data"
        blockMetered && link.metered -> "blocked on metered networks"
        blockUntrusted && link.untrusted -> "blocked on untrusted networks"
        blockScreenOff && link.screenOff -> "blocked while the screen is off"
        else -> null
    }

    companion object {
        fun decode(s: String): AppPolicy? {
            val colon = s.indexOf(':')
            if (colon <= 0) return null
            val uid = s.substring(0, colon).toIntOrNull() ?: return null
            val flags = s.substring(colon + 1)
            return AppPolicy(
                uid = uid,
                blockMobile = 'm' in flags,
                blockMetered = '$' in flags,
                blockUntrusted = 'u' in flags,
                blockScreenOff = 's' in flags,
            ).takeUnless { it.isEmpty }
        }

        fun decodeAll(set: Collection<String>): Map<Int, AppPolicy> =
            set.mapNotNull(::decode).associateBy { it.uid }
    }
}

/** What the packet path knows about where a new connection is going out. */
data class LinkContext(
    val mobile: Boolean = false,
    val metered: Boolean = false,
    val untrusted: Boolean = false,
    val screenOff: Boolean = false,
)

/**
 * The compiled decision table the capture engine asks on every new flow.
 *
 * Compiled once per settings change, never per packet: domain rules become a
 * suffix map, addresses a normalised set, and package names uids. A lookup is
 * then a handful of hash probes, which is what the packet path can afford.
 *
 * Two moments ask it. [verdict] runs when a flow opens, with whatever name the
 * device has already learnt for the address — from a DNS answer, or an earlier
 * handshake to it. [nameVerdict] runs when a handshake on an *open* flow
 * reveals a name nothing knew before: an SNI or QUIC Initial with Private DNS
 * on. Such a flow cannot be refused, the connection already exists upstream,
 * so it is cut instead. The info sheet says which happened.
 */
class Firewall private constructor(
    private val blockedUids: Set<Int>,
    private val globalDomains: Set<String>,
    private val appDomains: Map<Int, Set<String>>,
    private val globalAddrs: Set<String>,
    private val appAddrs: Map<Int, Set<String>>,
    private val globalCidrs: List<Watchlist.Cidr>,
    private val appCidrs: Map<Int, List<Watchlist.Cidr>>,
    private val blocklist: Set<String>,
    private val policies: Map<Int, AppPolicy>,
    private val budgetUids: Set<Int>,
) {

    val isEmpty: Boolean
        get() = blockedUids.isEmpty() && globalDomains.isEmpty() && appDomains.isEmpty() &&
            globalAddrs.isEmpty() && appAddrs.isEmpty() && globalCidrs.isEmpty() &&
            appCidrs.isEmpty() && blocklist.isEmpty() && policies.isEmpty() && budgetUids.isEmpty()

    fun isAppBlocked(uid: Int): Boolean = uid in blockedUids

    /**
     * Why a new connection must be refused, or null to let it through.
     * [remoteName] is the name already learnt for [remoteIp], if any.
     */
    fun verdict(uid: Int, remoteIp: String, remoteName: String?, link: LinkContext): String? {
        if (uid in blockedUids) return "app blocked"
        if (uid in budgetUids) return "over its data budget"
        policies[uid]?.reasonOn(link)?.let { return it }
        addressVerdict(uid, remoteIp)?.let { return it }
        if (!remoteName.isNullOrEmpty()) nameVerdict(uid, remoteName)?.let { return it }
        return null
    }

    /** Why a connection to [name] must not proceed, or null. */
    fun nameVerdict(uid: Int, name: String): String? {
        var n = name.lowercase().trimEnd('.')
        val own = appDomains[uid]
        while (true) {
            if (n in globalDomains || (own != null && n in own)) return "rule $n"
            if (n in blocklist) return "blocklist $n"
            val dot = n.indexOf('.')
            if (dot < 0) return null
            n = n.substring(dot + 1)
            // A bare TLD is never a rule: "com" would be every site at once.
            if (!n.contains('.')) return null
        }
    }

    private fun addressVerdict(uid: Int, ip: String): String? {
        val bytes = IpBytes.parseNormalized(ip) ?: return null
        val key = canonical(bytes)
        if (key in globalAddrs || appAddrs[uid]?.contains(key) == true) return "rule $ip"
        for (c in globalCidrs) if (Watchlist.contains(c, bytes)) return "rule $ip"
        appCidrs[uid]?.let { list ->
            for (c in list) if (Watchlist.contains(c, bytes)) return "rule $ip"
        }
        return null
    }

    companion object {
        val EMPTY = compile(emptyList(), emptySet(), emptyMap(), emptySet()) { null }

        /**
         * Build the table. [uidOf] maps a package to its current uid; a rule
         * for a package that is not installed compiles to nothing rather than
         * to a global rule, which is the dangerous direction to fail in.
         */
        fun compile(
            rules: List<FirewallRule>,
            blocklist: Set<String>,
            policies: Map<Int, AppPolicy>,
            blockedUids: Set<Int>,
            budgetUids: Set<Int> = emptySet(),
            uidOf: (String) -> Int?,
        ): Firewall {
            val gDomains = HashSet<String>()
            val aDomains = HashMap<Int, HashSet<String>>()
            val gAddrs = HashSet<String>()
            val aAddrs = HashMap<Int, HashSet<String>>()
            val gCidrs = ArrayList<Watchlist.Cidr>()
            val aCidrs = HashMap<Int, ArrayList<Watchlist.Cidr>>()
            for (r in rules) {
                val uid = if (r.global) null else (uidOf(r.packageName) ?: continue)
                val p = FirewallRule.normalize(r.pattern)
                val cidr = if (p.contains('/')) Watchlist.parseCidr(p) else null
                val addr = if (cidr == null) IpBytes.parseNormalized(p) else null
                when {
                    cidr != null ->
                        if (uid == null) gCidrs += cidr else aCidrs.getOrPut(uid) { ArrayList() } += cidr
                    addr != null ->
                        if (uid == null) gAddrs += canonical(addr)
                        else aAddrs.getOrPut(uid) { HashSet() } += canonical(addr)
                    FirewallRule.problem(p) == null ->
                        if (uid == null) gDomains += p else aDomains.getOrPut(uid) { HashSet() } += p
                }
            }
            return Firewall(
                blockedUids = blockedUids.toSet(),
                globalDomains = gDomains,
                appDomains = aDomains,
                globalAddrs = gAddrs,
                appAddrs = aAddrs,
                globalCidrs = gCidrs,
                appCidrs = aCidrs,
                blocklist = blocklist,
                policies = policies.filterValues { !it.isEmpty },
                budgetUids = budgetUids.toSet(),
            )
        }

        private fun canonical(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }
}

/**
 * A hosts-format block list, the shape tracker lists are published in.
 *
 * Accepts `0.0.0.0 name`, `127.0.0.1 name`, `::` forms and bare names, one per
 * line, with `#` comments. Localhost entries that every hosts file starts with
 * are dropped: blocking `localhost` would be a strange way to learn this
 * parser is literal.
 */
object Blocklist {
    private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "0.0.0.0")

    fun parse(lines: Sequence<String>, limit: Int = MAX_ENTRIES): Set<String> {
        val out = HashSet<String>()
        for (raw in lines) {
            if (out.size >= limit) break
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            val name = when {
                parts.size >= 2 && IpBytes.parse(parts[0]) != null -> parts[1]
                parts.size == 1 && IpBytes.parse(parts[0]) == null -> parts[0]
                else -> continue
            }
            val n = FirewallRule.normalize(name)
            if (n in SKIP || !n.contains('.') || FirewallRule.problem(n) != null) continue
            out += n
        }
        return out
    }

    /** Whether [name] or any parent domain is listed. */
    fun matches(list: Set<String>, name: String): Boolean {
        var n = name.lowercase().trimEnd('.')
        while (n.contains('.')) {
            if (n in list) return true
            n = n.substringAfter('.')
        }
        return false
    }

    /** Large enough for the common tracker lists, small enough to hold in memory. */
    const val MAX_ENTRIES = 300_000
}
