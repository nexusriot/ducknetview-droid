package com.vlad.ducknetview.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.search.Search
import com.vlad.ducknetview.domain.search.highlightRanges
import com.vlad.ducknetview.ui.SearchState

/**
 * Table furniture shared by every list screen.
 *
 * Test tags: search field = "search:field", mode toggle = "search:mode",
 * clear = "search:clear"; sort chip = "sort:<column>"; filter chip =
 * "chip:<label>"; empty state = "empty:<title>" (+ "empty:action");
 * detail sheet = "detail:<title>" (+ "detail:copy", "detail:close");
 * status banner = "status:banner" (+ "status:dismiss"); match navigation =
 * "search:prev", "search:next", "search:position". Each default tag is
 * applied before the caller's modifier, so passing your own testTag replaces
 * the default instead of being overridden by it.
 */
@Composable
fun TableSearchBar(
    search: SearchState,
    matchCount: Int,
    onQuery: (String) -> Unit,
    onToggleMode: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    matchPosition: Int = -1,
    onPrevMatch: (() -> Unit)? = null,
    onNextMatch: (() -> Unit)? = null,
) {
    val navigable = search.active && search.mode == SearchMode.HIGHLIGHT &&
        matchCount > 0 && onPrevMatch != null && onNextMatch != null

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = search.query,
            onValueChange = onQuery,
            modifier = Modifier
                .testTag("search:field")
                .then(modifier)
                .fillMaxWidth(),
            singleLine = true,
            isError = search.error != null,
            label = { Text("Search") },
            placeholder = { Text("text, or re:regex") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            supportingText = {
                Text(
                    text = search.error
                        ?: if (search.active) {
                            "$matchCount ${if (matchCount == 1) "match" else "matches"}"
                        } else {
                            "prefix with re: for a regex"
                        },
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = onToggleMode,
                        modifier = Modifier.testTag("search:mode"),
                    ) {
                        Text(if (search.mode == SearchMode.FILTER) "FILTER" else "MARK")
                    }
                    if (search.active) {
                        IconButton(onClick = onClear, modifier = Modifier.testTag("search:clear")) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                }
            },
        )

        if (navigable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "${if (matchPosition >= 1) matchPosition.toString() else "-"} / $matchCount",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag("search:position"),
                )
                IconButton(onClick = onPrevMatch, modifier = Modifier.testTag("search:prev")) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = onNextMatch, modifier = Modifier.testTag("search:next")) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match")
                }
            }
        }
    }
}

/**
 * [text] with every occurrence of [query] marked. [isRegex] forces the query to
 * be read as a regular expression even when the caller stripped the `re:`
 * prefix; the marking itself never fails, an unusable query simply marks
 * nothing.
 */
@Composable
fun HighlightedText(
    text: String,
    query: String,
    isRegex: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val effective = if (isRegex && !Search.isRegexQuery(query)) Search.REGEX_PREFIX + query else query
    val ranges = remember(text, effective) { highlightRanges(text, effective) }
    val markBackground = MaterialTheme.colorScheme.tertiaryContainer
    val markForeground = MaterialTheme.colorScheme.onTertiaryContainer

    val rendered = if (ranges.isEmpty()) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text)
            ranges.forEach { r ->
                addStyle(
                    SpanStyle(
                        background = markBackground,
                        color = markForeground,
                        fontWeight = FontWeight.Bold,
                    ),
                    r.first,
                    r.last + 1,
                )
            }
        }
    }

    Text(
        text = rendered,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun SortChips(
    columns: List<String>,
    active: String,
    desc: Boolean,
    onSort: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .testTag("sortchips")
            .then(modifier)
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            val isActive = column == active
            FilterChip(
                selected = isActive,
                onClick = { onSort(column) },
                modifier = Modifier.testTag("sort:$column"),
                label = {
                    Text(if (isActive) "$column ${if (desc) "▼" else "▲"}" else column)
                },
            )
        }
    }
}

@Composable
fun DuckFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.then(Modifier.testTag("chip:$label")),
        label = { Text(label) },
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .testTag("empty:$title")
            .then(modifier)
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.testTag("empty:action")) {
                Text(actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    title: String,
    lines: List<Pair<String, String>>,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("detail:$title"),
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { (label, value) -> KeyValueRow(label, value) }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
                TextButton(onClick = onCopy, modifier = Modifier.testTag("detail:copy")) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Copy")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("detail:close")) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun StatusBanner(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = Modifier
            .testTag("status:banner")
            .then(modifier)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("status:dismiss")) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}
