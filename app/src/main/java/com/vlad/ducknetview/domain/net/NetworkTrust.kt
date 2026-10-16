package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Transport

/** What the user has said about a network, or that they have said nothing. */
enum class Trust { UNKNOWN, TRUSTED, UNTRUSTED }

/**
 * Which network this is, and whether the user trusts it.
 *
 * Several things in this app were global that are really per-network
 * questions. "What am I exposing" only means anything relative to the network
 * asking, and "should capture be running" has an obvious answer on a home
 * link and a different obvious answer in a café — but both were settings the
 * user had to remember to change by hand, at exactly the moment they were
 * least likely to think of it.
 *
 * Pure: the key is derived from a [NetworkRow] and the verdict from settings,
 * so the policy is a plain JVM test and nothing here touches the platform.
 */
object NetworkTrust {

    /**
     * A stable name for the network a person means.
     *
     * Not the interface: `wlan0` is `wlan0` on every Wi-Fi network there has
     * ever been, so keying trust on it would mark every network at once. Not
     * the platform's `Network` id either, which Android re-issues on every
     * re-registration — the mistake that once had this app reporting
     * fabricated outages, recorded in `NetworkIdentityTest`.
     *
     * An SSID is what the user chose and what they would recognise in a list.
     * Cellular gets one identity per operator, because a mobile network is
     * either trusted or it is not and its cells are not separate decisions.
     * Anything else falls back to transport and interface, which is the best
     * available for a USB tether or an Ethernet dock.
     */
    fun keyOf(n: NetworkRow): String? {
        val transport = n.transport.name.lowercase()
        return when (n.transport) {
            Transport.WIFI -> n.wifi?.ssid?.takeIf { it.isNotBlank() }?.let { "wifi:$it" }
            Transport.CELLULAR ->
                "cellular:" + (n.cellular?.operator?.takeIf { it.isNotBlank() } ?: "any")
            // A VPN is somebody else's tunnel and not a network the user is
            // on in the sense this is asking about.
            Transport.VPN, Transport.LOOPBACK -> null
            else -> n.ifaceName.takeIf { it.isNotBlank() }?.let { "$transport:$it" }
        }
    }

    /** What to call it on screen. */
    fun labelOf(n: NetworkRow): String = when (n.transport) {
        Transport.WIFI -> n.wifi?.ssid?.takeIf { it.isNotBlank() } ?: n.ifaceName
        Transport.CELLULAR -> n.cellular?.operator?.takeIf { it.isNotBlank() } ?: "mobile"
        else -> n.ifaceName.ifBlank { n.transport.name.lowercase() }
    }

    fun of(key: String?, s: AppSettings): Trust = when {
        key == null -> Trust.UNKNOWN
        key in s.untrustedNetworks -> Trust.UNTRUSTED
        key in s.trustedNetworks -> Trust.TRUSTED
        else -> Trust.UNKNOWN
    }

    fun of(n: NetworkRow, s: AppSettings): Trust = of(keyOf(n), s)

    /**
     * Whether the automatic responses should run for this network.
     *
     * An explicitly untrusted network always counts. An unseen one counts too
     * when the user has asked for that, which is the setting that makes the
     * feature useful rather than something they have to prime in advance:
     * the café you have never been to before is precisely the one worth
     * reacting to.
     */
    fun shouldAct(trust: Trust, s: AppSettings): Boolean = when (trust) {
        Trust.UNTRUSTED -> true
        Trust.UNKNOWN -> s.treatUnknownNetworksAsUntrusted
        Trust.TRUSTED -> false
    }

    /**
     * Settings with [key] moved to [trust], removed from the other set.
     *
     * One function so the two sets can never both contain a key — a state the
     * UI would render as whichever [of] happened to test first.
     */
    fun apply(s: AppSettings, key: String, trust: Trust): AppSettings = s.copy(
        trustedNetworks = when (trust) {
            Trust.TRUSTED -> s.trustedNetworks + key
            else -> s.trustedNetworks - key
        },
        untrustedNetworks = when (trust) {
            Trust.UNTRUSTED -> s.untrustedNetworks + key
            else -> s.untrustedNetworks - key
        },
    )
}
