package com.vlad.ducknetview.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vlad.ducknetview.data.settings.SettingsRepository
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.ThroughputMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dir = tmp.newFolder()
        store = PreferenceDataStoreFactory.create(scope = storeScope) {
            File(dir, "settings.preferences_pb")
        }
        repo = SettingsRepository(store)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun everythingChanged() = AppSettings(
        intervalSeconds = 10,
        paused = true,
        rateUnit = RateUnit.BITS,
        throughputMode = ThroughputMode.TOTAL,
        searchMode = SearchMode.HIGHLIGHT,
        hideNoise = false,
        revDns = true,
        groupByHost = true,
        showClosed = true,
        lastTab = "connections",
        connsSortCol = "tx",
        connsSortDesc = false,
        appsSortCol = "name",
        appsSortDesc = false,
        servicesSortCol = "proto",
        servicesSortDesc = true,
        externalIpEnabled = false,
        latencyTargets = listOf("1.1.1.1", "gateway", "example.org"),
        watchlist = listOf("10.0.0.0/8", """re:^ads\."""),
        baseline = listOf("tcp|0.0.0.0:8080", "udp|[::]:5353"),
        baselineAt = 1_700_000_000_000L,
        blockedUids = setOf(10001, 10002),
        excludedUids = setOf(1000),
        scanFullRange = true,
        metricsEnabled = true,
        metricsPort = 19187,
        webhookUrl = "https://example.org/hook?x=1&y=2",
        broadcastOnAlert = true,
        alertAppBps = 1_000_000L,
        alertConnBps = 2_000_000L,
        alertRttMs = 250,
        alertConnGrowthPolls = 5,
        alertFanoutHosts = 20,
        alertsAckedAt = 1_700_000_001_000L,
        onboardingShown = true,
    )

    @Test
    fun `an untouched store yields the defaults`() = runTest {
        assertEquals(AppSettings(), repo.snapshot())
    }

    @Test
    fun `every field round-trips`() = runTest {
        val wanted = everythingChanged()
        assertNotEquals(AppSettings(), wanted)
        repo.update { wanted }
        assertEquals(wanted, repo.snapshot())
    }

    @Test
    fun `the settings flow sees the update`() = runTest {
        repo.update { it.copy(lastTab = "events", intervalSeconds = 5) }
        val s = repo.settings.first()
        assertEquals("events", s.lastTab)
        assertEquals(5, s.intervalSeconds)
    }

    @Test
    fun `collection values containing the codec separators round-trip`() = runTest {
        val awkward = listOf("a;b", "c=d", """e\f""", "", "日本語 🦆")
        repo.update { it.copy(watchlist = awkward, baseline = awkward, latencyTargets = awkward) }
        val s = repo.snapshot()
        assertEquals(awkward, s.watchlist)
        assertEquals(awkward, s.baseline)
        assertEquals(awkward, s.latencyTargets)
    }

    @Test
    fun `empty collections round-trip as empty, not as a single blank entry`() = runTest {
        repo.update { everythingChanged() }
        repo.update {
            it.copy(
                watchlist = emptyList(),
                baseline = emptyList(),
                latencyTargets = emptyList(),
                blockedUids = emptySet(),
                excludedUids = emptySet(),
            )
        }
        val s = repo.snapshot()
        assertEquals(emptyList<String>(), s.watchlist)
        assertEquals(emptyList<String>(), s.baseline)
        assertEquals(emptyList<String>(), s.latencyTargets)
        assertEquals(emptySet<Int>(), s.blockedUids)
        assertEquals(emptySet<Int>(), s.excludedUids)
    }

    @Test
    fun `uid sets round-trip`() = runTest {
        repo.update { it.copy(blockedUids = setOf(0, 1000, 10123), excludedUids = setOf(10999)) }
        val s = repo.snapshot()
        assertEquals(setOf(0, 1000, 10123), s.blockedUids)
        assertEquals(setOf(10999), s.excludedUids)
    }

    @Test
    fun `sequential updates compose`() = runTest {
        repo.update { it.copy(intervalSeconds = 3) }
        repo.update { it.copy(paused = true) }
        repo.update { it.copy(watchlist = it.watchlist + "1.2.3.4") }
        val s = repo.snapshot()
        assertEquals(3, s.intervalSeconds)
        assertEquals(true, s.paused)
        assertEquals(listOf("1.2.3.4"), s.watchlist)
    }

    @Test
    fun `a stored value of the wrong type falls back to the default without throwing`() = runTest {
        repo.update { everythingChanged() }
        store.edit { it[intPreferencesKey("watchlist")] = 5 }

        val s = repo.snapshot()
        assertEquals(AppSettings().watchlist, s.watchlist)
        assertEquals(everythingChanged().baseline, s.baseline)
        assertEquals(everythingChanged().metricsPort, s.metricsPort)
    }

    @Test
    fun `an unparseable uid set falls back to the default without throwing`() = runTest {
        repo.update { everythingChanged() }
        store.edit { it[stringPreferencesKey("excludedUids")] = "1;oops;3;" }

        val s = repo.snapshot()
        assertEquals(AppSettings().excludedUids, s.excludedUids)
        assertEquals(setOf(10001, 10002), s.blockedUids)
    }

    @Test
    fun `an unknown enum name falls back to the default`() = runTest {
        repo.update { everythingChanged() }
        store.edit { it[stringPreferencesKey("rateUnit")] = "PETABITS" }
        assertEquals(AppSettings().rateUnit, repo.snapshot().rateUnit)
    }

    @Test
    fun `writing after a corrupt value heals it`() = runTest {
        repo.update { everythingChanged() }
        store.edit { it[intPreferencesKey("watchlist")] = 5 }
        repo.update { it.copy(watchlist = listOf("192.168.0.0/16")) }
        assertEquals(listOf("192.168.0.0/16"), repo.snapshot().watchlist)
    }

    @Test
    fun `a partially written store keeps the defaults for absent keys`() = runTest {
        store.edit { it[stringPreferencesKey("lastTab")] = "usage" }
        val s = repo.snapshot()
        assertEquals("usage", s.lastTab)
        assertEquals(AppSettings().intervalSeconds, s.intervalSeconds)
        assertEquals(AppSettings().metricsPort, s.metricsPort)
        assertEquals(AppSettings().latencyTargets, s.latencyTargets)
    }
}
