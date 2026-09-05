package com.vlad.ducknetview.engine.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ByteArrayPoolTest {

    @Test
    fun acquireGivesABufferBigEnoughForTheRequest() {
        val pool = ByteArrayPool()
        for (size in intArrayOf(1, 20, 128, 129, 640, 1500, 1600, 4096, 16384, 65600)) {
            val buf = pool.acquire(size)
            assertTrue("size $size", buf.array.size >= size)
            assertEquals(0, buf.length)
        }
    }

    @Test
    fun aReleasedBufferIsHandedOutAgain() {
        val pool = ByteArrayPool()
        val first = pool.acquire(1500)
        pool.release(first)
        assertSame(first, pool.acquire(1500))
    }

    @Test
    fun acquireResetsTheLengthOfARecycledBuffer() {
        val pool = ByteArrayPool()
        val buf = pool.acquire(100)
        buf.length = 77
        pool.release(buf)
        assertEquals(0, pool.acquire(100).length)
    }

    @Test
    fun releasingTwiceDoesNotLendTheSameBufferTwice() {
        val pool = ByteArrayPool()
        val buf = pool.acquire(1500)
        pool.release(buf)
        pool.release(buf)
        assertEquals(1, pool.pooledCount())
        val a = pool.acquire(1500)
        val b = pool.acquire(1500)
        assertSame(buf, a)
        assertNotSame(a, b)
    }

    @Test
    fun bucketsAreBoundedSoAFloodOfReleasesDoesNotGrowForever() {
        val pool = ByteArrayPool(maxPerBucket = 4)
        val taken = (0 until 50).map { pool.acquire(1500) }
        taken.forEach { pool.release(it) }
        assertEquals(4, pool.pooledCount())
    }

    @Test
    fun buffersLargerThanEveryBucketAreServedButNeverRetained() {
        val pool = ByteArrayPool()
        val huge = pool.acquire(200_000)
        assertEquals(200_000, huge.array.size)
        pool.release(huge)
        assertEquals(0, pool.pooledCount())
    }

    @Test
    fun differentSizeClassesDoNotShareBuffers() {
        val pool = ByteArrayPool()
        val small = pool.acquire(100)
        pool.release(small)
        val large = pool.acquire(1500)
        assertNotSame(small, large)
        assertTrue(large.array.size >= 1500)
    }

    @Test
    fun clearDropsEverythingHeld() {
        val pool = ByteArrayPool()
        repeat(5) { pool.release(pool.acquire(640)) }
        assertTrue(pool.pooledCount() > 0)
        pool.clear()
        assertEquals(0, pool.pooledCount())
    }

    @Test
    fun aBufferInFlightIsNeverHandedToASecondThread() {
        val pool = ByteArrayPool(maxPerBucket = 8)
        val threads = 8
        val iterations = 2000
        val start = CountDownLatch(1)
        val violations = AtomicInteger(0)
        val live = Collections.synchronizedSet(mutableSetOf<PacketBuf>())
        val workers = (0 until threads).map { id ->
            Thread {
                start.await()
                repeat(iterations) {
                    val buf = pool.acquire(1500)
                    if (!live.add(buf)) violations.incrementAndGet()
                    buf.array[0] = id.toByte()
                    buf.length = id
                    Thread.yield()
                    if (buf.array[0] != id.toByte() || buf.length != id) {
                        violations.incrementAndGet()
                    }
                    live.remove(buf)
                    pool.release(buf)
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(30_000) }
        assertEquals(0, violations.get())
        assertTrue(pool.pooledCount() <= 8)
    }

    @Test
    fun concurrentReleasesStayWithinTheBound() {
        val pool = ByteArrayPool(maxPerBucket = 3)
        val bufs = (0 until 64).map { pool.acquire(640) }
        val start = CountDownLatch(1)
        val workers = bufs.map { buf ->
            Thread {
                start.await()
                pool.release(buf)
                pool.release(buf)
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertEquals(3, pool.pooledCount())
    }
}
