package com.vlad.ducknetview.engine.vpn.packet

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads the `server_name` out of a QUIC client Initial packet.
 *
 * Why this exists: QUIC is the blind spot that the Domains screen's own info
 * sheet had to admit to. A page load in a modern browser is entirely UDP/443,
 * which means no DNS answer crosses the TUN (the resolver is encrypted) and no
 * TLS record does either (the handshake is inside QUIC), so [DnsPeek] and
 * [TlsPeek] both see nothing and every row falls back to a bare address. On a
 * phone that is most of the traffic, not an edge case.
 *
 * ## Why this is readable at all, and why it is still not interception
 *
 * QUIC Initial packets are protected with keys that RFC 9001 §5.2 derives from
 * a **published, version-specific salt** and the Destination Connection ID the
 * client puts in the clear in the same packet. There is no secret anywhere in
 * the derivation: the salts below are copied out of the RFC, and every value
 * they are combined with is on the wire. Anyone on the path can do this, which
 * is the point — the protection exists to stop middleboxes ossifying the
 * format, not to keep the contents private, and the RFC says so.
 *
 * So by the line this project already drew for SNI, this is the same act: one
 * field is read, it is a name, it is sent before any key exchange, no key is
 * touched, no certificate is substituted, and the datagram is relayed byte for
 * byte either way. Nothing after the Initial is readable and nothing after the
 * Initial is looked at. Encrypted Client Hello will take this source away in
 * time exactly as it will take TLS SNI, and the degradation is the same one:
 * the field is simply absent and the row falls back to its address.
 *
 * ## Cost
 *
 * The cheap gate is [looksLikeInitial] — five bytes of comparison, run once on
 * the first datagram of a UDP flow and never again. Only a datagram that
 * passes it pays for the key derivation and the AES-GCM open, and that is
 * bounded by the Initial's own length. A client's first Initial is padded to
 * at least 1200 bytes and carries the whole ClientHello in the overwhelming
 * majority of cases.
 *
 * ## Reassembly, and why this differs from TlsPeek
 *
 * [TlsPeek] refuses to reassemble a ClientHello split across TCP segments,
 * because there it is rare and buffering attacker-controlled bytes for a
 * cosmetic label is not a trade worth making. That reasoning was carried over
 * here and it was wrong: measured on the test tablet, Chrome produced UDP/443
 * flows by the dozen and this returned a name for **none** of them. A modern
 * ClientHello carries a post-quantum key share and does not fit in one
 * Initial packet, so for QUIC a split hello is not the rare case, it is the
 * normal one — and declining to reassemble meant declining the feature.
 *
 * [Assembler] therefore holds CRYPTO data across the first few datagrams of
 * one flow, under hard limits: at most [MAX_INITIALS] packets and
 * [MAX_CRYPTO_BYTES] of buffer, abandoned the moment either is passed, the
 * hello completes, or the flow sends something that is not an Initial. Each
 * datagram's coalesced packets are walked, since a client may put both
 * Initials in one.
 *
 * Server-sent Initials, Retry, 0-RTT and Handshake packets are all ignored.
 *
 * One limit comes with holding state on the flow: a handshake interrupted
 * before it completes — an ICMP port-unreachable tears the relay's upstream
 * socket down, and the flow with it — loses whatever had been collected, and
 * the datagrams that follow start a new flow with a new assembler. Keying the
 * buffer outside the flow would survive that and is deliberately not done: a
 * browser's handshake goes to a host that is listening by construction, so
 * the case costs a label on a connection that was failing anyway, and the
 * alternative is unbounded state keyed on something no longer being tracked.
 */
object QuicPeek {

    /** QUIC v1, RFC 9000. */
    const val VERSION_1 = 0x00000001

    /** QUIC v2, RFC 9369. Same packet shape, different salt and labels. */
    const val VERSION_2 = 0x6b3343cf

