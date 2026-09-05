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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.ServiceRow
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
import com.vlad.ducknetview.ui.theme.DuckColors

private val SERVICE_SORT_COLUMNS = listOf("proto", "port", "service", "exposure", "seen")

@Composable
fun ServicesScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    // Only the listener's identity is held: the live row is looked up from the
    // current state every recomposition so the pane follows each new scan.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val s = state.settings
    val snap = state.snapshot
    val now = snap.atMillis
    val live = selectedKey?.let { key -> state.services.firstOrNull { it.key == key } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = if (snap.serviceScanAt > 0L) {
                    "Last scan ${formatClock(snap.serviceScanAt, now)}"
                } else {
                    "Last scan never"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("services:lastscan"),
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { actions.scanServices() },
                enabled = !snap.serviceScanRunning,
                modifier = Modifier.testTag("services:scan"),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Scan now")
            }
            IconButton(
                onClick = { actions.exportCurrentTable() },
                modifier = Modifier.testTag("services:export"),
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export table")
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            Text("Full port range", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = s.scanFullRange,
                onCheckedChange = { actions.setScanFullRange(it) },
                modifier = Modifier.testTag("services:fullrange"),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (s.scanFullRange) "1-65535, slower" else "common ports only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (snap.serviceScanRunning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("services:progress"),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        ) {
            TextButton(
                onClick = { actions.saveBaseline() },
                modifier = Modifier.testTag("services:save-baseline"),
            ) { Text("Save baseline") }
            TextButton(
                onClick = { actions.clearBaseline() },
                enabled = s.baselineAt > 0L,
                modifier = Modifier.testTag("services:clear-baseline"),
            ) { Text("Clear baseline") }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (s.baselineAt > 0L) {
                    "baseline ${formatClock(s.baselineAt, now)} · ${snap.security.offBaseline} off"
                } else {
                    "no baseline"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (snap.security.offBaseline > 0) DuckColors.Alert else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("services:baseline-state"),
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
            columns = SERVICE_SORT_COLUMNS,
            active = s.servicesSortCol,
            desc = s.servicesSortDesc,
            onSort = { actions.setSort("services", it) },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            when {
                snap.serviceScanAt == 0L && state.services.isEmpty() -> EmptyState(
                    title = EMPTY_SERVICES_NEVER,
                    message = "Android forbids enumerating other apps' listening sockets, so " +
                        "ducknetview scans this device itself — it connects to its own ports " +
                        "from the inside. Nothing is sent to any other host.",
                    actionLabel = "Scan now",
                    onAction = { actions.scanServices() },
                    modifier = tableModifier.testTag(emptyTag(EMPTY_SERVICES_NEVER)),
                )

                state.services.isEmpty() -> EmptyState(
                    title = EMPTY_SERVICES,
                    message = "The last scan found no listener matching the current search.",
                    actionLabel = "Scan again",
                    onAction = { actions.scanServices() },
                    modifier = tableModifier.testTag(emptyTag(EMPTY_SERVICES)),
                )

                else -> {
                    val listState = rememberLazyListState()
                    ScrollToMatch(state.matchCursor, state.services.size, listState)
                    LazyColumn(modifier = tableModifier, state = listState) {
                        itemsIndexed(state.services, key = { _, row -> row.key }) { index, row ->
                            MatchRow(index = index, state = state) {
                                ServiceRowItem(row, state, actions) { selectedKey = row.key }
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
                            "Select a listener to see its detail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SectionCard(
                            title = "${live.proto} ${live.bindAddr}:${live.port}",
                            modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                        ) {
                            serviceDetailLines(live, now).forEach { (k, v) ->
                                KeyValueRow(label = k, value = v)
                            }
                            Row { ServiceSheetActions(live, actions) }
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
                title = "${live.proto} ${live.bindAddr}:${live.port}",
                lines = serviceDetailLines(live, now),
                onCopy = { actions.copyText("service", serviceTabText(live, now)) },
                onDismiss = { selectedKey = null },
                actions = { ServiceSheetActions(live, actions) },
            )
        }
    }
}

@Composable
private fun ServiceSheetActions(row: ServiceRow, actions: UiActions) {
    if (row.offBaseline) {
        TextButton(
            onClick = { actions.acceptIntoBaseline(row) },
            modifier = Modifier.testTag("sheet:accept"),
        ) { Text("Accept into baseline") }
    }
}

@Composable
private fun ServiceRowItem(
    row: ServiceRow,
    state: UiState,
    actions: UiActions,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { actions.copyText("service", serviceTabText(row, state.snapshot.atMillis)) },
            )
            .testTag("service:${row.proto}:${row.port}")
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.proto.toString(), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                HighlightedText(
                    text = "${row.bindAddr}:${row.port}",
                    query = state.highlightQuery,
                    isRegex = state.highlightIsRegex,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DuckColors.forExposure(row.exposure),
                )
                if (row.service.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(row.service, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "first ${formatClock(row.firstSeen, state.snapshot.atMillis)} · " +
                    "last ${formatClock(row.lastSeen, state.snapshot.atMillis)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.isNew) {
            Text(
                "NEW",
                style = MaterialTheme.typography.labelSmall,
                color = DuckColors.Info,
                modifier = Modifier.testTag("service:new:${row.proto}:${row.port}"),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (row.offBaseline) {
            Text(
                "●",
                style = MaterialTheme.typography.labelSmall,
                color = DuckColors.Alert,
                modifier = Modifier.testTag("service:offbaseline:${row.proto}:${row.port}"),
            )
            IconButton(
                onClick = { actions.acceptIntoBaseline(row) },
                modifier = Modifier.testTag("accept:${row.proto}:${row.port}"),
            ) {
                Icon(Icons.Default.Check, contentDescription = "Accept into baseline")
            }
        }
    }
}

private fun serviceDetailLines(row: ServiceRow, now: Long): List<Pair<String, String>> = listOf(
    "Proto" to row.proto.toString(),
    "Bind address" to row.bindAddr,
    "Port" to row.port.toString(),
    "Service" to row.service.ifEmpty { "-" },
    "Exposure" to row.exposure.name.lowercase(),
    "First seen" to formatClock(row.firstSeen, now),
    "Last seen" to formatClock(row.lastSeen, now),
    "Age" to Units.age(if (row.firstSeen > 0L) now - row.firstSeen else -1L),
    "New" to if (row.isNew) "yes" else "no",
    "Off baseline" to if (row.offBaseline) "yes" else "no",
    "Owner" to if (row.uid >= 0) appDisplayName(row.appLabel, "", row.uid) else "unknown",
)
