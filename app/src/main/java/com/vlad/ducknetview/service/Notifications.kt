package com.vlad.ducknetview.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vlad.ducknetview.MainActivity
import com.vlad.ducknetview.R
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.rates.Units

object Notifications {

    const val ENGINE_ID = 1001
    private const val TALKER_LINES = 3
    private const val CHANNEL_ENGINE = "engine"
    private const val CHANNEL_ALERT = "alerts"
    private const val CHANNEL_WARN = "warnings"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ENGINE,
                "Capture engine",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows that traffic capture is running" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Off-baseline listeners, watchlist hits and threshold breaches" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WARN,
                "Warnings",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The stop action goes to a broadcast receiver rather than straight to the
     * service: a notification action fires while the app is otherwise in the
     * background, and a receiver running from an explicit broadcast gets the
     * short foreground grace that makes the subsequent service call legal on
     * Android 12+. A PendingIntent.getService() to the VpnService would depend
     * on the service still being in the foreground state to be allowed.
     */
    private fun stopCapture(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, StopCaptureReceiver::class.java)
                .setAction(StopCaptureReceiver.ACTION_STOP_CAPTURE)
                .setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * The ongoing engine notification.
     *
     * Rebuilt only from [com.vlad.ducknetview.engine.vpn.VpnBridge]'s updater,
     * which the capture service calls on its 5 s expiry tick — not on every
     * poll and never per packet. A notification rebuilt many times a second is
     * itself a battery bug, so the cadence deliberately lags the UI.
     */
    fun engineNotification(
        context: Context,
        snapshot: NetSnapshot,
        unit: RateUnit = RateUnit.BYTES,
    ): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_duck)
            .setContentTitle("Capturing network traffic")
            .setContentText(engineText(snapshot, unit))
            .setStyle(NotificationCompat.BigTextStyle().bigText(engineBigText(snapshot, unit)))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(context))
            .addAction(R.drawable.ic_stat_duck, "Stop capture", stopCapture(context))
            .build()
    }

    /** Kept so the service's start-up call and older callers still compile. */
    fun engineNotification(context: Context, rxBps: Long, txBps: Long): Notification =
        engineNotification(context, NetSnapshot(total = Throughput(rxBps, txBps)))

    /** The collapsed line: rates, connection count, and the loudest talker. */
    fun engineText(snapshot: NetSnapshot, unit: RateUnit = RateUnit.BYTES): String {
        val parts = ArrayList<String>(3)
        parts += "↓ ${Units.rate(snapshot.total.rxBps, unit)}   ↑ ${Units.rate(snapshot.total.txBps, unit)}"
        val conns = snapshot.conns.size
        if (conns > 0) parts += "$conns ${if (conns == 1) "conn" else "conns"}"
        topTalker(snapshot)?.let { parts += "top ${it.label}" }
        return parts.joinToString(" · ")
    }

    /** The expanded form: the same line plus however many talkers we have. */
    fun engineBigText(snapshot: NetSnapshot, unit: RateUnit = RateUnit.BYTES): String {
        val lines = ArrayList<String>()
        lines += engineText(snapshot, unit)
        val apps = snapshot.topApps.take(TALKER_LINES)
        if (apps.isNotEmpty()) {
            lines += "Apps: " + apps.joinToString(", ") { "${it.label} ${Units.bytes(it.total)}" }
        }
        val hosts = snapshot.topHosts.take(TALKER_LINES)
        if (hosts.isNotEmpty()) {
            lines += "Hosts: " + hosts.joinToString(", ") { "${it.label} ${Units.bytes(it.total)}" }
        }
        if (apps.isEmpty() && hosts.isEmpty()) {
            lines += "No traffic attributed yet."
        }
        return lines.joinToString("\n")
    }

    /** An app is a more useful headline than a bare address, so apps win. */
    fun topTalker(snapshot: NetSnapshot): Talker? =
        snapshot.topApps.firstOrNull { it.label.isNotBlank() }
            ?: snapshot.topHosts.firstOrNull { it.label.isNotBlank() }

    fun alertNotification(context: Context, event: Event): Notification {
        ensureChannels(context)
        val channel = if (event.level == EventLevel.ALERT) CHANNEL_ALERT else CHANNEL_WARN
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_duck)
            .setContentTitle("${event.kind.label}: ${event.subject}")
            .setContentText(event.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.detail))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .build()
    }

    fun notifyEvent(context: Context, event: Event) {
        if (event.level == EventLevel.INFO) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.notify(event.hashCode() and 0x7FFFFFFF, alertNotification(context, event))
        }
    }
}