    /** RFC 9001 §5.2. */
    internal val SALT_V1 = byteArrayOf(
        0x38, 0x76, 0x2c, 0xf7.toByte(), 0xf5.toByte(), 0x59, 0x34, 0xb3.toByte(),
        0x4d, 0x17, 0x9a.toByte(), 0xe6.toByte(), 0xa4.toByte(), 0xc8.toByte(),
        0x0c, 0xad.toByte(), 0xcc.toByte(), 0xbb.toByte(), 0x7f, 0x0a,
    )

    /** RFC 9369 §3.3.1. */
    private val SALT_V2 = byteArrayOf(
        0x0d, 0xed.toByte(), 0xe3.toByte(), 0xde.toByte(), 0xf7.toByte(), 0x00,
        0xa6.toByte(), 0xdb.toByte(), 0x81.toByte(), 0x93.toByte(), 0x81.toByte(),
        0xbe.toByte(), 0x6e, 0x26, 0x9d.toByte(), 0xcb.toByte(), 0xf9.toByte(),
        0xbd.toByte(), 0x2e, 0xd9.toByte(),
    )

    /** Enough to classify the header form, the fixed bit and the version. */
    const val MIN_LENGTH = 7

    /** The longest connection id QUIC v1 permits. */
    private const val MAX_CID = 20

    private const val FRAME_PADDING = 0x00L
    private const val FRAME_PING = 0x01L
    private const val FRAME_CRYPTO = 0x06L

    /** A ClientHello larger than this is not one worth reassembling. */
    private const val MAX_CRYPTO_BYTES = 16384

    /**
     * Initial packets one flow may contribute before the attempt is dropped.
     * Four covers a post-quantum hello with room to spare; a client still
     * sending Initials past that is not one this needs to label.
     */
    const val MAX_INITIALS = 4

    /** The TLS handshake header: one type byte and a three-byte length. */
    private const val HANDSHAKE_HEADER = 4

    /**
     * Disjoint pieces of the handshake stream one flow may leave outstanding.
     * A real client sends two or three; a stream arriving in scattered
     * fragments is not one this needs to label.
     */
    private const val MAX_RANGES = 64

    /**
     * True when these bytes open a client Initial of a version this can read.
     *
     * Run on the first datagram of every UDP flow, so it does nothing but
     * compare: long-header form, the fixed bit, a known version, and the
     * packet type that version gives Initial. Everything expensive is behind
     * it.
     */
    fun looksLikeInitial(buf: ByteArray, offset: Int, length: Int): Boolean {
        if (length < MIN_LENGTH || offset < 0 || offset + MIN_LENGTH > buf.size) return false
        val b0 = buf[offset].toInt() and 0xFF
        // Header form = 1 (long) and fixed bit = 1. A short-header packet
        // belongs to a connection already established, which cannot be the
        // first datagram of a flow, and a cleared fixed bit is not QUIC as
        // this app understands it.
        if (b0 and 0xC0 != 0xC0) return false
        val version = Packets.u32(buf, offset + 1).toInt()
        val type = (b0 shr 4) and 0x03
        return when (version) {
            VERSION_1 -> type == 0x00
            VERSION_2 -> type == 0x01
            else -> false
        }
    }

    /**
     * The SNI host name carried in this datagram's Initial, or null.
     *
     * Null covers every uninteresting case alike, as in [TlsPeek]: not QUIC,
     * not an Initial, a version this does not know, a packet whose lengths run
     * past what was handed in, a ClientHello continued in a later datagram, a
     * frame type that has no business in a client's first Initial, or an
     * authentication failure — which is the normal answer for a packet that
     * merely looked like one.
     */
    fun serverName(buf: ByteArray, offset: Int, length: Int): String? =
        Assembler().offer(buf, offset, length)

    /**
     * Collects a ClientHello out of one flow's Initial packets.
     *
     * One per UDP flow, created only when the flow's first datagram looks
     * like an Initial, and discarded the moment [done] goes true — which it
     * does on success, on the limits, or on the first datagram that is not an
     * Initial. Nothing here outlives a handshake.
     */
    class Assembler {

