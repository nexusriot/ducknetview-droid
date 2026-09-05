package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.CellularState
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.WifiState
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.DualSparkline
import com.vlad.ducknetview.ui.components.DuckFilterChip
import com.vlad.ducknetview.ui.components.EmptyState
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.TagBadge
import com.vlad.ducknetview.ui.theme.DuckColors

/**
 * Links list plus detail. Android reports a handful of networks at most, so
 * this is a plain scrolling Column rather than a LazyColumn — every row stays
 * composed, which also keeps the screen straightforward to drive from tests.
 *
 * Test tags: "screen:interfaces", "iface:<id>" per row, "iface:detail",
 * "chip:Hide noise", "empty:No links".
 */
@Composable
fun InterfacesScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    val all = state.snapshot.networks
    val hideNoise = state.settings.hideNoise
    val rows = if (hideNoise) all.filter { !it.isNoise } else all
    val selected = rows.firstOrNull { it.id == state.snapshot.selectedNetworkId }
        ?: rows.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("screen:interfaces"),
    ) {
        FilterRow(shown = rows.size, total = all.size, hideNoise = hideNoise, actions = actions)

        if (rows.isEmpty()) {
            EmptyStateForFilter(all.size, hideNoise, actions)
            return@Column
        }

        if (twoPane) {
            Row(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rows.forEach { NetworkListRow(it, it.id == selected?.id, actions) }
                }
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) {
                    if (selected != null) NetworkDetail(selected, state.settings.rateUnit, state.wifiPermissionGranted, actions)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { NetworkListRow(it, it.id == selected?.id, actions) }
                if (selected != null) NetworkDetail(selected, state.settings.rateUnit, state.wifiPermissionGranted, actions)
            }
        }
    }
}

@Composable
private fun FilterRow(shown: Int, total: Int, hideNoise: Boolean, actions: UiActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "$shown of $total links",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .testTag("interfaces:count"),
        )
        DuckFilterChip(
            label = "Hide noise",
            selected = hideNoise,
            onClick = actions::toggleHideNoise,
        )
    }
}

@Composable
private fun EmptyStateForFilter(total: Int, hideNoise: Boolean, actions: UiActions) {
    if (total > 0 && hideNoise) {
        EmptyState(
            title = "No links",
            message = "Hide-noise is on, so loopback, dummy and down interfaces are " +
                "hidden — and that is every one of the $total interfaces reported " +
                "right now.",
            actionLabel = "Show everything",
            onAction = actions::toggleHideNoise,
        )
    } else {
        EmptyState(
            title = "No links",
            message = "No networks have been reported yet. This fills in on the " +
                "first poll, or when a network comes up.",
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NetworkListRow(row: NetworkRow, selected: Boolean, actions: UiActions) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("iface:${row.id}")
            .combinedClickable(
                onClick = { actions.selectNetwork(row.id) },
                onLongClick = { actions.copyText("interface", row.asCopyText()) },
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.ifaceName + if (row.isDefault) "  •default" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.transportLabel + " · " +
                        (row.addresses.firstOrNull() ?: if (row.up) "no address" else "down"),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "↓ ${Units.rate(row.rxBps, RateUnit.BYTES)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = DuckColors.Rx,
                )
                Text(
                    text = "↑ ${Units.rate(row.txBps, RateUnit.BYTES)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = DuckColors.Tx,
                )
            }
        }
    }
}

