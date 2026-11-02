package com.vlad.ducknetview.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.domain.diff.SnapshotDiff
import com.vlad.ducknetview.ui.DiffView
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.components.SectionCard

/**
 * What changed between a snapshot file and this device now — the TUI's
 * `--diff`. Each section is shown only when it has something in it, and an
 * unchanged comparison says so in words rather than as a page of empty cards.
 */
@Composable
fun DiffScreen(view: DiffView, actions: UiActions) {
    val d = view.diff
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("screen:diff"),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(
                title = "Compared with ${view.label}",
                trailing = {
                    OutlinedButton(
                        onClick = actions::closeDiff,
                        modifier = Modifier.testTag("diff:close"),
                    ) { Text("Close") }
                },
            ) {
                Text(
                    text = if (d.isEmpty) {
                        "Nothing differs: the same listeners, apps, hosts and links as the file."
                    } else {
                        countLabel(d.changeCount, "change") + " since the snapshot was taken."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("diff:summary"),
                )
            }
        }
        for ((title, lines) in sections(d)) {
            if (lines.isEmpty()) continue
            item {
                SectionCard(title = title) {
                    for (line in lines) {
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** The diff as titled lists of lines, in the order a reader cares about. */
internal fun sections(d: SnapshotDiff): List<Pair<String, List<String>>> = listOf(
    "Listeners gained" to d.listenersGained.map { "${it.proto} ${it.bindAddr}:${it.port} ${it.service}".trim() },
    "Listeners moved" to d.listenersMoved.map { (a, b) ->
        "${a.proto} ${a.bindAddr}:${a.port} → ${b.bindAddr}:${b.port}"
    },
    "Listeners lost" to d.listenersLost.map { "${it.proto} ${it.bindAddr}:${it.port} ${it.service}".trim() },
    "Hosts contacted for the first time" to d.hostsFirstContact,
    "Apps that appeared" to d.appsAppeared.map { appDisplayName(it.label, it.packageName, it.uid) },
    "Apps gone" to d.appsGone.map { appDisplayName(it.label, it.packageName, it.uid) },
    "Links that appeared" to d.networksAppeared.map { "${it.ifaceName} (${it.transport.name.lowercase()})" },
    "Links gone" to d.networksGone.map { "${it.ifaceName} (${it.transport.name.lowercase()})" },
)
