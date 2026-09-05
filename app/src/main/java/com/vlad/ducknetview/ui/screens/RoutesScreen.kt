@file:OptIn(ExperimentalFoundationApi::class)

package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.NetworkRow
import com.vlad.ducknetview.domain.model.RouteRow
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.TagBadge
import com.vlad.ducknetview.ui.theme.DuckColors

/**
 * One card per network — Android routinely has Wi-Fi, cellular and a VPN up at
 * the same time, each with its own table — plus a permanent note about what
 * this screen structurally cannot show.
 *
 * Test tags: "screen:routes", "route:<networkId>" per card (per list row when
 * split in two), "route:<networkId>:<destination>" per route row,
 * "routes:arpnote".
 */
@Composable
fun RoutesScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    // Only the network id is kept: the row itself is re-read from the snapshot so
    // the pane follows route and DNS changes on the selected network.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val networks = state.snapshot.networks
    val live = selectedId?.let { id -> networks.firstOrNull { it.id == id } }

    if (!twoPane) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
                .testTag("screen:routes"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            networks.forEach { row ->
                Box(Modifier.testTag("route:${row.id}")) {
                    NetworkRoutesCard(row)
                }
            }
            PlatformLimitsCard()
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .testTag("screen:routes"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            networks.forEach { row ->
                NetworkListRow(
                    row = row,
                    selected = row.id == live?.id,
                    actions = actions,
                    onSelect = { selectedId = row.id },
                )
            }
            PlatformLimitsCard()
        }
        Column(
            modifier = Modifier
                .weight(0.58f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (live == null) {
                Text(
                    text = "Select a network to see its routes and DNS.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Box(Modifier.testTag(TAG_DETAIL_SHEET)) {
                    NetworkRoutesCard(live)
                }
            }
        }
    }
}

@Composable
private fun NetworkListRow(
    row: NetworkRow,
    selected: Boolean,
    actions: UiActions,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("route:${row.id}")
            .combinedClickable(
                onClick = onSelect,
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
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "${row.ifaceName} · ${row.transportLabel}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${row.routes.size} ${if (row.routes.size == 1) "route" else "routes"}" +
                    if (row.isDefault) " · default" else "",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NetworkRoutesCard(row: NetworkRow) {
    SectionCard(title = "${row.ifaceName} · ${row.transportLabel}") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TagBadge(row.transportLabel, DuckColors.Info)
            TagBadge(row.ifaceName, DuckColors.Local)
            if (row.isDefault) TagBadge("default", DuckColors.Rx)
            if (!row.up) TagBadge("down", DuckColors.Warn)
        }
        KeyValueRow(
            label = "DNS",
            value = row.dnsServers.joinToString(", ").ifEmpty { "-" },
        )
        val privateDns = row.privateDns
        if (privateDns != null) {
            KeyValueRow(label = "Private DNS", value = privateDns)
        }
        Text(
            text = "Routes",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (row.routes.isEmpty()) {
            Text(
                text = "no routes reported for this network",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.routes.forEach { route -> RouteLine(row.id, route) }
    }
}

@Composable
private fun RouteLine(networkId: String, route: RouteRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("route:$networkId:${route.destination}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = route.destination,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (route.isDefault) DuckColors.Rx else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = "via ${route.gateway ?: "-"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = "dev ${route.iface}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.25f),
        )
        if (route.isDefault) TagBadge("default", DuckColors.Rx)
    }
}

@Composable
private fun PlatformLimitsCard() {
    SectionCard(title = "What this screen cannot show") {
        Text(
            text = "The ARP / neighbour table is not available to apps on " +
                "Android 10 and later. /proc/net/arp is unreadable outside the " +
                "system UID and there is no public API for it, so neighbours are " +
                "not shown here — this is a platform limit, not a gap in the data " +
                "collection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("routes:arpnote"),
        )
        Text(
            text = "Routes and DNS are refreshed when the platform reports a " +
                "network change, not on the poll timer, so this screen can sit " +
                "still while the rest of the app ticks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("routes:cadencenote"),
        )
    }
}
