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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.domain.usage.DailyUsage
import com.vlad.ducknetview.domain.usage.UsageRollup
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.BarChart
import com.vlad.ducknetview.ui.components.DetailSheet
import com.vlad.ducknetview.ui.components.DuckFilterChip
import com.vlad.ducknetview.ui.components.EmptyState
import com.vlad.ducknetview.ui.components.KeyValueRow
import com.vlad.ducknetview.ui.components.SectionCard
import com.vlad.ducknetview.ui.components.StatTile
import com.vlad.ducknetview.ui.theme.DuckColors

internal const val EMPTY_USAGE = "No usage history yet"

private const val TOP_ROWS = 15
private const val SHEET_TOP_ROWS = 5

/**
 * The daily-history screen: the app's answer to "where did this month's data
 * go", mirroring the TUI's `--usage`. Everything here is derived from
 * [UiState.usage]; the range selector is view state only, so it lives in a
 * `remember` rather than in the ViewModel.
 *
 * Test tags: "screen:usage", "usage:range-7|30|all", "usage:refresh",
 * "usage:total-rx", "usage:total-tx", "usage:chart", "usage:busiest",
 * "usage:day:<dayEpoch>", "usage:app:<name>", "usage:host:<name>",
 * "usage:access-card", "usage:loading".
 */
@Composable
fun UsageScreen(
    state: UiState,
    actions: UiActions,
    modifier: Modifier = Modifier,
    twoPane: Boolean = false,
) {
    var range by remember { mutableStateOf<Int?>(7) }
    // The day number is the identity; the row is re-read so a rollup landing
    // mid-view updates the pane instead of freezing yesterday's numbers.
    var selectedDay by remember { mutableStateOf<Long?>(null) }

    val days = rangeOf(state.usage, range)
    val (apps, hosts) = aggregate(days)
    val totalRx = days.sumOf { it.rx }
    val totalTx = days.sumOf { it.tx }
    val selected = selectedDay?.let { epoch -> state.usage.firstOrNull { it.dayEpoch == epoch } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
            .testTag("screen:usage"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${days.size} ${if (days.size == 1) "day" else "days"} of history",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { actions.refreshUsage() },
                modifier = Modifier.testTag("usage:refresh"),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh usage history")
            }
        }

        if (!state.usageAccessGranted) {
            UsageAccessCard(actions)
        }

        if (state.usageLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().testTag("usage:loading"),
            )
        }

        if (state.usage.isEmpty()) {
            if (!state.usageLoading) {
                EmptyState(
                    title = EMPTY_USAGE,
                    message = "Daily totals are rolled up as the app runs, and the last " +
                        "${UsageRollup.MAX_DAYS} days are kept. Come back tomorrow and this " +
                        "screen will have something to compare.",
                    actionLabel = "Refresh",
                    onAction = { actions.refreshUsage() },
                )
            }
        } else {
            RangeChips(range) { range = it }

            TotalsGrid(totalRx, totalTx, dailyAverage(days))

            val history: @Composable () -> Unit = {
                DailyCard(
                    days = days,
                    onSelect = { day -> selectedDay = day.dayEpoch },
                    onCopy = { day -> actions.copyText("usage", dayTabText(day)) },
                )

                TopCard(
                    title = "Top apps",
                    kind = "app",
                    counts = apps,
                    color = DuckColors.Tx,
                    actions = actions,
                )
                TopCard(
                    title = "Top hosts",
                    kind = "host",
                    counts = hosts,
                    color = DuckColors.Rx,
                    actions = actions,
                )
            }

            if (twoPane) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(
                        modifier = Modifier.weight(0.55f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        history()
                    }
                    Column(modifier = Modifier.weight(0.45f)) {
                        if (selected == null) {
                            Text(
                                text = "Select a day to see its top apps and hosts.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SectionCard(
                                title = formatDay(selected.dayEpoch),
                                modifier = Modifier.fillMaxWidth().testTag(TAG_DETAIL_SHEET),
                            ) {
                                dayDetailLines(selected).forEach { (k, v) ->
                                    KeyValueRow(label = k, value = v)
                                }
                            }
                        }
                    }
                }
            } else {
                history()
            }
        }
    }

    if (!twoPane && selected != null) {
        Box(Modifier.testTag(TAG_DETAIL_SHEET)) {
            DetailSheet(
                title = formatDay(selected.dayEpoch),
                lines = dayDetailLines(selected),
                onCopy = { actions.copyText("usage", dayTabText(selected)) },
                onDismiss = { selectedDay = null },
            )
        }
    }
}

@Composable
private fun UsageAccessCard(actions: UiActions) {
    SectionCard(
        title = "Per-app history locked",
        modifier = Modifier.fillMaxWidth().testTag("usage:access-card"),
    ) {
        Text(
            text = "Device totals are always available, but breaking a day down by app " +
                "reads the system's network usage database. That needs the Usage Access " +
                "special permission, which only you can grant in Settings — it is not a " +
                "runtime prompt.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { actions.requestUsageAccess() },
            modifier = Modifier.testTag("usage:access-grant"),
        ) {
            Text("Open Usage Access")
        }
    }
}