        private var crypto: ByteArray? = null
        private var initials = 0

        /**
         * The byte ranges of the handshake stream received so far, merged and
         * half-open.
         *
         * A high-water mark is not enough: a client may send the tail of its
         * hello before the head, and a mark would then forget the tail was
         * ever here and call the hello short forever. The list is bounded —
         * past [MAX_RANGES] disjoint pieces this is not a handshake worth
         * reassembling.
         */
        private val ranges = ArrayList<IntArray>()

        /** True once there is no point offering anything further. */
        var done: Boolean = false
            private set

        /**
         * Absorb one datagram; returns the host name once the hello is whole.
         *
         * Null covers every uninteresting case alike, as in [TlsPeek]: not
         * QUIC, not an Initial, a version this does not know, lengths that
         * run past what was handed in, a hello still incomplete, a frame type
         * with no business in a client Initial, or an authentication failure
         * — which is the normal answer for a packet that merely looked like
         * one.
         */
        fun offer(buf: ByteArray, offset: Int, length: Int): String? = try {
            absorb(buf, offset, length)
        } catch (e: Exception) {
            // Every step is bounds-checked, but this is attacker-supplied
            // input going to JCE on the packet path: a provider throwing
            // something unexpected must cost the flow its label, not the
            // capture engine its thread.
            done = true
            null
        }

        private fun absorb(buf: ByteArray, offset: Int, length: Int): String? {
            if (done) return null
            if (!looksLikeInitial(buf, offset, length)) {
                // The client has moved past its Initials, so the hello is
                // either already in hand or never coming.
                done = true
                return null
            }
            val end = offset + minOf(length, buf.size - offset)
            var pos = offset
            // A client may coalesce both Initials into one datagram, so the
            // whole datagram is walked rather than just its first packet.
            while (pos < end && initials < MAX_INITIALS) {
                val packet = readInitial(buf, pos, end) ?: break
                initials++
                if (!collect(packet.plaintext)) {
                    done = true
                    return null
                }
                val name = complete()
                if (name != null) {
                    done = true
                    return name
                }
                if (packet.next <= pos) break
                pos = packet.next
            }
            if (initials >= MAX_INITIALS) done = true
            return null
        }

        /** Fold a packet's CRYPTO frames into the stream; false to give up. */
        private fun collect(plaintext: ByteArray): Boolean {
            val r = ByteReader(plaintext, 0, plaintext.size)
            while (r.position < plaintext.size) {
                when (val type = r.varint() ?: return true) {
                    FRAME_PADDING, FRAME_PING -> continue
                    FRAME_CRYPTO -> {
                        val frameOffset = r.varint() ?: return false
                        val frameLength = r.varint() ?: return false
                        if (frameOffset < 0 || frameLength < 0) return false
                        val endOfFrame = frameOffset + frameLength
                        if (endOfFrame > MAX_CRYPTO_BYTES) return false
                        val data = r.bytes(frameLength.toInt()) ?: return false
                        val needed = endOfFrame.toInt()
                        var buffer = crypto
                        if (buffer == null || buffer.size < needed) {
                            buffer = (buffer ?: ByteArray(0)).copyOf(needed)
                            crypto = buffer
                        }
                        System.arraycopy(data, 0, buffer, frameOffset.toInt(), data.size)
                        if (!record(frameOffset.toInt(), needed)) return false
                    }

                    else -> {
                        // Stepping through a frame this does not know would
                        // need a guessed length, and a wrong answer delivered
                        // confidently is worse than none.
                        if (type < 0) return false
                        return true
                    }
                }
            }
            return true
        }

