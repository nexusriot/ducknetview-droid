package com.vlad.ducknetview.domain.asn

import com.vlad.ducknetview.domain.net.IpBytes
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** The autonomous system an address belongs to. */
data class AsnInfo(val number: Long, val country: String, val name: String) {
    /** `AS13335 CLOUDFLARENET`, or just `AS13335` when the table has no name. */
    val label: String get() = if (name.isEmpty()) "AS$number" else "AS$number $name"
}

/**
 * Offline IP-to-ASN lookup over an iptoasn.com dump, as the TUI's
 * `asn_database` does.
 *
 * The input is that site's TSV — `range_start, range_end, AS_number,
 * country_code, AS_description` — IPv4, IPv6 or the combined file, plain or
 * gzipped. Offline because asking a whois service about every remote address
 * would leak the device's whole contact list to a third party, which is the
 * opposite of what a privacy-minded monitor is for.
 *
 * Memory is the constraint: the combined file is around half a million rows,
 * and a `List<Range>` of boxed objects would cost tens of megabytes on a phone.
 * Ranges are therefore held in parallel primitive arrays sorted by start, and
 * each row stores only an index into a table of distinct [AsnInfo]s — there
 * are far fewer ASes than ranges, so the strings are held once each. Lookup is
 * a binary search.
 *
 * iptoasn ranges are disjoint, and the search relies on that: it finds the
 * last range starting at or before the address and checks its end. Nested or
 * overlapping input would still return an answer, but not necessarily the
 * most specific one.
 */
