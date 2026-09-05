package com.vlad.ducknetview.service

import android.app.Notification
import android.content.Context
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.NetSnapshot
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.Talker
import com.vlad.ducknetview.domain.model.Throughput
import com.vlad.ducknetview.domain.rates.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun conn(port: Int) = ConnRow(
        key = "tcp:10.0.0.2:$port-93.184.216.34:443",
        proto = Proto.TCP,
        localAddr = "10.0.0.2",
        localPort = port,
        remoteAddr = "93.184.216.34",
        remotePort = 443,
        state = ConnState.ESTABLISHED,
        uid = 10123,
    )

    private fun busy() = NetSnapshot(
        total = Throughput(rxBps = 2_048L, txBps = 1_024L),
        conns = listOf(conn(44321), conn(44322), conn(44323)),
        topApps = listOf(
            Talker("10123", "Browser", rx = 900_000, tx = 100_000),
            Talker("10456", "Mail", rx = 5_000, tx = 2_000),
        ),
        topHosts = listOf(Talker("93.184.216.34", "example.com", rx = 800_000, tx = 90_000)),
    )

    private fun textOf(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()

    private fun bigTextOf(n: Notification): String =
        (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "").toString()

    @Test
    fun `an empty snapshot builds without throwing`() {
        val n = Notifications.engineNotification(context, NetSnapshot())
        assertNotNull(n)
        assertTrue(textOf(n).contains(Units.rate(0L, RateUnit.BYTES)))
    }

    @Test
    fun `a populated snapshot builds without throwing`() {
        val n = Notifications.engineNotification(context, busy())
        assertNotNull(n)
        assertTrue(textOf(n).isNotEmpty())
    }

    @Test
    fun `the collapsed text carries the formatted rate`() {
        val n = Notifications.engineNotification(context, busy())
        val text = textOf(n)
        assertTrue(text, text.contains(Units.rate(2_048L, RateUnit.BYTES)))
        assertTrue(text, text.contains(Units.rate(1_024L, RateUnit.BYTES)))
    }

    @Test
    fun `the collapsed text carries the connection count`() {
        assertTrue(textOf(Notifications.engineNotification(context, busy())).contains("3 conns"))
    }

    @Test
    fun `a single connection is not pluralised`() {
        val snap = NetSnapshot(conns = listOf(conn(1)))
        assertTrue(Notifications.engineText(snap).contains("1 conn"))
        assertFalse(Notifications.engineText(snap).contains("1 conns"))
    }

    @Test
    fun `the connection count is omitted when there are none`() {
        assertFalse(Notifications.engineText(NetSnapshot()).contains("conn"))
    }

    @Test
    fun `the collapsed text names the top talker`() {
        assertTrue(Notifications.engineText(busy()).contains("top Browser"))
    }

    @Test
    fun `an app outranks a host as the headline talker`() {
        assertEquals("Browser", Notifications.topTalker(busy())?.label)
    }

    @Test
    fun `a host is the headline when no app is attributed`() {
        val snap = NetSnapshot(topHosts = listOf(Talker("1.1.1.1", "one.one.one.one", 10, 10)))
        assertEquals("one.one.one.one", Notifications.topTalker(snap)?.label)
    }

    @Test
    fun `a blank talker label is never used as the headline`() {
        val snap = NetSnapshot(
            topApps = listOf(Talker("10000", "  ", 10, 10)),
            topHosts = listOf(Talker("1.1.1.1", "one.one.one.one", 5, 5)),
        )
        assertEquals("one.one.one.one", Notifications.topTalker(snap)?.label)
    }

    @Test
    fun `there is no talker at all for an empty snapshot`() {
        assertEquals(null, Notifications.topTalker(NetSnapshot()))
    }

    @Test
    fun `the bits unit changes the rendered rate`() {
        val bytes = Notifications.engineText(busy(), RateUnit.BYTES)
        val bits = Notifications.engineText(busy(), RateUnit.BITS)
        assertTrue(bits.contains(Units.rate(2_048L, RateUnit.BITS)))
        assertTrue(bytes != bits)
    }

    @Test
    fun `the expanded text lists top apps and hosts`() {
        val big = bigTextOf(Notifications.engineNotification(context, busy()))
        assertTrue(big, big.contains("Apps: "))
        assertTrue(big, big.contains("Browser"))
        assertTrue(big, big.contains("Hosts: "))
        assertTrue(big, big.contains("example.com"))
    }

    @Test
    fun `the expanded text starts with the collapsed line`() {
        val snap = busy()
        assertTrue(Notifications.engineBigText(snap).startsWith(Notifications.engineText(snap)))
    }

    @Test
    fun `the expanded text says so when nothing is attributed yet`() {
        assertTrue(Notifications.engineBigText(NetSnapshot()).contains("No traffic attributed yet"))
    }

    @Test
    fun `the expanded talker list is capped`() {
        val many = (1..10).map { Talker("uid$it", "App $it", rx = it * 1000L, tx = 0L) }
        val big = Notifications.engineBigText(NetSnapshot(topApps = many))
        assertTrue(big.contains("App 1"))
        assertFalse(big.contains("App 9"))
    }

    @Test
    fun `the three-argument wrapper still builds a notification`() {
        val n = Notifications.engineNotification(context, 4_096L, 512L)
        assertNotNull(n)
        assertTrue(textOf(n).contains(Units.rate(4_096L, RateUnit.BYTES)))
        assertTrue(textOf(n).contains(Units.rate(512L, RateUnit.BYTES)))
    }

    @Test
    fun `the wrapper agrees with the snapshot overload`() {
        val viaWrapper = textOf(Notifications.engineNotification(context, 4_096L, 512L))
        val viaSnapshot = textOf(
            Notifications.engineNotification(
                context,
                NetSnapshot(total = Throughput(4_096L, 512L)),
            )
        )
        assertEquals(viaSnapshot, viaWrapper)
    }

    @Test
    fun `the engine notification is ongoing and silent`() {
        val n = Notifications.engineNotification(context, busy())
        assertTrue((n.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
    }

    @Test
    fun `the engine notification carries a stop action`() {
        val n = Notifications.engineNotification(context, busy())
        val labels = (n.actions ?: emptyArray()).map { it.title.toString() }
        assertTrue(labels.toString(), labels.contains("Stop capture"))
    }

    @Test
    fun `the stop action has a pending intent`() {
        val n = Notifications.engineNotification(context, busy())
        val action = n.actions.first { it.title.toString() == "Stop capture" }
        assertNotNull(action.actionIntent)
    }

    @Test
    fun `the engine notification has a content intent back into the app`() {
        assertNotNull(Notifications.engineNotification(context, busy()).contentIntent)
    }

    @Test
    fun `ensuring channels twice is harmless`() {
        Notifications.ensureChannels(context)
        Notifications.ensureChannels(context)
        assertNotNull(Notifications.engineNotification(context, NetSnapshot()))
    }

    @Test
    fun `the stop receiver ignores an unrelated action`() {
        val receiver = StopCaptureReceiver()
        receiver.onReceive(context, android.content.Intent("com.example.SOMETHING_ELSE"))
        receiver.onReceive(context, null)
    }

    @Test
    fun `the stop receiver accepts its own action without throwing`() {
        val receiver = StopCaptureReceiver()
        receiver.onReceive(
            context,
            android.content.Intent(StopCaptureReceiver.ACTION_STOP_CAPTURE),
        )
    }

    @Test
    fun `the tile is active only while capture runs`() {
        assertEquals(Tile.STATE_ACTIVE, CaptureTile.stateFor(true))
        assertEquals(Tile.STATE_INACTIVE, CaptureTile.stateFor(false))
    }

    @Test
    fun `the tile subtitle follows the same state`() {
        assertEquals("Capturing", CaptureTile.subtitleFor(true))
        assertEquals("Off", CaptureTile.subtitleFor(false))
    }

    @Test
    fun `a running engine always stops, consent or not`() {
        assertEquals(CaptureTile.Action.STOP, CaptureTile.actionFor(true, consentNeeded = false))
        assertEquals(CaptureTile.Action.STOP, CaptureTile.actionFor(true, consentNeeded = true))
    }

    @Test
    fun `a stopped engine with consent already granted starts directly`() {
        assertEquals(CaptureTile.Action.START, CaptureTile.actionFor(false, consentNeeded = false))
    }

    @Test
    fun `a stopped engine needing consent defers to the activity`() {
        assertEquals(
            CaptureTile.Action.ASK_CONSENT,
            CaptureTile.actionFor(false, consentNeeded = true),
        )
    }

    @Test
    fun `the tile failure message names the app so a dropped start is visible`() {
        assertTrue(CaptureTile.START_FAILED.contains("ducknetview"))
        assertTrue(CaptureTile.START_FAILED.isNotBlank())
    }
}
