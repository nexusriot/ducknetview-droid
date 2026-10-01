package com.vlad.ducknetview.engine.vpn.packet

/**
 * The one rule for a host name read off the wire.
 *
 * Both name sources are attacker-controlled — a TLS ClientHello is written by
 * whatever the device connected to, and a DNS response by whatever answered —
 * and both end up in the same places: the connection table, the Domains screen,
 * the Room store behind it and the CSV export. They are checked against the
 * same rule here so the two cannot drift apart; [DnsPeek] used to do no
 * checking at all while [TlsPeek] did, which let a hostile responder put
 * control characters and multi-kilobyte strings where a name belongs.
 */
object HostNames {

    /** The longest a DNS name may be on the wire, so the longest worth keeping. */
    const val MAX_LENGTH = 253

    /**
     * Deliberately narrower than the RFCs allow: a presentation-form name may
     * carry escapes and arbitrary bytes, but a name this app will *show* has no
     * business containing them, and nothing downstream has to defend itself if
     * they never get in.
     */
    fun isPlausible(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_LENGTH) return false
        return name.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
        }
    }
}