@Composable
private fun NetworkDetail(
    row: NetworkRow,
    unit: RateUnit,
    wifiPermissionGranted: Boolean,
    actions: UiActions,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("iface:detail"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(title = row.ifaceName) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TagBadge(row.transportLabel, DuckColors.Info)
                TagBadge(if (row.metered) "metered" else "unmetered", DuckColors.Warn)
                if (row.validated) TagBadge("validated", DuckColors.NewRow)
                if (row.isDefault) TagBadge("default", DuckColors.Rx)
            }
            KeyValueRow(
                label = "Addresses",
                value = row.addresses.joinToString(", ").ifEmpty { "-" },
            )
            KeyValueRow(label = "MTU", value = if (row.mtu > 0) row.mtu.toString() else "-")
            KeyValueRow(label = "State", value = if (row.up) "up" else "down")
        }

        SectionCard(title = "Throughput") {
            KeyValueRow(
                label = "Now",
                value = "↓ ${Units.rate(row.rxBps, unit)}   ↑ ${Units.rate(row.txBps, unit)}",
            )
            KeyValueRow(
                label = "Session peak",
                value = "↓ ${Units.rate(row.peakRxBps, unit)}   ↑ ${Units.rate(row.peakTxBps, unit)}",
            )
            KeyValueRow(
                label = "Cumulative",
                value = "↓ ${Units.bytes(row.rxBytes)}   ↑ ${Units.bytes(row.txBytes)}",
            )
            DualSparkline(rx = row.rxHistory, tx = row.txHistory)
        }

        val wifi = row.wifi
        if (wifi != null) WifiCard(wifi, wifiPermissionGranted, actions)

        val cellular = row.cellular
        if (cellular != null) CellularCard(cellular)
    }
}

@Composable
private fun WifiCard(
    wifi: WifiState,
    permissionGranted: Boolean,
    actions: UiActions,
) {
    SectionCard(title = "Wi-Fi") {
        val measured = wifi.rssiDbm != WifiState.UNKNOWN_RSSI
        // Without the location grant the platform withholds SSID and signal
        // detail, so an unexplained "not measured" would look like a bug in
        // this app rather than a permission the user can give.
        if (!permissionGranted && !measured) {
            Text(
                text = "Android withholds Wi-Fi radio detail until location " +
                    "access is granted. Nothing leaves the device; the app only " +
                    "reads the radio state of the link you are already on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("wifi:permission-note"),
            )
            TextButton(
                onClick = { actions.requestWifiPermission() },
                modifier = Modifier.testTag("wifi:permission-grant"),
            ) { Text("Grant location access") }
        }
        KeyValueRow(
            label = "Signal",
            value = if (measured) "${wifi.rssiDbm} dBm · ${wifi.rating}" else wifi.rating,
            valueColor = if (measured && wifi.qualityPercent < 40) DuckColors.Warn else null,
        )
        KeyValueRow(
            label = "Quality",
            value = if (wifi.qualityPercent >= 0) "${wifi.qualityPercent}%" else "-",
        )
        KeyValueRow(
            label = "Link speed",
            value = if (wifi.linkSpeedMbps > 0) "${wifi.linkSpeedMbps} Mb/s" else "-",
        )
        if (wifi.txLinkSpeedMbps >= 0 || wifi.rxLinkSpeedMbps >= 0) {
            KeyValueRow(
                label = "Tx / Rx link",
                value = "${speed(wifi.txLinkSpeedMbps)} / ${speed(wifi.rxLinkSpeedMbps)}",
            )
        }
        KeyValueRow(label = "Band", value = wifi.band.ifEmpty { "-" })
        KeyValueRow(label = "Standard", value = wifi.standard ?: "-")
        KeyValueRow(label = "SSID", value = wifi.ssid ?: "-")
        KeyValueRow(label = "BSSID", value = wifi.bssid ?: "-")
    }
}

@Composable
private fun CellularCard(cellular: CellularState) {
    SectionCard(title = "Cellular") {
        KeyValueRow(label = "Network type", value = cellular.networkType.ifEmpty { "-" })
        KeyValueRow(label = "Operator", value = cellular.operator ?: "-")
        KeyValueRow(label = "Signal", value = "${cellular.signalLevel}/4")
    }
}

private fun speed(mbps: Int): String = if (mbps >= 0) "$mbps Mb/s" else "-"

internal val NetworkRow.transportLabel: String
    get() = transport.name.lowercase()

/** Tab-separated, matching the TUI's spreadsheet-friendly copy format. */
internal fun NetworkRow.asCopyText(): String = listOf(
    ifaceName,
    transportLabel,
    if (up) "up" else "down",
    addresses.joinToString(","),
    if (mtu > 0) mtu.toString() else "",
    gateway ?: "",
    dnsServers.joinToString(","),
    rxBps.toString(),
    txBps.toString(),
).joinToString("\t")
