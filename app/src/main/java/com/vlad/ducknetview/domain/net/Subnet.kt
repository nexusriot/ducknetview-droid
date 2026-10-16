package com.vlad.ducknetview.domain.net

/**
 * The host addresses of an IPv4 on-link prefix.
 *
 * Pure arithmetic over the address the device already holds, so it is a plain
 * JVM test. The caller does the knocking; this only says what there is to
 * knock on.
 *
 * IPv6 is deliberately refused rather than handled. An on-link IPv6 prefix is
 * a /64, which is 2^64 addresses: a sweep is not a long scan there, it is an
 * impossible one, and neighbour discovery for IPv6 needs multicast the app
 * cannot read. Answering "no hosts" for IPv6 is the honest result, and the
 * screen says why rather than showing an empty table.
 */
object Subnet {

    /**
     * The narrowest prefix worth refusing to sweep. /22 is 1022 hosts, which
     * at this scanner's concurrency is already a minute of work; anything
     * wider is a different feature with a different consent conversation.
     */
    const val MIN_PREFIX = 22

    /** A hard ceiling independent of the prefix, so a bad input cannot run away. */
    const val MAX_HOSTS = 1022

    /**
     * Every usable host address in the prefix holding [cidr], in order.
     *
     * The network and broadcast addresses are left out — nothing answers on
     * them, and a broadcast ping is the one probe on this list that would
     * reach hosts the user did not ask about. The device's own address stays
     * in: it is a real host on the link and leaving it out would make the
     * count disagree with the prefix.
     *
     * @param cidr an address with a prefix length, as `LinkProperties`
     *   reports it: `192.168.1.5/24`.
     */
    fun hostsOf(cidr: String, limit: Int = MAX_HOSTS): List<String> {
        val slash = cidr.lastIndexOf('/')
        if (slash <= 0 || slash == cidr.length - 1) return emptyList()
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return emptyList()
        val addr = IpBytes.parseV4(cidr.substring(0, slash)) ?: return emptyList()
        if (prefix < MIN_PREFIX || prefix > 32) return emptyList()

        // /31 and /32 have no network or broadcast address to exclude, and a
        // /31 is a point-to-point link whose two addresses are both hosts.
        val width = 32 - prefix
        val size = 1L shl width
        val base = toLong(addr) and (-1L shl width).let { it and 0xFFFFFFFFL }

        val first: Long
        val last: Long
        if (width <= 1) {
            first = base
            last = base + size - 1
        } else {
            first = base + 1
            last = base + size - 2
        }

        val count = ((last - first) + 1).coerceAtMost(limit.toLong()).toInt()
        if (count <= 0) return emptyList()
        return (0 until count).map { toText(first + it) }
    }

    /** How many hosts [hostsOf] would return, without building the list. */
    fun hostCount(cidr: String, limit: Int = MAX_HOSTS): Int = hostsOf(cidr, limit).size

    /**
     * The first on-link IPv4 prefix among a link's addresses, or null when the
     * link has none — an IPv6-only network, or one with nothing assigned yet.
     */
    fun firstV4(addresses: List<String>): String? = addresses.firstOrNull { cidr ->
        val slash = cidr.lastIndexOf('/')
        slash > 0 && IpBytes.parseV4(cidr.substring(0, slash)) != null
    }

    private fun toLong(b: ByteArray): Long {
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    private fun toText(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
}
