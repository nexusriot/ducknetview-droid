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
    val domainsSortCol: String = "seen",
    val domainsSortDesc: Boolean = true,
    val neighboursSortCol: String = "address",
    val neighboursSortDesc: Boolean = false,
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
    /**
     * The plan's allowance in bytes; zero disables the whole budget feature,
     * matching every other threshold on that screen.
     */
    val dataCapBytes: Long = 0L,
    /** Day of the month the plan resets. Clamped into a short month. */
    val cycleStartDay: Int = 1,
    /** Count mobile only, which is what a cap is almost always written against. */
    val quotaMobileOnly: Boolean = true,
    /** Warn at this percentage of the cap; zero disables that rule alone. */
    val alertQuotaPercent: Int = 0,
    /** Warn when the projection passes the cap. */
    val alertQuotaProjected: Boolean = false,
    /**
     * Networks the user has vouched for, and ones they have not, by the
     * stable key `NetworkTrust.keyOf` derives. Two sets rather than a map
     * because the third state — never seen before — is the interesting one,
     * and absence expresses it without a sentinel.
     */
    val trustedNetworks: Set<String> = emptySet(),
    val untrustedNetworks: Set<String> = emptySet(),
    /** Whether a network nobody has judged yet is treated as untrusted. */
    val treatUnknownNetworksAsUntrusted: Boolean = false,
    /** Start capture on joining an untrusted network, if consent already exists. */
    val autoCaptureOnUntrusted: Boolean = false,
    /** Scan this device's own listeners on joining an untrusted network. */
    val autoScanOnUntrusted: Boolean = false,
    val alertsAckedAt: Long = 0L,
    /** Start capture after a reboot — only ever once VPN consent exists. */
    val startOnBoot: Boolean = false,
    /** Warn when one app moves this many bytes in the background today; zero is off. */
    val alertBackgroundBytes: Long = 0L,
    /** Encoded [com.vlad.ducknetview.domain.firewall.FirewallRule]s. */
    val firewallRules: List<String> = emptyList(),
    /** Encoded [com.vlad.ducknetview.domain.firewall.AppPolicy]s, one per uid. */
    val appPolicies: Set<String> = emptySet(),
    /** Enforce the imported hosts-format block list (it is always used for labels). */
    val blocklistEnabled: Boolean = false,
    /** Encoded [com.vlad.ducknetview.domain.views.SavedView]s, in the user's order. */
    val savedViews: List<String> = emptyList(),
    /**
     * The TUN's MTU. 1500 matches the links underneath; larger values cut
     * packets, writes and ACKs on the relay but change what apps put on the
     * wire through the tunnel, so it is the user's experiment to run.
     */
    val tunMtu: Int = DEFAULT_MTU,
    /** Encoded per-app allowances; see [com.vlad.ducknetview.domain.usage.AppBudgets]. */
    val appBudgets: Set<String> = emptySet(),
    /** Accept the exported automation intents (Tasker and the like). */
    val automationEnabled: Boolean = false,
    /** False until the first-run explainer has been seen and dismissed. */
    val onboardingShown: Boolean = false,
) {
    companion object {
        /** The TUI's `+`/`-` steps: 1 s to 10 s. */
        val INTERVAL_STEPS = listOf(1, 2, 3, 5, 10)

        const val DEFAULT_MTU = 1500
        val MTU_STEPS = listOf(1500, 4000, 9000, 16000)
    }
}
