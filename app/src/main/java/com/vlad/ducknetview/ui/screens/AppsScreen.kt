@file:OptIn(ExperimentalFoundationApi::class)

package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.DetailSheet
import com.vlad.ducknetview.ui.components.DuckFilterChip
import com.vlad.ducknetview.ui.components.EmptyState
import com.vlad.ducknetview.ui.components.HighlightedText
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.SortChips
import com.vlad.ducknetview.ui.components.TableSearchBar
import com.vlad.ducknetview.ui.theme.DuckColors

internal val APP_SORT_COLUMNS = listOf("name", "conns", "rx", "tx", "today")

@Composable
fun AppsScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    // Blocked-only is a pure view filter with no ViewModel state behind it, so it
    // is applied here to the already-filtered list the ViewModel supplied.
    var blockedOnly by remember { mutableStateOf(false) }
    var usageCardDismissed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AppRow?>(null) }

    // Rows keep their position in state.apps: matchedRows indexes that list, not
    // the locally blocked-only view.
    val rows = state.apps.withIndex().filter { !blockedOnly || it.value.blocked }
    val live = selected?.let { sel -> state.apps.firstOrNull { it.uid == sel.uid } ?: sel }
    val s = state.settings

    val columns = APP_SORT_COLUMNS
        .filter { it != "conns" || state.caps.hasConnections }
        .filter { it != "today" || state.usageAccessGranted }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                countLabel(rows.size, "app"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { actions.exportCurrentTable() },
                modifier = Modifier.testTag("apps:export"),
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export table")
            }
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

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            DuckFilterChip(
                "my apps",
                state.filters.userAppsOnly,
                { actions.toggleUserAppsOnly() },
                Modifier.testTag("chip:mine"),
            )
            DuckFilterChip(
                "blocked",
                blockedOnly,
                { blockedOnly = !blockedOnly },
                Modifier.testTag("chip:blocked"),
            )
            DuckFilterChip(
                if (s.throughputMode == ThroughputMode.RATE) "rate" else "total",
                s.throughputMode == ThroughputMode.TOTAL,
                { actions.toggleThroughputMode() },
                Modifier.testTag("chip:mode"),
            )
        }

        SortChips(
            columns = columns,
            active = s.appsSortCol,
            desc = s.appsSortDesc,
            onSort = { actions.setSort("apps", it) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (!state.usageAccessGranted && !usageCardDismissed) {
            SectionCard(
                title = "Usage history locked",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("apps:usage-card"),
                trailing = {
                    TextButton(
                        onClick = { usageCardDismissed = true },
                        modifier = Modifier.testTag("apps:usage-dismiss"),
                    ) { Text("Dismiss") }
                },
            ) {
                Text(
                    "Per-app daily totals come from the system's network usage database. " +
                        "Reading it needs the Usage Access permission, which only you can " +
                        "grant in Settings — it is not a runtime prompt.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { actions.requestUsageAccess() },
                    modifier = Modifier.testTag("apps:usage-grant"),
                ) { Text("Open Usage Access") }
            }
        }

        HorizontalDivider()

        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            if (rows.isEmpty()) {
                EmptyState(
                    title = EMPTY_APPS,
                    message = if (blockedOnly) {
                        "No app is blocked right now."
                    } else {
                        "Nothing matches the current search and filters."
                    },
                    modifier = tableModifier.testTag(emptyTag(EMPTY_APPS)),
                )
            } else {
                val listState = rememberLazyListState()
                ScrollToMatch(
                    target = rows.indexOfFirst { it.index == state.matchCursor },
                    itemCount = rows.size,
                    listState = listState,
                )
                LazyColumn(modifier = tableModifier, state = listState) {
                    items(rows, key = { it.value.uid }) { (index, app) ->
                        MatchRow(index = index, state = state) {
                            AppRowItem(app, state, actions) { selected = app }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
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
                            "Select an app to see its detail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SectionCard(
                            title = appDisplayName(live.label, live.packageName, live.uid),
                            modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                        ) {
                            appDetailLines(live, state).forEach { (k, v) ->
                                KeyValueRow(label = k, value = v)
                            }
                            Row { AppSheetActions(live, actions) }
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
                title = appDisplayName(live.label, live.packageName, live.uid),
                lines = appDetailLines(live, state),
                onCopy = { actions.copyText("app", appTabText(live, s.rateUnit)) },
                onDismiss = { selected = null },
                actions = { AppSheetActions(live, actions) },
            )
        }
    }
}

@Composable
private fun AppSheetActions(app: AppRow, actions: UiActions) {
    TextButton(
        onClick = { actions.blockApp(app.uid, !app.blocked) },
        modifier = Modifier.testTag("sheet:block"),
    ) { Text(if (app.blocked) "Unblock" else "Block") }
    TextButton(
        onClick = { actions.excludeApp(app.uid, !app.excludedFromVpn) },
        modifier = Modifier.testTag("sheet:exclude"),
    ) { Text(if (app.excludedFromVpn) "Include in VPN" else "Exclude from VPN") }
    TextButton(
        onClick = { actions.openAppInfo(app.packageName) },
        modifier = Modifier.testTag("sheet:appinfo"),
    ) { Text("App info") }
}

@Composable
private fun AppRowItem(
    app: AppRow,
    state: UiState,
    actions: UiActions,
    onSelect: () -> Unit,
) {
    val s = state.settings
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { actions.copyText("app", appTabText(app, s.rateUnit)) },
            )
            .testTag("app:${app.uid}")
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HighlightedText(
                text = appDisplayName(app.label, app.packageName, app.uid),
                query = state.highlightQuery,
                isRegex = state.highlightIsRegex,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (app.blocked) {
                Text(
                    "blocked",
                    style = MaterialTheme.typography.labelSmall,
                    color = DuckColors.Alert,
                    modifier = Modifier.testTag("app:blocked:${app.uid}"),
                )
                Spacer(Modifier.width(8.dp))
            }
            // Connection counts only exist while the capture engine is running.
            if (state.caps.hasConnections) {
                Text(
                    countLabel(app.connCount, "conn"),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("app:conns:${app.uid}"),
                )
            }
        }
        Text(
            app.packageName.ifEmpty { "uid ${app.uid}" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                rxTxText(app.rxBps, app.txBps, app.sessionRx, app.sessionTx, s.throughputMode, s.rateUnit),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            if (state.usageAccessGranted) {
                Text(
                    "today ${Units.bytes(app.todayRx + app.todayTx)}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("app:today:${app.uid}"),
                )
            }
        }
    }
}

// CPU and memory columns from the TUI are deliberately absent: Android gives an app
// no view of other processes' CPU or RSS, so the column would always be blank.
private fun appDetailLines(app: AppRow, state: UiState): List<Pair<String, String>> {
    val s = state.settings
    val lines = mutableListOf(
        "UID" to app.uid.toString(),
        "Package" to app.packageName.ifEmpty { "-" },
        "System app" to if (app.isSystem) "yes" else "no",
    )
    if (state.caps.hasConnections) {
        lines += "Connections" to app.connCount.toString()
        lines += "Remote hosts" to app.remoteHosts.toString()
    }
    lines += "Rate" to "↓ ${Units.rate(app.rxBps, s.rateUnit)}  ↑ ${Units.rate(app.txBps, s.rateUnit)}"
    lines += "Session" to "↓ ${Units.bytes(app.sessionRx)}  ↑ ${Units.bytes(app.sessionTx)}"
    if (state.usageAccessGranted) {
        lines += "Today" to Units.bytes(app.todayRx + app.todayTx)
    }
    lines += "Blocked" to if (app.blocked) "yes" else "no"
    lines += "Excluded from VPN" to if (app.excludedFromVpn) "yes" else "no"
    return lines
}
