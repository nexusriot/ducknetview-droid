package com.vlad.ducknetview.service

import com.vlad.ducknetview.domain.export.Prometheus
import com.vlad.ducknetview.domain.model.NetSnapshot
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Serves [Prometheus] output over plain HTTP on the LAN — the Android answer to
 * the TUI's `--metrics` textfile, for a phone that no cron job can reach.
 *
 * SECURITY: the endpoint is unauthenticated and unencrypted, and what it exposes
 * (interfaces, listeners, remote hosts, per-app traffic) is a fairly complete
 * picture of what this device is doing on the network. It is intended for a
 * trusted home or lab network only, and it never listens until the user turns it
 * on in Settings.
 *
 * Requests are served one at a time on a single accept loop: a scrape is a few
 * kilobytes served in microseconds, and a thread pool would buy nothing but more
 * ways for a hostile client to hold resources open.
 */
class MetricsServer(private val snapshotProvider: () -> NetSnapshot) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var job: Job? = null
    @Volatile private var port: Int? = null

    /** The port actually bound, which differs from the requested one for port 0. */
    val boundPort: Int? get() = port

    /**
     * Binds synchronously so the caller learns about a taken port immediately;
     * only the accept loop moves onto a coroutine.
     */
    @Synchronized
    fun start(requestedPort: Int): Boolean {
        if (server != null) {
            if (port == requestedPort) return true
            stopLocked()
        }
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                // Wildcard bind: the whole point is to be scrapeable from the LAN.
                bind(InetSocketAddress(requestedPort), BACKLOG)
            }
        } catch (e: BindException) {
            // Not "privileged": Android lets an app bind a low port, and this
            // one really did serve on port 80 of the test tablet. Telling the
            // user that an app cannot use the port would send them to change a
            // setting that was never the problem, so the cause a BindException
            // actually has is named first and the caveat follows it.
            fail(
                if (requestedPort in 1..1023) {
                    "port $requestedPort is already in use, or refused to this app; " +
                        "a port above 1023 is less likely to be either"
                } else {
                    "port $requestedPort is already in use"
                }
            )
            return false
        } catch (e: IllegalArgumentException) {
            fail("port $requestedPort is out of range (1-65535)")
            return false
        } catch (e: SecurityException) {
            fail("the system refused to open port $requestedPort")
            return false
        } catch (e: IOException) {
            fail("could not open port $requestedPort: ${e.message ?: e.javaClass.simpleName}")
            return false
        }

        server = socket
        port = socket.localPort
        _lastError.value = null
        _running.value = true
        job = scope.launch { acceptLoop(socket) }
        return true
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        val socket = server
        server = null
        port = null
        _running.value = false
        // A failure message is about an endpoint that was being asked to run.
        // Only a successful start used to clear it, so after a rejected port
        // the complaint outlived both the switch and the port that caused it:
        // the field could read 9187, the endpoint be off, and the line
        // underneath still say "port 99999 is out of range".
        _lastError.value = null
        // Closing the listening socket releases the port at once and makes the
        // blocked accept() throw, which is how the loop learns to finish.
        runCatching { socket?.close() }
        job?.cancel()
        job = null
    }

    private fun fail(message: String) {
        _lastError.value = message
        _running.value = false
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            try {
                client.soTimeout = READ_TIMEOUT_MS
                handle(client)
            } catch (e: Exception) {
                // One bad client — a malformed request, a peer that vanished
                // mid-write — must never end the accept loop.
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        val requestLine = readLine(input, MAX_REQUEST_LINE)
        if (requestLine == null) {
            respond(output, "400 Bad Request", TEXT, "bad request\n")
            return
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2 || parts[0].isEmpty() || !parts[1].startsWith("/")) {
            respond(output, "400 Bad Request", TEXT, "bad request\n")
            return
        }
        if (!drainHeaders(input)) {
            respond(output, "431 Request Header Fields Too Large", TEXT, "headers too large\n")
            return
        }

        val method = parts[0]
        val path = parts[1].substringBefore('?')
        if (method != "GET") {
            respond(output, "405 Method Not Allowed", TEXT, "method not allowed\n", listOf("Allow: GET"))
            return
        }
        when (path) {
            "/metrics" -> respond(output, "200 OK", METRICS_TYPE, render())
            "/" -> respond(output, "200 OK", HTML, INDEX)
            else -> respond(output, "404 Not Found", TEXT, "not found\n")
        }
    }

    private fun render(): String = runCatching { Prometheus.render(snapshotProvider()) }
        .getOrElse { "# ducknetview: snapshot unavailable\nducknetview_up 0\n" }

    /**
     * Reads one CRLF- or LF-terminated line, refusing anything past [cap] bytes
     * rather than growing a buffer for whatever a client chooses to send.
     */
    private fun readLine(input: InputStream, cap: Int): String? {
        val sb = StringBuilder(64)
        var count = 0
        while (true) {
            val c = try {
                input.read()
            } catch (e: SocketTimeoutException) {
                return null
            }
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            count++
            if (count > cap) return null
            when (c) {
                '\n'.code -> return sb.toString()
                '\r'.code -> Unit
                else -> sb.append(c.toChar())
            }
        }
    }

    /** Consumes headers up to the blank line; false when the client sent too many. */
    private fun drainHeaders(input: InputStream): Boolean {
        var budget = MAX_HEADER_BYTES
        while (true) {
            // A header line past the cap, a timeout or a hangup all end the
            // headers here: the request is answered from what was understood
            // rather than by reading whatever else the client wants to send.
            val line = readLine(input, MAX_REQUEST_LINE) ?: return true
            if (line.isEmpty()) return true
            budget -= line.length + 2
            if (budget <= 0) return false
        }
    }

    private fun respond(
        output: OutputStream,
        status: String,
        contentType: String,
        body: String,
        extraHeaders: List<String> = emptyList(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder(256)
            .append("HTTP/1.1 ").append(status).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Connection: close\r\n")
        for (h in extraHeaders) head.append(h).append("\r\n")
        head.append("\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    companion object {
        const val METRICS_TYPE = "text/plain; version=0.0.4; charset=utf-8"

        /**
         * The scrape URL to show the user: a loopback address is useless to a
         * Prometheus running anywhere else, so it is never offered.
         */
        fun lanUrl(addresses: List<String>, port: Int): String? {
            val cleaned = addresses.map { it.substringBefore('/').trim() }.filter { it.isNotEmpty() }
            cleaned.firstOrNull { it.count { c -> c == '.' } == 3 && !it.startsWith("127.") }
                ?.let { return "http://$it:$port/metrics" }
            cleaned.firstOrNull {
                it.contains(':') && it != "::1" && !it.lowercase().startsWith("fe80")
            }?.let { return "http://[$it]:$port/metrics" }
            return null
        }

        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val BACKLOG = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_REQUEST_LINE = 8 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024

        private val INDEX = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><title>ducknetview</title></head>
            <body><h1>ducknetview</h1>
            <p>Prometheus metrics: <a href="/metrics">/metrics</a></p>
            <p>This endpoint is unauthenticated. Serve it on a trusted network only.</p>
            </body></html>

        """.trimIndent()
    }
}
