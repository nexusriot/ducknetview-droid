package com.vlad.ducknetview

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.OpenableColumns
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.ui.MainViewModel
import com.vlad.ducknetview.ui.Tab
import com.vlad.ducknetview.ui.UiState
import com.vlad.ducknetview.ui.components.AdaptiveScaffold
import com.vlad.ducknetview.ui.screens.AppsScreen
import com.vlad.ducknetview.ui.screens.ConnectionsScreen
import com.vlad.ducknetview.ui.screens.EventsScreen
import com.vlad.ducknetview.ui.screens.InterfacesScreen
import com.vlad.ducknetview.ui.screens.OnboardingSheet
import com.vlad.ducknetview.ui.screens.ScreenInfoSheet
import com.vlad.ducknetview.ui.screens.UsageInfoSheet
import com.vlad.ducknetview.ui.screens.OverviewScreen
import com.vlad.ducknetview.ui.screens.RoutesScreen
import com.vlad.ducknetview.ui.screens.ServicesScreen
import com.vlad.ducknetview.ui.screens.UsageScreen
import com.vlad.ducknetview.ui.screens.SettingsScreen
import com.vlad.ducknetview.ui.theme.DuckTheme

/** Width at which every list screen splits into a list plus a detail pane. */
private const val TWO_PANE_MIN_WIDTH_DP = 720

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Quick Settings tile cannot raise the VPN consent dialog itself,
        // so it hands the request here instead of failing silently.
        if (intent?.getBooleanExtra(EXTRA_START_CAPTURE, false) == true) {
            intent.removeExtra(EXTRA_START_CAPTURE)
            vm.startVpn()
        }
        setContent {
            DuckTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val tab by vm.tab.collectAsStateWithLifecycle()
                val wantsVpn by vm.vpnStartRequest.collectAsStateWithLifecycle()
                val wantsPermission by vm.permissionRequest.collectAsStateWithLifecycle()

                val vpnLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        DuckVpnService.start(this)
                    }
                }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { vm.onPermissionResult() }

                LaunchedEffect(wantsVpn) {
                    if (wantsVpn) {
                        vm.consumeVpnStartRequest()
                        val intent = VpnService.prepare(this@MainActivity)
                        if (intent != null) vpnLauncher.launch(intent)
                        else DuckVpnService.start(this@MainActivity)
                    }
                }
                LaunchedEffect(wantsPermission) {
                    if (wantsPermission) {
                        vm.consumePermissionRequest()
                        permissionLauncher.launch(wifiPermissions())
                    }
                }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                }

                val widthDp = LocalConfiguration.current.screenWidthDp
                var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
                var showInfo by rememberSaveable { mutableStateOf(false) }
                val wantsSnapshot by vm.snapshotOpenRequest.collectAsStateWithLifecycle()

                val snapshotLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) vm.loadSnapshot(uri, displayNameOf(uri))
                }
                LaunchedEffect(wantsSnapshot) {
                    if (wantsSnapshot) {
                        vm.consumeSnapshotOpenRequest()
                        snapshotLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                }

                AdaptiveScaffold(
                    state = state,
                    actions = vm,
                    current = tab,
                    onTab = { t ->
                        overlay = Overlay.NONE
                        vm.setTab(t)
                    },
                    widthDp = widthDp,
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        TopBar(
                            title = when (overlay) {
                                Overlay.SETTINGS -> "Settings"
                                Overlay.USAGE -> "Usage"
                                Overlay.NONE -> tab.title
                            },
                            paused = state.snapshot.paused,
                            capturing = state.vpnRunning,
                            frozenLabel = state.snapshot.frozenLabel,
                            overlay = overlay,
                            onToggleSettings = {
                                overlay = if (overlay == Overlay.SETTINGS) Overlay.NONE
                                else Overlay.SETTINGS
                            },
                            onToggleUsage = {
                                if (overlay == Overlay.USAGE) {
                                    overlay = Overlay.NONE
                                } else {
                                    overlay = Overlay.USAGE
                                    vm.refreshUsage()
                                }
                            },
                            onCloseSnapshot = vm::closeSnapshot,
                            onPause = vm::togglePause,
                            onRefresh = vm::refreshNow,
                            onInfo = { showInfo = true },
                        )
                        Box(Modifier.fillMaxSize()) {
                            when (overlay) {
                                Overlay.SETTINGS -> SettingsScreen(state, vm)
                                Overlay.USAGE ->
                                    UsageScreen(state, vm, twoPane = widthDp >= TWO_PANE_MIN_WIDTH_DP)
                                Overlay.NONE -> Screen(tab, state, widthDp)
                            }
                        }
                        if (showInfo) {
                            if (overlay == Overlay.USAGE) {
                                UsageInfoSheet(state) { showInfo = false }
                            } else {
                                ScreenInfoSheet(tab, state) { showInfo = false }
                            }
                        }
                        if (state.settingsLoaded && !state.settings.onboardingShown) {
                            OnboardingSheet(
                                onDismiss = { vm.updateSettings { s -> s.copy(onboardingShown = true) } },
                                onEnableCapture = {
                                    vm.updateSettings { s -> s.copy(onboardingShown = true) }
                                    vm.startVpn()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    enum class Overlay { NONE, SETTINGS, USAGE }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TopBar(
        title: String,
        paused: Boolean,
        capturing: Boolean,
        frozenLabel: String?,
        overlay: Overlay,
        onToggleSettings: () -> Unit,
        onToggleUsage: () -> Unit,
        onCloseSnapshot: () -> Unit,
        onPause: () -> Unit,
        onRefresh: () -> Unit,
        onInfo: () -> Unit,
    ) {
        TopAppBar(
            title = {
                Text(
                    text = if (paused) "$title  ⏸" else title,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            actions = {
                if (frozenLabel != null) {
                    // A frozen snapshot is not this device: say so loudly and
                    // give the way back, or the whole UI is quietly lying.
                    AssistChip(
                        onClick = onCloseSnapshot,
                        label = { Text("snapshot") },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Close snapshot") },
                        modifier = Modifier.padding(end = 4.dp).testTag("badge:snapshot"),
                    )
                }
                IconButton(onClick = onInfo, modifier = Modifier.testTag("action:info")) {
                    Icon(Icons.Filled.Info, contentDescription = "What this screen can see")
                }
                IconButton(onClick = onToggleUsage, modifier = Modifier.testTag("action:usage")) {
                    Icon(Icons.Filled.History, contentDescription = "Usage history")
                }
                if (capturing) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = "capture running",
                        modifier = Modifier.padding(horizontal = 4.dp).testTag("badge:capturing"),
                    )
                }
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("action:refresh")) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh now")
                }
                IconButton(onClick = onPause, modifier = Modifier.testTag("action:pause")) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                    )
                }
                IconButton(
                    onClick = onToggleSettings,
                    modifier = Modifier.testTag("action:settings"),
                ) {
                    Icon(
                        if (overlay == Overlay.SETTINGS) Icons.Filled.Close else Icons.Filled.Settings,
                        contentDescription = "Settings",
                    )
                }
            },
        )
    }

    @Composable
    private fun Screen(tab: Tab, state: UiState, widthDp: Int) {
        val twoPane = widthDp >= TWO_PANE_MIN_WIDTH_DP
        when (tab) {
            Tab.OVERVIEW -> OverviewScreen(state, vm)
            Tab.INTERFACES -> InterfacesScreen(state, vm, twoPane = twoPane)
            Tab.SERVICES -> ServicesScreen(state, vm, twoPane = twoPane)
            Tab.APPS -> AppsScreen(state, vm, twoPane = twoPane)
            Tab.CONNECTIONS -> ConnectionsScreen(state, vm, twoPane = twoPane)
            Tab.ROUTES -> RoutesScreen(state, vm, twoPane = twoPane)
            Tab.EVENTS -> EventsScreen(state, vm, twoPane = twoPane)
        }
    }

    /** SAF gives a display name in the document cursor; the path is opaque. */
    private fun displayNameOf(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "snapshot"

    private fun wifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    companion object {
        const val EXTRA_TAB = "tab"

        /** Set by the Quick Settings tile when capture still needs consent. */
        const val EXTRA_START_CAPTURE = "start_capture"
        fun intentFor(activity: Activity, tab: Tab): Intent =
            Intent(activity, MainActivity::class.java).putExtra(EXTRA_TAB, tab.route)
    }
}
