package com.vlad.ducknetview.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.vlad.ducknetview.DuckApp
import com.vlad.ducknetview.domain.export.SnapshotJson
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Entry points for Tasker, Automate and `adb shell am broadcast`.
 *
 * Off until the user switches it on in Settings: an exported receiver is a
 * door any app on the device can knock on, and starting capture or a LAN
 * sweep on another app's say-so is something to opt into. Every command —
 * accepted or refused — goes into the event log, so a run nobody remembers
 * asking for can be traced.
 */
class AutomationReceiver : BroadcastReceiver() {

    enum class Command(val action: String, val label: String) {
        START_CAPTURE("com.vlad.ducknetview.action.START_CAPTURE", "start capture"),
        STOP_CAPTURE("com.vlad.ducknetview.action.STOP_CAPTURE", "stop capture"),
        SCAN_SERVICES("com.vlad.ducknetview.action.SCAN_SERVICES", "scan this device's listeners"),
        SCAN_HOSTS("com.vlad.ducknetview.action.SCAN_HOSTS", "sweep the LAN for hosts"),
        EXPORT_SNAPSHOT("com.vlad.ducknetview.action.EXPORT_SNAPSHOT", "export a snapshot"),
        ;

        companion object {
            fun of(action: String?): Command? = entries.firstOrNull { it.action == action }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val command = Command.of(intent?.action) ?: return
        val app = context.applicationContext as? DuckApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deps = app.deps
                val s = deps.settings.snapshot()
                val outcome = if (!s.automationEnabled) {
                    "refused — automation is switched off in Settings"
                } else {
                    deps.engine.start()
                    run(context, command)
                }
                deps.engine.log(
                    Event(
                        at = System.currentTimeMillis(),
                        level = EventLevel.INFO,
                        kind = EventKind.AUTOMATION,
                        subject = command.label,
                        detail = outcome,
                    )
                )
            } finally {
                pending.finish()
            }
        }
    }

    private fun run(context: Context, command: Command): String {
        val deps = (context.applicationContext as DuckApp).deps
        return when (command) {
            Command.START_CAPTURE -> when {
                VpnBridge.running.value -> "already capturing"
                runCatching { VpnService.prepare(context) }.getOrNull() != null ->
                    "refused — capture has never been allowed; open the app once to grant it"
                DuckVpnService.start(context) -> "started"
                else -> "the system refused to start the capture service"
            }
            Command.STOP_CAPTURE -> {
                DuckVpnService.stop(context)
                "stopped"
            }
            Command.SCAN_SERVICES -> {
                deps.engine.scanServices { }
                "scan started"
            }
            Command.SCAN_HOSTS -> {
                deps.engine.scanNeighbours()
                "sweep started"
            }
            Command.EXPORT_SNAPSHOT -> {
                val dir = context.getExternalFilesDir("exports") ?: File(context.filesDir, "exports")
                dir.mkdirs()
                val file = File(dir, "snapshot_${System.currentTimeMillis()}.json")
                file.writeText(SnapshotJson.encode(deps.engine.snapshot.value))
                "written to ${file.absolutePath}"
            }
        }
    }
}
