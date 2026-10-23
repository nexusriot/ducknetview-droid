package com.vlad.ducknetview.domain.model

/**
 * Where a name for a remote host came from.
 *
 * The distinction is not decoration: DNS is what the device *asked for*, SNI is
 * what it *told the server it wanted*, and on a device with Private DNS on only
 * the second is observable. A row says which, so "no DNS activity at all" reads
 * as the encrypted-resolver story it is rather than as an idle device.
 *
 * [QUIC] is kept apart from [SNI] although both are a `server_name` sent in a
 * ClientHello, because the two say different things about what the app is
 * doing: a QUIC row is an HTTP/3 flow on UDP, which is the traffic that was
 * invisible on this screen until the Initial packet could be read, and which
 * is still the first thing to suspect when a row looks absent.
 */
/**
 * @property label the column badge.
 * @property provenance how the event log words it. On the enum rather than at
 *   the call site because the call site was `if (source == SNI) "seen in TLS
 *   SNI" else "resolved by DNS"` — written when there were two sources, and
 *   never widened when [QUIC] arrived. Measured on the M10: every name learnt
 *   from a QUIC Initial was recorded in the permanent event log, the events
 *   CSV and the alert webhook as "resolved by DNS", including three the
 *   instrumented suite had just put on the wire itself with no DNS answer
 *   anywhere near them. On a device with Private DNS — the case QUIC peeking
 *   exists for — that is every name the app knows. A `when` over the enum
 *   makes the compiler ask the question the next time a source is added.
 */
enum class NameSource(val label: String, val provenance: String) {
    DNS("DNS", "resolved by DNS"),
    SNI("SNI", "seen in TLS SNI"),
    QUIC("QUIC", "seen in a QUIC Initial"),
    ;

    /** Whether the name was read off the wire rather than from a DNS answer. */
    val fromHandshake: Boolean get() = this != DNS
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
