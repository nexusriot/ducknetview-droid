package com.vlad.ducknetview.engine.api

import com.vlad.ducknetview.domain.model.LatencyMethod
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which measurement the prober makes, and what it says it made.
 *
 * The two are not interchangeable: an ICMP echo times the network, a TCP
 * handshake also times the peer's accept path. A device whose kernel refuses
 * echo sockets has to fall back without the user being told a wrong story.
 */
class LatencyProberEchoTest {

    private class FakeEcho(
        private val answer: Int?,
        val asked: MutableList<String> = ArrayList(),
    ) : EchoProbe {
        override suspend fun rttMillis(host: String, timeoutMs: Int): Int? {
            asked += host
            return answer
        }
    }

    @Test
    fun `the gateway is measured by echo when the kernel allows it`() = runBlocking {
        val echo = FakeEcho(17)
        val prober = LatencyProber(echo = echo)

        val results = prober.probeAll(targets = emptyList(), gateway = "192.168.1.1")

        val gw = results.single()
        assertEquals(LatencyMethod.ICMP, gw.method)
        assertEquals(17, gw.millis)
        assertTrue(gw.ok)
        assertNull(gw.note)
        // The port in the target exists only so the TCP fallback has one; the
        // echo is addressed to the host.
        assertEquals(listOf("192.168.1.1"), echo.asked)
    }

    @Test
    fun `a kernel that refuses echo sockets falls back to the handshake`() = runBlocking {
        // A closed port on loopback answers instantly with a refusal, which the
        // prober already counts as a valid round trip.
        val port = ServerSocket(0).use { it.localPort }
        val prober = LatencyProber(echo = FakeEcho(null))

        val results = prober.probeAll(targets = emptyList(), gateway = "127.0.0.1")
        val gw = results.single()

        assertEquals(LatencyMethod.TCP, gw.method)
        assertTrue(port > 0)
    }

    @Test
    fun `a target the user wrote as host and port is never answered with an echo`() = runBlocking {
        // Naming a port is naming a question about that port. Replying with an
        // echo to the host would measure something else and label it the same.
        val echo = FakeEcho(5)
        val prober = LatencyProber(echo = echo)

        val results = prober.probeAll(targets = listOf("127.0.0.1:9"), gateway = null)

        assertEquals(LatencyMethod.TCP, results.single().method)
        assertTrue(echo.asked.isEmpty())
    }

    @Test
    fun `an echo probe that throws is treated as no answer, not as a failure`() = runBlocking {
        val throwing = object : EchoProbe {
            override suspend fun rttMillis(host: String, timeoutMs: Int): Int =
                throw IllegalStateException("socket vanished")
        }
        val prober = LatencyProber(echo = throwing)

        val results = prober.probeAll(targets = emptyList(), gateway = "127.0.0.1")

        assertEquals(LatencyMethod.TCP, results.single().method)
    }

    @Test
    fun `the default prober makes no echo attempt at all`() = runBlocking {
        // EchoProbe.Unavailable is the stand-in for a device with no ping
        // socket, so the default behaviour is exactly what it was before.
        val prober = LatencyProber()
        val results = prober.probeAll(targets = emptyList(), gateway = "127.0.0.1")
        assertEquals(LatencyMethod.TCP, results.single().method)
    }

    @Test
    fun `an echo reading still records history for the sparkline`() = runBlocking {
        val prober = LatencyProber(echo = FakeEcho(9))
        prober.probeAll(targets = emptyList(), gateway = "192.168.1.1")
        prober.probeAll(targets = emptyList(), gateway = "192.168.1.1")

        val history = prober.history("192.168.1.1:80")
        assertEquals(listOf(9f, 9f), history)
    }
}
