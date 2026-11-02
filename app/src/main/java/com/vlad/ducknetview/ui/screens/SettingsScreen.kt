package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.watchlist.Watchlist
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.DuckFilterChip
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.theme.DuckColors

@Composable
fun SettingsScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    val s = state.settings

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SectionCard(title = "Capture engine", modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (state.vpnRunning) "Local capture running" else "Local capture off",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.vpnRunning,
                    enabled = state.vpnAvailable,
                    onCheckedChange = { on -> if (on) actions.startVpn() else actions.stopVpn() },
                    modifier = Modifier.testTag("settings:vpn-switch"),
                )
            }
            Text(
                if (state.vpnAvailable) {
                    "The capture engine is a VPN that terminates on this device — no " +
                        "remote server, no tunnel off the phone. Traffic is parsed and " +
                        "counted here and relayed onward as normal. It is what makes the " +
                        "connection table, per-connection bytes and RTT possible."
                } else {
                    "Another VPN is active. Android allows only one VPN at a time, so the " +
                        "capture engine cannot start until you disconnect it."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("settings:vpn-note"),
            )
            SwitchRow(
                label = "Start capture after a reboot",
                checked = s.startOnBoot,
                tag = "settings:startOnBoot",
            ) { v -> actions.updateSettings { it.copy(startOnBoot = v) } }
            if (s.startOnBoot && !state.vpnConsentGranted) {
                Text(
                    "Capture has never been allowed on this device, so a reboot cannot " +
                        "start it: the consent dialog needs the app open. Turn capture " +
                        "on once from here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings:boot-needs-consent"),
                )
            }
            KeyValueRow(
                label = "Always-on VPN",
                value = when {
                    state.lockdown -> "on, blocking without VPN"
                    state.alwaysOn -> "on"
                    else -> "off"
                },
                modifier = Modifier.testTag("settings:always-on"),
            )
            Text(
                "Android's own Always-on VPN (Settings → Network → VPN → ducknetview) " +
                    "keeps capture running across reboots and restarts it if it dies. " +
                    "With \"Block connections without VPN\" on as well, stopping capture " +
                    "here cuts the whole device off, not just the monitoring.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("TUN MTU", style = MaterialTheme.typography.bodyMedium)
                AppSettings.MTU_STEPS.forEach { mtu ->
                    DuckFilterChip(
                        mtu.toString(),
                        s.tunMtu == mtu,
                        { actions.updateSettings { it.copy(tunMtu = mtu) } },
                        Modifier.testTag("chip:mtu-$mtu"),
                    )
                }
            }
            Text(
                "Larger packets mean fewer of them to relay, which is the biggest lever " +
                    "on capture's CPU cost — but apps then send segments that size into " +
                    "the tunnel. Takes effect the next time capture starts.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Firewall", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Refuse new connections to a domain, an address or a range, for every " +
                    "app. Per-app rules, network policies and allowances live on each " +
                    "app's sheet. A name learnt before the connection opens is refused " +
                    "outright; one only visible in the TLS or QUIC handshake is cut " +
                    "the moment it appears. Needs capture.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.firewallRules.forEach { rule ->
                EditableEntryRow(
                    text = if (rule.global) rule.pattern else "${rule.pattern}  (${rule.packageName})",
                    tag = "rule:${rule.encode()}",
                    deleteTag = "rule:del:${rule.encode()}",
                    onDelete = { actions.removeFirewallRule(rule) },
                )
            }
            AddEntryRow(
                label = "Block domain, address or range",
                fieldTag = "settings:rule-add",
                buttonTag = "settings:rule-add-btn",
                onAdd = { actions.addFirewallRule(it, "") },
            )
            KeyValueRow(
                label = "Block list",
                value = if (state.blocklistSize > 0) "${state.blocklistSize} names" else "none imported",
                modifier = Modifier.testTag("settings:blocklist-size"),
            )
            SwitchRow(
                label = "Enforce the block list",
                checked = s.blocklistEnabled,
                tag = "settings:blocklistEnabled",
            ) { v -> actions.updateSettings { it.copy(blocklistEnabled = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions::importBlocklist,
                    modifier = Modifier.testTag("settings:blocklist-import"),
                ) { Text("Import hosts file") }
                if (state.blocklistSize > 0) {
                    OutlinedButton(
                        onClick = actions::clearBlocklist,
                        modifier = Modifier.testTag("settings:blocklist-clear"),
                    ) { Text("Remove") }
                }
            }
            Text(
                "A hosts-format list (0.0.0.0 tracker.example) from wherever you trust. " +
                    "Imported, it labels matching names on the Domains screen even when " +
                    "it is not enforced.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Refresh", modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Interval", style = MaterialTheme.typography.bodyMedium)
                AppSettings.INTERVAL_STEPS.forEach { step ->
                    DuckFilterChip(
                        "${step}s",
                        s.intervalSeconds == step,
                        { actions.setInterval(step) },
                        Modifier.testTag("chip:interval-$step"),
                    )
                }
            }
            SwitchRow(
                label = "Paused",
                checked = s.paused,
                tag = "settings:pause",
                onChange = { actions.togglePause() },
            )
            SwitchRow(
                label = "Show rates in bits/s",
                checked = s.rateUnit == RateUnit.BITS,
                tag = "settings:units",
                onChange = { actions.toggleRateUnit() },
            )
        }

        SectionCard(title = "Alerts", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Every threshold is opt-in: 0 disables the rule.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberSetting(
                label = "App rate (B/s)",
                help = "Fires when one app's rate exceeds this.",
                initial = s.alertAppBps.toString(),
                tag = "settings:alertAppBps",
            ) { v -> actions.updateSettings { it.copy(alertAppBps = v) } }
            NumberSetting(
                label = "Connection rate (B/s)",
                help = "Fires when a single connection exceeds this.",
                initial = s.alertConnBps.toString(),
                tag = "settings:alertConnBps",
            ) { v -> actions.updateSettings { it.copy(alertConnBps = v) } }
            NumberSetting(
                label = "RTT (ms)",
                help = "Fires when a handshake or probe is slower than this.",
                initial = s.alertRttMs.toString(),
                tag = "settings:alertRttMs",
                max = Int.MAX_VALUE.toLong(),
            ) { v -> actions.updateSettings { it.copy(alertRttMs = v.toInt()) } }
            NumberSetting(
                label = "Connection growth (polls)",
                help = "Fires when an app's connection count only grows for this many polls.",
                initial = s.alertConnGrowthPolls.toString(),
                tag = "settings:alertConnGrowthPolls",
                max = Int.MAX_VALUE.toLong(),
            ) { v -> actions.updateSettings { it.copy(alertConnGrowthPolls = v.toInt()) } }
            NumberSetting(
                label = "Fan-out (hosts per poll)",
                help = "Fires when an app reaches this many new hosts in one poll.",
                initial = s.alertFanoutHosts.toString(),
                tag = "settings:alertFanoutHosts",
                max = Int.MAX_VALUE.toLong(),
            ) { v -> actions.updateSettings { it.copy(alertFanoutHosts = v.toInt()) } }

            TextSetting(
                label = "Webhook URL",
                help = "Each alert is POSTed as JSON. Leave empty to send nothing.",
                initial = s.webhookUrl,
                tag = "settings:webhook",
            ) { v -> actions.updateSettings { it.copy(webhookUrl = v) } }

            SwitchRow(
                label = "Broadcast intent on alert",
                checked = s.broadcastOnAlert,
                tag = "settings:broadcast",
                onChange = { on -> actions.updateSettings { it.copy(broadcastOnAlert = on) } },
            )
        }

        SectionCard(title = "Untrusted networks", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Mark a network trusted or untrusted on the Links screen, where it " +
                    "is named. These decide what happens on arriving at one you do " +
                    "not trust \u2014 the checks you would run by hand in a caf\u00e9 " +
                    "and never remember to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                label = "Treat unseen networks as untrusted",
                checked = s.treatUnknownNetworksAsUntrusted,
                tag = "settings:treatUnknownUntrusted",
            ) { v -> actions.updateSettings { it.copy(treatUnknownNetworksAsUntrusted = v) } }
            SwitchRow(
                label = "Start capture automatically",
                checked = s.autoCaptureOnUntrusted,
                tag = "settings:autoCaptureOnUntrusted",
            ) { v -> actions.updateSettings { it.copy(autoCaptureOnUntrusted = v) } }
            SwitchRow(
                label = "Scan this device's listeners",
                checked = s.autoScanOnUntrusted,
                tag = "settings:autoScanOnUntrusted",
            ) { v -> actions.updateSettings { it.copy(autoScanOnUntrusted = v) } }
            Text(
                "The first capture on a device still needs its consent dialog, " +
                    "which only an open app can show \u2014 the same limit the Quick " +
                    "Settings tile has. Until it has been granted once, arriving " +
                    "on an untrusted network asks rather than starts.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValueRow(
                label = "Remembered",
                value = "${s.trustedNetworks.size} trusted · ${s.untrustedNetworks.size} untrusted",
            )
        }

        SectionCard(title = "Data budget", modifier = Modifier.fillMaxWidth()) {
            Text(
                "A monthly allowance, so the Usage screen can say whether this " +
                    "cycle is on course rather than only what it has spent. Zero " +
                    "turns the whole card off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberSetting(
                label = "Allowance (MB)",
                help = "The plan's cap. 0 disables the budget.",
                initial = (s.dataCapBytes / MB).toString(),
                tag = "settings:dataCap",
                max = MAX_CAP_MB,
            ) { v -> actions.updateSettings { it.copy(dataCapBytes = v * MB) } }
            NumberSetting(
                label = "Cycle starts on day",
                help = "Day of the month the plan resets. A day past the end of a " +
                    "short month falls on its last day.",
                initial = s.cycleStartDay.toString(),
                tag = "settings:cycleStartDay",
                max = 31,
            ) { v -> actions.updateSettings { it.copy(cycleStartDay = v.toInt().coerceIn(1, 31)) } }
            SwitchRow(
                label = "Count mobile data only",
                checked = s.quotaMobileOnly,
                tag = "settings:quotaMobileOnly",
            ) { v -> actions.updateSettings { it.copy(quotaMobileOnly = v) } }
            NumberSetting(
                label = "Warn at (% of cap)",
                help = "Fires once per 10% band past this. 0 disables the rule.",
                initial = s.alertQuotaPercent.toString(),
                tag = "settings:alertQuotaPercent",
                max = 1000,
            ) { v -> actions.updateSettings { it.copy(alertQuotaPercent = v.toInt()) } }
            SwitchRow(
                label = "Warn when projected over",
                checked = s.alertQuotaProjected,
                tag = "settings:alertQuotaProjected",
            ) { v -> actions.updateSettings { it.copy(alertQuotaProjected = v) } }
            if (!state.usageAccessGranted) {
                Text(
                    "The budget needs the system Usage Access permission to read " +
                        "cycle totals; without it the card says so rather than " +
                        "showing a budget of zero.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuckColors.Warn,
                    modifier = Modifier.testTag("settings:quota-needs-access"),
                )
            }
        }

        SectionCard(title = "Watchlist", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Accepted forms: a CIDR block (10.0.0.0/8), a bare IP (1.1.1.1), or a " +
                    "regular expression matched against the remote address and host name.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.watchlist.forEach { entry ->
                EditableEntryRow(
                    text = entry,
                    tag = "watchlist:$entry",
                    deleteTag = "watchlist:del:$entry",
                    onDelete = { actions.removeWatchlistEntry(entry) },
                    problem = Watchlist.problem(entry),
                    problemTag = "watchlist:problem:$entry",
                )
            }
            AddEntryRow(
                label = "Add watchlist entry",
                fieldTag = "settings:watchlist-add",
                buttonTag = "settings:watchlist-add-btn",
                onAdd = { actions.addWatchlistEntry(it) },
            )
        }

        SectionCard(title = "Latency targets", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Only the default gateway is probed unless you add a target here, so " +
                    "nothing leaves the LAN by default.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.latencyTargets.forEach { target ->
                EditableEntryRow(
                    text = target,
                    tag = "latency:$target",
                    deleteTag = "latency:del:$target",
                    onDelete = {
                        actions.updateSettings { it.copy(latencyTargets = it.latencyTargets - target) }
                    },
                )
            }
            AddEntryRow(
                label = "Add host or host:port",
                fieldTag = "settings:latency-add",
                buttonTag = "settings:latency-add-btn",
                onAdd = { entry ->
                    actions.updateSettings {
                        if (entry in it.latencyTargets) it
                        else it.copy(latencyTargets = it.latencyTargets + entry)
                    }
                },
            )
        }

        SectionCard(title = "Metrics", modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Prometheus endpoint",
                checked = s.metricsEnabled,
                tag = "settings:metrics-enabled",
                onChange = { on -> actions.updateSettings { it.copy(metricsEnabled = on) } },
            )
            NumberSetting(
                label = "Port",
                help = "Unauthenticated: serve it on a trusted network only.",
                initial = s.metricsPort.toString(),
                max = 65535L,
                // Every keystroke used to be applied, and each one rebinds the
                // listening socket. Typing 8080 therefore served the
                // unauthenticated metrics endpoint on port 80 on the way past
                // — verified on the test tablet, where an app can bind a low
                // port — and it stayed there for as long as the typing paused.
                commitOnDone = true,
                tag = "settings:metrics-port",
            ) { v -> actions.updateSettings { it.copy(metricsPort = v.toInt()) } }
            state.metricsUrl?.let { url ->
                KeyValueRow(label = "Scrape URL", value = url, modifier = Modifier.testTag("settings:metrics-url"))
            }
            state.metricsError?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("settings:metrics-error"),
                )
            }
        }

        SectionCard(title = "Snapshots", modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { actions.exportSnapshotJson() },
                modifier = Modifier.testTag("settings:export-json"),
            ) { Text("Export snapshot as JSON") }

            if (state.snapshot.frozen) {
                Text(
                    text = "Browsing ${state.snapshot.frozenLabel ?: "a snapshot"}. " +
                        "The live device is paused until you close it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("settings:snapshot-frozen"),
                )
                TextButton(
                    onClick = { actions.closeSnapshot() },
                    modifier = Modifier.testTag("settings:snapshot-close"),
                ) { Text("Back to this device") }
            } else {
                TextButton(
                    onClick = { actions.openSnapshot() },
                    modifier = Modifier.testTag("settings:snapshot-open"),
                ) { Text("Open a snapshot file") }
                Text(
                    text = "Reads a `ducknetview --json` snapshot and browses it " +
                        "frozen, so a server's snapshot can be inspected here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Saved views", modifier = Modifier.fillMaxWidth()) {
            Text(
                "A view keeps the current tab, search, quick filters, Connections sort " +
                    "and the group / closed / totals / reverse-DNS toggles under a name. " +
                    "The first four also appear as launcher shortcuts.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.savedViews.forEach { v ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${v.name}  ·  ${v.tab}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { actions.applyView(v.name) },
                        modifier = Modifier.testTag("view:apply:${v.name}"),
                    ) { Text("Open") }
                    TextButton(
                        onClick = { actions.deleteView(v.name) },
                        modifier = Modifier.testTag("view:del:${v.name}"),
                    ) { Text("Delete") }
                }
            }
            AddEntryRow(
                label = "Save current screen as",
                fieldTag = "settings:view-name",
                buttonTag = "settings:view-save",
                onAdd = { actions.saveView(it) },
            )
        }

        SectionCard(title = "Background data", modifier = Modifier.fillMaxWidth()) {
            NumberSetting(
                label = "Warn above (MB per app per day)",
                help = "An app moving more than this while not in the foreground. Needs " +
                    "Usage Access; works without capture. 0 disables it.",
                initial = (s.alertBackgroundBytes / MB).toString(),
                tag = "settings:alertBackground",
                max = MAX_CAP_MB,
            ) { v -> actions.updateSettings { it.copy(alertBackgroundBytes = v * MB) } }
        }

        SectionCard(title = "Network owners", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Import an ip2asn table (iptoasn.com, plain or .gz) and Connections " +
                    "shows the network behind each public address — the answer reverse " +
                    "DNS is silent on for most cloud hosts. Nothing is bundled and no " +
                    "lookup leaves the device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyValueRow(
                label = "AS table",
                value = if (state.asnSize > 0) "${state.asnSize} ranges" else "none imported",
                modifier = Modifier.testTag("settings:asn-size"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions::importAsn,
                    modifier = Modifier.testTag("settings:asn-import"),
                ) { Text("Import ip2asn") }
                if (state.asnSize > 0) {
                    OutlinedButton(
                        onClick = actions::clearAsn,
                        modifier = Modifier.testTag("settings:asn-clear"),
                    ) { Text("Remove") }
                }
            }
        }

        SectionCard(title = "Compare", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Open a snapshot exported earlier and see what changed since: listeners " +
                    "gained, lost or moved, new apps, first-contact hosts, links.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = actions::compareSnapshot,
                modifier = Modifier.testTag("settings:compare"),
            ) { Text("Compare a snapshot with now") }
        }

        SectionCard(title = "Automation", modifier = Modifier.fillMaxWidth()) {
            SwitchRow(
                label = "Accept automation intents",
                checked = s.automationEnabled,
                tag = "settings:automation",
            ) { v -> actions.updateSettings { it.copy(automationEnabled = v) } }
            Text(
                "Lets Tasker, Automate or adb send com.vlad.ducknetview.action." +
                    "START_CAPTURE, STOP_CAPTURE, SCAN_SERVICES, SCAN_HOSTS or " +
                    "EXPORT_SNAPSHOT. Any app on the device can send these, so they are " +
                    "refused until this is on; every command is logged either way.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Privacy", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Everything ducknetview collects stays on this device. The traffic it " +
                    "generates itself is: the external-IP lookup (ipify), the router latency probe " +
                    "and any latency targets you configure, the optional alert webhook, and " +
                    "the Hosts sweep (ICMP and mDNS on this link, only when you press Scan). " +
                    "Reverse DNS sends no queries: names come from DNS answers, TLS and QUIC " +
                    "handshakes already passing through capture. Packet contents are never " +
                    "stored — only addresses, ports, byte counts and those names.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings:privacy"),
            )
            SwitchRow(
                label = "External IP lookup (ipify)",
                checked = s.externalIpEnabled,
                tag = "settings:external-ip",
                onChange = { on -> actions.updateSettings { it.copy(externalIpEnabled = on) } },
            )
            KeyValueRow(label = "Reverse DNS", value = if (s.revDns) "on" else "off")
        }
    }
}

