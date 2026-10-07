@file:OptIn(ExperimentalFoundationApi::class)

package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.IpVersionFilter
import com.vlad.ducknetview.domain.model.ProtoFilter
import com.vlad.ducknetview.domain.model.StateFilter
import com.vlad.ducknetview.domain.model.Transport
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.ui.HostGroup
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

internal val CONN_SORT_COLUMNS = listOf("app", "proto", "remote", "state", "rx", "tx", "rtt", "age")

@Composable
fun ConnectionsScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    if (!state.caps.hasConnections) {
        EmptyState(
            title = EMPTY_CONNS_NO_CAPTURE,
            message = "A live connection table needs the local VPN capture engine. " +
                "Android does not let an app read other apps' sockets, so ducknetview " +
                "routes traffic through its own on-device tunnel to see them. Nothing " +
                "leaves the phone and packet contents are never stored.",
            actionLabel = "Enable capture",
            onAction = { actions.startVpn() },
            modifier = modifier
                .fillMaxSize()
                .testTag(emptyTag(EMPTY_CONNS_NO_CAPTURE)),
        )
        return
    }

    var selectedConn by remember { mutableStateOf<ConnRow?>(null) }
    var selectedGroup by remember { mutableStateOf<HostGroup?>(null) }
    var selectedClosed by remember { mutableStateOf<ClosedConn?>(null) }

    val now = state.snapshot.atMillis
    val settings = state.settings
    // The sheet re-reads the live row each tick so it keeps updating, as the TUI
    // overlay does. It must not fall back to the copy taken at selection time:
    // that pane went on reporting "established" for a connection that had
    // already retired, with an age that kept climbing and byte totals frozen at
    // whatever the last tick saw. When the flow is gone, show the closed record
    // — which carries its true final state and lifetime — and show nothing at
    // all when a chip or the search has merely hidden the row.
    val selected = selectedConn
    val liveConn = selected?.let { sel -> state.conns.firstOrNull { it.key == sel.key } }
    val retiredConn = if (selected != null && liveConn == null) {
        state.snapshot.closedConns.firstOrNull { it.row.key == selected.key }
    } else {
        null
    }
    val liveGroup = selectedGroup?.let { sel -> state.groups.firstOrNull { it.host == sel.host } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = "Δ +${state.snapshot.newConnCount}/-${state.snapshot.closedConnCount}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("conns:delta"),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = when {
                    settings.groupByHost -> countLabel(state.groups.size, "host")
                    settings.showClosed -> countLabel(state.closed.size, "closed", "closed")
                    else -> countLabel(state.conns.size, "row")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { actions.exportCurrentTable() },
                modifier = Modifier.testTag("conns:export"),
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

        ConnFilterChips(state, actions)

        SortChips(
            columns = if (state.caps.hasRtt) CONN_SORT_COLUMNS else CONN_SORT_COLUMNS - "rtt",
            active = settings.connsSortCol,
            desc = settings.connsSortDesc,
            onSort = { actions.setSort("conns", it) },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            when {
                settings.groupByHost -> GroupTable(state, tableModifier) {
                    selectedConn = null
                    selectedClosed = null
                    selectedGroup = it
                }

                settings.showClosed -> ClosedTable(state, actions, tableModifier) {
                    selectedConn = null
                    selectedGroup = null
                    selectedClosed = it
                }

                else -> FlatTable(state, actions, tableModifier) {
                    selectedGroup = null
                    selectedClosed = null
                    selectedConn = it
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
                    val closedPane = selectedClosed ?: retiredConn
                    val lines = when {
                        liveConn != null -> connDetailLines(liveConn, state)
                        liveGroup != null -> groupDetailLines(liveGroup, state)
                        closedPane != null -> closedDetailLines(closedPane, state)
                        else -> emptyList()
                    }
                    if (lines.isEmpty()) {
                        Text(
                            "Select a row to see its detail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SectionCard(
                            title = "Detail",
                            modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                        ) {
                            lines.forEach { (k, v) -> KeyValueRow(label = k, value = v) }
                            Row {
                                if (liveConn != null) {
                                    ConnSheetActions(liveConn, actions)
                                } else if (liveGroup != null) {
                                    TextButton(
                                        onClick = { actions.toggleWatchlist(liveGroup.host) },
                                        modifier = Modifier.testTag("sheet:watchlist"),
                                    ) { Text("Watchlist") }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            table(Modifier.weight(1f).fillMaxWidth())
        }
    }

    if (!twoPane) {
        if (liveConn != null) {
            Box(Modifier.testTag(TAG_DETAIL_SHEET)) {
                DetailSheet(
                    title = liveConn.remoteDisplay(settings.revDns),
                    lines = connDetailLines(liveConn, state),
                    onCopy = {
                        actions.copyText("connection", connTabText(liveConn, settings.revDns, settings.rateUnit, now))
                    },
                    onDismiss = { selectedConn = null },
                    actions = { ConnSheetActions(liveConn, actions) },
                )
            }
        }
        if (liveGroup != null) {
            Box(Modifier.testTag(TAG_DETAIL_SHEET)) {
                DetailSheet(
                    title = liveGroup.display,
                    lines = groupDetailLines(liveGroup, state),
                    onCopy = { actions.copyText("host", groupTabText(liveGroup, state)) },
                    onDismiss = { selectedGroup = null },
                    actions = {
                        TextButton(
                            onClick = { actions.toggleWatchlist(liveGroup.host) },
                            modifier = Modifier.testTag("sheet:watchlist"),
                        ) { Text("Watchlist") }
                    },
                )
            }
        }
        val closed = selectedClosed ?: retiredConn
        if (closed != null) {
            Box(Modifier.testTag(TAG_DETAIL_SHEET)) {
                DetailSheet(
                    title = closed.row.remoteDisplay(settings.revDns),
                    lines = closedDetailLines(closed, state),
                    onCopy = { actions.copyText("connection", closedTabText(closed, settings.revDns, now)) },
                    // This sheet can be showing either a row picked from the
                    // closed table or a live row that retired under the reader,
                    // so dismissing it has to drop both selections or it
                    // reopens on the next tick.
                    onDismiss = { selectedClosed = null; selectedConn = null },
                    // No block / app-info action here: the socket is gone and its uid may
                    // already have been recycled by the platform, so acting on it could hit
                    // an unrelated app.
                    actions = {},
                )
            }
        }
    }
}

@Composable
private fun ConnSheetActions(row: ConnRow, actions: UiActions) {
    TextButton(
        onClick = { actions.toggleWatchlist(row.remoteAddr) },
        modifier = Modifier.testTag("sheet:watchlist"),
    ) { Text("Watchlist") }
    TextButton(
        onClick = { actions.blockApp(row.uid, !row.blocked) },
        modifier = Modifier.testTag("sheet:block"),
    ) { Text(if (row.blocked) "Unblock app" else "Block app") }
    TextButton(
        onClick = { actions.openAppInfo(row.packageName) },
        modifier = Modifier.testTag("sheet:appinfo"),
    ) { Text("App info") }
}

@Composable
private fun ConnFilterChips(state: UiState, actions: UiActions) {
    val f = state.filters
    val s = state.settings
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        DuckFilterChip("all", f.proto == ProtoFilter.ALL, { actions.setProtoFilter(ProtoFilter.ALL) }, Modifier.testTag("chip:proto-all"))
        DuckFilterChip("tcp", f.proto == ProtoFilter.TCP, { actions.setProtoFilter(ProtoFilter.TCP) }, Modifier.testTag("chip:proto-tcp"))
        DuckFilterChip("udp", f.proto == ProtoFilter.UDP, { actions.setProtoFilter(ProtoFilter.UDP) }, Modifier.testTag("chip:proto-udp"))

        DuckFilterChip("v4", f.ipVersion == IpVersionFilter.V4, {
            actions.setIpVersionFilter(if (f.ipVersion == IpVersionFilter.V4) IpVersionFilter.ALL else IpVersionFilter.V4)
        }, Modifier.testTag("chip:ipv4"))
        DuckFilterChip("v6", f.ipVersion == IpVersionFilter.V6, {
            actions.setIpVersionFilter(if (f.ipVersion == IpVersionFilter.V6) IpVersionFilter.ALL else IpVersionFilter.V6)
        }, Modifier.testTag("chip:ipv6"))

        DuckFilterChip("established", f.state == StateFilter.ESTABLISHED, {
            actions.setStateFilter(if (f.state == StateFilter.ESTABLISHED) StateFilter.ALL else StateFilter.ESTABLISHED)
        }, Modifier.testTag("chip:state-established"))
        DuckFilterChip("active", f.state == StateFilter.ACTIVE, {
            actions.setStateFilter(if (f.state == StateFilter.ACTIVE) StateFilter.ALL else StateFilter.ACTIVE)
        }, Modifier.testTag("chip:state-active"))

        DuckFilterChip("public only", f.publicOnly, { actions.togglePublicOnly() }, Modifier.testTag("chip:public"))
        DuckFilterChip("my apps", f.userAppsOnly, { actions.toggleUserAppsOnly() }, Modifier.testTag("chip:mine"))

        DuckFilterChip("all nets", f.network == null, { actions.setNetworkFilter(null) }, Modifier.testTag("chip:net-all"))
        // A flow is labelled with the link it actually left on, which is never
        // a VPN interface: while capture is running the default route is this
        // app's own TUN, and naming every flow "tun0" would say nothing. So a
        // chip for a VPN link could only ever empty the table, which is what
        // the "tun0" chip did.
        state.snapshot.networks
            .filter { !it.isNoise && it.transport != Transport.VPN }
            .forEach { net ->
                DuckFilterChip(
                    net.ifaceName,
                    f.network == net.ifaceName,
                    { actions.setNetworkFilter(if (f.network == net.ifaceName) null else net.ifaceName) },
                    Modifier.testTag("chip:net-${net.ifaceName}"),
                )
            }

        if (f.uid != null) {
            DuckFilterChip(
                "uid ${f.uid} ×",
                true,
                { actions.setUidFilter(null) },
                Modifier.testTag("chip:uid"),
            )
        }

        DuckFilterChip("rDNS", s.revDns, { actions.toggleRevDns() }, Modifier.testTag("chip:revdns"))
        DuckFilterChip("group", s.groupByHost, { actions.toggleGrouping() }, Modifier.testTag("chip:group"))
        DuckFilterChip("closed", s.showClosed, { actions.toggleShowClosed() }, Modifier.testTag("chip:closed"))
        DuckFilterChip(
            if (s.throughputMode == ThroughputMode.RATE) "rate" else "total",
            s.throughputMode == ThroughputMode.TOTAL,
            { actions.toggleThroughputMode() },
            Modifier.testTag("chip:mode"),
        )
    }
}

@Composable
private fun FlatTable(
    state: UiState,
    actions: UiActions,
    modifier: Modifier,
    onSelect: (ConnRow) -> Unit,
) {
    if (state.conns.isEmpty()) {
        EmptyState(
            title = EMPTY_CONNS,
            message = "Nothing matches the current search and filters. " +
                "Short-lived sockets can also close between two polls.",
            modifier = modifier.testTag(emptyTag(EMPTY_CONNS)),
        )
        return
    }
    val s = state.settings
    val now = state.snapshot.atMillis
    val listState = rememberLazyListState()
    ScrollToMatch(state.matchCursor, state.conns.size, listState)
    LazyColumn(modifier = modifier, state = listState) {
        itemsIndexed(state.conns, key = { _, row -> row.key }) { index, row ->
            MatchRow(
                index = index,
                state = state,
                watchlisted = row.watchlisted,
                isNew = row.isNew,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelect(row) },
                            onLongClick = {
                                actions.copyText("connection", connTabText(row, s.revDns, s.rateUnit, now))
                            },
                        )
                        .testTag("conn:${row.key}")
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            appDisplayName(row.appLabel, row.packageName, row.uid),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(row.proto.toString(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Text(row.state.toString(), style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Text(Units.age(row.ageMillis(now)), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.local,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text("  →  ", style = MaterialTheme.typography.bodySmall)
                        HighlightedText(
                            text = row.remoteDisplay(s.revDns),
                            query = state.highlightQuery,
                            isRegex = state.highlightIsRegex,
                            style = MaterialTheme.typography.bodySmall,
                            color = DuckColors.forScope(row.scope),
                            modifier = Modifier.weight(1f),
                        )
                        if (row.service.isNotEmpty()) {
                            Text(row.service, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rxTxText(row.rxBps, row.txBps, row.rxBytes, row.txBytes, s.throughputMode, s.rateUnit),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        // The RTT column has no source outside the capture engine, so it is
                        // dropped rather than shown blank (the TUI does the same).
                        if (state.caps.hasRtt && row.rttMillis >= 0) {
                            Text(
                                Units.millis(row.rttMillis),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag("conn:rtt:${row.key}"),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (row.network.isNotEmpty()) {
                            Text(row.network, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun GroupTable(
    state: UiState,
    modifier: Modifier,
    onSelect: (HostGroup) -> Unit,
) {
    if (state.groups.isEmpty()) {
        EmptyState(
            title = EMPTY_GROUPS,
            message = "No remote hosts to group. Grouping folds every connection to the " +
                "same remote address into one row.",
            modifier = modifier.testTag(emptyTag(EMPTY_GROUPS)),
        )
        return
    }
    val s = state.settings
    LazyColumn(modifier = modifier) {
        items(state.groups, key = { it.host }) { g ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(rowTint(g.watchlisted, false))
                    .combinedClickable(onClick = { onSelect(g) }, onLongClick = { onSelect(g) })
                    .testTag("group:${g.host}")
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        g.display,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DuckColors.forScope(g.scope),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(countLabel(g.connCount, "conn"), style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    rxTxText(g.rxBps, g.txBps, g.rxTotal, g.txTotal, s.throughputMode, s.rateUnit),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    g.states.entries.joinToString("  ") { "${it.key} ${it.value}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (g.apps.isNotEmpty()) {
                    Text(
                        g.apps.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ClosedTable(
    state: UiState,
    actions: UiActions,
    modifier: Modifier,
    onSelect: (ClosedConn) -> Unit,
) {
    if (state.closed.isEmpty()) {
        EmptyState(
            title = EMPTY_CLOSED,
            // The search and the chips now reach this table, so an empty one
            // has two causes and saying the wrong one sends the reader looking
            // in the wrong place.
            message = if (state.snapshot.closedConns.isEmpty()) {
                "No connection has closed yet. Closed connections are remembered " +
                    "for this session only, newest first."
            } else {
                "No closed connection matches the current search and filters."
            },
            modifier = modifier.testTag(emptyTag(EMPTY_CLOSED)),
        )
        return
    }
    val s = state.settings
    val now = state.snapshot.atMillis
    LazyColumn(modifier = modifier) {
        items(state.closed, key = { "${it.row.key}@${it.closedAt}" }) { c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onSelect(c) },
                        onLongClick = { actions.copyText("connection", closedTabText(c, s.revDns, now)) },
                    )
                    .testTag("conn:${c.row.key}")
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        appDisplayName(c.row.appLabel, c.row.packageName, c.row.uid),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(c.row.proto.toString(), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Text("lifetime ${Units.age(c.lifetimeMillis)}", style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "${c.row.local}  →  ${c.row.remoteDisplay(s.revDns)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DuckColors.forScope(c.row.scope),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "final ↓ ${Units.bytes(c.finalRx)}  ↑ ${Units.bytes(c.finalTx)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text("closed ${formatClock(c.closedAt, now)}", style = MaterialTheme.typography.labelSmall)
                }
            }
            HorizontalDivider()
        }
    }
}

private fun connDetailLines(row: ConnRow, state: UiState): List<Pair<String, String>> {
    val s = state.settings
    val now = state.snapshot.atMillis
    val lines = mutableListOf(
        "Proto" to row.proto.toString(),
        "Local" to row.local,
        "Remote" to row.remote,
        "Resolved host" to (row.resolvedHost ?: "-"),
        "Service" to row.service.ifEmpty { "-" },
        "State" to row.state.toString(),
        "Scope" to row.scope.toString(),
        "Watchlist" to if (row.watchlisted) "yes" else "no",
        "Age" to Units.age(row.ageMillis(now)),
        "Rate" to "↓ ${Units.rate(row.rxBps, s.rateUnit)}  ↑ ${Units.rate(row.txBps, s.rateUnit)}",
        "Total" to "↓ ${Units.bytes(row.rxBytes)}  ↑ ${Units.bytes(row.txBytes)}",
    )
    if (state.caps.hasRtt) lines += "RTT" to Units.millis(row.rttMillis)
    lines += "Network" to row.network.ifEmpty { "-" }
    lines += "App" to appDisplayName(row.appLabel, row.packageName, row.uid)
    lines += "Package" to row.packageName.ifEmpty { "-" }
    lines += "UID" to row.uid.toString()
    lines += "Blocked" to if (row.blocked) "yes" else "no"
    return lines
}

private fun groupDetailLines(g: HostGroup, state: UiState): List<Pair<String, String>> {
    val s = state.settings
    return listOf(
        "Host" to g.host,
        "Display" to g.display,
        "Connections" to g.connCount.toString(),
        "Scope" to g.scope.toString(),
        "Watchlist" to if (g.watchlisted) "yes" else "no",
        "Rate" to "↓ ${Units.rate(g.rxBps, s.rateUnit)}  ↑ ${Units.rate(g.txBps, s.rateUnit)}",
        "Total" to "↓ ${Units.bytes(g.rxTotal)}  ↑ ${Units.bytes(g.txTotal)}",
        "States" to g.states.entries.joinToString("  ") { "${it.key} ${it.value}" },
        "Apps" to g.apps.joinToString(", ").ifEmpty { "-" },
    )
}

private fun groupTabText(g: HostGroup, state: UiState): String {
    val s = state.settings
    return listOf(
        g.host,
        g.display,
        g.connCount.toString(),
        Units.rate(g.rxBps, s.rateUnit),
        Units.rate(g.txBps, s.rateUnit),
        Units.bytes(g.rxTotal),
        Units.bytes(g.txTotal),
        g.states.entries.joinToString(" ") { "${it.key} ${it.value}" },
        g.apps.joinToString(" "),
    ).joinToString("\t")
}

private fun closedDetailLines(c: ClosedConn, state: UiState): List<Pair<String, String>> = listOf(
    "Proto" to c.row.proto.toString(),
    "Local" to c.row.local,
    "Remote" to c.row.remote,
    "Resolved host" to (c.row.resolvedHost ?: "-"),
    "Service" to c.row.service.ifEmpty { "-" },
    "Scope" to c.row.scope.toString(),
    "Final RX" to Units.bytes(c.finalRx),
    "Final TX" to Units.bytes(c.finalTx),
    "Lifetime" to Units.age(c.lifetimeMillis),
    "Closed at" to formatClock(c.closedAt, state.snapshot.atMillis),
    "App" to appDisplayName(c.row.appLabel, c.row.packageName, c.row.uid),
    "UID" to "${c.row.uid} (may already be recycled)",
)
