package com.vlad.ducknetview.testing

import com.vlad.ducknetview.engine.vpn.packet.QuicPeek
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Builds the QUIC client Initial packets the peek is supposed to read.
 *
 * Shared between the JVM test, which builds a packet and reads it straight
 * back, and the device test, which puts the same bytes on the wire through
 * the TUN. One copy on purpose: this is a crypto construction, and a second
 * copy is the thing that drifts until one of the two suites is quietly
 * testing something other than what ships.
 *
 * The protection applied here is the inverse of what
 * [com.vlad.ducknetview.engine.vpn.packet.QuicPeek] undoes, and the key
 * schedule both use is pinned against RFC 9001 Appendix A by
 * `QuicPeekTest`.
 */
object QuicPackets {

    /**
     * A client Initial carrying a ClientHello, protected exactly as RFC 9001
     * says a client must protect one. This is the encryption side of what the
     * peek undoes, and the key schedule it uses is the one pinned above.
     */
    fun initial(
        sni: String?,
        version: Int = QuicPeek.VERSION_1,
        cryptoOffset: Long = 0L,
        splitCryptoAt: Int = 0,
        leadingPadding: Int = 0,
        cryptoLengthOverride: Long? = null,
        dcid: ByteArray = hex("8394c8f03e515708"),
    ): ByteArray {
        val hello = clientHello(sni)
        val frames = ArrayList<Byte>()
        repeat(leadingPadding) { frames += 0x00 }
        if (splitCryptoAt in 1 until hello.size) {
            frames += cryptoFrame(cryptoOffset, hello.copyOfRange(0, splitCryptoAt), null)
            frames += cryptoFrame(
                cryptoOffset + splitCryptoAt,
                hello.copyOfRange(splitCryptoAt, hello.size),
                null,
            )
        } else {
            frames += cryptoFrame(cryptoOffset, hello, cryptoLengthOverride)
        }
        return buildInitial(frames, version = version, dcid = dcid)
    }

    /** An Initial carrying [data] as a single CRYPTO frame at [cryptoOffset]. */
    fun initialCarrying(
        data: ByteArray,
        cryptoOffset: Long,
        pad: Boolean = true,
    ): ByteArray = buildInitial(ArrayList(cryptoFrame(cryptoOffset, data, null)), pad = pad)

    /** Protect a frame payload as a client Initial, exactly as RFC 9001 says. */
    fun buildInitial(
        frames: ArrayList<Byte>,
        version: Int = QuicPeek.VERSION_1,
        dcid: ByteArray = hex("8394c8f03e515708"),
        pad: Boolean = true,
    ): ByteArray {
        // A client's first Initial is padded to at least 1200 bytes.
        if (pad) while (frames.size < 1162) frames += 0x00
        val plaintext = frames.toByteArray()

        val keys = QuicPeek.initialKeys(version, dcid)!!
        val packetNumber = 0L
        val pnBytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val typeBits = if (version == QuicPeek.VERSION_2) 0x01 else 0x00
        val firstByte = 0xC0 or (typeBits shl 4) or 0x03 // 4-byte packet number

        val header = ArrayList<Byte>()
        header += firstByte.toByte()
        header += byteArrayOf(
            ((version ushr 24) and 0xFF).toByte(),
            ((version ushr 16) and 0xFF).toByte(),
            ((version ushr 8) and 0xFF).toByte(),
            (version and 0xFF).toByte(),
        ).toList()
        header += dcid.size.toByte()
        header += dcid.toList()
        header += 0x00 // zero-length source connection id
        header += 0x00 // zero-length token
        header += varint((pnBytes.size + plaintext.size + 16).toLong()).toList()
        val pnOffset = header.size
        header += pnBytes.toList()

        val aad = header.toByteArray()
        val sealed = seal(keys.key, keys.iv, packetNumber, aad, plaintext)

        val packet = aad + sealed
        // Header protection last, as the sender does: the sample is taken
        // from the ciphertext that is now in place.
        val mask = aesEcb(keys.hp, packet.copyOfRange(pnOffset + 4, pnOffset + 20))
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
        for (i in pnBytes.indices) {
            packet[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }
        return packet
    }

    fun cryptoFrame(offset: Long, data: ByteArray, lengthOverride: Long?): List<Byte> {
        val out = ArrayList<Byte>()
        out += 0x06
        out += varint(offset).toList()
        out += varint(lengthOverride ?: data.size.toLong()).toList()
        out += data.toList()
        return out
    }

    /** A bare ClientHello handshake message: no TLS record wrapper, as QUIC carries it. */
    fun clientHello(sni: String?): ByteArray {
        val extensions = ArrayList<Byte>()
        if (sni != null) {
            val host = sni.toByteArray(Charsets.US_ASCII)
            val entry = ArrayList<Byte>()
            entry += 0x00 // host_name
            entry += u16(host.size)
            entry += host.toList()
            val list = ArrayList<Byte>()
            list += u16(entry.size)
            list += entry
            extensions += u16(0x0000) // server_name
            extensions += u16(list.size)
            extensions += list
        }
        // A second extension, so the walk has to step over one to reach the
        // first when SNI is absent and past one when it is present.
        extensions += u16(0x002b) // supported_versions
        extensions += u16(3)
        extensions += listOf<Byte>(0x02, 0x03, 0x04)

        val body = ArrayList<Byte>()
        body += u16(0x0303)
        repeat(32) { body += 0x2A }
        body += 0x00 // empty session id
        body += u16(2) // cipher suites
        body += listOf<Byte>(0x13, 0x01)
        body += 0x01 // compression methods
        body += 0x00
        body += u16(extensions.size)
        body += extensions

        val out = ArrayList<Byte>()
        out += 0x01 // ClientHello
        out += listOf(
            ((body.size shr 16) and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        out += body
        return out.toByteArray()
    }

    fun seal(
        key: ByteArray,
        iv: ByteArray,
        packetNumber: Long,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val nonce = iv.copyOf()
        for (i in 0 until 8) {
            val b = ((packetNumber ushr (8 * (7 - i))) and 0xFF).toInt()
            val at = nonce.size - 8 + i
            nonce[at] = (nonce[at].toInt() xor b).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun aesEcb(key: ByteArray, sample: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(sample)
    }

    fun varint(v: Long): ByteArray = when {
        v < 0x40 -> byteArrayOf(v.toByte())
        v < 0x4000 -> byteArrayOf((0x40 or (v shr 8).toInt()).toByte(), v.toByte())
        v < 0x4000_0000 -> byteArrayOf(
            (0x80 or (v shr 24).toInt()).toByte(),
            (v shr 16).toByte(),
            (v shr 8).toByte(),
            v.toByte(),
        )

        else -> byteArrayOf(
            (0xC0 or (v shr 56).toInt()).toByte(),
            (v shr 48).toByte(),
            (v shr 40).toByte(),
            (v shr 32).toByte(),
            (v shr 24).toByte(),
            (v shr 16).toByte(),
            (v shr 8).toByte(),
            v.toByte(),
        )
    }

    fun u16(v: Int) = listOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
