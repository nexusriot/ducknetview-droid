package com.vlad.ducknetview.engine.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Overview card's "what does the internet see me as" line.
 *
 * The ported lesson from the TUI: a *fresh* connection per fetch, with
 * `Connection: close`. A pooled keep-alive socket survives a VPN toggle or a
 * Wi-Fi-to-cellular handover and happily replays the pre-change answer, so the
 * one number whose whole job is to notice a route change would be the last
 * thing to notice it.
 */
class ExternalIpFetcher(
    private val endpoint: String = IPIFY,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val transport: suspend (String, Int) -> String? = { url, ms -> httpGet(url, ms) },
) {

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    val value: String? get() = cached
    val fetchedAt: Long get() = cachedAt

    suspend fun fetch(): String? {
        val body = try {
            transport(endpoint, timeoutMs)
        } catch (e: Exception) {
            null
        } ?: return null
        val ip = parseIp(body) ?: return null
        cached = ip
        cachedAt = System.currentTimeMillis()
        return ip
    }

    suspend fun refreshIfStale(
        maxAgeMs: Long = MAX_AGE_MS,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val current = cached
        if (current != null && now - cachedAt in 0 until maxAgeMs) return current
        return fetch() ?: current
    }

    fun invalidate() {
        cached = null
        cachedAt = 0L
    }

    companion object {
        const val IPIFY = "https://api.ipify.org"
        const val TIMEOUT_MS = 5000
        const val MAX_AGE_MS = 30_000L

        /** Enough for the longest textual IPv6 plus slack; anything else is not an IP. */
        private const val MAX_BODY_CHARS = 128

        private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
        private val IPV6 = Regex("""^[0-9A-Fa-f:]{2,45}$""")

        /**
         * A captive portal answers this request with an HTML login page and a
         * 200, so the body is validated as an address before it is believed.
         */
        internal fun parseIp(body: String): String? {
            val text = body.trim()
            if (text.isEmpty() || text.length > MAX_BODY_CHARS) return null
            if (IPV4.matches(text)) {
                val octets = text.split('.').mapNotNull { it.toIntOrNull() }
                if (octets.size != 4 || octets.any { it !in 0..255 }) return null
                return text
            }
            if (text.contains(':') && IPV6.matches(text)) return text
            return null
        }

        private suspend fun httpGet(url: String, timeoutMs: Int): String? =
            withContext(Dispatchers.IO) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = timeoutMs
                        readTimeout = timeoutMs
                        useCaches = false
                        instanceFollowRedirects = false
                        setRequestProperty("Connection", "close")
                        setRequestProperty("Accept", "text/plain")
                    }
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                        val buf = CharArray(MAX_BODY_CHARS)
                        val read = reader.read(buf, 0, MAX_BODY_CHARS)
                        if (read <= 0) null else String(buf, 0, read)
                    }
                } catch (e: Exception) {
                    null
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (e: Exception) {
                        // disconnecting a half-open connection is best effort
                    }
                }
            }
    }
}
