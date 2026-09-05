package com.vlad.ducknetview.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.data.db.ClosedConnEntity
import com.vlad.ducknetview.data.db.DailyUsageEntity
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.data.db.EventEntity
import com.vlad.ducknetview.domain.model.ClosedConn
import com.vlad.ducknetview.domain.model.ConnRow
import com.vlad.ducknetview.domain.model.ConnState
import com.vlad.ducknetview.domain.model.Event
import com.vlad.ducknetview.domain.model.EventKind
import com.vlad.ducknetview.domain.model.EventLevel
import com.vlad.ducknetview.domain.model.Proto
import com.vlad.ducknetview.domain.model.Scope
import com.vlad.ducknetview.domain.usage.DailyUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoriesTest {

    private lateinit var db: DuckDatabase
    private lateinit var scope: CoroutineScope

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DuckDatabase::class.java,
        ).allowMainThreadQueries().build()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** record() is fire-and-forget, so tests must wait for the work it spawned. */
    private suspend fun drain() {
        scope.coroutineContext.job.children.toList().forEach { it.join() }
    }

    /** Rebases a small ordinal onto [now] so record()'s retention prune keeps it. */
    private fun at(n: Long) = now - 10_000L + n

    private fun eventRepo() = EventRepository(db.events(), scope) { now }

    private fun event(at: Long, level: EventLevel = EventLevel.INFO, subject: String = "s") =
        Event(at = at, level = level, kind = EventKind.NEW_PUBLIC_HOST, subject = subject, detail = "d")

    private fun closed(closedAt: Long, port: Int) = ClosedConn(
        row = ConnRow(
            key = "k$port",
            proto = Proto.TCP,
            localAddr = "10.0.0.2",
            localPort = port,
            remoteAddr = "1.1.1.1",
            remotePort = 443,
            state = ConnState.CLOSED,
            uid = 10001,
            scope = Scope.PUBLIC,
        ),
        closedAt = closedAt,
        lifetimeMillis = 10L,
        finalRx = 1L,
        finalTx = 2L,
    )

    @Test
    fun `event repository records and exposes events newest first`() = runTest {
        val repo = eventRepo()
        repo.record(listOf(event(at(100), subject = "a"), event(at(300), subject = "c"), event(at(200), subject = "b")))
        drain()
        assertEquals(listOf("c", "b", "a"), repo.recent.first().map { it.subject })
    }

    @Test
    fun `recording an empty list does nothing`() = runTest {
        val repo = eventRepo()
        repo.record(emptyList())
        drain()
        assertEquals(0, db.events().count())
    }

    @Test
    fun `recording prunes events older than the retention window`() = runTest {
        db.events().insert(
            listOf(
                EventEntity(at = now - EventRepository.RETENTION_MILLIS - 1, level = "INFO", kind = "service_up", subject = "ancient", detail = ""),
                EventEntity(at = now - 1000, level = "INFO", kind = "service_up", subject = "fresh", detail = ""),
            )
        )
        val repo = eventRepo()
        repo.record(listOf(event(now, subject = "newest")))
        drain()
        assertEquals(listOf("newest", "fresh"), repo.recent.first().map { it.subject })
    }

    @Test
    fun `unacked alert count ignores informational events`() = runTest {
        val repo = eventRepo()
        repo.record(
            listOf(
                event(at(100), EventLevel.INFO),
                event(at(200), EventLevel.WARN),
                event(at(300), EventLevel.ALERT),
            )
        )
        drain()
        assertEquals(2, repo.unackedAlertCount(MutableStateFlow(0L)).first())
    }

    @Test
    fun `unacked alert count follows the ack timestamp`() = runTest {
        val repo = eventRepo()
        repo.record(listOf(event(at(200), EventLevel.WARN), event(at(300), EventLevel.ALERT)))
        drain()
        val acked = MutableStateFlow(0L)
        val counts = repo.unackedAlertCount(acked)
        assertEquals(2, counts.first())
        acked.value = at(250)
        assertEquals(1, counts.first())
        acked.value = at(300)
        assertEquals(0, counts.first())
    }

    @Test
    fun `clearing the event log empties it`() = runTest {
        val repo = eventRepo()
        repo.record(listOf(event(at(1)), event(at(2))))
        drain()
        repo.clear()
        assertEquals(emptyList<Event>(), repo.recent.first())
    }

    @Test
    fun `csv export includes every stored event`() = runTest {
        val repo = eventRepo()
        repo.record(listOf(event(at(100), subject = "host-a"), event(at(200), subject = "host-b")))
        drain()
        val csv = repo.exportCsv()
        assertTrue(csv, csv.contains("host-a"))
        assertTrue(csv, csv.contains("host-b"))
    }

    @Test
    fun `closed connection repository trims to the newest two hundred`() = runTest {
        val repo = ClosedConnRepository(db.closedConns(), scope)
        repo.record((1..250).map { closed(closedAt = it * 100L, port = it) })
        drain()
        val rows = repo.recent.first()
        assertEquals(ClosedConnRepository.LIMIT, db.closedConns().count())
        assertEquals(250, rows.first().row.localPort)
        assertEquals(51, rows.last().row.localPort)
    }

    @Test
    fun `closed connection repository ignores an empty batch`() = runTest {
        val repo = ClosedConnRepository(db.closedConns(), scope)
        repo.record(emptyList())
        drain()
        assertEquals(0, db.closedConns().count())
    }

    @Test
    fun `closed connections rehydrate as domain rows`() = runTest {
        val repo = ClosedConnRepository(db.closedConns(), scope)
        repo.record(listOf(closed(500, port = 9)))
        drain()
        val row = repo.recent.first().single()
        assertEquals(500L, row.closedAt)
        assertEquals(1L, row.finalRx)
        assertEquals(Proto.TCP, row.row.proto)
        assertEquals(ConnState.CLOSED, row.row.state)
    }

    @Test
    fun `merge today inserts a day that is not yet stored`() = runTest {
        val repo = UsageRepository(db.usage())
        repo.mergeToday(usage(20_000, rx = 10, tx = 20, apps = mapOf("a" to 5L)))
        val stored = repo.all.first().single()
        assertEquals(20_000L, stored.dayEpoch)
        assertEquals(10L, stored.rx)
        assertEquals(mapOf("a" to 5L), stored.apps)
    }

    @Test
    fun `merge today accumulates into the day already stored`() = runTest {
        val repo = UsageRepository(db.usage())
        repo.mergeToday(usage(20_000, rx = 10, tx = 20, apps = mapOf("a" to 5L), hosts = mapOf("h" to 1L)))
        repo.mergeToday(usage(20_000, rx = 7, tx = 3, apps = mapOf("a" to 2L, "b" to 1L), hosts = mapOf("h" to 4L)))
        val stored = repo.all.first().single()
        assertEquals(17L, stored.rx)
        assertEquals(23L, stored.tx)
        assertEquals(mapOf("a" to 7L, "b" to 1L), stored.apps)
        assertEquals(mapOf("h" to 5L), stored.hosts)
    }

    @Test
    fun `merge today prunes history to forty days`() = runTest {
        val repo = UsageRepository(db.usage())
        (1..45).forEach { repo.mergeToday(usage(20_000L + it, rx = 1, tx = 1)) }
        val days = repo.all.first().map { it.dayEpoch }
        assertEquals(UsageRepository.KEEP_DAYS, days.size)
        assertEquals(20_045L, days.first())
        assertEquals(20_006L, days.last())
    }

    @Test
    fun `merge caps each map at the fifty heaviest keys`() {
        val many = (1..120).associate { "k$it" to it.toLong() }
        val merged = UsageRepository.merge(null, usage(1, apps = many, hosts = many))
        assertEquals(UsageRepository.TOP_N, merged.apps.size)
        assertEquals(UsageRepository.TOP_N, merged.hosts.size)
        assertTrue(merged.apps.containsKey("k120"))
        assertFalse(merged.apps.containsKey("k1"))
        assertEquals(120L, merged.apps["k120"])
    }

    @Test
    fun `merge of a null existing day keeps the incoming counters`() {
        val merged = UsageRepository.merge(null, usage(3, rx = 9, tx = 8))
        assertEquals(3L, merged.dayEpoch)
        assertEquals(9L, merged.rx)
        assertEquals(8L, merged.tx)
    }

    @Test
    fun `last days reads back the newest rows`() = runTest {
        val repo = UsageRepository(db.usage())
        (1..5).forEach { repo.mergeToday(usage(20_000L + it, rx = it.toLong(), tx = 0)) }
        assertEquals(listOf(20_005L, 20_004L), repo.lastDays(2).map { it.dayEpoch })
    }

    @Test
    fun `a host is new exactly once`() = runTest {
        val repo = HostSeenRepository(db.hostsSeen())
        assertTrue(repo.isNew("a.example", 100))
        assertFalse(repo.isNew("a.example", 200))
        assertFalse(repo.isNew("a.example", 300))
        assertTrue(repo.isNew("b.example", 400))
    }

    @Test
    fun `seeing a host again refreshes lastSeen but not firstSeen`() = runTest {
        val repo = HostSeenRepository(db.hostsSeen())
        repo.isNew("a.example", 100)
        repo.isNew("a.example", 500)
        val row = db.hostsSeen().seen("a.example")
        assertEquals(100L, row?.firstSeen)
        assertEquals(500L, row?.lastSeen)
    }

    @Test
    fun `an empty host is never reported as new`() = runTest {
        val repo = HostSeenRepository(db.hostsSeen())
        assertFalse(repo.isNew("", 100))
        assertEquals(0, db.hostsSeen().count())
    }

    @Test
    fun `the host table survives a restart, so hosts stay known`() = runTest {
        val repo = HostSeenRepository(db.hostsSeen())
        repo.isNew("persisted.example", 1)
        val reborn = HostSeenRepository(db.hostsSeen())
        assertFalse(reborn.isNew("persisted.example", 2))
        assertEquals(listOf("persisted.example"), reborn.known())
    }

    @Test
    fun `closed conn entity columns map straight through`() = runTest {
        db.closedConns().insert(
            listOf(
                ClosedConnEntity(
                    closedAt = 1,
                    proto = "UDP",
                    localAddr = "::1",
                    localPort = 5353,
                    remoteAddr = "ff02::fb",
                    remotePort = 5353,
                    uid = 1000,
                    appLabel = "mdns",
                    service = "mdns",
                    scope = "MULTICAST",
                    finalRx = 1,
                    finalTx = 2,
                    lifetimeMillis = 3,
                    network = "wifi0",
                    resolvedHost = null,
                )
            )
        )
        val repo = ClosedConnRepository(db.closedConns(), scope)
        val row = repo.recent.first().single()
        assertEquals(Proto.UDP, row.row.proto)
        assertEquals(Scope.MULTICAST, row.row.scope)
    }

    @Test
    fun `usage rows written directly are read back through the mapper`() = runTest {
        db.usage().upsert(DailyUsageEntity(7L, 1L, 2L, "app=3;", "host=4;"))
        val repo = UsageRepository(db.usage())
        val row = repo.all.first().single()
        assertEquals(mapOf("app" to 3L), row.apps)
        assertEquals(mapOf("host" to 4L), row.hosts)
    }

    private fun usage(
        dayEpoch: Long,
        rx: Long = 0L,
        tx: Long = 0L,
        apps: Map<String, Long> = emptyMap(),
        hosts: Map<String, Long> = emptyMap(),
    ) = DailyUsage(dayEpoch = dayEpoch, rx = rx, tx = tx, apps = apps, hosts = hosts)
}
