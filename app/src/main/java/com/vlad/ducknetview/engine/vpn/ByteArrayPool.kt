package com.vlad.ducknetview.engine.vpn

/**
 * A borrowed byte buffer plus how much of it is live.
 *
 * Ownership is single-writer and explicit: whoever holds a [PacketBuf] may read
 * and write [array], and loses that right the moment it hands the buffer on
 * (to [TunWriter.submit], to a channel) or gives it back to the pool. Nobody
 * may touch a buffer they have handed on.
 */
class PacketBuf internal constructor(val array: ByteArray) {
    var length: Int = 0

    /** True while the pool holds it; guards a double release from lending it twice. */
    internal var pooled: Boolean = false
}

/**
 * Size-bucketed, bounded, thread-safe pool of packet buffers.
 *
 * The relay allocates one array per segment and one per datagram otherwise,
 * which at MTU-sized segments is megabytes of garbage per megabyte relayed.
 * Buckets are exact array sizes, so a released buffer always lands in the
 * bucket it came from; anything larger than the biggest bucket is served but
 * never retained.
 */
class ByteArrayPool(
    private val maxPerBucket: Int = 48,
    private val bucketSizes: IntArray = intArrayOf(128, 640, 1600, 4096, 16384, 65600),
) {
    private val buckets = Array(bucketSizes.size) { ArrayDeque<PacketBuf>() }
    private val lock = Any()

    fun acquire(size: Int): PacketBuf {
        val b = bucketFor(size)
        if (b < 0) return PacketBuf(ByteArray(size.coerceAtLeast(1)))
        val reused = synchronized(lock) { buckets[b].removeLastOrNull() }
        if (reused != null) {
            reused.pooled = false
            reused.length = 0
            return reused
        }
        return PacketBuf(ByteArray(bucketSizes[b]))
    }

    /** Give a buffer back. Releasing one twice is a no-op, not a double lend. */
    fun release(buf: PacketBuf) {
        val b = exactBucket(buf.array.size)
        if (b < 0) return
        synchronized(lock) {
            if (buf.pooled) return
            if (buckets[b].size >= maxPerBucket) return
            buf.pooled = true
            buf.length = 0
            buckets[b].addLast(buf)
        }
    }

    fun clear() {
        synchronized(lock) { for (q in buckets) q.clear() }
    }

    /** Buffers currently idle in the pool; for tests and diagnostics. */
    fun pooledCount(): Int = synchronized(lock) { buckets.sumOf { it.size } }

    private fun bucketFor(size: Int): Int {
        for (i in bucketSizes.indices) if (size <= bucketSizes[i]) return i
        return -1
    }

    private fun exactBucket(size: Int): Int {
        for (i in bucketSizes.indices) if (size == bucketSizes[i]) return i
        return -1
    }
}