/** Caps are written in megabytes; the model stores bytes. */
private const val MB = 1_000_000L

/** A terabyte of allowance, past which the field is refusing nonsense. */
private const val MAX_CAP_MB = 1_000_000L

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

/**
 * A whole-number setting that says when it cannot use what was typed.
 *
 * It used to store `toLongOrNull() ?: 0L`, and 0 is documented on this very
 * screen as "disables the rule" — so a number too large to parse turned the
 * alert off while the box went on showing the number the user had typed. There
 * was no error, and the field never re-seeds, so the screen disagreed with the
 * engine until it was left and reopened. For a monitor, silently switching an
 * alert off is the worst direction to fail in.
 *
 * An out-of-range value is now reported and simply not stored, which leaves the
 * previous threshold in force. Emptying the field still means 0, because that
 * is what the user is saying and the help text spells out what 0 does.
 *
 * @param max the largest value the setting can hold. [Int]-typed settings pass
 *   [Int.MAX_VALUE]: they used to take `v.toInt()`, which truncates the low 32
 *   bits rather than clamping, so 4294967296 became 0 (rule off) and
 *   2147483648 became negative (also off).
 */
@Composable
private fun NumberSetting(
    label: String,
    help: String,
    initial: String,
    tag: String,
    max: Long = Long.MAX_VALUE,
    commitOnDone: Boolean = false,
    onValue: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val problem = numberProblem(text, max)
    val latest = rememberUpdatedState(text)
    val sink = rememberUpdatedState(onValue)

    fun push(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            sink.value(0L)
        } else {
            trimmed.toLongOrNull()?.takeIf { it in 0..max }?.let(sink.value)
        }
    }

    // A setting that only stores a number can be pushed on every keystroke.
    // One that *opens a socket* cannot: see [commitOnDone].
    if (commitOnDone) {
        DisposableEffect(Unit) { onDispose { push(latest.value) } }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                if (!commitOnDone) push(raw)
            },
            label = { Text(label) },
            singleLine = true,
            isError = problem != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = if (commitOnDone) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { push(latest.value) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(tag)
                .then(
                    if (commitOnDone) {
                        Modifier.onFocusChanged { if (!it.isFocused) push(latest.value) }
                    } else {
                        Modifier
                    }
                ),
        )
        Text(
            problem ?: help,
            style = MaterialTheme.typography.labelSmall,
            color = if (problem != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("$tag:problem"),
        )
    }
}

