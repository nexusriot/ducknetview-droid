package com.vlad.ducknetview.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.domain.model.DomainObservation
import com.vlad.ducknetview.domain.model.DomainRow
import com.vlad.ducknetview.domain.model.NameSource
import com.vlad.ducknetview.data.db.DuckDatabase
import kotlinx.coroutines.flow.first
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
class DomainRepositoryTest {

    private lateinit var db: DuckDatabase
    private lateinit var repo: DomainRepository
    private var clock = 10_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DuckDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = DomainRepository(db.domains()) { clock }
    }

    @After
    fun tearDown() = db.close()

    private fun seen(
        name: String,
        uid: Int = 10100,
        at: Long = 1000L,
        source: NameSource = NameSource.DNS,
        addresses: List<String> = listOf("93.184.216.34"),
    ) = DomainObservation(name, uid, source, at, addresses)

    @Test
    fun `a first sighting is stored and reported as new`() = runTest {
        val fresh = repo.record(listOf(seen("example.com")))

        assertEquals(listOf("example.com"), fresh.map { it.name })
        val stored = repo.recent.first().single()
        assertEquals("example.com", stored.name)
        assertEquals(1, stored.lookups)
        assertEquals(1000L, stored.firstSeen)
        assertEquals(listOf("93.184.216.34"), stored.addresses)
    }

    @Test
    fun `a repeat sighting counts up without being reported as new again`() = runTest {
        repo.record(listOf(seen("example.com", at = 1000L)))
        val second = repo.record(listOf(seen("example.com", at = 2000L)))

        assertTrue(second.isEmpty())
        val stored = repo.recent.first().single()
        assertEquals(2, stored.lookups)
        assertEquals(1000L, stored.firstSeen)
        assertEquals(2000L, stored.lastSeen)
    }

    @Test
    fun `the same name from two apps is two rows`() = runTest {
        // Collapsing them would throw away the only attribution this table has.
        repo.record(listOf(seen("cdn.example.com", uid = 10100)))
        repo.record(listOf(seen("cdn.example.com", uid = 10200)))

        val rows = repo.recent.first()
        assertEquals(2, rows.size)
        assertEquals(setOf(10100, 10200), rows.map { it.uid }.toSet())
    }

    @Test
    fun `repeat sightings in one batch accumulate rather than overwrite`() = runTest {
        // Several answers can arrive between two poll ticks and are written as
        // one batch, so the loop has to see its own earlier writes.
        val fresh = repo.record(
            listOf(seen("example.com", at = 1000L), seen("example.com", at = 1500L)),
        )
        assertEquals(1, fresh.size)
        assertEquals(2, repo.recent.first().single().lookups)
    }

    @Test
    fun `new addresses for a known name are merged in`() = runTest {
        repo.record(listOf(seen("cdn.example.com", addresses = listOf("1.1.1.1"))))
        repo.record(listOf(seen("cdn.example.com", addresses = listOf("1.1.1.1", "2.2.2.2"))))

        assertEquals(listOf("1.1.1.1", "2.2.2.2"), repo.recent.first().single().addresses)
    }

    @Test
    fun `an SNI sighting never downgrades a name already seen in DNS`() = runTest {
        repo.record(listOf(seen("example.com", source = NameSource.DNS)))
        repo.record(listOf(seen("example.com", source = NameSource.SNI)))

        // DNS is the stronger statement: it is what the device asked its
        // resolver, rather than what it told one server it wanted.
        assertEquals(NameSource.DNS, repo.recent.first().single().source)
    }

    @Test
    fun `a name only ever seen in SNI stays marked as such`() = runTest {
        repo.record(listOf(seen("example.com", source = NameSource.SNI)))
        repo.record(listOf(seen("example.com", source = NameSource.SNI)))
        assertEquals(NameSource.SNI, repo.recent.first().single().source)
    }

    @Test
    fun `an empty name is ignored rather than stored`() = runTest {
        assertTrue(repo.record(listOf(seen(""))).isEmpty())
        assertTrue(repo.recent.first().isEmpty())
    }

    @Test
    fun `nothing in means nothing done`() = runTest {
        assertTrue(repo.record(emptyList()).isEmpty())
    }

    @Test
    fun `rows older than the retention window are dropped`() = runTest {
        clock = 100_000_000L
        repo.record(listOf(seen("old.example.com", at = 1L)))
        // Pruning runs on the next write, so a second sighting is what sweeps.
        clock = 1L + DomainRepository.RETENTION_MILLIS + 100_000_000L
        repo.record(listOf(seen("new.example.com", at = clock)))

        assertEquals(listOf("new.example.com"), repo.recent.first().map { it.name })
    }

    @Test
    fun `the store is bounded by row count as well as by age`() = runTest {
        val over = DomainRepository.MAX_ROWS + 20
        repo.record((1..over).map { seen("h$it.example.com", at = 1000L + it) })

        val rows = repo.recent.first()
        assertTrue(rows.size <= DomainRepository.RECENT_LIMIT)
        assertEquals(DomainRepository.MAX_ROWS, db.domains().count())
        // The newest survive, which is the same ordering the screen renders.
        assertTrue(rows.first().name == "h$over.example.com")
    }

    @Test
    fun `clear empties the store`() = runTest {
        repo.record(listOf(seen("example.com")))
        repo.clear()
        assertTrue(repo.recent.first().isEmpty())
    }

    @Test
    fun `the export carries the stored fields`() = runTest {
        repo.record(listOf(seen("example.com", addresses = listOf("1.1.1.1", "2.2.2.2"))))
        val csv = repo.exportCsv()

        assertTrue(csv.startsWith("name,uid,app,package,source,lookups"))
        assertTrue(csv.contains("example.com"))
        assertTrue(csv.contains("1.1.1.1 2.2.2.2"))
    }

    @Test
    fun `merge builds a first row from one sighting`() {
        val row = DomainRepository.merge(null, seen("example.com", at = 77L))
        assertEquals(1, row.lookups)
        assertEquals(77L, row.firstSeen)
        assertEquals(77L, row.lastSeen)
    }

    @Test
    fun `merge keeps the earliest first-seen even when answers arrive out of order`() {
        val first = DomainRepository.merge(null, seen("example.com", at = 500L))
        val merged = DomainRepository.merge(first, seen("example.com", at = 100L))
        assertEquals(100L, merged.firstSeen)
        assertEquals(500L, merged.lastSeen)
    }

    @Test
    fun `merge caps the addresses a CDN can accumulate`() {
        var row: DomainRow? = null
        for (i in 1..DomainRepository.MAX_ADDRESSES + 10) {
            row = DomainRepository.merge(row, seen("cdn.example.com", addresses = listOf("10.0.0.$i")))
        }
        assertEquals(DomainRepository.MAX_ADDRESSES, row!!.addresses.size)
        // The first ones seen are the ones kept, so the set is stable rather
        // than churning on every answer.
        assertEquals("10.0.0.1", row.addresses.first())
        assertFalse(row.addresses.contains("10.0.0.18"))
    }
}
