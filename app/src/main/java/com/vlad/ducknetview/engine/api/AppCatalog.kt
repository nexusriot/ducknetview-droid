package com.vlad.ducknetview.engine.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.vlad.ducknetview.domain.model.AppRow
import java.util.concurrent.ConcurrentHashMap

/**
 * UID -> app identity. On Android the UID *is* the process identity the TUI's
 * Processes tab was really about, so this is the whole "who owns this traffic"
 * lookup.
 *
 * Every call degrades instead of throwing: an unknown UID is a normal outcome
 * (kernel traffic, a just-uninstalled app, a UID outside package visibility)
 * and must never take a poll tick down.
 */
class AppCatalog(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager? = try {
        appContext.packageManager
    } catch (e: Exception) {
        null
    }

    private val cache = ConcurrentHashMap<Int, AppRow>()

    @Volatile
    private var installed: List<AppRow>? = null

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            invalidate()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registered = try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // already gone
        }
    }

    fun invalidate() {
        cache.clear()
        installed = null
    }

    fun row(uid: Int): AppRow = cache.getOrPut(uid) { build(uid) }

    fun label(uid: Int): String = row(uid).label

    /** Non-system apps only — the Apps screen's `u` filter. */
    fun userAppUids(): Set<Int> =
        all().asSequence().filter { !it.isSystem }.map { it.uid }.toSet()

    fun allUids(): Set<Int> = all().asSequence().map { it.uid }.toSet()

    @Suppress("DEPRECATION")
    fun all(): List<AppRow> {
        installed?.let { return it }
        val manager = pm
        val rows = if (manager == null) {
            emptyList()
        } else {
            try {
                manager.getInstalledApplications(0)
                    .asSequence()
                    .map { it.uid }
                    .distinct()
                    .map { row(it) }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            } catch (e: Exception) {
                emptyList()
            }
        }
        installed = rows
        return rows
    }

    private fun build(uid: Int): AppRow {
        if (uid < 0) return AppRow(uid = uid, packageName = "", label = fallbackLabel(uid))
        val manager = pm ?: return AppRow(uid, "", fallbackLabel(uid), isSystem = isSystemUid(uid))
        val packages = try {
            manager.getPackagesForUid(uid)
        } catch (e: Exception) {
            null
        }
        if (packages.isNullOrEmpty()) {
            val name = try {
                manager.getNameForUid(uid)
            } catch (e: Exception) {
                null
            }
            return AppRow(
                uid = uid,
                packageName = "",
                label = prettyUidName(name) ?: fallbackLabel(uid),
                isSystem = isSystemUid(uid),
            )
        }
        if (packages.size == 1) {
            val pkg = packages[0]
            val info = appInfo(manager, pkg)
            return AppRow(
                uid = uid,
                packageName = pkg,
                label = info?.let { labelOf(manager, it) } ?: pkg,
                isSystem = info?.let { isSystemApp(it) } ?: isSystemUid(uid),
            )
        }
        // A shared UID has no single app to name it after; the platform's own
        // "shared:android.uid.system" style name is the honest label.
        val shared = try {
            manager.getNameForUid(uid)
        } catch (e: Exception) {
            null
        }
        val infos = packages.mapNotNull { appInfo(manager, it) }
        return AppRow(
            uid = uid,
            packageName = packages.sorted().first(),
            label = prettyUidName(shared) ?: fallbackLabel(uid),
            isSystem = isSystemUid(uid) || infos.any { isSystemApp(it) },
        )
    }

    @Suppress("DEPRECATION")
    private fun appInfo(manager: PackageManager, pkg: String): ApplicationInfo? = try {
        manager.getApplicationInfo(pkg, 0)
    } catch (e: Exception) {
        null
    }

    private fun labelOf(manager: PackageManager, info: ApplicationInfo): String = try {
        manager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() } ?: info.packageName
    } catch (e: Exception) {
        info.packageName
    }

    companion object {

        internal fun fallbackLabel(uid: Int): String = when (uid) {
            Process.INVALID_UID -> "uid ${Process.INVALID_UID}"
            ROOT_UID -> "root"
            SYSTEM_UID -> "system"
            else -> "uid $uid"
        }

        internal fun prettyUidName(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (trimmed.startsWith("shared:")) {
                return trimmed.removePrefix("shared:").takeIf { it.isNotBlank() } ?: trimmed
            }
            return trimmed
        }

        internal fun isSystemApp(info: ApplicationInfo): Boolean =
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        /** Below the first app UID the kernel and platform own the range. */
        internal fun isSystemUid(uid: Int): Boolean = uid in 0 until FIRST_APPLICATION_UID

        const val ROOT_UID = 0
        const val SYSTEM_UID = 1000
        const val FIRST_APPLICATION_UID = 10000
    }
}
