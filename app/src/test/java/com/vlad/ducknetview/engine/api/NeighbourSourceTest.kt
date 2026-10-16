package com.vlad.ducknetview.engine.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.model.NeighbourSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which source a neighbour row credits.
 *
 * Found on the M10 rather than by a test: a Canon printer whose name came
 * from an mDNS record was labelled "via ping", because the label was set by
 * whichever source reached the row first instead of by what it could
 * actually account for. Most hosts on a real network answer both ways, so
 * this was wrong for most rows — and wrong in the one direction that matters,
 * since a ping cannot produce a name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NeighbourSourceTest {

    private val scanner =
        NeighbourScanner(ApplicationProvider.getApplicationContext<Context>())

    private fun row(rtt: Int, services: List<String>) =
        NeighbourRow(address = "192.168.1.5", rttMillis = rtt, services = services)

    @Test
    fun `an echo reply alone is ping`() {
        assertEquals(NeighbourSource.PING, scanner.sourceOf(row(rtt = 7, services = emptyList())))
    }

    @Test
    fun `a name with no echo reply is mDNS`() {
        assertEquals(NeighbourSource.MDNS, scanner.sourceOf(row(rtt = -1, services = listOf("ipp"))))
    }

    @Test
    fun `a host that answered both ways credits both`() {
        assertEquals(NeighbourSource.BOTH, scanner.sourceOf(row(rtt = 75, services = listOf("ipp"))))
    }

    @Test
    fun `a zero millisecond reply is still a reply`() {
        // -1 means "no answer"; 0 means "fast". Treating the sentinel as a
        // number is how an unreachable host would sort to the top of a
        // latency column, and how one would be credited to the wrong source.
        assertEquals(NeighbourSource.PING, scanner.sourceOf(row(rtt = 0, services = emptyList())))
        assertEquals(NeighbourSource.BOTH, scanner.sourceOf(row(rtt = 0, services = listOf("ssh"))))
    }
}