/** Why [text] cannot be stored as a threshold, or null when it can. */
internal fun numberProblem(text: String, max: Long): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toLongOrNull()
        // A run of digits too long for a Long is a whole number, just not one
        // that fits; saying "not a whole number" sends the reader hunting for a
        // typo that is not there. Either way it is refused, not stored.
        ?: return if (trimmed.all { it.isDigit() }) {
            "cannot be above $max, so the setting still holds its previous value"
        } else {
            "not a whole number, so the setting still holds its previous value"
        }
    if (value < 0) return "cannot be negative, so the setting still holds its previous value"
    if (value > max) return "cannot be above $max, so the setting still holds its previous value"
    return null
}

@Composable
private fun TextSetting(
    label: String,
    help: String,
    initial: String,
    tag: String,
    onValue: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                onValue(raw.trim())
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        Text(
            help,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * @param problem why this entry can never match, when it cannot. It is shown
 *   under the entry rather than suppressing it: the user wrote it on purpose,
 *   so the fix is to say what is wrong, not to drop it silently.
 */
@Composable
private fun EditableEntryRow(
    text: String,
    tag: String,
    deleteTag: String,
    onDelete: () -> Unit,
    problem: String? = null,
    problemTag: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.testTag(deleteTag)) {
                Icon(Icons.Default.Delete, contentDescription = "Remove $text")
            }
        }
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(problemTag ?: "$tag:problem"),
            )
        }
    }
}

@Composable
private fun AddEntryRow(
    label: String,
    fieldTag: String,
    buttonTag: String,
    onAdd: (String) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag(fieldTag),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(
            onClick = {
                val trimmed = entry.trim()
                if (trimmed.isNotEmpty()) {
                    onAdd(trimmed)
                    entry = ""
                }
            },
            modifier = Modifier.testTag(buttonTag),
        ) {
            Icon(Icons.Default.Add, contentDescription = label)
        }
    }
}
