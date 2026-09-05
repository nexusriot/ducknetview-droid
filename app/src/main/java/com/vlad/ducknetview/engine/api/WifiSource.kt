package com.vlad.ducknetview.engine.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.vlad.ducknetview.domain.model.CellularState
import com.vlad.ducknetview.domain.model.WifiState

/**
 * Wi-Fi radio state for the Interfaces detail pane.
 *
 * Two facts drive the shape of this class. First, from API 31 the authoritative
 * `WifiInfo` is the one hanging off the active network's capabilities, and
 * `WifiManager.getConnectionInfo()` is deprecated — but it is still the only
 * path that works when the capabilities carry no transport info, so both are
 * tried. Second, without location permission the platform hands back the
 * placeholder SSID `"<unknown ssid>"` and the anonymised BSSID
 * `02:00:00:00:00:00` instead of failing; those are reported as null so the UI
 * hides the field rather than rendering a fake value.
 */
class WifiSource(context: Context) {

    private val appContext = context.applicationContext

    private val wifiManager = try {
        appContext.getSystemService(WifiManager::class.java)
    } catch (e: Exception) {
        null
    }

    private val cm = try {
        appContext.getSystemService(ConnectivityManager::class.java)
    } catch (e: Exception) {
        null
    }

    fun current(): WifiState? {
        val info = transportInfo() ?: legacyInfo() ?: return null
        return stateOf(info)
    }

    /** True when the radio is on; a disabled radio is not a missing permission. */
    fun enabled(): Boolean = try {
        wifiManager?.isWifiEnabled == true
    } catch (e: Exception) {
        false
    }

    private fun transportInfo(): WifiInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = cm ?: return null
        return try {
            val active = manager.activeNetwork ?: return null
            manager.getNetworkCapabilities(active)?.transportInfo as? WifiInfo
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyInfo(): WifiInfo? = try {
        wifiManager?.connectionInfo
    } catch (e: SecurityException) {
        null
    } catch (e: Exception) {
        null
    }

    private fun stateOf(info: WifiInfo): WifiState? {
        val ssid = cleanSsid(safeString { info.ssid })
        val bssid = cleanBssid(safeString { info.bssid })
        val rssi = sanitizeRssi(safeInt({ info.rssi }, WifiState.UNKNOWN_RSSI))
        val freq = safeInt({ info.frequency }, 0).let { if (it > 0) it else 0 }
        val link = safeInt({ info.linkSpeed }, -1)
        val tx = safeInt({ info.txLinkSpeedMbps }, -1)
        val rx = safeInt({ info.rxLinkSpeedMbps }, -1)
        val standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            standardName(safeInt({ info.wifiStandard }, 0), freq)
        } else {
            null
        }
        val nothingKnown = ssid == null && bssid == null &&
            rssi == WifiState.UNKNOWN_RSSI && freq == 0 && link <= 0
        if (nothingKnown) return null
        return WifiState(
            ssid = ssid,
            bssid = bssid,
            rssiDbm = rssi,
            linkSpeedMbps = link,
            txLinkSpeedMbps = tx,
            rxLinkSpeedMbps = rx,
            frequencyMhz = freq,
            standard = standard,
        )
    }

    private inline fun safeString(block: () -> String?): String? = try {
        block()
    } catch (e: Exception) {
        null
    }

    private inline fun safeInt(block: () -> Int, fallback: Int): Int = try {
        block()
    } catch (e: Exception) {
        fallback
    }

    companion object {

        /** What `WifiManager.UNKNOWN_SSID` holds; matched literally to stay minSdk-safe. */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** The BSSID the platform substitutes when location access is denied. */
        const val ANONYMISED_BSSID = "02:00:00:00:00:00"

        /** Wi-Fi 6E is 11ax in the 6 GHz band; the platform has no separate constant. */
        const val SIX_GHZ_FLOOR_MHZ = 5925

        internal fun cleanSsid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            var s = raw.trim()
            if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
                s = s.substring(1, s.length - 1)
            }
            if (s.isBlank() || s == UNKNOWN_SSID) return null
            return s
        }

        internal fun cleanBssid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val s = raw.trim()
            if (s == ANONYMISED_BSSID || s == "00:00:00:00:00:00") return null
            return s
        }

        internal fun sanitizeRssi(rssi: Int): Int =
            if (rssi >= 0 || rssi <= -127) WifiState.UNKNOWN_RSSI else rssi

        internal fun standardName(standard: Int, frequencyMhz: Int): String? = when (standard) {
            ScanResult.WIFI_STANDARD_LEGACY -> "Legacy"
            ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
            ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
            ScanResult.WIFI_STANDARD_11AX ->
                if (frequencyMhz >= SIX_GHZ_FLOOR_MHZ) "Wi-Fi 6E" else "Wi-Fi 6"
            ScanResult.WIFI_STANDARD_11AD -> "WiGig"
            STANDARD_11BE -> "Wi-Fi 7"
            else -> null
        }

        /** `ScanResult.WIFI_STANDARD_11BE`, inlined so the file stays API-30 clean. */
        private const val STANDARD_11BE = 8
    }
}

/**
 * Mobile-network state. Every getter here is permission-gated on some vendor
 * builds even when the docs say otherwise, so each one degrades on its own.
 */
class CellularSource(context: Context) {

    private val tm = try {
        context.applicationContext.getSystemService(TelephonyManager::class.java)
    } catch (e: Exception) {
        null
    }

    fun current(): CellularState? {
        val manager = tm ?: return null
        val type = try {
            networkTypeName(manager.dataNetworkType)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
        val operator = try {
            manager.networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        val level = try {
            manager.signalStrength?.level ?: -1
        } catch (e: SecurityException) {
            -1
        } catch (e: Exception) {
            -1
        }
        if (type == null && operator == null && level < 0) return null
        return CellularState(
            networkType = type ?: "unknown",
            operator = operator,
            signalLevel = level,
        )
    }

    companion object {
        internal fun networkTypeName(type: Int): String? = when (type) {
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> null
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            -> "EVDO"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            else -> null
        }
    }
}
