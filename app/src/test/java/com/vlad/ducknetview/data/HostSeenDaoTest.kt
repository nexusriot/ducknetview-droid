package com.vlad.ducknetview.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.data.db.DuckDatabase
import com.vlad.ducknetview.data.db.HostSeenEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostSeenDaoTest {

    private lateinit var db: DuckDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            DuckDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `seen returns null for an unknown host`() = runTest {
        assertNull(db.hostsSeen().seen("nowhere.example"))
    }

    @Test
    fun `upsert stores and then updates a host`() = runTest {
        db.hostsSeen().upsert(HostSeenEntity("a.example", firstSeen = 10, lastSeen = 10))
        db.hostsSeen().upsert(HostSeenEntity("a.example", firstSeen = 10, lastSeen = 50))
        val row = db.hostsSeen().seen("a.example")
        assertEquals(10L, row?.firstSeen)
        assertEquals(50L, row?.lastSeen)
        assertEquals(1, db.hostsSeen().count())
    }

    @Test
    fun `allHosts lists most recently seen first`() = runTest {
        db.hostsSeen().upsert(HostSeenEntity("old", 1, 1))
        db.hostsSeen().upsert(HostSeenEntity("new", 2, 900))
        db.hostsSeen().upsert(HostSeenEntity("mid", 3, 400))
        assertEquals(listOf("new", "mid", "old"), db.hostsSeen().allHosts())
    }

    @Test
    fun `prune evicts the least recently seen hosts`() = runTest {
        (1..10).forEach { db.hostsSeen().upsert(HostSeenEntity("h$it", 0, it.toLong())) }
        db.hostsSeen().prune(3)
        assertEquals(listOf("h10", "h9", "h8"), db.hostsSeen().allHosts())
    }

    @Test
    fun `prune is a no-op below the cap`() = runTest {
        (1..3).forEach { db.hostsSeen().upsert(HostSeenEntity("h$it", 0, it.toLong())) }
        db.hostsSeen().prune(4096)
        assertEquals(3, db.hostsSeen().count())
    }
}
