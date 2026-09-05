package com.vlad.ducknetview.domain.model

/** Rate/total column mode — the TUI's `t` toggle. */
enum class ThroughputMode { RATE, TOTAL }

/** bytes/s vs bits/s — the TUI's `U` toggle. */
enum class RateUnit { BYTES, BITS }

/** Whether a search query hides non-matching rows or merely marks them. */
enum class SearchMode { FILTER, HIGHLIGHT }

enum class ProtoFilter { ALL, TCP, UDP }
enum class IpVersionFilter { ALL, V4, V6 }
enum class StateFilter { ALL, ESTABLISHED, ACTIVE }
enum class EventLevelFilter { ALL, WARN_PLUS, ALERTS }

/**
 * Everything the TUI persists to config.json, saved on change rather than on
 * exit (a phone can be killed at any moment, exactly like the pty quit path
 * the TUI had to work around).
 */
data class AppSettings(
    val intervalSeconds: Int = 2,
    val paused: Boolean = false,
    val rateUnit: RateUnit = RateUnit.BYTES,
    val throughputMode: ThroughputMode = ThroughputMode.RATE,
    val searchMode: SearchMode = SearchMode.FILTER,
    val hideNoise: Boolean = true,
    val revDns: Boolean = false,
    val groupByHost: Boolean = false,
    val showClosed: Boolean = false,
    val lastTab: String = "overview",
    val connsSortCol: String = "rx",
    val connsSortDesc: Boolean = true,
    val appsSortCol: String = "rx",
    val appsSortDesc: Boolean = true,
    val servicesSortCol: String = "port",
    val servicesSortDesc: Boolean = false,
    val externalIpEnabled: Boolean = true,
    val latencyTargets: List<String> = emptyList(),
    val watchlist: List<String> = emptyList(),
    val baseline: List<String> = emptyList(),
    val baselineAt: Long = 0L,
    val blockedUids: Set<Int> = emptySet(),
    val excludedUids: Set<Int> = emptySet(),
    val scanFullRange: Boolean = false,
    val metricsEnabled: Boolean = false,
    val metricsPort: Int = 9187,
    val webhookUrl: String = "",
    val broadcastOnAlert: Boolean = false,
    val alertAppBps: Long = 0L,
    val alertConnBps: Long = 0L,
    val alertRttMs: Int = 0,
    val alertConnGrowthPolls: Int = 0,
    val alertFanoutHosts: Int = 0,
    val alertsAckedAt: Long = 0L,
    /** False until the first-run explainer has been seen and dismissed. */
    val onboardingShown: Boolean = false,
) {
    companion object {
        /** The TUI's `+`/`-` steps: 1 s to 10 s. */
        val INTERVAL_STEPS = listOf(1, 2, 3, 5, 10)
    }
}
