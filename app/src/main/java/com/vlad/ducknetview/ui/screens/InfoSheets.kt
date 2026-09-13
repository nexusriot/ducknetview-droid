package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.Capabilities
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.UiState

/**
 * The honesty rule made discoverable: every screen can say, in the mode it is
 * actually running in, what it can and cannot see.
 *
 * The wording is derived from [Capabilities] and [EngineMode] rather than
 * written per screen, so it cannot go stale when the engine changes underneath.
 *
 * Test tags: sheet = "info:<tab route>" / "info:usage" / "info:onboarding",
 * close = "info:close", onboarding buttons = "info:enable" and "info:later".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenInfoSheet(tab: Tab, state: UiState, onDismiss: () -> Unit) {
    InfoSheet(
        tag = "info:${tab.route}",
        title = "${tab.title}: what this screen can see",
        lines = infoFor(tab, state.caps, state.snapshot.engine),
        onDismiss = onDismiss,
    )
}

/** The Usage overlay is not a [Tab], so it gets its own entry point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageInfoSheet(state: UiState, onDismiss: () -> Unit) {
    InfoSheet(
        tag = "info:usage",
        title = "Usage: what this screen can see",
        lines = usageInfo(state.usageAccessGranted),
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(onDismiss: () -> Unit, onEnableCapture: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("info:onboarding"),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = ONBOARDING_TITLE, style = MaterialTheme.typography.titleMedium)
            ONBOARDING_LINES.forEach { (heading, body) -> InfoParagraph(heading, body) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onEnableCapture,
                    modifier = Modifier.testTag("info:enable"),
                ) { Text("Enable capture") }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("info:later"),
                ) { Text("Not now") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoSheet(
    tag: String,
    title: String,
    lines: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(tag),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { (heading, body) -> InfoParagraph(heading, body) }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("info:close"),
            ) { Text("Close") }
        }
    }
}

@Composable
private fun InfoParagraph(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

const val ONBOARDING_TITLE = "Two ways to watch this device"

val ONBOARDING_LINES: List<Pair<String, String>> = listOf(
    "API mode, always on" to
        "Throughput, links, Wi-Fi, routes, DNS and latency come from Android's own " +
        "counters. No permission dialog, no cost. There is no live connection " +
        "table: Android closed /proc/net and netlink sock_diag to apps in Android 10.",
    "Capture mode, opt-in" to
        "A local VpnService routes this device's traffic into a TUN device the app " +
        "owns, so every flow can be listed with exact per-connection bytes, RTT, the " +
        "owning app, and closed-connection history.",
    "It stays on the phone" to
        "The capture engine is not a remote VPN and connects to no server. Packet " +
        "contents are never stored — the proxy relays and counts bytes. This is a " +
        "monitor, not a sniffer.",
    "One VPN at a time" to
        "Android allows a single active VPN, so capture is unavailable while " +
        "WireGuard, Tailscale or a work profile VPN is connected. API mode keeps " +
        "working, and the top bar always says which mode you are in.",
    "What it costs" to
        "Capture costs roughly five times the CPU per megabyte, which shows up as " +
        "battery, and on a fast link it gives up around a tenth of the download " +
        "speed. Turn it on when you want the connection table, off when you do not.",
)

/**
 * The per-screen honesty text, pure so it can be asserted without Compose.
 * Never returns an empty list.
 */
fun infoFor(tab: Tab, caps: Capabilities, engine: EngineMode): List<Pair<String, String>> {
    val capturing = engine == EngineMode.VPN || caps.hasConnections
    return when (tab) {
        Tab.OVERVIEW -> overviewInfo(capturing, caps)
        Tab.INTERFACES -> interfacesInfo(capturing)
        Tab.SERVICES -> servicesInfo(capturing)
        Tab.APPS -> appsInfo(capturing, caps)
        Tab.CONNECTIONS -> connectionsInfo(capturing, caps)
        Tab.ROUTES -> routesInfo(capturing)
        Tab.EVENTS -> eventsInfo(capturing)
    }
}

/** The Usage overlay's equivalent, split out because Usage is not a [Tab]. */
fun usageInfo(usageAccessGranted: Boolean): List<Pair<String, String>> = listOf(
    "Per-app history" to if (usageAccessGranted) {
        "Usage Access is granted, so daily totals can be broken down per app."
    } else {
        "Per-app history needs the system Usage Access permission, which only the " +
            "user can grant in Settings. Without it the device-level daily history " +
            "still works; the per-app breakdown is left out rather than guessed."
    },
    "What is kept" to
        "40 days of daily totals, with the top 50 apps and the top 50 hosts per day. " +
        "Older days are dropped; nothing is uploaded.",
    "How the day is counted" to
        "NetworkStatsManager reports cumulative totals for the current day, so the " +
        "rollup replaces today's row rather than adding to it. A day can therefore " +
        "change until midnight.",
    "Hosts" to
        "Per-host history is only recorded while the capture engine is running, " +
        "because Android has no per-host accounting of its own.",
)

