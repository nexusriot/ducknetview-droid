package com.vlad.ducknetview.data

import android.content.Context
import android.net.Uri
import com.vlad.ducknetview.domain.asn.AsnTable
import com.vlad.ducknetview.domain.firewall.Blocklist
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Files the user imports and the app keeps: a hosts-format block list and an
 * ip2asn table. Both are far too large for DataStore, so each is copied into
 * the app's own storage once and parsed into memory on start.
 *
 * Nothing is bundled and nothing is fetched. Without a file the feature does
 * not exist — no OWNER column, no tracker badges — rather than existing empty.
 */
class ImportStore(context: Context) {

    private val dir = context.applicationContext.filesDir
    private val resolver = context.applicationContext.contentResolver
    private val blocklistFile = File(dir, BLOCKLIST)
    private val asnFile = File(dir, ASN)

    private val _blocklist = MutableStateFlow<Set<String>>(emptySet())
    val blocklist: StateFlow<Set<String>> = _blocklist.asStateFlow()

    private val _asn = MutableStateFlow(AsnTable.EMPTY)
    val asn: StateFlow<AsnTable> = _asn.asStateFlow()

    /** Read whatever was imported before. Failures leave the feature absent. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (blocklistFile.exists()) {
            runCatching { blocklistFile.bufferedReader().useLines { Blocklist.parse(it) } }
                .onSuccess { _blocklist.value = it }
        }
        if (asnFile.exists()) {
            runCatching { asnFile.inputStream().use { AsnTable.parse(it) } }
                .onSuccess { _asn.value = it }
        }
    }

    /**
     * Copy, parse and keep a block list. Answers how many names it holds, or
     * throws with a reason the screen can show — a file with no usable lines
     * is refused rather than installed as a list that blocks nothing.
     */
    suspend fun importBlocklist(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$BLOCKLIST.tmp")
        copy(uri, tmp)
        val parsed = tmp.bufferedReader().useLines { Blocklist.parse(it) }
        if (parsed.isEmpty()) {
            tmp.delete()
            error("no host names found in that file")
        }
        tmp.renameTo(blocklistFile)
        _blocklist.value = parsed
        parsed.size
    }

    /** As [importBlocklist], for an ip2asn TSV (plain or gzipped). */
    suspend fun importAsn(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$ASN.tmp")
        copy(uri, tmp)
        val table = tmp.inputStream().use { AsnTable.parse(it) }
        if (table.size == 0) {
            tmp.delete()
            error("no usable ip2asn rows in that file")
        }
        tmp.renameTo(asnFile)
        _asn.value = table
        table.size
    }

    suspend fun clearBlocklist() = withContext(Dispatchers.IO) {
        blocklistFile.delete()
        _blocklist.value = emptySet()
    }

    suspend fun clearAsn() = withContext(Dispatchers.IO) {
        asnFile.delete()
        _asn.value = AsnTable.EMPTY
    }

    private fun copy(uri: Uri, to: File) {
        val input: InputStream = resolver.openInputStream(uri) ?: error("could not open the file")
        input.use { src -> to.outputStream().use { src.copyTo(it) } }
    }

    private companion object {
        const val BLOCKLIST = "blocklist.txt"
        const val ASN = "ip2asn.tsv"
    }
}
