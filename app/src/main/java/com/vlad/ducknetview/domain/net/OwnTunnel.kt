package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Transport

/**
 * This app's own capture tunnel, recognised among the links it reports on.
 *
 * The capture engine is a `VpnService`, so while it runs the platform publishes
 * `tun0` as an ordinary network and this app monitors it like any other. That
 * made the engine's own switch produce network outages: starting capture logged
 * `INFO network_up tun0 (vpn) appeared` and stopping it logged **`WARN
 * network_down tun0 (vpn) vanished`** — a warning, so a notification and a
 * webhook POST — for a link this app had created and removed itself, one line
 * away from the `engine_started` / `engine_stopped` entries that already said
 * so. Five such pairs accumulated in one hour of testing on the M10.
 *
 * It is the same fault `NetworkIdentityTest` guards (DESIGN, round six: "it
 * fabricated Wi-Fi outages, and caused them itself"), which was fixed for the
 * flapping-netId case and not for the one link the app is actually responsible
 * for. The engine lifecycle is its own event vocabulary; a second one reporting
 * the same act as an outage can only disagree with it.
 *
 * Identified by address rather than by interface name — `tun0` is `tun0` for
 * every VPN on Android, exactly as `wlan0` is — and these two addresses are
 * this app's own choice, configured in one place and read here.
 */
object OwnTunnel {

    /** The IPv4 address `DuckVpnService` gives its TUN. */
    const val V4 = "10.215.173.1"

    /** The IPv6 address `DuckVpnService` gives its TUN. */
    const val V6 = "fd00:6475:636b::1"

    /**
     * Whether [n] is this app's capture tunnel.
     *
     * Deliberately not "is any VPN": somebody else's tunnel going down is real
     * news, and is the one externally caused failure that ends capture.
     */
    fun isOurs(n: NetworkRow): Boolean {
        if (n.transport != Transport.VPN) return false
        return n.addresses.any { addr ->
            val bare = addr.substringBefore('/').substringBefore('%').trim()
                .removePrefix("[").removeSuffix("]")
            bare.equals(V4, ignoreCase = true) || bare.equals(V6, ignoreCase = true)
        }
    }
}
