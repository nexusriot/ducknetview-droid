package com.vlad.ducknetview.domain.model

/**
 * Where a name for a remote host came from.
 *
 * The distinction is not decoration: DNS is what the device *asked for*, SNI is
 * what it *told the server it wanted*, and on a device with Private DNS on only
 * the second is observable. A row says which, so "no DNS activity at all" reads
 * as the encrypted-resolver story it is rather than as an idle device.
 */
enum class NameSource(val label: String) {
    DNS("DNS"),
    SNI("SNI"),
}

/** One sighting of a name, as the capture engine observes it. */
data class DomainObservation(
    val name: String,
    val uid: Int,
    val source: NameSource,
    val at: Long,
    val addresses: List<String> = emptyList(),
)

/**
 * A name the device has looked up or asked for, accumulated across sightings.
 *
 * This is history rather than live state, so it does not live on [NetSnapshot]:
 * it is read from its own store, like the event log and the usage rollup.
 */
data class DomainRow(
    val name: String,
    val uid: Int,
    val appLabel: String = "",
    val packageName: String = "",
    val source: NameSource = NameSource.DNS,
    val lookups: Int = 0,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val addresses: List<String> = emptyList(),
    val watchlisted: Boolean = false,
) {
    /** Stable identity: the same name asked for by two apps is two rows. */
    val key: String get() = "$name|$uid"

    /**
     * The registrable-looking tail, for grouping chatty subdomains. Public
     * suffixes are not consulted — that needs a list this app will not ship —
     * so this is a display aid and is never what a rule matches on.
     */
    val parentDomain: String
        get() {
            val parts = name.split('.').filter { it.isNotEmpty() }
            return if (parts.size <= 2) name else parts.takeLast(2).joinToString(".")
        }
}
