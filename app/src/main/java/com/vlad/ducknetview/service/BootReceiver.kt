package com.vlad.ducknetview.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vlad.ducknetview.DuckApp
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts capture after a reboot, when the user asked for that.
 *
 * Only ever with consent already granted: the consent dialog needs an open
 * Activity, and a boot receiver that opened one would be an app throwing a
 * screen at someone who just turned their phone on. Without consent this does
 * nothing, and the Settings switch says why.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val app = context.applicationContext as? DuckApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = app.deps.settings.snapshot()
                if (!s.startOnBoot) return@launch
                val consent = consentGranted(context)
                if (!shouldStart(s.startOnBoot, consent)) {
                    report(app, EventLevel.WARN, "not started: capture has never been allowed on this device")
                    return@launch
                }
                DuckVpnService.start(context)
                // A start request is not a start. Some OEM builds drop service
                // starts from apps not allowed to auto-start, silently — the
                // M10's "awbms" does exactly that at boot — so the log says
                // what actually happened rather than what was asked for.
                val deadline = System.currentTimeMillis() + CONFIRM_MS
                while (!VpnBridge.running.value && System.currentTimeMillis() < deadline) delay(250)
                if (VpnBridge.running.value) {
                    report(app, EventLevel.INFO, "capture started after boot")
                } else {
                    report(
                        app, EventLevel.WARN,
                        "capture was requested after boot but did not start — the system " +
                            "refused the service; allow ducknetview to run in the background " +
                            "or auto-start in the device's battery settings",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun report(app: DuckApp, level: EventLevel, detail: String) {
        app.deps.engine.log(
            Event(
                at = System.currentTimeMillis(),
                level = level,
                kind = EventKind.BOOT_START,
                subject = "start on boot",
                detail = detail,
            )
        )
    }

    private fun consentGranted(context: Context): Boolean =
        runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

    companion object {
        fun shouldStart(enabled: Boolean, consent: Boolean): Boolean = enabled && consent

        /** Inside goAsync's ~10 s budget, with room for the write after it. */
        private const val CONFIRM_MS = 7_000L
    }
}
