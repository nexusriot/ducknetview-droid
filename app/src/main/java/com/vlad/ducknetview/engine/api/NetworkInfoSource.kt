package com.vlad.ducknetview.engine.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.RouteRow
import com.vlad.ducknetview.domain.model.Transport
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The `LinkProperties` / `NetworkCapabilities` replacement for the TUI's
 * `/proc/net/route` + `/etc/resolv.conf` reads.
 *
 * Everything is callback-driven: routes and DNS on Android change without any
 * poll tick (a Wi-Fi handover rewrites both in milliseconds), so the Routes and
 * Interfaces screens must not wait for the next sample to notice.
 */
class NetworkInfoSource(context: Context) {

    private val cm = try {
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
    } catch (e: Exception) {
        null
    }

    private val caps = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val links = ConcurrentHashMap<Network, LinkProperties>()
    private val order = CopyOnWriteArrayList<Network>()

    @Volatile
    private var defaultNetwork: Network? = null

    private val _networks = MutableStateFlow<List<NetworkRow>>(emptyList())
    val networks: StateFlow<List<NetworkRow>> = _networks.asStateFlow()

    private var started = false

    private val allCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!order.contains(network)) order.add(network)
            rebuild()
        }

        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            caps[network] = nc
            if (!order.contains(network)) order.add(network)
            rebuild()
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            links[network] = lp
            if (!order.contains(network)) order.add(network)
            rebuild()
        }

        override fun onLost(network: Network) {
            caps.remove(network)
            links.remove(network)
            order.remove(network)
            if (defaultNetwork == network) defaultNetwork = null
            rebuild()
        }
    }

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            defaultNetwork = network
            rebuild()
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) defaultNetwork = null
            rebuild()
        }
    }

    fun start() {
        val manager = cm ?: return
        if (started) return
        started = true
        seed(manager)
        try {
            manager.registerDefaultNetworkCallback(defaultCallback)
        } catch (e: Exception) {
            // Missing ACCESS_NETWORK_STATE or a too-many-callbacks throw; degrade
            // to the seeded snapshot rather than taking the app down.
        }
        try {
            val request = NetworkRequest.Builder().clearCapabilities().build()
            manager.registerNetworkCallback(request, allCallback)
        } catch (e: Exception) {
            // as above
        }
        rebuild()
    }

    fun stop() {
        val manager = cm
        started = false
        if (manager != null) {
            try {
                manager.unregisterNetworkCallback(defaultCallback)
            } catch (e: Exception) {
                // never registered, or already gone
            }
            try {
                manager.unregisterNetworkCallback(allCallback)
            } catch (e: Exception) {
                // as above
            }
        }
        caps.clear()
        links.clear()
        order.clear()
        defaultNetwork = null
        // Publish the teardown too: a stale list outliving the callbacks would
        // have the UI rendering links nothing is tracking any more.
        _networks.value = emptyList()
    }

    @Suppress("DEPRECATION")
    private fun seed(manager: ConnectivityManager) {
        try {
            defaultNetwork = manager.activeNetwork
            for (net in manager.allNetworks) {
                manager.getNetworkCapabilities(net)?.let { caps[net] = it }
                manager.getLinkProperties(net)?.let { links[net] = it }
                if (!order.contains(net)) order.add(net)
            }
        } catch (e: Exception) {
            // No permission, or a vendor CM that throws on enumeration.
        }
    }

    private fun rebuild() {
        val def = defaultNetwork
        val rows = order.mapNotNull { net ->
            try {
                row(net, caps[net], links[net], net == def)
            } catch (e: Exception) {
                null
            }
        }
        _networks.value = rows
    }

    private fun row(
        net: Network,
        nc: NetworkCapabilities?,
        lp: LinkProperties?,
        isDefault: Boolean,
    ): NetworkRow {
        val iface = lp?.interfaceName.orEmpty()
        val routes = lp?.routes?.mapNotNull { routeRow(it, iface) } ?: emptyList()
        return NetworkRow(
            id = net.toString(),
            ifaceName = iface,
            transport = transportOf { nc?.hasTransport(it) == true },
            up = nc == null ||
                nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            addresses = lp?.linkAddresses?.mapNotNull {
                addrText(it.address, it.prefixLength)
            } ?: emptyList(),
            mtu = lp?.mtu ?: 0,
            metered = nc != null &&
                !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            validated = nc != null &&
                nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isDefault = isDefault,
            gateway = gatewayOf(routes),
            dnsServers = lp?.dnsServers?.mapNotNull { hostText(it) } ?: emptyList(),
            domains = lp?.domains?.takeIf { it.isNotBlank() },
            privateDns = lp?.privateDnsServerName?.takeIf { it.isNotBlank() },
            routes = routes,
        )
    }

    private fun routeRow(route: RouteInfo, fallbackIface: String): RouteRow? {
        val destText: String? = try {
            route.destination?.toString()
        } catch (e: Exception) {
            null
        }
        val dest = destText ?: return null
        val gw: InetAddress? = try {
            route.gateway
        } catch (e: Exception) {
            null
        }
        val isDefault = try {
            route.isDefaultRoute
        } catch (e: Exception) {
            dest == "0.0.0.0/0" || dest == "::/0"
        }
        return RouteRow(
            destination = dest,
            gateway = gatewayText(gw),
            iface = route.`interface`?.takeIf { it.isNotBlank() } ?: fallbackIface,
            isDefault = isDefault,
        )
    }

    companion object {

        /**
         * VPN is checked first on purpose: a VPN `Network` also carries the
         * transport of the link it rides on, so the naive first-match order
         * would label every tunnel "wifi".
         */
        internal fun transportOf(has: (Int) -> Boolean): Transport = when {
            has(NetworkCapabilities.TRANSPORT_VPN) -> Transport.VPN
            has(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            has(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            has(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            has(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> Transport.BLUETOOTH
            else -> Transport.OTHER
        }

        /** The default route's next hop, or null when the link is point-to-point. */
        internal fun gatewayOf(routes: List<RouteRow>): String? {
            val route = routes.firstOrNull { it.isDefault && !it.gateway.isNullOrBlank() }
                ?: routes.firstOrNull {
                    (it.destination == "0.0.0.0/0" || it.destination == "::/0") &&
                        !it.gateway.isNullOrBlank()
                }
                ?: return null
            return zoned(route.gateway!!, route.iface)
        }

        /**
         * An IPv6 link-local next hop does not identify a host on its own: the
         * same `fe80::` address can exist on every interface, so `connect()` to
         * a scopeless one fails with `EINVAL`. On a dual-stack network the
         * default route's next hop is normally exactly that, which made the
         * Overview's gateway latency read a permanent "unreachable" — the one
         * hop the app can actually measure, and it measured nothing.
         *
         * `RouteInfo.getGateway()` hands back an address with no zone on it, so
         * it is taken from the route's own interface, which is by definition
         * the one the next hop is reachable through.
         */
        internal fun zoned(gateway: String, iface: String): String =
            if (iface.isNotBlank() && '%' !in gateway && isIpv6LinkLocal(gateway)) {
                "$gateway%$iface"
            } else {
                gateway
            }

        /** fe80::/10, i.e. the first three nibbles in fe8..feb. */
        private fun isIpv6LinkLocal(addr: String): Boolean {
            if (':' !in addr) return false
            val head = addr.lowercase().take(3)
            return head == "fe8" || head == "fe9" || head == "fea" || head == "feb"
        }

        /** An on-link route reports 0.0.0.0/:: as its "gateway"; that is not one. */
        internal fun gatewayText(addr: InetAddress?): String? {
            if (addr == null) return null
            if (addr.isAnyLocalAddress) return null
            return hostText(addr)
        }

        /** Strips the `%wlan0` scope suffix Android puts on IPv6 link-locals. */
        internal fun hostText(addr: InetAddress?): String? {
            val raw = try {
                addr?.hostAddress
            } catch (e: Exception) {
                null
            } ?: return null
            val cut = raw.indexOf('%')
            val text = if (cut >= 0) raw.substring(0, cut) else raw
            return text.takeIf { it.isNotBlank() }
        }

        internal fun addrText(addr: InetAddress?, prefixLength: Int): String? {
            val host = hostText(addr) ?: return null
            val width = if (prefixLength in 0..128) prefixLength
            else if (addr is Inet6Address) 128 else 32
            return "$host/$width"
        }
    }
}
