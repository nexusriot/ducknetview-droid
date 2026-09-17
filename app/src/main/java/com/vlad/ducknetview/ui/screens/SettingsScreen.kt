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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.DuckFilterChip
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard

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
            ) { v -> actions.updateSettings { it.copy(alertRttMs = v.toInt()) } }
            NumberSetting(
                label = "Connection growth (polls)",
                help = "Fires when an app's connection count only grows for this many polls.",
                initial = s.alertConnGrowthPolls.toString(),
                tag = "settings:alertConnGrowthPolls",
            ) { v -> actions.updateSettings { it.copy(alertConnGrowthPolls = v.toInt()) } }
            NumberSetting(
                label = "Fan-out (hosts per poll)",
                help = "Fires when an app reaches this many new hosts in one poll.",
                initial = s.alertFanoutHosts.toString(),
                tag = "settings:alertFanoutHosts",
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

        SectionCard(title = "Privacy", modifier = Modifier.fillMaxWidth()) {
            Text(
                "Everything ducknetview collects stays on this device. The only outbound " +
                    "calls it ever makes are: the optional external-IP lookup (ipify), the " +
                    "latency targets you configure, the optional alert webhook, and " +
                    "reverse-DNS lookups when you turn them on. Packet contents are never " +
                    "captured, inspected or stored — only addresses, ports and byte counts.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("settings:privacy"),
            )
            KeyValueRow(label = "External IP lookup", value = if (s.externalIpEnabled) "on" else "off")
            KeyValueRow(label = "Reverse DNS", value = if (s.revDns) "on" else "off")
        }
    }
}

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
 * The field keeps whatever was typed; only the parsed value is pushed upstream.
 * Anything that is not a number — including an empty field — means 0, which is
 * the "rule disabled" value, so no input can put the settings into a bad state.
 */
@Composable
private fun NumberSetting(
    label: String,
    help: String,
    initial: String,
    tag: String,
    onValue: (Long) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                onValue(raw.trim().toLongOrNull() ?: 0L)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        Text(
            help,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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

@Composable
private fun EditableEntryRow(
    text: String,
    tag: String,
    deleteTag: String,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.testTag(deleteTag)) {
            Icon(Icons.Default.Delete, contentDescription = "Remove $text")
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
