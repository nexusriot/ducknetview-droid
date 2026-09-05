package com.vlad.ducknetview.domain.rdns

/**
 * Bounded reverse-DNS cache.
 *
 * The lookup itself is not here: the resolver is injected at the call site as a
 * `suspend (String) -> String?`, so this class stays a data structure and its
 * tests never touch the network. Least-recently-used entries are evicted first,
 * on the assumption that the addresses on screen are the ones being read.
 *
 * A stored `""` means "resolved, but the address has no PTR record" — a real
 * answer worth remembering. A `null` from [get] means "not looked up yet".
 */
class RdnsCache(private val max: Int = MAX_ENTRIES) {

    private val entries = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > max
    }

    val size: Int get() = entries.size

    fun get(ip: String): String? = entries[ip]

    fun contains(ip: String): Boolean = entries.containsKey(ip)

    fun put(ip: String, host: String) {
        if (ip.isEmpty()) return
        entries[ip] = host
    }

    /** The name to display for an address: the PTR when there is one. */
    fun display(ip: String): String = entries[ip]?.takeIf { it.isNotEmpty() } ?: ip

    /** Addresses not in [live] are gone from the tables and need no name. */
    fun retain(live: Set<String>) {
        entries.keys.retainAll(live)
    }

    fun clear() {
        entries.clear()
    }

    /** Snapshot, most-recently-used last, for tests and for persistence. */
    fun snapshot(): Map<String, String> = LinkedHashMap(entries)

    companion object {
        const val MAX_ENTRIES = 4096
    }
}
