package com.vlad.ducknetview.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.UiActions
import com.vlad.ducknetview.ui.UiState

/**
 * Width at which the bottom bar becomes a rail — the Material "medium" break.
 * Passed in rather than read from the window so the layout choice is testable.
 */
private const val RAIL_MIN_WIDTH_DP = 600

/**
 * Test tags: "nav:rail", "nav:bar", one "nav:<route>" per destination,
 * "badge:events" for the unacked-alert count.
 */
@Composable
fun AdaptiveScaffold(
    state: UiState,
    actions: UiActions,
    current: Tab,
    onTab: (Tab) -> Unit,
    widthDp: Int,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val useRail = widthDp >= RAIL_MIN_WIDTH_DP
    Scaffold(
        modifier = modifier.then(Modifier.testTag("scaffold")),
        // The top inset is applied once, to the row below, so that everything
        // in it clears the status bar — including the rail and the status
        // banner, which sit outside the padding handed to the screen content
        // and would otherwise be drawn underneath the system clock.
        contentWindowInsets = WindowInsets.systemBars
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        bottomBar = {
            if (!useRail) {
                NavigationBar(modifier = Modifier.testTag("nav:bar")) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == current,
                            onClick = { onTab(tab) },
                            modifier = Modifier.testTag("nav:${tab.route}"),
                            icon = { TabIcon(tab, state.unackedAlerts) },
                            label = { Text(tab.title) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            if (useRail) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .testTag("nav:rail"),
                ) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = tab == current,
                            onClick = { onTab(tab) },
                            modifier = Modifier.testTag("nav:${tab.route}"),
                            icon = { TabIcon(tab, state.unackedAlerts) },
                            label = { Text(tab.title) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                val status = state.status
                if (status != null) {
                    StatusBanner(text = status, onDismiss = actions::dismissStatus)
                }
                content(padding)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabIcon(tab: Tab, unackedAlerts: Int) {
    val icon = iconFor(tab)
    if (tab == Tab.EVENTS && unackedAlerts > 0) {
        BadgedBox(
            badge = {
                Badge(modifier = Modifier.testTag("badge:events")) {
                    Text(unackedAlerts.toString())
                }
            },
        ) {
            Icon(icon, contentDescription = tab.title)
        }
    } else {
        Icon(icon, contentDescription = tab.title)
    }
}

private fun iconFor(tab: Tab): ImageVector = when (tab) {
    Tab.OVERVIEW -> Icons.Filled.Dashboard
    Tab.INTERFACES -> Icons.Filled.SettingsEthernet
    Tab.SERVICES -> Icons.Filled.Dns
    Tab.APPS -> Icons.Filled.Apps
    Tab.CONNECTIONS -> Icons.Filled.SwapVert
    Tab.DOMAINS -> Icons.Filled.Language
    Tab.ROUTES -> Icons.Filled.AltRoute
    Tab.EVENTS -> Icons.Filled.Notifications
}
