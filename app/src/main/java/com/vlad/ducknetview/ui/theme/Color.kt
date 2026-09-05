package com.vlad.ducknetview.ui.theme

import androidx.compose.ui.graphics.Color
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Exposure
import com.vlad.ducknetview.domain.model.Scope

/**
 * The semantic palette ported from ducknetview's TUI colour table. These are
 * meaning-carrying colours (exposure, scope, event level, rx/tx) and are the
 * only literal colours screens may use; everything structural comes from
 * MaterialTheme.
 */
object DuckColors {
    val Exposed = Color(0xFFFF9800)
    val Lan = Color(0xFFFFC107)
    val Local = Color(0xFF9E9E9E)

    val Public = Color(0xFFFF7043)
    val Private = Color(0xFFFFCA28)
    val Loopback = Color(0xFF78909C)

    val Watchlist = Color(0xFFE040FB)
    val NewRow = Color(0xFF66BB6A)

    val Alert = Color(0xFFEF5350)
    val Warn = Color(0xFFFFA726)
    val Info = Color(0xFF90A4AE)

    val Rx = Color(0xFF4FC3F7)
    val Tx = Color(0xFFAED581)

    fun forScope(s: Scope): Color = when (s) {
        Scope.LOOPBACK -> Loopback
        Scope.PRIVATE -> Private
        Scope.PUBLIC -> Public
        Scope.MULTICAST -> Info
    }

    fun forExposure(e: Exposure): Color = when (e) {
        Exposure.LOCAL -> Local
        Exposure.LAN -> Lan
        Exposure.EXPOSED -> Exposed
    }

    fun forLevel(l: EventLevel): Color = when (l) {
        EventLevel.INFO -> Info
        EventLevel.WARN -> Warn
        EventLevel.ALERT -> Alert
    }
}
