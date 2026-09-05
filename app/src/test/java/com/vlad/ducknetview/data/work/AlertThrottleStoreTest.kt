package com.vlad.ducknetview.data.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vlad.ducknetview.domain.baseline.Baseline
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.ServiceRow
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlertThrottleStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var throttle: AlertThrottleStore

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(tmp.newFolder(), "throttle.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        throttle = AlertThrottleStore(store)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun `an untouched ledger is empty`() = runTest {
        assertTrue(throttle.lastAlerted().isEmpty())
    }

    @Test
    fun `a recorded key comes back with its timestamp`() = runTest {
        throttle.record(listOf("tcp|10.0.0.1:8080"), now)
        assertEquals(mapOf("tcp|10.0.0.1:8080" to now), throttle.lastAlerted())
    }

    @Test
    fun `several keys are recorded in one write`() = runTest {
        throttle.record(listOf("tcp|a:1", "tcp|b:2"), now)
        assertEquals(setOf("tcp|a:1", "tcp|b:2"), throttle.lastAlerted().keys)
    }

    @Test
    fun `recording the same key again advances its stamp`() = runTest {
        throttle.record(listOf("tcp|a:1"), now)
        throttle.record(listOf("tcp|a:1"), now + 1000)
        assertEquals(now + 1000, throttle.lastAlerted()["tcp|a:1"])
    }

    /**
     * The point of the ledger: a periodic worker gets a fresh process, so the
     * stamps have to be on disk by the time record() returns rather than only
     * in the instance that wrote them.
     */
    @Test
    fun `recording puts the key on disk before it returns`() = runTest {
        throttle.record(listOf("tcp|a:1"), now)
        assertTrue(file.isFile)
        val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue(onDisk.contains("offBaseline:tcp|a:1"))
    }

    @Test
    fun `entries older than the retention window are evicted`() = runTest {
        throttle.record(listOf("tcp|old:1"), now)
        throttle.record(listOf("tcp|new:2"), now + AlertThrottleStore.RETAIN_MILLIS + 1)
        val stored = throttle.lastAlerted()
        assertFalse(stored.containsKey("tcp|old:1"))
        assertTrue(stored.containsKey("tcp|new:2"))
    }

    @Test
    fun `an entry still inside the retention window is kept`() = runTest {
        throttle.record(listOf("tcp|old:1"), now)
        throttle.record(listOf("tcp|new:2"), now + ServiceScanLogic.RATE_LIMIT_MILLIS)
        assertTrue(throttle.lastAlerted().containsKey("tcp|old:1"))
    }

    @Test
    fun `retention is twice the alert window so eviction cannot un-suppress`() {
        assertEquals(2 * ServiceScanLogic.RATE_LIMIT_MILLIS, AlertThrottleStore.RETAIN_MILLIS)
    }

    @Test
    fun `an empty key list writes nothing`() = runTest {
        throttle.record(emptyList(), now)
        assertTrue(throttle.lastAlerted().isEmpty())
    }

    @Test
    fun `an empty key is skipped`() = runTest {
        throttle.record(listOf(""), now)
        assertTrue(throttle.lastAlerted().isEmpty())
    }

    @Test
    fun `a foreign value of the wrong type is ignored rather than thrown over`() = runTest {
        store.edit { it[stringPreferencesKey("offBaseline:tcp|a:1")] = "not a timestamp" }
        assertTrue(throttle.lastAlerted().isEmpty())
    }

    @Test
    fun `keys written by something else are not reported as alerts`() = runTest {
        store.edit { it[stringPreferencesKey("somethingElse")] = "x" }
        throttle.record(listOf("tcp|a:1"), now)
        assertEquals(setOf("tcp|a:1"), throttle.lastAlerted().keys)
    }

    @Test
    fun `clear empties the ledger`() = runTest {
        throttle.record(listOf("tcp|a:1"), now)
        throttle.clear()
        assertNull(throttle.lastAlerted()["tcp|a:1"])
    }

    @Test
    fun `a recorded key suppresses the next scan of the same listener`() = runTest {
        throttle.record(listOf("tcp|192.168.1.5:8080"), now)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(serviceRow()),
            baseline(),
            throttle.lastAlerted(),
            now + 60_000,
        )
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `the same listener alerts again once the window has passed`() = runTest {
        throttle.record(listOf("tcp|192.168.1.5:8080"), now)
        val alerts = ServiceScanLogic.offBaselineAlerts(
            listOf(serviceRow()),
            baseline(),
            throttle.lastAlerted(),
            now + ServiceScanLogic.RATE_LIMIT_MILLIS,
        )
        assertEquals(1, alerts.size)
    }

    private fun serviceRow() = ServiceRow(proto = Proto.TCP, bindAddr = "192.168.1.5", port = 8080)

    private fun baseline() = Baseline.of(listOf("tcp|127.0.0.1:22"), now)
}