        /** Merge one received range in; false when there are too many pieces. */
        private fun record(start: Int, end: Int): Boolean {
            if (end <= start) return true
            var s = start
            var e = end
            val it = ranges.iterator()
            while (it.hasNext()) {
                val r = it.next()
                // Touching counts as overlapping: [0,5) and [5,9) are one run.
                if (r[1] < s || r[0] > e) continue
                s = minOf(s, r[0])
                e = maxOf(e, r[1])
                it.remove()
            }
            ranges += intArrayOf(s, e)
            return ranges.size <= MAX_RANGES
        }

        /** How much of the stream is present contiguously from byte zero. */
        private val filled: Int
            get() {
                for (r in ranges) if (r[0] == 0) return r[1]
                return 0
            }

        /** The host, once the buffered run covers the whole handshake message. */
        private fun complete(): String? {
            val buffer = crypto ?: return null
            if (filled < HANDSHAKE_HEADER) return null
            val declared = ((buffer[1].toInt() and 0xFF) shl 16) or
                ((buffer[2].toInt() and 0xFF) shl 8) or
                (buffer[3].toInt() and 0xFF)
            val needed = HANDSHAKE_HEADER + declared
            if (needed > MAX_CRYPTO_BYTES || filled < needed) return null
            return TlsPeek.clientHelloServerName(buffer, 0, needed)
                ?.takeIf { HostNames.isPlausible(it) }
        }
    }

    private class Opened(val plaintext: ByteArray, val next: Int)

    /** Unprotect one Initial at [offset]; null when it is not a readable one. */
    private fun readInitial(buf: ByteArray, offset: Int, limit: Int): Opened? {
        if (!looksLikeInitial(buf, offset, limit - offset)) return null
        val r = ByteReader(buf, offset, limit)

        val b0 = r.u8()
        val version = r.u32() ?: return null

        val dcidLen = r.u8()
        if (dcidLen < 0 || dcidLen > MAX_CID) return null
        val dcid = r.bytes(dcidLen) ?: return null
        val scidLen = r.u8()
        if (scidLen < 0 || scidLen > MAX_CID) return null
        if (r.bytes(scidLen) == null) return null

        val tokenLength = r.varint() ?: return null
        if (tokenLength < 0 || tokenLength > (limit - r.position)) return null
        r.position += tokenLength.toInt()

        val payloadLength = r.varint() ?: return null
        val pnOffset = r.position
        if (payloadLength < 4 || payloadLength > (limit - pnOffset)) return null
        val packetEnd = pnOffset + payloadLength.toInt()

        // Every Initial a client sends carries the same Destination
        // Connection ID until the server answers, so deriving per packet from
        // the packet's own id is both simplest and always right.
        val keys = initialKeys(version, dcid) ?: return null

        // Header protection, RFC 9001 §5.4. The sample starts four bytes past
        // the packet number field, which is where it would end if the number
        // were the longest it can be — so the sample can be taken before the
        // length is known, which is the whole trick.
        val sampleOffset = pnOffset + 4
        if (sampleOffset + 16 > packetEnd) return null
        val mask = headerMask(keys.hp, buf, sampleOffset) ?: return null

        val firstByte = b0 xor (mask[0].toInt() and 0x0F)
        val pnLength = (firstByte and 0x03) + 1
        if (pnOffset + pnLength > packetEnd) return null

        // The AAD is the header exactly as it appears on the wire except with
        // protection removed, so it has to be rebuilt rather than sliced.
        val headerLength = pnOffset + pnLength - offset
        val header = ByteArray(headerLength)
        System.arraycopy(buf, offset, header, 0, headerLength)
        header[0] = firstByte.toByte()
        var packetNumber = 0L
        for (i in 0 until pnLength) {
            val plain = (buf[pnOffset + i].toInt() xor mask[1 + i].toInt()) and 0xFF
            header[headerLength - pnLength + i] = plain.toByte()
            packetNumber = (packetNumber shl 8) or plain.toLong()
        }

        val cipherStart = pnOffset + pnLength
        val cipherLength = packetEnd - cipherStart
        // GCM needs at least its own tag.
        if (cipherLength <= 16) return null

        val plaintext = open(
            key = keys.key,
            iv = keys.iv,
            packetNumber = packetNumber,
            aad = header,
            buf = buf,
            offset = cipherStart,
            length = cipherLength,
        ) ?: return null

        return Opened(plaintext, packetEnd)
    }

