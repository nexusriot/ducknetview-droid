@file:OptIn(ExperimentalFoundationApi::class)

package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.domain.sort.Sorters
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

/**
 * The names this device asked for, and which app asked.
 *
 * Every row here was already passing through the capture engine and being
 * thrown away: DNS answers were parsed to label one column of the connection
 * table, and the question — the part a person recognises — was never kept.
 *
 * Two sources feed it, and the screen says which. DNS answers are read off the
 * TUN; where Private DNS is configured there are none to read, and the only
 * name left in the clear is the SNI in a TLS ClientHello. A device with an
 * encrypted resolver therefore shows SNI rows and no DNS ones, which is a fact
 * about the network rather than a gap in the table.
 */
@Composable
fun DomainsScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    // Only the row's identity is held; the live row is re-resolved from each
    // new state so an open pane keeps counting up instead of going stale.
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val s = state.settings
    val now = state.snapshot.atMillis
    val live = selectedKey?.let { key -> state.domains.firstOrNull { it.key == key } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = summary(state),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("domains:summary"),
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { actions.clearDomains() },
                modifier = Modifier.testTag("domains:clear"),
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear name history")
            }
            IconButton(
                onClick = { actions.exportCurrentTable() },
                modifier = Modifier.testTag("domains:export"),
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export table")
            }
        }

        val privateDns = state.privateDns
        if (privateDns != null) {
            Text(
                text = "Private DNS is on ($privateDns), so lookups are encrypted and none " +
                    "cross the capture engine. Names below come from TLS SNI instead.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("domains:privatedns"),
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
            columns = Sorters.DOMAIN_COLUMNS,
            active = s.domainsSortCol,
            desc = s.domainsSortDesc,
            onSort = { actions.setSort("domains", it) },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()

        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            when {
                state.domains.isEmpty() && state.snapshot.frozen -> EmptyState(
                    title = EMPTY_DOMAINS,
                    message = frozenTableMessage(state.snapshot.frozenLabel, "name history"),
                    modifier = tableModifier.testTag(emptyTag(EMPTY_DOMAINS)),
                )

                state.domains.isEmpty() && !state.caps.hasConnections -> EmptyState(
                    title = EMPTY_DOMAINS_NO_CAPTURE,
                    message = "Names are read off the traffic the capture engine relays. " +
                        "Android gives an app no other way to see what this device looks " +
                        "up, so this screen stays empty until capture is on.",
                    modifier = tableModifier.testTag(emptyTag(EMPTY_DOMAINS_NO_CAPTURE)),
                )

                state.domains.isEmpty() -> EmptyState(
                    title = EMPTY_DOMAINS,
                    message = if (privateDns != null) {
                        "Lookups on this network are encrypted, so nothing is read from " +
                            "DNS. A name appears here the first time an app opens a TLS " +
                            "connection that carries one."
                    } else {
                        "A name appears here the first time an app looks it up, or opens a " +
                            "TLS connection that carries one."
                    },
                    modifier = tableModifier.testTag(emptyTag(EMPTY_DOMAINS)),
                )

                else -> {
                    val listState = rememberLazyListState()
                    ScrollToMatch(state.matchCursor, state.domains.size, listState)
                    LazyColumn(modifier = tableModifier, state = listState) {
                        itemsIndexed(state.domains, key = { _, row -> row.key }) { index, row ->
                            MatchRow(index = index, state = state, watchlisted = row.watchlisted) {
                                DomainRowItem(row, state, actions) { selectedKey = row.key }
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
                            "Select a name to see its detail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SectionCard(
                            title = live.name,
                            modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                        ) {
                            domainDetailLines(live, now).forEach { (k, v) ->
                                KeyValueRow(label = k, value = v)
                            }
                            Row { DomainSheetActions(live, actions) }
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
                title = live.name,
                lines = domainDetailLines(live, now),
                onCopy = { actions.copyText("domain", domainTabText(live, now)) },
                onDismiss = { selectedKey = null },
                actions = { DomainSheetActions(live, actions) },
            )
        }
    }
}

private fun summary(state: UiState): String {
    val total = state.domains.size
    val sni = state.domains.count { it.source == NameSource.SNI }
    val names = "$total ${if (total == 1) "name" else "names"}"
    return if (sni > 0) "$names · $sni from SNI" else names
}

@Composable
private fun DomainSheetActions(row: DomainRow, actions: UiActions) {
    TextButton(
        onClick = { actions.toggleWatchlist(row.name) },
        modifier = Modifier.testTag("sheet:watch"),
    ) {
        Text(if (row.watchlisted) "Remove from watchlist" else "Add to watchlist")
    }
    if (row.uid >= 0) {
        TextButton(
            onClick = { actions.setUidFilter(row.uid) },
            modifier = Modifier.testTag("sheet:filter-uid"),
        ) { Text("Filter by this app") }
    }
}

@Composable
private fun DomainRowItem(
    row: DomainRow,
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
                onLongClick = {
                    actions.copyText("domain", domainTabText(row, state.snapshot.atMillis))
                },
            )
            .testTag("domain:${row.name}:${row.uid}")
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            HighlightedText(
                text = row.name,
                query = state.highlightQuery,
                isRegex = state.highlightIsRegex,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.watchlisted) DuckColors.Watchlist else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(appDisplayName(row.appLabel, row.packageName, row.uid))
                    append(" · ")
                    append(row.lookups)
                    append(if (row.lookups == 1) " lookup" else " lookups")
                    append(" · last ")
                    append(formatClock(row.lastSeen, state.snapshot.atMillis))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = row.source.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (row.source == NameSource.SNI) DuckColors.Info else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("domain:source:${row.name}:${row.uid}"),
        )
        Spacer(Modifier.width(8.dp))
    }
}

private fun domainDetailLines(row: DomainRow, now: Long): List<Pair<String, String>> = listOf(
    "Name" to row.name,
    "Parent" to row.parentDomain,
    "Asked by" to if (row.uid >= 0) {
        appDisplayName(row.appLabel, row.packageName, row.uid)
    } else {
        "unknown"
    },
    "Source" to when (row.source) {
        NameSource.DNS -> "DNS answer seen on the wire"
        NameSource.SNI -> "TLS SNI (no DNS answer was visible)"
    },
    "Lookups" to row.lookups.toString(),
    "First seen" to formatClock(row.firstSeen, now),
    "Last seen" to formatClock(row.lastSeen, now),
    "Age" to Units.age(if (row.firstSeen > 0L) now - row.firstSeen else -1L),
    "Addresses" to row.addresses.joinToString(", ").ifEmpty { "-" },
    "Watchlisted" to if (row.watchlisted) "yes" else "no",
)

/** Tab-separated, so a copied row pastes into a spreadsheet as the TUI's does. */
private fun domainTabText(row: DomainRow, now: Long): String = listOf(
    row.name,
    row.appLabel,
    row.source.label,
    row.lookups.toString(),
    formatClock(row.lastSeen, now),
    row.addresses.joinToString(" "),
).joinToString("\t")
