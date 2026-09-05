package com.vlad.ducknetview.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vlad.ducknetview.engine.vpn.DuckVpnService

/**
 * Backs the ongoing notification's "Stop capture" action.
 *
 * The receiver is not exported, and the action is checked anyway: a stop that
 * any other app could trigger would be a way to blind the monitor.
 */
class StopCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_CAPTURE) return
        DuckVpnService.stop(context)
        // The service removes its own foreground notification as it shuts down,
        // but that is asynchronous; clearing it here keeps a dead engine from
        // leaving a live-looking row in the shade if the stop is refused.
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(Notifications.ENGINE_ID)
        }
    }

    companion object {
        const val ACTION_STOP_CAPTURE = "com.vlad.ducknetview.STOP_CAPTURE"
    }
}
