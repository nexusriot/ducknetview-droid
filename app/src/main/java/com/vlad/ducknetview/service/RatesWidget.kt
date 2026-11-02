package com.vlad.ducknetview.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.vlad.ducknetview.MainActivity
import com.vlad.ducknetview.R
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.rates.Units
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge

/**
 * A home-screen widget: live rates, whether capture is on, and the loudest
 * talker, with a capture toggle. A third consumer of what the tile and the
 * ongoing notification already show — it computes nothing of its own.
 *
 * Plain RemoteViews rather than Glance: the widget is two lines of text and a
 * button, and a dependency for that would cost more than it renders.
 */
class RatesWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, null, RateUnit.BYTES)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            toggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun toggle(context: Context) {
        val running = VpnBridge.running.value
        val consent = runCatching { VpnService.prepare(context) }.getOrNull()
        when (CaptureTile.actionFor(running, consentNeeded = consent != null)) {
            CaptureTile.Action.STOP -> DuckVpnService.stop(context)
            CaptureTile.Action.START -> DuckVpnService.start(context)
            CaptureTile.Action.ASK_CONSENT -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_START_CAPTURE, true)
            )
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.vlad.ducknetview.WIDGET_TOGGLE"

        /** What the widget's two text lines say, kept apart so it can be tested. */
        fun lines(snap: NetSnapshot?, capturing: Boolean, unit: RateUnit): Pair<String, String> {
            if (snap == null || snap.atMillis == 0L) {
                return "ducknetview" to if (capturing) "capturing" else "open the app to start"
            }
            val rates = "↓ ${Units.rate(snap.total.rxBps, unit)}  ↑ ${Units.rate(snap.total.txBps, unit)}"
            val top = snap.topApps.firstOrNull()?.label
            val second = buildString {
                append(if (capturing) "capturing" else "API mode")
                if (capturing) append(" · ${snap.conns.size} conns")
                if (!top.isNullOrEmpty()) append(" · top: $top")
            }
            return rates to second
        }

        fun render(context: Context, snap: NetSnapshot?, unit: RateUnit) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, RatesWidget::class.java)
            val ids = runCatching { manager.getAppWidgetIds(component) }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val capturing = VpnBridge.running.value
            val (first, second) = lines(snap, capturing, unit)
            val views = RemoteViews(context.packageName, R.layout.widget_rates).apply {
                setTextViewText(R.id.widget_rates, first)
                setTextViewText(R.id.widget_status, second)
                setTextViewText(R.id.widget_toggle, if (capturing) "Stop" else "Capture")
                setOnClickPendingIntent(
                    R.id.widget_toggle,
                    PendingIntent.getBroadcast(
                        context, 0,
                        Intent(context, RatesWidget::class.java).setAction(ACTION_TOGGLE),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context, 1,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            runCatching { manager.updateAppWidget(ids, views) }
        }
    }
}
