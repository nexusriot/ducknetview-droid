package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.AppRow
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.ServiceRow
import com.vlad.ducknetview.domain.model.ThroughputMode
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.domain.search.Search
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.theme.DuckColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal const val TAG_DETAIL_SHEET = "detail:sheet"
internal const val TAG_MATCH_CURSOR = "match:cursor"

internal fun matchTag(index: Int): String = "match:$index"

internal const val EMPTY_CONNS_NO_CAPTURE = "Capture engine is off"
internal const val EMPTY_CONNS = "No connections"
internal const val EMPTY_GROUPS = "No hosts"
internal const val EMPTY_CLOSED = "No closed connections yet"
internal const val EMPTY_APPS = "No apps"
internal const val EMPTY_SERVICES_NEVER = "Never scanned"
internal const val EMPTY_SERVICES = "No listeners found"
internal const val EMPTY_EVENTS = "No events yet"
internal const val EMPTY_DOMAINS = "No names seen yet"
internal const val EMPTY_DOMAINS_NO_CAPTURE = "Capture engine is off"

internal fun emptyTag(title: String): String = "empty:$title"

/** Rate or cumulative columns, following the global rate/total toggle. */
internal fun rxTxText(
    rxBps: Long,
    txBps: Long,
    rxTotal: Long,
    txTotal: Long,
    mode: ThroughputMode,
    unit: RateUnit,
): String = if (mode == ThroughputMode.RATE) {
    "↓ ${Units.rate(rxBps, unit)}  ↑ ${Units.rate(txBps, unit)}"
} else {
    "↓ ${Units.bytes(rxTotal)}  ↑ ${Units.bytes(txTotal)}"
}

/** Watchlist tint outranks the new-row tint when a row is both. */
internal fun rowTint(watchlisted: Boolean, isNew: Boolean): Color = when {
    watchlisted -> DuckColors.Watchlist.copy(alpha = ROW_TINT_ALPHA)
    isNew -> DuckColors.NewRow.copy(alpha = ROW_TINT_ALPHA)
    else -> Color.Transparent
}

internal const val ROW_TINT_ALPHA = 0.20f
internal const val MATCH_TINT_ALPHA = 0.30f
internal const val MATCH_CURSOR_TINT_ALPHA = 0.55f
private val MATCH_CURSOR_BORDER = 2.dp

/** True while a highlight search is marking rows rather than filtering them. */
internal val UiState.highlighting: Boolean
    get() = search.active && search.mode == SearchMode.HIGHLIGHT

/** The query cells should mark, or empty when marking is off. */
internal val UiState.highlightQuery: String
    get() = if (highlighting) search.query else ""

internal val UiState.highlightIsRegex: Boolean
    get() = Search.isRegexQuery(search.query)

/** 1-based position of the focused match among the matched rows, or -1. */
internal val UiState.matchPosition: Int
    get() {
        if (matchCursor < 0 || matchedRows.isEmpty()) return -1
        val at = matchedRows.sorted().indexOf(matchCursor)
        return if (at < 0) -1 else at + 1
    }

internal fun UiState.isMatchedRow(index: Int): Boolean = highlighting && index in matchedRows

/**
 * Wraps one table row so a highlight-search hit is unmistakable.
 *
 * Precedence: the match tint outranks the watchlist and new-row tints. While a
 * highlight search is running the match is what the user is hunting for, so it
 * must never be masked by a row that happens to also be watchlisted or new;
 * outside a highlight search [rowTint]'s own precedence applies unchanged.
 *
 * The tags live on wrapper nodes rather than on the row itself because Compose
 * keeps only the first testTag on a node, and the row already carries its own.
 */
