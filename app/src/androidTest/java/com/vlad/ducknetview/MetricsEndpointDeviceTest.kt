package com.vlad.ducknetview

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns the metrics endpoint on through the real settings store and scrapes it
 * over a real socket on the device. A unit test can prove the server serves;
 * only this proves the setting actually reaches it in the shipped wiring.
 */
@RunWith(AndroidJUnit4::class)
class MetricsEndpointDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun enablingTheSettingMakesTheEndpointServeMetrics() {
        val app = context.applicationContext as DuckApp
        val port = 9191

        runBlocking {
            app.deps.settings.update { it.copy(metricsEnabled = true, metricsPort = port) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val body = waitForBody("http://127.0.0.1:$port/metrics", 20_000)
            assertTrue("endpoint served nothing", !body.isNullOrBlank())
            assertTrue("missing ducknetview_up", body!!.contains("ducknetview_up"))
            assertTrue("missing HELP lines", body.contains("# HELP"))
            assertTrue("missing TYPE lines", body.contains("# TYPE"))
            println("METRICS bytes=${body.length}")
            println("METRICS sample=" + body.lineSequence().take(12).joinToString(" | "))
        } finally {
            scenario.close()
            runBlocking { app.deps.settings.update { it.copy(metricsEnabled = false) } }
        }
    }

    /**
     * Holds the endpoint open so a scrape from another machine can prove LAN
     * reachability. Skipped unless -e holdSeconds is supplied, since a test
     * that sleeps for half a minute has no business running by default.
     */
    @Test
    fun holdOpenForAnExternalScrape() {
        val hold = InstrumentationRegistry.getArguments().getString("holdSeconds")?.toIntOrNull()
        assumeTrue("no -e holdSeconds supplied", hold != null)
        val app = context.applicationContext as DuckApp
        val port = 9191
        runBlocking {
            app.deps.settings.update { it.copy(metricsEnabled = true, metricsPort = port) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            waitForBody("http://127.0.0.1:$port/metrics", 20_000)
            println("METRICS holding on port $port for ${hold}s")
            Thread.sleep(hold!! * 1000L)
        } finally {
            scenario.close()
            runBlocking { app.deps.settings.update { it.copy(metricsEnabled = false) } }
        }
    }

    private fun waitForBody(url: String, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val body = runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 2000
                    c.readTimeout = 3000
                    if (c.responseCode == 200) {
                        c.inputStream.bufferedReader().use(BufferedReader::readText)
                    } else {
                        null
                    }
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) return body
            Thread.sleep(500)
        }
        return null
    }
}
