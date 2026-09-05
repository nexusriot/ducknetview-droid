package com.vlad.ducknetview

import android.net.VpnService
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.engine.vpn.DuckVpnService
import com.vlad.ducknetview.engine.vpn.VpnBridge
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures what the design named as the go/no-go risk for the capture engine:
 * how much throughput the userspace TCP proxy costs, and how much CPU it burns
 * doing it. CPU time is the honest proxy for battery here — the proxy's cost is
 * per-packet work, not radio time, and it is measurable without a power rig.
 *
 * Needs a large file served on the LAN so the number reflects the proxy rather
 * than someone's CDN:
 *
 *   adb shell am instrument -w \
 *     -e benchUrl http://192.168.88.65:8099/blob.bin -e benchMib 64 \
 *     -e class com.vlad.ducknetview.CaptureBenchmarkTest \
 *     com.vlad.ducknetview.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Without benchUrl the whole class skips rather than reporting a number that
 * measures the internet instead of the code.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CaptureBenchmarkTest {

    private data class Run(val seconds: Double, val bytes: Long, val cpuSeconds: Double) {
        val mibPerSecond: Double get() = (bytes / 1048576.0) / seconds.coerceAtLeast(1e-9)
        /** CPU-seconds spent per MiB moved: the number that predicts battery. */
        val cpuPerMib: Double get() = cpuSeconds / (bytes / 1048576.0).coerceAtLeast(1e-9)
    }

    private val args: Bundle by lazy { InstrumentationRegistry.getArguments() }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val benchUrl: String? get() = args.getString("benchUrl")
    private val benchMib: Int get() = args.getString("benchMib")?.toIntOrNull() ?: 32

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun stop() {
        runCatching { scenario?.close() }
        scenario = null
        DuckVpnService.stop(context)
        waitUntil(10_000) { VpnBridge.table == null }
        VpnBridge.captureOwnTraffic = false
    }

    @Test
    fun t1_measureProxyCostAgainstADirectBaseline() {
        val url = benchUrl
        assumeTrue("no -e benchUrl supplied; skipping the benchmark", !url.isNullOrBlank())
        assumeTrue(
            "VPN consent not pre-granted (appops ACTIVATE_VPN)",
            VpnService.prepare(context) == null,
        )

        // The activity stays up for BOTH arms. Foregrounding only the captured
        // arm would charge the proxy for the UI's rendering, and an OEM
        // background manager blocks the service start from the background
        // anyway.
        scenario = ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(3000)

        download(url!!, 4)

        val direct = ArrayList<Run>()
        val captured = ArrayList<Run>()

        // Alternate the arms so a drifting Wi-Fi link biases both equally;
        // a single A-then-B pass measures the link's mood, not the code.
        repeat(ROUNDS) { round ->
            direct += measure(url, benchMib).also { report("direct[$round]", it) }

            VpnBridge.captureOwnTraffic = true
            DuckVpnService.start(context)
            val up = waitUntil(25_000) { VpnBridge.table != null }
            assumeTrue(
                "capture engine did not attach; an OEM background manager may be blocking it",
                up,
            )
            download(url, 4)
            captured += measure(url, benchMib).also { report("captured[$round]", it) }

            DuckVpnService.stop(context)
            waitUntil(15_000) { VpnBridge.table == null }
            VpnBridge.captureOwnTraffic = false
        }

        val dThroughput = median(direct.map { it.mibPerSecond })
        val cThroughput = median(captured.map { it.mibPerSecond })
        val dCpu = median(direct.map { it.cpuPerMib })
        val cCpu = median(captured.map { it.cpuPerMib })

        println("BENCH median_direct_throughput=%.2f MiB/s".format(dThroughput))
        println("BENCH median_captured_throughput=%.2f MiB/s".format(cThroughput))
        println("BENCH throughput_ratio=%.3f".format(cThroughput / dThroughput.coerceAtLeast(1e-9)))
        println("BENCH median_direct_cpu_per_mib=%.4f s".format(dCpu))
        println("BENCH median_captured_cpu_per_mib=%.4f s".format(cCpu))
        println("BENCH cpu_multiplier=%.2fx".format(cCpu / dCpu.coerceAtLeast(1e-9)))

        // A floor, not a target: this fails only if the proxy has collapsed to
        // unusable, so it stays a regression guard rather than a flaky perf
        // assertion on a shared Wi-Fi link.
        assertTrue(
            "captured throughput $cThroughput MiB/s is below the usable floor",
            cThroughput > 0.5,
        )
        assertTrue("captured transfers moved no data", captured.all { it.bytes > 0 })
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }

    private fun measure(url: String, mib: Int): Run {
        val cpuBefore = processCpuSeconds()
        val startedAt = System.nanoTime()
        val bytes = download(url, mib)
        val seconds = (System.nanoTime() - startedAt) / 1e9
        return Run(seconds, bytes, processCpuSeconds() - cpuBefore)
    }

    private fun download(url: String, mib: Int): Long {
        val limit = mib.toLong() * 1048576L
        var total = 0L
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Connection", "close")
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (total < limit) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        return total
    }

    /**
     * utime + stime for this process from /proc/self/stat. The VpnService runs
     * in this same process, so the proxy's work is included.
     */
    private fun processCpuSeconds(): Double = runCatching {
        val fields = File("/proc/self/stat").readText()
        // The comm field can contain spaces and parentheses, so index from the
        // closing paren rather than splitting the whole line.
        val after = fields.substring(fields.lastIndexOf(')') + 2).split(" ")
        val utime = after[11].toLong()
        val stime = after[12].toLong()
        (utime + stime) / TICKS_PER_SECOND
    }.getOrDefault(0.0)

    private fun report(label: String, r: Run) {
        println(
            "BENCH %s throughput=%.2f MiB/s bytes=%d seconds=%.2f cpu=%.3fs cpu_per_mib=%.4fs"
                .format(label, r.mibPerSecond, r.bytes, r.seconds, r.cpuSeconds, r.cpuPerMib)
        )
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(200)
        }
        return predicate()
    }

    private companion object {
        /** USER_HZ is 100 on every Android device in practice. */
        const val TICKS_PER_SECOND = 100.0
        const val ROUNDS = 3
    }
}
