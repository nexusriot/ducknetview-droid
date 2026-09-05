package com.vlad.ducknetview.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an export into the app's cache and hands back a shareable content
 * URI. Files are timestamped the same way the TUI names its CSVs.
 */
class Exporter(private val context: Context) {

    suspend fun write(baseName: String, content: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            prune(dir)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val extension = if (content.trimStart().startsWith("{")) "json" else "csv"
            val file = File(dir, "${baseName}_$stamp.$extension")
            file.writeText(content)
            FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        }.getOrNull()
    }

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val KEEP = 20
    }
}