private fun engineLine(capturing: Boolean): Pair<String, String> =
    if (capturing) {
        "Engine: capture" to
            "Traffic is routed through this app's own TUN device, so connections, " +
            "per-connection bytes, RTT and per-app attribution are all real " +
            "measurements rather than estimates."
    } else {
        "Engine: API" to
            "Only Android's public counters are in play. Anything that needs to see " +
            "individual sockets is unavailable until capture is started; the app " +
            "hides those columns instead of showing them empty."
    }

private fun overviewInfo(capturing: Boolean, caps: Capabilities): List<Pair<String, String>> =
    listOf(
        engineLine(capturing),
        "Throughput" to
            "Device totals from TrafficStats and NetworkStatsManager. Peaks, session " +
            "totals and the sparklines are computed here and reset with the session, " +
            "not read from the system.",
        "Security summary" to if (capturing) {
            "Exposed listeners come from the device self-scan; public connections, " +
            "watchlist hits and off-baseline counts are all live."
        } else {
            "Exposed listeners come from the device self-scan, but the public-connection " +
                "and watchlist counts stay at zero without capture, because there is no " +
                "connection table to count."
        },
        "Top talkers" to if (caps.hasPerAppLive) {
            "Per-app and per-host rates are attributed flow by flow through " +
                "getConnectionOwnerUid, so a row names the app that actually moved the bytes."
        } else {
            "Per-app rates are coarse per-uid counters and there are no per-host rates " +
                "at all: Android does not tell an app which remote address its neighbours " +
                "are talking to."
        },
        "Latency" to
            "Measured by opening a TCP connection to each target and timing the " +
            "handshake. ICMP ping needs a raw socket, which apps do not get, so this " +
            "is a TCP handshake time and is labelled as such.",
        "External IP" to
            "Optional and the only lookup that leaves the device: one HTTPS request to " +
            "ipify. Turn it off in Settings and nothing goes out.",
    )

private fun interfacesInfo(capturing: Boolean): List<Pair<String, String>> = listOf(
    "Where this comes from" to
        "ConnectivityManager reports every simultaneously active network, so a link " +
        "is listed even when it is not the default route.",
    "Wi-Fi radio detail" to
        "SSID, BSSID, band and standard need location permission — Android treats " +
        "Wi-Fi identity as location data. Without it the radio is still listed, but " +
        "the identity fields read as unavailable rather than being invented.",
    "Signal" to
        "RSSI is whatever the driver reports. Some devices never report it; that " +
        "shows as \"not measured\" instead of a fake bar.",
    "Byte counters" to
        "Per-interface bytes come from TrafficStats, which counts since boot, so the " +
        "rates here are differences between two polls.",
    "Not available" to
        "Per-interface error, drop and collision counters have no unprivileged " +
        "source on Android; those columns are omitted rather than shown as zero." +
        if (capturing) " Capture does not recover them either — they are a kernel counter." else "",
)

private fun servicesInfo(capturing: Boolean): List<Pair<String, String>> = listOf(
    "This scans the device itself" to
        "Android forbids enumerating other apps' listening sockets, so there is no " +
        "list to read. Instead the app connects to this device — loopback plus every " +
        "local address — and reports what answers.",
    "TCP only" to
        "A UDP listener does not answer a connect, so UDP is not reliably detectable " +
        "by scanning and is skipped. An absent UDP row means \"unknown\", not \"closed\".",
    "Exposure" to
        "A port bound to loopback is local; one bound to a LAN address is reachable " +
        "by the network you are on; a wildcard bind is treated as exposed.",
    "Owning app" to if (capturing) {
        "Where the capture engine has seen a flow on the port, the owning app is " +
            "filled in from its uid lookup. Ports nothing has connected to stay unattributed."
    } else {
        "The app behind a port cannot be resolved without the capture engine, so the " +
            "owner column stays empty in API mode."
    },
    "Baseline" to
        "Save the current set as the baseline; anything outside it is flagged " +
        "afterwards, including a listener that moves to a wider exposure. The " +
        "background scan re-checks every six hours once a baseline exists.",
)

