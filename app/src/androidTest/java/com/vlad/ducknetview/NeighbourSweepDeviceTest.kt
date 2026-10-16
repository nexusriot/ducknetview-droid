package com.vlad.ducknetview

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vlad.ducknetview.domain.model.NeighbourRow
import com.vlad.ducknetview.domain.model.NeighbourSource
import com.vlad.ducknetview.domain.net.Subnet
import com.vlad.ducknetview.engine.api.NeighbourScanner
import com.vlad.ducknetview.engine.vpn.PingSockets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The LAN sweep against a real network.
 *
 * Everything in [NeighbourScanner] that a JVM test can reach is pure
 * arithmetic, which `SubnetTest` already covers. What only a device can
 * answer is whether the unprivileged ping socket actually reaches the link,
 * whether `NsdManager` returns anything, and whether the two sources are
 * credited correctly when a host answers both ways — which is precisely
 * where the first version was wrong, labelling an mDNS-named printer "via
 * ping".
 *
 * Every assertion here is conditional on the link answering at all: a wired
 * CI device, a guest network with client isolation, or a kernel that refuses
 * ping sockets are all legitimate environments, and the suite skips rather
 * than reporting a failure it cannot distinguish from one.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class NeighbourSweepDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val app: DuckApp
        get() = context.applicationContext as DuckApp

    private var scenario: ActivityScenario<MainActivity>? = null

    /**
     * The engine is started by the ViewModel, so without an Activity there is
     * no poll loop and the snapshot stays at its empty default — which these
     * tests read to find the link to sweep.
     *
     * The first version of this file had no such setup and every case
     * skipped with "no IPv4 prefix on the default link", reporting `OK (7
     * tests)` while doing nothing at all. An assumption that is always
     * violated is indistinguishable from a passing suite in the runner's
     * output, which is the worst failure mode a test can have.
     */
    @Before
    fun startEngine() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (app.deps.engine.snapshot.value.networks.isNotEmpty()) break
            Thread.sleep(200)
        }
    }

    @After
    fun stopActivity() {
        runCatching { scenario?.close() }
        scenario = null
    }

    /**
     * Guards the setup above: if this device really has no IPv4 link the
     * suite is entitled to skip, but a skip caused by the engine never
     * having run is a silent hole and must fail instead.
     */
    @Test
    fun t00_theEngineIsRunningSoTheSweepTestsAreNotVacuous() {
        assertTrue(
            "the engine published no networks, so every test in this class " +
                "would skip and the suite would report OK having done nothing",
            app.deps.engine.snapshot.value.networks.isNotEmpty(),
        )
    }

    /** This device's own on-link IPv4 prefix, or null when it has none. */
    private fun subnet(): String? =
        app.deps.engine.snapshot.value.networks
            .firstOrNull { it.isDefault && !it.isNoise }
            ?.addresses
            ?.let(Subnet::firstV4)

    private fun sweep(): List<NeighbourRow> {
        val prefix = subnet()
        assumeTrue("no IPv4 prefix on the default link to sweep", prefix != null)
        assumeTrue(
            "this kernel does not allow unprivileged ICMP sockets " +
                "(net.ipv4.ping_group_range)",
            PingSockets.open(4, timeoutMs = 1) != null,
        )
        val link = app.deps.engine.snapshot.value.networks.firstOrNull { it.isDefault }
        return runBlocking {
            NeighbourScanner(context).scan(
                subnet = prefix!!,
                gateway = link?.gateway,
                selfAddresses = app.deps.engine.snapshot.value.networks
                    .flatMap { it.addresses }
                    .map { it.substringBefore('/') }
                    .toSet(),
            )
        }
    }

    @Test
    fun t01_theSweepFindsSomethingOnARealLink() {
        val rows = sweep()
        assumeTrue(
            "nothing on this link answered an echo or published mDNS, which a " +
                "guest or client-isolation network is built to do",
            rows.isNotEmpty(),
        )
        assertTrue("a sweep that answers must name addresses", rows.all { it.address.isNotEmpty() })
    }

    @Test
    fun t02_theDeviceFindsItself() {
        val rows = sweep()
        assumeTrue("nothing answered", rows.isNotEmpty())
        // The device's own address is in the swept range by construction and
        // always answers its own echo, so this is the one row that does not
        // depend on what else is on the network.
        val self = rows.firstOrNull { it.isSelf }
        assertNotNull("the sweep did not find this device in its own prefix: ${rows.map { it.address }}", self)
        assertTrue("this device should answer its own echo", self!!.answeredPing)
    }

    @Test
    fun t03_theGatewayIsMarkedWhenItAnswers() {
        val gateway = app.deps.engine.snapshot.value.networks
            .firstOrNull { it.isDefault }?.gateway?.substringBefore('%')
        assumeTrue("no IPv4 gateway on this link", gateway?.count { it == '.' } == 3)
        val rows = sweep()
        val row = rows.firstOrNull { it.address == gateway }
        assumeTrue("the gateway did not answer an echo", row != null)
        assertTrue("the gateway row should be marked as such", row!!.isGateway)
    }

    @Test
    fun t04_everyRowCreditsASourceItsOwnFieldsSupport() {
        // The defect this test exists for: the source used to be whichever
        // one reached the row first, so a Canon printer named from an mDNS
        // record came back labelled "via ping" on the M10 — a claim an echo
        // reply cannot possibly support.
        val rows = sweep()
        assumeTrue("nothing answered", rows.isNotEmpty())
        for (row in rows) {
            val expected = when {
                row.answeredPing && row.fromMdns -> NeighbourSource.BOTH
                row.fromMdns -> NeighbourSource.MDNS
                else -> NeighbourSource.PING
            }
            assertEquals(
                "${row.address} claims ${row.source.label} with rtt=${row.rttMillis} " +
                    "and services=${row.services}",
                expected,
                row.source,
            )
        }
    }

    @Test
    fun t05_aNamedRowWasNamedByMdns() {
        val rows = sweep()
        val named = rows.filter { it.hostname.isNotEmpty() }
        assumeTrue("nothing on this link publishes mDNS", named.isNotEmpty())
        for (row in named) {
            assertTrue(
                "${row.address} has the name '${row.hostname}' but credits only " +
                    "${row.source.label}, which cannot produce one",
                row.source == NeighbourSource.MDNS || row.source == NeighbourSource.BOTH,
            )
        }
    }

    @Test
    fun t06_rowsAreUniqueAndOrderedNumerically() {
        val rows = sweep()
        assumeTrue("nothing answered", rows.isNotEmpty())
        val addresses = rows.map { it.address }
        assertEquals("the same host appeared twice: $addresses", addresses.distinct(), addresses)
        // .9 before .10, which a text sort gets backwards and which is the
        // first thing a reader notices on a real network.
        val keys = addresses.map { a -> a.split('.').fold(0L) { acc, p -> (acc shl 8) or (p.toLongOrNull() ?: 0L) } }
        assertEquals("rows are not in numeric address order: $addresses", keys.sorted(), keys)
    }

    @Test
    fun t07_theBroadcastAddressIsNeverProbed() {
        val prefix = subnet()
        assumeTrue("no IPv4 prefix", prefix != null)
        val hosts = Subnet.hostsOf(prefix!!)
        assumeTrue("prefix too wide to sweep", hosts.isNotEmpty())
        // The one probe on the list that would reach hosts nobody asked
        // about. Asserted against the real prefix rather than a crafted one.
        val last = hosts.last().substringAfterLast('.').toInt()
        assertFalse("the broadcast address is in the sweep list", last == 255)
        assertFalse("the network address is in the sweep list", hosts.first().endsWith(".0"))
    }
}
