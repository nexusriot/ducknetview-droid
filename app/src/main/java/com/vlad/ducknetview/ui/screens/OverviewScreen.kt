package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.EngineMode
import com.vlad.ducknetview.domain.model.LatencySample
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.BarChart
import com.vlad.ducknetview.ui.components.DualSparkline
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.Sparkline
import com.vlad.ducknetview.ui.components.StatTile
import com.vlad.ducknetview.ui.components.TagBadge
import com.vlad.ducknetview.ui.theme.DuckColors

/**
 * The dashboard. Every card below the header appears only once it has
 * something to say, mirroring the TUI's panel behaviour — an empty card is
 * worse than no card because it reads as "measured zero" rather than
 * "not measured".
 *
 * Test tags: "screen:overview", "overview:header", "card:<title>",
 * "tile:<label>", "churn:delta", "overview:externalip".
 */
@Composable
fun OverviewScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    val snap = state.snapshot
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("screen:overview"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OverviewHeader(state)

        SecurityCard(state, actions)

        val defaultNetwork = snap.networks.firstOrNull { it.isDefault }
            ?: snap.networks.firstOrNull { !it.isNoise }
        if (defaultNetwork != null && defaultNetwork.hasNameInfo) {
            GatewayCard(defaultNetwork)
        }

        if (snap.latency.isNotEmpty()) {
            SectionCard(title = "Path latency") {
                snap.latency.forEach { LatencyRow(it) }
            }
        }

        if (hasThroughput(state)) {
            ThroughputCard(state)
        }

        if (snap.topApps.isNotEmpty() || snap.topHosts.isNotEmpty()) {
            TopTalkersCard(state)
        }

        if (snap.engine == EngineMode.VPN) {
            SectionCard(title = "Connection churn") {
                Text(
                    text = "Δ +${snap.newConnCount}/-${snap.closedConnCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("churn:delta"),
                )
                BarChart(values = snap.churnHistory)
            }
        }

        val externalIp = snap.externalIp
        if (externalIp != null) {
            SectionCard(
                title = "External IP",
                trailing = {
                    IconButton(
                        onClick = actions::refreshExternalIp,
                        modifier = Modifier.testTag("externalip:refresh"),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh external IP")
                    }
                },
            ) {
                Box(Modifier.testTag("overview:externalip")) {
                    KeyValueRow(label = "Address", value = externalIp)
                }
                KeyValueRow(label = "Age", value = ageOf(snap.atMillis, snap.externalIpAt))
            }
        }

        if (!state.caps.hasConnections) {
            SectionCard(title = "Capture is off") {
                Text(
                    text = "Android does not let an app read other apps' sockets. " +
                        "Per-connection and per-app live data need the local VPN " +
                        "capture engine; without it this build shows only what the " +
                        "public network APIs report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = actions::startVpn,
                    modifier = Modifier.testTag("overview:enablecapture"),
                ) {
                    Text("Enable capture")
                }
            }
        }
    }
}

private val NetworkRow.hasNameInfo: Boolean
    get() = gateway != null || dnsServers.isNotEmpty() || privateDns != null

private fun hasThroughput(state: UiState): Boolean {
    val s = state.snapshot
    return s.total.rxBps > 0 || s.total.txBps > 0 || s.sessionRx > 0 || s.sessionTx > 0 ||
        s.rxHistory.isNotEmpty() || s.txHistory.isNotEmpty()
}

private fun ageOf(now: Long, at: Long): String =
    if (at <= 0L) "-" else Units.age(now - at)

@Composable
private fun OverviewHeader(state: UiState) {
    val snap = state.snapshot
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("overview:header"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = snap.deviceName.ifEmpty { "This device" },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("overview:device"),
            )
            Text(
                text = "up ${Units.uptime(snap.uptimeMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("overview:uptime"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (snap.engine == EngineMode.VPN) {
                    TagBadge("VPN capture", DuckColors.Tx)
                } else {
                    TagBadge("API mode", DuckColors.Info)
                }
                if (snap.paused) {
                    TagBadge("⏸ PAUSED", DuckColors.Alert)
                }
                TagBadge("every ${snap.intervalSeconds}s", DuckColors.Rx)
            }
        }
    }
}