@Composable
internal fun MatchRow(
    index: Int,
    state: UiState,
    modifier: Modifier = Modifier,
    watchlisted: Boolean = false,
    isNew: Boolean = false,
    content: @Composable () -> Unit,
) {
    val matched = state.isMatchedRow(index)
    val focused = matched && index == state.matchCursor
    val accent = MaterialTheme.colorScheme.tertiary
    val tint = when {
        focused -> accent.copy(alpha = MATCH_CURSOR_TINT_ALPHA)
        matched -> accent.copy(alpha = MATCH_TINT_ALPHA)
        else -> rowTint(watchlisted, isNew)
    }
    Box(
        modifier
            .fillMaxWidth()
            .background(tint)
            .then(if (matched) Modifier.testTag(matchTag(index)) else Modifier),
    ) {
        if (focused) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(MATCH_CURSOR_BORDER, accent)
                    .testTag(TAG_MATCH_CURSOR),
            ) { content() }
        } else {
            content()
        }
    }
}

/**
 * Scrolls the focused match into view. [target] is a position in the rendered
 * list; an out-of-range cursor is ignored, because the list is re-polled while
 * the cursor stands still.
 */
@Composable
internal fun ScrollToMatch(target: Int, itemCount: Int, listState: LazyListState) {
    LaunchedEffect(target, itemCount) {
        if (target in 0 until itemCount) listState.animateScrollToItem(target)
    }
}

internal fun formatClock(at: Long, now: Long): String {
    if (at <= 0L) return "-"
    val pattern = if (sameDay(at, now)) "HH:mm:ss" else "MMM d HH:mm:ss"
    return SimpleDateFormat(pattern, Locale.US).format(Date(at))
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

internal fun appDisplayName(label: String, packageName: String, uid: Int): String = when {
    label.isNotEmpty() -> label
    packageName.isNotEmpty() -> packageName
    else -> "uid $uid"
}

/** Tab-separated so a pasted row lands in spreadsheet columns, as in the TUI. */
internal fun connTabText(row: ConnRow, revDns: Boolean, unit: RateUnit, now: Long): String = listOf(
    appDisplayName(row.appLabel, row.packageName, row.uid),
    row.proto.toString(),
    row.local,
    row.remoteDisplay(revDns),
    row.service,
    row.state.toString(),
    Units.rate(row.rxBps, unit),
    Units.rate(row.txBps, unit),
    Units.bytes(row.rxBytes),
    Units.bytes(row.txBytes),
    if (row.rttMillis >= 0) Units.millis(row.rttMillis) else "",
    Units.age(row.ageMillis(now)),
    row.network,
).joinToString("\t")

internal fun closedTabText(c: ClosedConn, revDns: Boolean, now: Long): String = listOf(
    appDisplayName(c.row.appLabel, c.row.packageName, c.row.uid),
    c.row.proto.toString(),
    c.row.local,
    c.row.remoteDisplay(revDns),
    c.row.service,
    Units.bytes(c.finalRx),
    Units.bytes(c.finalTx),
    Units.age(c.lifetimeMillis),
    formatClock(c.closedAt, now),
).joinToString("\t")

internal fun appTabText(app: AppRow, unit: RateUnit): String = listOf(
    appDisplayName(app.label, app.packageName, app.uid),
    app.packageName,
    app.uid.toString(),
    app.connCount.toString(),
    Units.rate(app.rxBps, unit),
    Units.rate(app.txBps, unit),
    Units.bytes(app.sessionRx),
    Units.bytes(app.sessionTx),
    Units.bytes(app.todayRx + app.todayTx),
    if (app.blocked) "blocked" else "",
).joinToString("\t")

internal fun serviceTabText(row: ServiceRow, now: Long): String = listOf(
    row.proto.toString(),
    "${row.bindAddr}:${row.port}",
    row.service,
    row.exposure.name.lowercase(),
    formatClock(row.firstSeen, now),
    formatClock(row.lastSeen, now),
    if (row.offBaseline) "off-baseline" else "",
).joinToString("\t")

internal fun eventTabText(e: Event, now: Long): String = listOf(
    formatClock(e.at, now),
    e.level.name.lowercase(),
    e.kind.label,
    e.subject,
    e.detail,
).joinToString("\t")
