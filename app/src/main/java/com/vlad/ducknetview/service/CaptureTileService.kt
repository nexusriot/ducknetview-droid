package com.vlad.ducknetview.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.vlad.ducknetview.MainActivity
import com.vlad.ducknetview.R
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Everything the tile decides, kept out of the service so it can be tested
 * without a tile lifecycle.
 */
object CaptureTile {

    /** What one tap should do, given what the system currently allows. */
    enum class Action { START, STOP, ASK_CONSENT }

    fun stateFor(running: Boolean): Int =
        if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

    fun subtitleFor(running: Boolean): String = if (running) "Capturing" else "Off"

    /**
     * Consent is only in the way when starting. Stopping never needs it, and
     * asking for it from a tile is impossible — see [CaptureTileService].
     */
    fun actionFor(running: Boolean, consentNeeded: Boolean): Action = when {
        running -> Action.STOP
        consentNeeded -> Action.ASK_CONSENT
        else -> Action.START
    }

    const val START_FAILED = "ducknetview could not start capture"
}

/**
 * A Quick Settings toggle for the capture engine.
 *
 * Two constraints shape this. First, `VpnService.prepare()` can return a
 * consent Intent and a tile cannot show one, so that tap opens MainActivity and
 * the consent dialog is raised there instead of the toggle quietly doing
 * nothing. Second, some OEM background managers drop `startForegroundService`
 * for apps that are not allowed to run in the background (see the README's
 * "OEM background managers"); when that happens the start reports failure and
 * the tile says so rather than latching to active on a dead engine.
 */
class CaptureTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        watcher = s.launch {
            VpnBridge.running.collect { running -> render(running) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val running = VpnBridge.running.value
        val consent = runCatching { VpnService.prepare(this) }.getOrNull()
        when (CaptureTile.actionFor(running, consentNeeded = consent != null)) {
            CaptureTile.Action.STOP -> {
                DuckVpnService.stop(this)
                render(false)
            }
            CaptureTile.Action.START -> {
                val ok = DuckVpnService.start(this)
                if (!ok) {
                    runCatching {
                        Toast.makeText(this, CaptureTile.START_FAILED, Toast.LENGTH_LONG).show()
                    }
                }
                render(ok && VpnBridge.running.value)
            }
            CaptureTile.Action.ASK_CONSENT -> openAppForConsent()
        }
    }

    /**
     * The consent dialog needs an Activity. API 34 removed the Intent overload
     * of startActivityAndCollapse in favour of a PendingIntent, so both paths
     * exist; if neither is permitted the tile is left as-is rather than
     * pretending the engine started.
     */
    private fun openAppForConsent() {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_START_CAPTURE, true)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        2,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun render(running: Boolean) {
        val tile = qsTile ?: return
        runCatching {
            tile.state = CaptureTile.stateFor(running)
            tile.label = getString(R.string.app_name)
            tile.contentDescription = getString(R.string.app_name)
            tile.subtitle = CaptureTile.subtitleFor(running)
            tile.updateTile()
        }
    }
}
