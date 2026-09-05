package com.vlad.ducknetview.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vlad.ducknetview.data.db.ClosedConnEntity
import com.vlad.ducknetview.data.db.DuckDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClosedConnDaoTest {

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

    private fun conn(closedAt: Long, port: Int = 443) = ClosedConnEntity(
        closedAt = closedAt,
        proto = "TCP",
        localAddr = "10.0.0.2",
        localPort = port,
        remoteAddr = "1.1.1.1",
        remotePort = 443,
        uid = 10001,
        appLabel = "App",
        service = "https",
        scope = "PUBLIC",
        finalRx = 10L,
        finalTx = 20L,
        lifetimeMillis = 1000L,
        network = "wifi0",
        resolvedHost = null,
    )

    @Test
    fun `recent returns newest first`() = runTest {
        db.closedConns().insert(listOf(conn(100, port = 1), conn(300, port = 3), conn(200, port = 2)))
        assertEquals(listOf(3, 2, 1), db.closedConns().recent(10).first().map { it.localPort })
    }

    @Test
    fun `trimTo keeps the newest rows and deletes the oldest`() = runTest {
        // Inserted oldest-first so a naive "delete by rowid" trim would keep the
        // wrong end; ports double as identity here.
        db.closedConns().insert((1..10).map { conn(closedAt = it * 100L, port = it) })
        db.closedConns().trimTo(3)
        assertEquals(3, db.closedConns().count())
        assertEquals(listOf(10, 9, 8), db.closedConns().recent(50).first().map { it.localPort })
    }

    @Test
    fun `trimTo keeps the newest rows when they were inserted first`() = runTest {
        db.closedConns().insert((1..10).map { conn(closedAt = (11 - it) * 100L, port = it) })
        db.closedConns().trimTo(4)
        assertEquals(listOf(1, 2, 3, 4), db.closedConns().recent(50).first().map { it.localPort })
    }

    @Test
    fun `trimTo is a no-op when the table is already within the limit`() = runTest {
        db.closedConns().insert((1..5).map { conn(closedAt = it * 100L, port = it) })
        db.closedConns().trimTo(200)
        assertEquals(5, db.closedConns().count())
    }

    @Test
    fun `trimTo breaks closedAt ties the same way recent orders them`() = runTest {
        db.closedConns().insert(listOf(conn(500, port = 1), conn(500, port = 2), conn(500, port = 3)))
        db.closedConns().trimTo(2)
        assertEquals(listOf(3, 2), db.closedConns().recent(50).first().map { it.localPort })
    }

    @Test
    fun `clear empties the table`() = runTest {
        db.closedConns().insert(listOf(conn(1), conn(2)))
        db.closedConns().clear()
        assertEquals(0, db.closedConns().count())
    }

    @Test
    fun `a nullable resolved host survives the column round-trip`() = runTest {
        db.closedConns().insert(listOf(conn(1).copy(resolvedHost = "one.one.one.one"), conn(2)))
        val rows = db.closedConns().recent(10).first()
        assertEquals(listOf(null, "one.one.one.one"), rows.map { it.resolvedHost })
    }
}