class AsnTable private constructor(
    private val v4Start: LongArray,
    private val v4End: LongArray,
    private val v4Info: IntArray,
    private val v6StartHi: LongArray,
    private val v6StartLo: LongArray,
    private val v6EndHi: LongArray,
    private val v6EndLo: LongArray,
    private val v6Info: IntArray,
    private val infos: Array<AsnInfo>,
    /** Lines that were not a usable range: garbage, a bad address, a reversed range. */
    val skipped: Int,
) {

    /** Routed ranges held, both families together. */
    val size: Int get() = v4Start.size + v6StartHi.size

    /** The AS announcing [ip], or null for a non-literal, an unrouted or unknown address. */
    fun lookup(ip: String): AsnInfo? {
        val b = IpBytes.parseNormalized(ip) ?: return null
        return lookup(b)
    }

    /** [lookup] for an already-parsed 4- or 16-byte address. */
    fun lookup(bytes: ByteArray): AsnInfo? {
        val b = IpBytes.normalize(bytes)
        return when (b.size) {
            4 -> lookupV4(v4Of(b))
            16 -> lookupV6(hiOf(b), loOf(b))
            else -> null
        }
    }

    private fun lookupV4(ip: Long): AsnInfo? {
        var lo = 0
        var hi = v4Start.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (v4Start[mid] <= ip) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0 || ip > v4End[found]) return null
        return infos[v4Info[found]]
    }

    private fun lookupV6(ipHi: Long, ipLo: Long): AsnInfo? {
        var lo = 0
        var hi = v6StartHi.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (compare128(v6StartHi[mid], v6StartLo[mid], ipHi, ipLo) <= 0) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0 || compare128(ipHi, ipLo, v6EndHi[found], v6EndLo[found]) > 0) return null
        return infos[v6Info[found]]
    }

    companion object {

        val EMPTY = AsnTable(
            LongArray(0), LongArray(0), IntArray(0),
            LongArray(0), LongArray(0), LongArray(0), LongArray(0), IntArray(0),
            emptyArray(), 0,
        )

        /**
         * Reads a whole dump. Gzip is detected by its `1f 8b` magic rather than
         * by a file name, because the stream may come from a content URI that
         * has none. The stream is consumed but not closed — the caller opened it.
         *
         * Rows announcing AS 0 are iptoasn's "Not routed" filler and are dropped
         * silently: they are not garbage, but they are not an answer either.
         */
        fun parse(input: InputStream): AsnTable {
            val buffered = BufferedInputStream(input)
            buffered.mark(2)
            val m0 = buffered.read()
            val m1 = buffered.read()
            buffered.reset()
            val source: InputStream =
                if (m0 == 0x1F && m1 == 0x8B) GZIPInputStream(buffered) else buffered

            val b = Builder()
            source.bufferedReader(Charsets.UTF_8).forEachLine { b.add(it) }
            return b.build()
        }

        private fun v4Of(b: ByteArray): Long =
            ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)

        private fun hiOf(b: ByteArray): Long = longAt(b, 0)

        private fun loOf(b: ByteArray): Long = longAt(b, 8)

        private fun longAt(b: ByteArray, from: Int): Long {
            var v = 0L
            for (i in from until from + 8) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }

        private fun compare128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Int {
            val c = java.lang.Long.compareUnsigned(aHi, bHi)
            return if (c != 0) c else java.lang.Long.compareUnsigned(aLo, bLo)
        }
    }

    /** Growable primitive columns; sorted and trimmed once in [build]. */
    private class Builder {
        private var v4Start = LongArray(1024)
        private var v4End = LongArray(1024)
        private var v4Info = IntArray(1024)
        private var v4Count = 0

        private var v6StartHi = LongArray(256)
        private var v6StartLo = LongArray(256)
        private var v6EndHi = LongArray(256)
        private var v6EndLo = LongArray(256)
        private var v6Info = IntArray(256)
        private var v6Count = 0

        private val infoIndex = HashMap<AsnInfo, Int>()
        private val infos = ArrayList<AsnInfo>()
        private var skipped = 0

        fun add(line: String) {
            if (line.isBlank()) return
            val f = line.split('\t')
            if (f.size < 5) {
                skipped++
                return
            }
            val number = f[2].trim().toLongOrNull()
            if (number == null || number < 0 || number > 0xFFFF_FFFFL) {
                skipped++
                return
            }
            if (number == 0L) return

            val start = IpBytes.parse(f[0])
            val end = IpBytes.parse(f[1])
            if (start == null || end == null || start.size != end.size) {
                skipped++
                return
            }
            // A description containing a tab would split into extra fields;
            // putting it back together is kinder than dropping the row.
            val name = (if (f.size == 5) f[4] else f.subList(4, f.size).joinToString("\t")).trim()
            val info = intern(AsnInfo(number, f[3].trim(), name))

            if (start.size == 4) {
                val s = v4Of(start)
                val e = v4Of(end)
                if (s > e) {
                    skipped++
                    return
                }
                if (v4Count == v4Start.size) growV4()
                v4Start[v4Count] = s
                v4End[v4Count] = e
                v4Info[v4Count] = info
                v4Count++
            } else {
                val sHi = hiOf(start)
                val sLo = loOf(start)
                val eHi = hiOf(end)
                val eLo = loOf(end)
                if (compare128(sHi, sLo, eHi, eLo) > 0) {
                    skipped++
                    return
                }
                if (v6Count == v6StartHi.size) growV6()
                v6StartHi[v6Count] = sHi
                v6StartLo[v6Count] = sLo
                v6EndHi[v6Count] = eHi
                v6EndLo[v6Count] = eLo
                v6Info[v6Count] = info
                v6Count++
            }
        }

        private fun intern(info: AsnInfo): Int = infoIndex.getOrPut(info) {
            infos += info
            infos.size - 1
        }

        private fun growV4() {
            val n = v4Start.size * 2
            v4Start = v4Start.copyOf(n)
            v4End = v4End.copyOf(n)
            v4Info = v4Info.copyOf(n)
        }

        private fun growV6() {
            val n = v6StartHi.size * 2
            v6StartHi = v6StartHi.copyOf(n)
            v6StartLo = v6StartLo.copyOf(n)
            v6EndHi = v6EndHi.copyOf(n)
            v6EndLo = v6EndLo.copyOf(n)
            v6Info = v6Info.copyOf(n)
        }

        fun build(): AsnTable {
            // The published dumps are already sorted, so the boxed sort below
            // normally never runs; it is kept because the binary search is
            // silently wrong on unsorted input.
            val v4Sorted = (1 until v4Count).all { v4Start[it - 1] <= v4Start[it] }
            val o4: List<Int> = if (v4Sorted) IdentityList(v4Count)
            else (0 until v4Count).sortedWith { a, b -> v4Start[a].compareTo(v4Start[b]) }
            val v6Order = Comparator<Int> { a, b ->
                compare128(v6StartHi[a], v6StartLo[a], v6StartHi[b], v6StartLo[b])
            }
            val v6Sorted = (1 until v6Count).all { v6Order.compare(it - 1, it) <= 0 }
            val o6: List<Int> = if (v6Sorted) IdentityList(v6Count)
            else (0 until v6Count).sortedWith(v6Order)
            return AsnTable(
                v4Start = LongArray(v4Count) { v4Start[o4[it]] },
                v4End = LongArray(v4Count) { v4End[o4[it]] },
                v4Info = IntArray(v4Count) { v4Info[o4[it]] },
                v6StartHi = LongArray(v6Count) { v6StartHi[o6[it]] },
                v6StartLo = LongArray(v6Count) { v6StartLo[o6[it]] },
                v6EndHi = LongArray(v6Count) { v6EndHi[o6[it]] },
                v6EndLo = LongArray(v6Count) { v6EndLo[o6[it]] },
                v6Info = IntArray(v6Count) { v6Info[o6[it]] },
                infos = infos.toTypedArray(),
                skipped = skipped,
            )
        }
    }

    /** `[0, 1, …, n-1]` without allocating n boxed integers. */
    private class IdentityList(override val size: Int) : AbstractList<Int>() {
        override fun get(index: Int): Int = index
    }
}
