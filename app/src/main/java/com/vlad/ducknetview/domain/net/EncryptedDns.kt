package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Proto

/**
 * Recognises encrypted DNS by where a flow goes.
 *
 * Why: with Private DNS on, a device's lookups vanish from the plain-DNS view
 * entirely, and an app that ships its own DNS-over-HTTPS resolver bypasses the
 * system setting altogether. Neither is visible as "DNS" in the connection
 * table — they are just more TLS flows — so this puts the name back on them.
 *
 * This is a classification, not a guarantee. Port 853 is assigned to DoT and
 * DoQ but anything can listen there; a DoH guess rests on the destination
 * being a well-known public resolver address, and those same addresses also
 * serve ordinary HTTPS (1.1.1.1 has a web page). Conversely, DoH to any
 * resolver not listed here — a private one, or a provider reached by a
 * hostname on shared CDN addresses — is indistinguishable from web traffic and
 * is not reported. The resolver list holds exact addresses only: ranges such
 * as NextDNS's anycast /24s are shared with other services, and widening to
 * them would label unrelated flows.
 *
 * Addresses are compared as parsed bytes via [IpBytes], so every textual
 * spelling of an IPv6 literal (`2001:4860:4860:0:0:0:0:8888`, bracketed,
 * upper case, IPv4-mapped) matches, and nothing ever triggers a DNS lookup.
 */
object EncryptedDns {

    enum class Kind(val label: String) {
        DOT("DNS-over-TLS"),
        DOQ("DNS-over-QUIC"),
        DOH("DNS-over-HTTPS"),
    }

    private const val PORT_DOT_DOQ = 853
    private const val PORT_HTTPS = 443

    private val RESOLVER_LITERALS = listOf(
        // Google Public DNS
        "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844",
        // Cloudflare
        "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001",
        // Quad9
        "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9",
        // AdGuard
        "94.140.14.14", "94.140.15.15",
        // OpenDNS
        "208.67.222.222", "208.67.220.220",
    )

    /** Keyed by the normalised bytes as a string, so lookups are a hash probe. */
    private val RESOLVERS: Set<String> = RESOLVER_LITERALS.map { lit ->
        key(requireNotNull(IpBytes.parseNormalized(lit)) { "bad resolver literal $lit" })
    }.toSet()

    /**
     * What kind of encrypted DNS this flow looks like, or null when it does not
     * look like one. Any port-853 flow counts regardless of destination, since
     * the port itself is the signal; 443 counts only towards a known resolver.
     * UDP/443 to a resolver is DoH over HTTP/3, still DoH.
     */
    fun kindOf(proto: Proto, remoteAddr: String, remotePort: Int): Kind? = when {
        proto == Proto.TCP && remotePort == PORT_DOT_DOQ -> Kind.DOT
        proto == Proto.UDP && remotePort == PORT_DOT_DOQ -> Kind.DOQ
        (proto == Proto.TCP || proto == Proto.UDP) && remotePort == PORT_HTTPS &&
            isKnownResolver(remoteAddr) -> Kind.DOH
        else -> null
    }

    /** True when [addr] is a literal for one of the listed public resolvers. */
    fun isKnownResolver(addr: String): Boolean {
        val b = IpBytes.parseNormalized(addr) ?: return false
        return key(b) in RESOLVERS
    }

    private fun key(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }
}
