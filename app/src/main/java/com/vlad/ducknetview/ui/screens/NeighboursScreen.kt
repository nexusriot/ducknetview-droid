@file:OptIn(ExperimentalFoundationApi::class)

package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.net.Subnet
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.DetailSheet
import com.vlad.ducknetview.ui.components.EmptyState
import com.vlad.ducknetview.ui.components.HighlightedText
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.SortChips
import com.vlad.ducknetview.ui.components.TableSearchBar
import com.vlad.ducknetview.ui.components.TagBadge
import com.vlad.ducknetview.ui.theme.DuckColors

internal val NEIGHBOUR_SORT_COLUMNS = listOf("address", "name", "rtt", "seen")

/**
 * Other hosts on this link — the honest replacement for a neighbour table
 * Android will not show.
 *
 * Every other screen in this app is passive. This one is not, which is why it
 * has a button and no automatic refresh: a sweep puts packets on the wire to
 * hosts the user did not name, and doing that on a two-second timer in
 * somebody's office is not a thing a monitor gets to decide for itself.
 */
@Composable
fun NeighboursScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val s = state.settings
    val snap = state.snapshot
    val now = snap.atMillis
    val live = selectedKey?.let { key -> state.neighbours.firstOrNull { it.key == key } }
    val sweepable = snap.networks
        .firstOrNull { it.isDefault && !it.isNoise }
        ?.addresses
        ?.let(Subnet::firstV4)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = if (snap.neighbourScanAt > 0L) {
                    val where = snap.neighbourSubnet.ifEmpty { "this link" }
                    "Last sweep ${formatClock(snap.neighbourScanAt, now)} · $where"
                } else {
                    "Never swept"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("neighbours:lastscan"),
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { actions.scanNeighbours() },
                // Frozen means a snapshot file is on screen; sweeping would
                // measure this device and show it under that file's banner.
                enabled = !snap.neighbourScanRunning && !snap.frozen && sweepable != null,
                modifier = Modifier.testTag("neighbours:scan"),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Sweep")
            }
            IconButton(
                onClick = { actions.exportCurrentTable() },
                modifier = Modifier.testTag("neighbours:export"),
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export table")
            }
        }

        if (sweepable != null) {
            Text(
                text = "${Subnet.hostCount(sweepable)} addresses in $sweepable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("neighbours:range"),
            )
        }

        if (snap.neighbourScanRunning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("neighbours:progress"),
            )
        }

        TableSearchBar(
            search = state.search,
            matchCount = state.matchCount,
            onQuery = { actions.setSearch(it) },
            onToggleMode = { actions.toggleSearchMode() },
            onClear = { actions.clearSearch() },
            modifier = Modifier.fillMaxWidth(),
            matchPosition = state.matchPosition,
            onPrevMatch = actions::prevMatch,
            onNextMatch = actions::nextMatch,
        )

        SortChips(
            columns = NEIGHBOUR_SORT_COLUMNS,
            active = s.neighboursSortCol,
            desc = s.neighboursSortDesc,
            onSort = { actions.setSort("neighbours", it) },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            when {
                snap.frozen -> EmptyState(
                    title = EMPTY_NEIGHBOURS_FROZEN,
                    message = "A snapshot file carries no neighbour sweep — the format " +
                        "predates this screen, and a sweep run now would describe this " +
                        "device's network, not the one the file came from.",
                    modifier = tableModifier.testTag(emptyTag(EMPTY_NEIGHBOURS_FROZEN)),
                )

                sweepable == null -> EmptyState(
                    title = EMPTY_NEIGHBOURS_NO_V4,
                    message = "This link has no IPv4 prefix to sweep. An on-link IPv6 " +
                        "prefix is a /64 — eighteen quintillion addresses — so there is " +
                        "nothing to walk, and the neighbour discovery IPv6 uses instead " +
                        "needs multicast no app can read on Android.",
                    modifier = tableModifier.testTag(emptyTag(EMPTY_NEIGHBOURS_NO_V4)),
                )

                snap.neighbourScanAt == 0L && state.neighbours.isEmpty() -> EmptyState(
                    title = EMPTY_NEIGHBOURS_NEVER,
                    message = "Android has not exposed the ARP / neighbour table to apps " +
                        "since version 10, so the only way to answer this is to ask the " +
                        "network: an ICMP echo to each address in $sweepable, plus mDNS " +
                        "for the names. Nothing leaves this link.",
                    actionLabel = "Sweep",
                    onAction = { actions.scanNeighbours() },
                    modifier = tableModifier.testTag(emptyTag(EMPTY_NEIGHBOURS_NEVER)),
                )

                state.neighbours.isEmpty() -> EmptyState(
                    title = EMPTY_NEIGHBOURS,
                    message = if (snap.neighbours.isEmpty()) {
                        "Nothing on this link answered. A host that filters ICMP and " +
                            "publishes no mDNS is invisible here and still on the " +
                            "network — this is a list of what replied, not a census. " +
                            "Guest and client-isolation networks answer nothing by design."
                    } else {
                        "No host matches the current search."
                    },
                    actionLabel = "Sweep again",
                    onAction = { actions.scanNeighbours() },
                    modifier = tableModifier.testTag(emptyTag(EMPTY_NEIGHBOURS)),
                )

                else -> {
                    val listState = rememberLazyListState()
                    ScrollToMatch(state.matchCursor, state.neighbours.size, listState)
                    LazyColumn(modifier = tableModifier, state = listState) {
                        itemsIndexed(state.neighbours, key = { _, r -> r.key }) { index, row ->
                            MatchRow(index = index, state = state) {
                                NeighbourRowItem(row, state) { selectedKey = row.key }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        if (twoPane) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                table(Modifier.weight(0.55f).fillMaxSize())
                Column(
                    Modifier
                        .weight(0.45f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    if (live == null) {
                        Text(
                            "Select a host to see its detail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SectionCard(
                            title = live.display,
                            modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                        ) {
                            neighbourDetailLines(live, now).forEach { (k, v) ->
                                KeyValueRow(label = k, value = v)
                            }
                        }
                    }
                }
            }
        } else {
            table(Modifier.weight(1f).fillMaxWidth())
        }
    }

    if (!twoPane && live != null) {
        Box(Modifier.testTag(TAG_DETAIL_SHEET)) {
            DetailSheet(
                title = live.display,
                lines = neighbourDetailLines(live, now),
                onCopy = { actions.copyText("host", neighbourTabText(live)) },
                onDismiss = { selectedKey = null },
            )
        }
    }
}

@Composable
private fun NeighbourRowItem(row: NeighbourRow, state: UiState, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onClick)
            .testTag("neighbour:${row.address}")
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HighlightedText(
                text = row.address,
                query = state.highlightQuery,
                isRegex = state.highlightIsRegex,
                style = MaterialTheme.typography.bodyMedium
                    .copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            if (row.isGateway) {
                TagBadge("gateway", DuckColors.Rx)
                Spacer(Modifier.width(6.dp))
            }
            if (row.isSelf) {
                TagBadge("this device", DuckColors.Info)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = if (row.rttMillis >= 0) Units.millis(row.rttMillis) else "—",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.testTag("neighbour:rtt:${row.address}"),
            )
        }
        val subtitle = buildList {
            if (row.hostname.isNotEmpty()) add(row.hostname)
            if (row.services.isNotEmpty()) add(row.services.joinToString(" "))
            add("via ${row.source.label}")
        }.joinToString(" · ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun neighbourDetailLines(row: NeighbourRow, now: Long): List<Pair<String, String>> {
    val lines = mutableListOf(
        "Address" to row.address,
        "Name" to row.hostname.ifEmpty { "-" },
        "Found by" to row.source.label,
        "Round trip" to if (row.rttMillis >= 0) {
            Units.millis(row.rttMillis)
        } else {
            "no echo reply — found by mDNS alone"
        },
        "Services" to row.services.joinToString(", ").ifEmpty { "-" },
    )
    if (row.isGateway) lines += "Role" to "this link's gateway"
    if (row.isSelf) lines += "Role" to "this device"
    lines += "Last seen" to formatClock(row.lastSeen, now)
    return lines
}

/** Tab-separated, so a copied row pastes into a spreadsheet as the TUI's does. */
private fun neighbourTabText(row: NeighbourRow): String = listOf(
    row.address,
    row.hostname,
    row.source.label,
    if (row.rttMillis >= 0) row.rttMillis.toString() else "",
    row.services.joinToString(" "),
).joinToString("\t")
