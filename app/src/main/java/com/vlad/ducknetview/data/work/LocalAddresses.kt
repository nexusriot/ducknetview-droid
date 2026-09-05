package com.vlad.ducknetview.data.work

import android.content.Context
import android.net.ConnectivityManager
import com.vlad.ducknetview.engine.api.PortScanner

/**
 * The device's own addresses, read straight from `ConnectivityManager`.
 *
 * A background worker runs in its own process context with no live
 * `NetworkInfoSource` behind it — the callback-driven source only knows what it
 * has been told since it was started, which for a freshly created process is
 * nothing. Reading `LinkProperties` once, here, is the only way a worker can
 * know which LAN addresses to knock on.
 */
object LocalAddresses {

    /**
     * Loopback is always present: a device with no LAN address still has local
     * listeners, and the scanner needs at least one target to say anything.
     */
    @Suppress("DEPRECATION")
    fun of(context: Context): List<String> {
        val raw = ArrayList<String>()
        val cm = try {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        } catch (e: Exception) {
            null
        }
        if (cm != null) {
            val networks = try {
                cm.allNetworks
            } catch (e: Exception) {
                emptyArray()
            }
            for (network in networks) {
                val link = try {
                    cm.getLinkProperties(network)
                } catch (e: Exception) {
                    null
                } ?: continue
                for (address in link.linkAddresses) {
                    val host = try {
                        address.address?.hostAddress
                    } catch (e: Exception) {
                        null
                    }
                    if (host != null) raw.add(host)
                }
            }
        }
        return usable(raw)
    }

    /**
     * Normalises and de-duplicates, dropping the addresses a TCP connect cannot
     * usefully target: link-local IPv6 needs a scope id that `InetAddress`
     * parsing has already stripped, and loopback is added once by the scanner
     * itself regardless of how many interfaces report it.
     */
    fun usable(hostAddresses: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        out.add(PortScanner.LOOPBACK)
        for (candidate in hostAddresses) {
            val normalized = PortScanner.normalizeAddr(candidate) ?: continue
            if (isLinkLocal(normalized) || isLoopback(normalized)) continue
            out.add(normalized)
        }
        return out.toList()
    }

    private fun isLinkLocal(addr: String): Boolean {
        val lower = addr.lowercase()
        return lower.startsWith("fe80:") || lower.startsWith("169.254.")
    }

    private fun isLoopback(addr: String): Boolean =
        addr == "::1" || addr.startsWith("127.")
}
