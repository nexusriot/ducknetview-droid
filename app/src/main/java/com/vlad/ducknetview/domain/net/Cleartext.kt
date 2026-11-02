package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.Proto

/**
 * Names the unencrypted application protocol a flow is probably speaking,
 * judged by its well-known destination port.
 *
 * The list is deliberately short. Every entry is a protocol whose default port
 * carries credentials or content in the clear *and* that a phone plausibly
 * talks to — plain HTTP, legacy mail and file transfer, remote shells, IoT
 * brokers, and the UDP management protocols a LAN device still answers on.
 * A long list of every historical cleartext port would flag noise (`daytime`,
 * `finger`) that no app on a phone ever opens, and a flag that is mostly noise
 * stops being read.
 *
 * This is a port heuristic, not inspection: a service can run TLS on port 80
 * or plaintext on 443, and nothing here looks at the bytes. The label is a
 * prompt to look, not a verdict.
 */
object Cleartext {

    private val TCP = mapOf(
        21 to "ftp",
        23 to "telnet",
        25 to "smtp",
        80 to "http",
        110 to "pop3",
        143 to "imap",
        1883 to "mqtt",
        5222 to "xmpp",
        8080 to "http-alt",
    )

    private val UDP = mapOf(
        69 to "tftp",
        161 to "snmp",
        514 to "syslog",
    )

    /**
     * Protocols with an in-band upgrade to TLS on the same port: STARTTLS for
     * SMTP, IMAP and XMPP, STLS for POP3, AUTH TLS for FTP. A flow to one of
     * these may well be encrypted after the first exchange, so the screen
     * should say "may be cleartext" rather than "is cleartext". HTTP, telnet,
     * MQTT and the UDP protocols have no such upgrade on their plain port.
     */
    private val UPGRADABLE = setOf("smtp", "imap", "pop3", "ftp", "xmpp")

    /** The cleartext protocol label for this destination, or null when none is known. */
    fun protocolOf(proto: Proto, remotePort: Int): String? = when (proto) {
        Proto.TCP -> TCP[remotePort]
        Proto.UDP -> UDP[remotePort]
        Proto.ICMP, Proto.OTHER -> null
    }

    /** True when [label] (from [protocolOf]) can switch to TLS mid-connection. */
    fun mayUpgrade(label: String): Boolean = label in UPGRADABLE
}
