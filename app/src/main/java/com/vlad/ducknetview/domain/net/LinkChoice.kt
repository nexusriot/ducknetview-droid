package com.vlad.ducknetview.domain.net

import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Transport

/**
 * Picking one network, or one address, out of the several a device has at once.
 *
 * Both answers used to be taken by "first in the list", which is the order
 * [android.net.LinkProperties] happens to hand them over in. That is not an
 * order with any meaning: it put a flow's `network` on nothing at all and named
 * a Wi-Fi link after its `fe80::` link-local address, the one address every
 * interface has and none can be reached on.
 */
object LinkChoice {

    /**
     * The interface actually carrying this device's traffic.
     *
     * While capture is on, the default route belongs to this app's own TUN, so
     * "the default network" is the tunnel rather than the link underneath it.
     * A flow's label has to name the link it really left on, so the VPN
     * transport is skipped and the best remaining network wins: the default
     * one, else a validated one, else whatever is up.
     */
    fun underlyingLabel(networks: List<NetworkRow>): String {
        val candidates = networks.filter { it.transport != Transport.VPN && it.ifaceName.isNotEmpty() }
        if (candidates.isEmpty()) return ""
        val best = candidates.firstOrNull { it.isDefault }
            ?: candidates.firstOrNull { it.validated && it.up }
            ?: candidates.firstOrNull { it.up }
            ?: candidates.first()
        return best.ifaceName
    }

    /**
     * The address that identifies a link to a person reading a one-line row.
     *
     * A global IPv4 or IPv6 address first — that is the one the device is
     * reachable on — then a unique-local IPv6, and a link-local or loopback
     * address only when there is nothing else. Returns null for a link with no
     * address at all, which the caller renders as "no address" or "down".
     */
    fun displayAddress(addresses: List<String>): String? {
        if (addresses.isEmpty()) return null
        return addresses.minByOrNull { rank(it) }
    }

    private fun rank(address: String): Int {
        // Link addresses carry a prefix width ("192.168.88.34/24"), which the
        // address parser does not accept, so an unstripped literal would rank
        // every address as unparseable and the order would collapse back to the
        // platform's own.
        val literal = address.substringBefore('/')
        val bytes = IpBytes.parse(literal) ?: return 5
        return when {
            isLoopback(bytes) -> 4
            isLinkLocal(bytes) -> 3
            isUniqueLocalV6(bytes) -> 2
            bytes.size == 4 -> 0
            else -> 1
        }
    }

    private fun isLoopback(b: ByteArray): Boolean = when (b.size) {
        4 -> b[0].toInt() and 0xFF == 127
        16 -> b.take(15).all { it.toInt() == 0 } && b[15].toInt() == 1
        else -> false
    }

    private fun isLinkLocal(b: ByteArray): Boolean = when (b.size) {
        // 169.254.0.0/16
        4 -> (b[0].toInt() and 0xFF) == 169 && (b[1].toInt() and 0xFF) == 254
        // fe80::/10
        16 -> (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xC0) == 0x80
        else -> false
    }

    /** fc00::/7 — routable on a LAN but not globally, so below a global address. */
    private fun isUniqueLocalV6(b: ByteArray): Boolean =
        b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
}