    /** Visible for tests, which pin it against RFC 9001's published vector. */
    internal class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)

    internal fun initialKeys(version: Int, dcid: ByteArray): Keys? {
        val salt = when (version) {
            VERSION_1 -> SALT_V1
            VERSION_2 -> SALT_V2
            else -> return null
        }
        val suffix = if (version == VERSION_2) "quicv2 " else "quic "
        val initial = hkdfExtract(salt, dcid)
        val client = hkdfExpandLabel(initial, "client in", 32)
        return Keys(
            key = hkdfExpandLabel(client, suffix + "key", 16),
            iv = hkdfExpandLabel(client, suffix + "iv", 12),
            hp = hkdfExpandLabel(client, suffix + "hp", 16),
        )
    }

    private fun headerMask(hp: ByteArray, buf: ByteArray, sampleOffset: Int): ByteArray? {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
        val sample = ByteArray(16)
        System.arraycopy(buf, sampleOffset, sample, 0, 16)
        return cipher.doFinal(sample)?.takeIf { it.size >= 5 }
    }

    private fun open(
        key: ByteArray,
        iv: ByteArray,
        packetNumber: Long,
        aad: ByteArray,
        buf: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray? {
        // The nonce is the IV with the packet number XORed into its right-hand
        // end, left-padded — RFC 9001 §5.3.
        val nonce = iv.copyOf()
        for (i in 0 until 8) {
            val shift = 8 * (7 - i)
            val b = ((packetNumber ushr shift) and 0xFF).toInt()
            val at = nonce.size - 8 + i
            nonce[at] = (nonce[at].toInt() xor b).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(buf, offset, length)
        } catch (e: javax.crypto.AEADBadTagException) {
            // The ordinary answer for a datagram that merely looked like an
            // Initial, and for one this build's version support cannot read.
            null
        }
    }

    internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    /**
     * HKDF-Expand-Label from TLS 1.3 (RFC 8446 §7.1), which QUIC reuses
     * wholesale. Every output here is at most one HMAC block wide, so the
     * counter never passes 1.
     */
    internal fun hkdfExpandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val full = "tls13 $label".toByteArray(Charsets.US_ASCII)
        val info = ByteArray(2 + 1 + full.size + 1)
        info[0] = ((length shr 8) and 0xFF).toByte()
        info[1] = (length and 0xFF).toByte()
        info[2] = full.size.toByte()
        System.arraycopy(full, 0, info, 3, full.size)
        info[info.size - 1] = 0
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOf(length)
    }

    /** A bounds-checked walk; every accessor answers null rather than throwing. */
    private class ByteReader(val buf: ByteArray, var position: Int, val end: Int) {

        fun u8(): Int {
            if (position >= end) return -1
            return buf[position++].toInt() and 0xFF
        }

        fun u32(): Int? {
            if (position + 4 > end) return null
            val v = Packets.u32(buf, position).toInt()
            position += 4
            return v
        }

        fun bytes(n: Int): ByteArray? {
            if (n < 0 || position + n > end) return null
            val out = ByteArray(n)
            System.arraycopy(buf, position, out, 0, n)
            position += n
            return out
        }

        /**
         * A QUIC variable-length integer, RFC 9000 §16: the top two bits of
         * the first byte give the encoded width, the rest is the value.
         */
        fun varint(): Long? {
            if (position >= end) return null
            val first = buf[position].toInt() and 0xFF
            val width = 1 shl (first shr 6)
            if (position + width > end) return null
            var value = (first and 0x3F).toLong()
            for (i in 1 until width) {
                value = (value shl 8) or (buf[position + i].toLong() and 0xFF)
            }
            position += width
            return value
        }
    }
}
