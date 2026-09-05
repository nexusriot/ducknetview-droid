package com.vlad.ducknetview.service

import android.content.Context
import android.content.Intent
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android stand-in for the TUI's `--on-alert` hook: a notification (handled
 * elsewhere), an optional webhook POST, and an optional broadcast Intent for
 * automation apps. Nothing here ever runs a shell command.
 */
object AlertDelivery {

    const val ACTION_ALERT = "com.vlad.ducknetview.ALERT"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun deliver(context: Context, settings: AppSettings, event: Event) {
        if (settings.broadcastOnAlert) broadcast(context, event)
        val url = settings.webhookUrl.trim()
        if (url.isNotEmpty()) scope.launch { postWebhook(url, event) }
    }

    private fun broadcast(context: Context, event: Event) {
        val intent = Intent(ACTION_ALERT).apply {
            setPackage(null)
            putExtra("kind", event.kind.label)
            putExtra("level", event.level.name.lowercase())
            putExtra("subject", event.subject)
            putExtra("detail", event.detail)
            putExtra("at", event.at)
        }
        runCatching { context.sendBroadcast(intent) }
    }

    private suspend fun postWebhook(url: String, event: Event) {
        withTimeoutOrNull(WEBHOOK_TIMEOUT_MS) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Connection", "close")
                    conn.outputStream.use { it.write(encode(event).toByteArray()) }
                    conn.responseCode
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private fun encode(e: Event): String = buildString {
        append('{')
        append("\"kind\":\"").append(esc(e.kind.label)).append("\",")
        append("\"level\":\"").append(esc(e.level.name.lowercase())).append("\",")
        append("\"subject\":\"").append(esc(e.subject)).append("\",")
        append("\"detail\":\"").append(esc(e.detail)).append("\",")
        append("\"at\":").append(e.at)
        append('}')
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }

    private const val WEBHOOK_TIMEOUT_MS = 10_000L
}