private fun appsInfo(capturing: Boolean, caps: Capabilities): List<Pair<String, String>> = listOf(
    "Live rates" to if (caps.hasPerAppLive) {
        "Exact: every captured flow carries the uid the kernel attributed it to, so " +
            "an app's rate is the sum of its own flows."
    } else {
        "Coarse. Android exposes per-uid byte counters but no live socket ownership, " +
            "so rates are per-uid deltas between polls and short bursts can land on " +
            "the wrong side of a tick."
    },
    "Connection counts" to if (capturing) {
        "One row per app with the flows it currently owns, from the capture engine."
    } else {
        "There is no connection table in API mode, so connection counts and remote-host " +
            "counts are zero here until capture is started."
    },
    "Not available" to
        "CPU% and memory% of other processes are unavailable on Android — /proc entries " +
        "for other apps have been unreadable since Android 10 — so those columns are " +
        "omitted rather than faked.",
    "Today's totals" to
        "From NetworkStatsManager, which needs the system Usage Access permission. " +
        "Without it the column is blank rather than estimated.",
    "Blocking and excluding" to
        "Blocking refuses an app's upstream socket inside the capture engine, and " +
        "excluding keeps an app out of the TUN entirely. Both only do anything while " +
        "capture is running; this is not a system firewall.",
)

private fun connectionsInfo(capturing: Boolean, caps: Capabilities): List<Pair<String, String>> =
    if (!capturing) {
        listOf(
            "No live table in API mode" to
                "This screen is empty by design, not by accident. Listing this device's " +
                "sockets needs /proc/net or netlink sock_diag, and Android has blocked " +
                "both for unprivileged apps since Android 10. No public API replaces " +
                "them, so no app can show you a connection table without capturing.",
            "What capture changes" to
                "Start the capture engine and every TCP and UDP flow is routed through " +
                "a TUN device this app owns, which makes the table exact rather than " +
                "sampled: bytes, RTT, state, age and the owning app.",
            "What still works without it" to
                "Throughput, links, routes, DNS, latency, the device self-scan and the " +
                "event log all keep working in API mode.",
            "Privacy either way" to
                "Nothing here leaves the device, and packet contents are never stored " +
                "in either mode.",
        )
    } else {
        listOf(
            "Live flows" to
                "Every TCP and UDP flow passes through this app's TUN device, so the " +
                "table is the whole of this device's traffic, not a sample.",
            "Bytes and RTT" to if (caps.hasPerConnBytes) {
                "Counted by the proxy itself as it moves them, so per-connection totals " +
                    "are exact. RTT is measured on the upstream socket, so it is the app-to-peer " +
                    "round trip this device actually experienced."
            } else {
                "Per-connection byte counters are unavailable in this mode."
            },
            "App attribution" to
                "getConnectionOwnerUid names the app that owns each socket. Very " +
                "short-lived flows can answer INVALID_UID; those render as an unknown " +
                "app rather than being dropped from the table.",
            "Retransmits" to
                "No unprivileged source exists for retransmit counts, so the column is " +
                "hidden rather than approximated.",
            "Closed connections" to
                "Kept with their true lifetime totals, because the proxy saw the whole " +
                "connection from SYN to close.",
            "Privacy" to
                "Payloads are relayed and counted, never stored. This is a monitor, " +
                "not a sniffer.",
        )
    }

private fun routesInfo(capturing: Boolean): List<Pair<String, String>> = listOf(
    "Routes and DNS" to
        "Read from ConnectivityManager's LinkProperties for every simultaneously " +
        "active network, including the ones that are not the default route.",
    "ARP / neighbour table" to
        "Unavailable on Android 10 and later: /proc/net/arp is unreadable and there is " +
        "no API replacement. The screen says so instead of leaving a silent gap.",
    "Private DNS" to
        "The configured private-DNS mode and hostname are reported per network when " +
        "the system exposes them.",
    "While capturing" to if (capturing) {
        "A default route through this app's own TUN device is present — that is the " +
            "capture engine itself, not a second VPN."
    } else {
        "Starting capture adds a default route through this app's own TUN device; " +
            "that entry is the capture engine, not another VPN."
    },
)

private fun eventsInfo(capturing: Boolean): List<Pair<String, String>> = listOf(
    "What change detection covers" to
        "Each poll is diffed against the previous one: listeners appearing, " +
        "vanishing or moving to a wider exposure, networks coming and going, " +
        "watchlist hits, and the first contact with each new public host.",
    "Thresholds" to if (capturing) {
        "All five alert rules are evaluated: per-app rate, per-connection rate, RTT, " +
            "connection-count growth, and fan-out to many new hosts at once."
    } else {
        "Only the rules that a device-level counter can answer run in API mode. " +
            "Per-connection rate, connection growth and fan-out need the capture " +
            "engine and are not evaluated, so they never fire rather than firing wrongly."
    },
    "First contact" to
        "Recorded in the database rather than in memory, so restarting the app does " +
        "not replay every known host as brand new.",
    "Delivery" to
        "Warnings and alerts raise a notification, and optionally a webhook POST or a " +
        "broadcast Intent for automation apps. Nothing here runs a shell command.",
    "Retention" to
        "The log is local and capped; clearing it in Settings deletes it from the " +
        "device. It is never uploaded.",
)
