package com.vlad.ducknetview.engine.vpn.packet

/**
 * Reads A/AAAA answers out of DNS responses that pass through the TUN, so the
 * connection table can label a remote address without ever issuing a PTR
 * query of its own. Only questions and answers are read; nothing is stored or
 * forwarded anywhere.
 */
object DnsPeek {

    data class Answer(val name: String, val ip: String)

    /**
     * One answered question: the name the device asked for and the addresses it
     * was given. The question matters as much as the answers — a CNAME chain
     * answers under the final name, and what the user recognises is the one
     * their app asked about.
     */
    data class Observation(val question: String, val answers: List<Answer>) {
        val addresses: List<String> get() = answers.map { it.ip }
    }

    fun parseAnswers(payload: ByteArray): List<Answer> = parse(payload)?.answers ?: emptyList()

    fun parse(payload: ByteArray): Observation? {
        if (payload.size < 12) return null
        val flags = Packets.u16(payload, 2)
        if (flags and 0x8000 == 0) return null // question, not a response
        if (flags and 0x000F != 0) return null // rcode != NOERROR

        val qdCount = Packets.u16(payload, 4)
        val anCount = Packets.u16(payload, 6)
        if (anCount == 0) return null

        var offset = 12
        var question = ""
        repeat(qdCount) {
            if (question.isEmpty()) question = plausibleName(payload, offset)
            val skipped = skipName(payload, offset) ?: return null
            offset = skipped + 4
            if (offset > payload.size) return null
        }

        val out = ArrayList<Answer>(anCount)
        repeat(anCount) {
            val nameEnd = skipName(payload, offset) ?: return Observation(question, out)
            if (nameEnd + 10 > payload.size) return Observation(question, out)
            val name = plausibleName(payload, offset)
            val type = Packets.u16(payload, nameEnd)
            val rdLength = Packets.u16(payload, nameEnd + 8)
            val rdOffset = nameEnd + 10
            if (rdOffset + rdLength > payload.size) return Observation(question, out)
            when {
                // An unreadable or implausible name labels nothing, and the row
                // falls back to the bare address rather than showing whatever
                // the responder put there.
                name.isEmpty() -> Unit
                type == TYPE_A && rdLength == 4 ->
                    out += Answer(name, Packets.ipv4ToString(payload.copyOfRange(rdOffset, rdOffset + 4)))
                type == TYPE_AAAA && rdLength == 16 ->
                    out += Answer(name, Packets.ipv6ToString(payload.copyOfRange(rdOffset, rdOffset + 16)))
            }
            offset = rdOffset + rdLength
        }
        return Observation(question, out)
    }

    /**
     * The name at [start], or "" when there is none this app will show.
     *
     * The bytes are whatever answered the lookup, so the result is held to the
     * same rule as a name read from a TLS handshake — see [HostNames]. Before
     * this check a response could put control characters, newlines and, through
     * compression-pointer expansion, multi-kilobyte strings straight into the
     * Domains screen, the Room store and the CSV export.
     */
    private fun plausibleName(buf: ByteArray, start: Int): String {
        val name = readName(buf, start, 0) ?: return ""
        return if (HostNames.isPlausible(name)) name else ""
    }

    /** Returns the offset just past the name, following no pointers. */
    private fun skipName(buf: ByteArray, start: Int): Int? {
        var i = start
        var guard = 0
        while (i < buf.size && guard++ < 128) {
            val len = buf[i].toInt() and 0xFF
            when {
                len == 0 -> return i + 1
                len and 0xC0 == 0xC0 -> return if (i + 2 <= buf.size) i + 2 else null
                else -> i += len + 1
            }
        }
        return null
    }

    /** Compression pointers are followed, with a depth guard against loops. */
    private fun readName(buf: ByteArray, start: Int, depth: Int): String? {
        if (depth > 8) return null
        val sb = StringBuilder()
        var i = start
        var guard = 0
        while (i < buf.size && guard++ < 128) {
            val len = buf[i].toInt() and 0xFF
            when {
                len == 0 -> return sb.toString()
                len and 0xC0 == 0xC0 -> {
                    if (i + 1 >= buf.size) return null
                    val ptr = ((len and 0x3F) shl 8) or (buf[i + 1].toInt() and 0xFF)
                    val rest = readName(buf, ptr, depth + 1) ?: return null
                    if (sb.isNotEmpty() && rest.isNotEmpty()) sb.append('.')
                    sb.append(rest)
                    return if (sb.length > HostNames.MAX_LENGTH) null else sb.toString()
                }
                else -> {
                    if (i + 1 + len > buf.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    sb.append(String(buf, i + 1, len, Charsets.US_ASCII))
                    // Give up as soon as the name cannot be a legal one rather
                    // than building the whole of it to throw away: compression
                    // pointers let a few hundred bytes describe tens of
                    // kilobytes.
                    if (sb.length > HostNames.MAX_LENGTH) return null
                    i += len + 1
                }
            }
        }
        return null
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
}
