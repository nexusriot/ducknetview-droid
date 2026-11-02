package com.vlad.ducknetview.domain.model

enum class EventLevel { INFO, WARN, ALERT }

/**
 * Event kinds ported from ducknetview's event log, plus the Android-specific
 * ones (engine lifecycle, default-network changes) that have no TUI analogue.
 */
enum class EventKind(val label: String) {
    SERVICE_UP("service_up"),
    SERVICE_DOWN("service_down"),
    SERVICE_MOVED("service_moved"),
    OFF_BASELINE("off_baseline"),
    WATCHLIST_HIT("watchlist_hit"),
    NEW_PUBLIC_HOST("new_public_host"),
    NEW_DOMAIN("new_domain"),
    NETWORK_UP("network_up"),
    NETWORK_DOWN("network_down"),
    NETWORK_CHANGED("network_changed"),
    NETWORK_UNTRUSTED("network_untrusted"),
    ENGINE_STARTED("engine_started"),
    ENGINE_STOPPED("engine_stopped"),
    ENGINE_REVOKED("engine_revoked"),
    CAPTIVE_PORTAL("captive_portal"),
    THRESHOLD_APP_BPS("app_bps"),
    THRESHOLD_CONN_BPS("connection_bps"),
    THRESHOLD_RTT("rtt_ms"),
    THRESHOLD_APP_BPS_CLEARED("app_bps_cleared"),
    THRESHOLD_CONN_BPS_CLEARED("connection_bps_cleared"),
    THRESHOLD_RTT_CLEARED("rtt_ms_cleared"),
    BACKGROUND_DATA("background_data"),
    CLEARTEXT_FLOW("cleartext_flow"),
    TLS_DEPRECATED("tls_deprecated"),
    APP_BUDGET("app_budget"),
    AUTOMATION("automation"),
    BOOT_START("boot_start"),
    CONN_GROWTH("conn_growth"),
    BLOCKED_ATTEMPT("blocked_attempt"),
    QUOTA_PERCENT("quota_percent"),
    QUOTA_PROJECTED("quota_projected"),
    FANOUT("fanout"),
    SUPPRESSED("suppressed"),
    ;

    companion object {
        fun fromLabel(s: String): EventKind? = entries.firstOrNull { it.label == s }
    }
}

data class Event(
    val id: Long = 0L,
    val at: Long,
    val level: EventLevel,
    val kind: EventKind,
    val subject: String,
    val detail: String = "",
) {
    fun toCsvRow(): List<String> =
        listOf(at.toString(), level.name.lowercase(), kind.label, subject, detail)
}