@Composable
private fun SecurityCard(state: UiState, actions: UiActions) {
    val sec = state.snapshot.security
    SectionCard(title = "Security") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Exposed",
                value = sec.exposedServices.toString(),
                modifier = Modifier.weight(1f),
                tint = if (sec.exposedServices > 0) DuckColors.Exposed else null,
                onClick = { actions.setTab(Tab.SERVICES) },
            )
            StatTile(
                label = "Public conns",
                value = sec.publicConns.toString(),
                modifier = Modifier.weight(1f),
                onClick = { actions.setTab(Tab.CONNECTIONS) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Watchlist",
                value = sec.watchlistHits.toString(),
                modifier = Modifier.weight(1f),
                tint = if (sec.watchlistHits > 0) DuckColors.Watchlist else null,
                onClick = { actions.setTab(Tab.CONNECTIONS) },
            )
            StatTile(
                label = "Off-baseline",
                value = sec.offBaseline.toString(),
                modifier = Modifier.weight(1f),
                tint = if (sec.offBaseline > 0) DuckColors.Alert else null,
                onClick = { actions.setTab(Tab.SERVICES) },
            )
        }
    }
}

@Composable
private fun GatewayCard(row: NetworkRow) {
    SectionCard(title = "Gateway & DNS") {
        KeyValueRow(label = "Gateway", value = row.gateway ?: "-")
        KeyValueRow(
            label = "DNS",
            value = row.dnsServers.joinToString(", ").ifEmpty { "-" },
        )
        val privateDns = row.privateDns
        if (privateDns != null) {
            KeyValueRow(label = "Private DNS", value = privateDns)
        }
    }
}

@Composable
private fun LatencyRow(sample: LatencySample) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("latency:${sample.target}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sample.label.ifEmpty { sample.target },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (sample.ok) Units.millis(sample.millis) else "unreachable",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (sample.ok) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    DuckColors.Warn
                },
            )
        }
        Sparkline(values = sample.history, height = 24.dp)
        // Which method produced the figure, because the two are not the same
        // measurement: an echo times the network, a handshake also times the
        // peer's accept path.
        Text(
            text = sample.method.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("latency:method:${sample.target}"),
        )
        val note = sample.note
        if (note != null && !sample.ok) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThroughputCard(state: UiState) {
    val snap = state.snapshot
    val unit = state.settings.rateUnit
    SectionCard(title = "Throughput") {
        KeyValueRow(
            label = "Now",
            value = "↓ ${Units.rate(snap.total.rxBps, unit)}   ↑ ${Units.rate(snap.total.txBps, unit)}",
        )
        KeyValueRow(
            label = "Session peak",
            value = "↓ ${Units.rate(snap.totalPeakRx, unit)}   ↑ ${Units.rate(snap.totalPeakTx, unit)}",
        )
        KeyValueRow(
            label = "Session total",
            value = "↓ ${Units.bytes(snap.sessionRx)}   ↑ ${Units.bytes(snap.sessionTx)}",
        )
        DualSparkline(rx = snap.rxHistory, tx = snap.txHistory)
    }
}

@Composable
private fun TopTalkersCard(state: UiState) {
    val snap = state.snapshot
    SectionCard(title = "Top talkers") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TalkerColumn(
                heading = "Apps",
                kind = "app",
                talkers = snap.topApps,
                modifier = Modifier.weight(1f),
            )
            TalkerColumn(
                heading = "Hosts",
                kind = "host",
                talkers = snap.topHosts,
                modifier = Modifier.weight(1f),
            )
        }
        KeyValueRow(
            label = "Session total",
            value = Units.bytes(snap.sessionRx + snap.sessionTx),
        )
    }
}

@Composable
private fun TalkerColumn(
    heading: String,
    kind: String,
    talkers: List<Talker>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (talkers.isEmpty()) {
            Text(
                text = "-",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        talkers.forEach { talker ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("talker:$kind:${talker.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = talker.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Units.bytes(talker.total),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
