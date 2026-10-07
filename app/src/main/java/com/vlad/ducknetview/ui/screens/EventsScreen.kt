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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventLevelFilter
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.DetailSheet
import com.vlad.ducknetview.ui.components.DuckFilterChip
import com.vlad.ducknetview.ui.components.EmptyState
import com.vlad.ducknetview.ui.components.HighlightedText
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.TableSearchBar
import com.vlad.ducknetview.ui.theme.DuckColors

@Composable
fun EventsScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    // Only the event id is held, so a cleared or re-filtered log cannot leave a
    // stale copy of the row pinned in the detail pane.
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val now = state.snapshot.atMillis
    val live = selectedId?.let { id -> state.events.firstOrNull { it.id == id } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        ) {
            Text(
                text = if (state.unackedAlerts > 0) {
                    countLabel(state.events.size, "event") + " · ${state.unackedAlerts} unacked"
                } else {
                    countLabel(state.events.size, "event")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (state.unackedAlerts > 0) DuckColors.Alert else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("events:count"),
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { actions.ackAlerts() },
                modifier = Modifier.testTag("events:ack"),
            ) {
                Icon(Icons.Default.Done, contentDescription = "Acknowledge alerts")
            }
            IconButton(
                onClick = { actions.clearEvents() },
                modifier = Modifier.testTag("events:clear"),
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear events")
            }
            IconButton(
                onClick = { actions.exportEvents() },
                modifier = Modifier.testTag("events:export"),
            ) {
                Icon(Icons.Default.Share, contentDescription = "Export events")
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
                "all",
                state.eventFilter == EventLevelFilter.ALL,
                { actions.setEventFilter(EventLevelFilter.ALL) },
                Modifier.testTag("chip:level-all"),
            )
            DuckFilterChip(
                "warn+",
                state.eventFilter == EventLevelFilter.WARN_PLUS,
                { actions.setEventFilter(EventLevelFilter.WARN_PLUS) },
                Modifier.testTag("chip:level-warn"),
            )
            DuckFilterChip(
                "alerts",
                state.eventFilter == EventLevelFilter.ALERTS,
                { actions.setEventFilter(EventLevelFilter.ALERTS) },
                Modifier.testTag("chip:level-alerts"),
            )
        }

        HorizontalDivider()

        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            if (state.events.isEmpty()) {
                EmptyState(
                    title = EMPTY_EVENTS,
                    message = "The event log records what changed — a listener appearing, a " +
                        "network going down, a threshold breached — rather than the current " +
                        "state. An empty log means nothing has changed yet.",
                    modifier = tableModifier.testTag(emptyTag(EMPTY_EVENTS)),
                )
            } else {
                val listState = rememberLazyListState()
                ScrollToMatch(state.matchCursor, state.events.size, listState)
                LazyColumn(modifier = tableModifier, state = listState) {
                    itemsIndexed(state.events, key = { _, e -> e.id }) { index, e ->
                        MatchRow(index = index, state = state) {
                            EventRowItem(e, state, now, actions) { selectedId = e.id }
                        }
                        HorizontalDivider()
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
                            "Select an event to see its detail.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        SectionCard(
                            title = live.kind.label,
                            modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                        ) {
                            eventDetailLines(live, now).forEach { (k, v) ->
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
                title = live.kind.label,
                lines = eventDetailLines(live, now),
                onCopy = { actions.copyText("event", eventTabText(live, now)) },
                onDismiss = { selectedId = null },
            )
        }
    }
}

private fun eventDetailLines(e: Event, now: Long): List<Pair<String, String>> = listOf(
    "Time" to formatClock(e.at, now),
    "Level" to e.level.name.lowercase(),
    "Kind" to e.kind.label,
    "Subject" to e.subject,
    "Detail" to e.detail.ifEmpty { "-" },
)

@Composable
private fun EventRowItem(
    e: Event,
    state: UiState,
    now: Long,
    actions: UiActions,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { actions.copyText("event", eventTabText(e, now)) },
            )
            .testTag("event:${e.id}")
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(DuckColors.forLevel(e.level)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(e.at, now), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    e.kind.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = DuckColors.forLevel(e.level),
                )
                Spacer(Modifier.width(8.dp))
                HighlightedText(
                    text = e.subject,
                    query = state.highlightQuery,
                    isRegex = state.highlightIsRegex,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (e.detail.isNotEmpty()) {
                Text(
                    e.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