@Composable
private fun RangeChips(range: Int?, onRange: (Int?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        DuckFilterChip("7 days", range == 7, { onRange(7) }, Modifier.testTag("usage:range-7"))
        DuckFilterChip("30 days", range == 30, { onRange(30) }, Modifier.testTag("usage:range-30"))
        DuckFilterChip("All", range == null, { onRange(null) }, Modifier.testTag("usage:range-all"))
    }
}

@Composable
private fun TotalsGrid(totalRx: Long, totalTx: Long, average: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Down",
                value = Units.bytes(totalRx),
                tint = DuckColors.Rx,
                modifier = Modifier.weight(1f).testTag("usage:total-rx"),
            )
            StatTile(
                label = "Up",
                value = Units.bytes(totalTx),
                tint = DuckColors.Tx,
                modifier = Modifier.weight(1f).testTag("usage:total-tx"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "Combined",
                value = Units.bytes(totalRx + totalTx),
                modifier = Modifier.weight(1f).testTag("usage:total-all"),
            )
            StatTile(
                label = "Daily average",
                value = Units.bytes(average),
                modifier = Modifier.weight(1f).testTag("usage:average"),
            )
        }
    }
}

@Composable
private fun DailyCard(
    days: List<DailyUsage>,
    onSelect: (DailyUsage) -> Unit,
    onCopy: (DailyUsage) -> Unit,
) {
    // The chart reads oldest to newest left to right; the list below reads
    // newest first, which is the order the days arrive in.
    val oldestFirst = days.asReversed()
    val busiest = busiestDay(days)

    SectionCard(title = "Daily traffic") {
        Box(Modifier.testTag("usage:chart")) {
            BarChart(
                values = oldestFirst.map { it.total.toFloat() },
                height = 56.dp,
                color = DuckColors.Warn,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = oldestFirst.firstOrNull()?.let { formatDay(it.dayEpoch) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = oldestFirst.lastOrNull()?.let { formatDay(it.dayEpoch) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (busiest != null) {
            Text(
                text = "Busiest ${formatDay(busiest.dayEpoch)} — ${Units.bytes(busiest.total)}",
                style = MaterialTheme.typography.bodySmall,
                color = DuckColors.Warn,
                modifier = Modifier.testTag("usage:busiest"),
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        days.forEach { day ->
            DayRow(day, onSelect = { onSelect(day) }, onCopy = { onCopy(day) })
        }
    }
}

@Composable
private fun DayRow(day: DailyUsage, onSelect: () -> Unit, onCopy: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onSelect, onLongClick = onCopy)
            .testTag("usage:day:${day.dayEpoch}")
            .padding(vertical = 5.dp),
    ) {
        Text(
            text = formatDay(day.dayEpoch),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.32f),
        )
        Text(
            text = "↓ ${Units.bytes(day.rx)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = DuckColors.Rx,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.24f),
        )
        Text(
            text = "↑ ${Units.bytes(day.tx)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = DuckColors.Tx,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.24f),
        )
        Text(
            text = Units.bytes(day.total),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.20f),
        )
    }
}

@Composable
private fun TopCard(
    title: String,
    kind: String,
    counts: Map<String, Long>,
    color: Color,
    actions: UiActions,
) {
    SectionCard(title = title) {
        if (counts.isEmpty()) {
            Text(
                text = if (kind == "app") {
                    "No per-app breakdown recorded for this range."
                } else {
                    "No per-host breakdown recorded for this range."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val rows = counts.entries.take(TOP_ROWS)
        val max = rows.first().value
        rows.forEach { (name, bytes) ->
            UsageBarRow(
                tag = "usage:$kind:$name",
                name = name,
                bytes = bytes,
                max = max,
                color = color,
                onCopy = { actions.copyText("usage", "$name\t${Units.bytes(bytes)}") },
            )
        }
    }
}

@Composable
private fun UsageBarRow(
    tag: String,
    name: String,
    bytes: Long,
    max: Long,
    color: Color,
    onCopy: () -> Unit,
) {
    val fraction = if (max > 0L) (bytes.toDouble() / max.toDouble()).toFloat() else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onCopy, onLongClick = onCopy)
            .testTag(tag)
            .padding(vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Units.bytes(bytes),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(color.copy(alpha = 0.18f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

private fun dayDetailLines(day: DailyUsage): List<Pair<String, String>> {
    val lines = mutableListOf(
        "Down" to Units.bytes(day.rx),
        "Up" to Units.bytes(day.tx),
        "Total" to Units.bytes(day.total),
    )
    val apps = byBytesDesc(day.apps).entries.take(SHEET_TOP_ROWS)
    if (apps.isEmpty()) {
        lines += "Top apps" to "-"
    } else {
        apps.forEach { (name, n) -> lines += "app $name" to Units.bytes(n) }
    }
    val hosts = byBytesDesc(day.hosts).entries.take(SHEET_TOP_ROWS)
    if (hosts.isEmpty()) {
        lines += "Top hosts" to "-"
    } else {
        hosts.forEach { (name, n) -> lines += "host $name" to Units.bytes(n) }
    }
    return lines
}

/** Tab-separated so a pasted day lands in spreadsheet columns, as in the TUI. */
private fun dayTabText(day: DailyUsage): String = listOf(
    formatDay(day.dayEpoch),
    Units.bytes(day.rx),
    Units.bytes(day.tx),
    Units.bytes(day.total),
).joinToString("\t")
