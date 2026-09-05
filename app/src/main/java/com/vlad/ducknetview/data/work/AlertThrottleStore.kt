package com.vlad.ducknetview.data.work

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.duckAlertThrottleStore: DataStore<Preferences> by
    preferencesDataStore(name = "work_alert_throttle")

/**
 * When each `proto|bind:port` was last alerted on.
 *
 * This has to survive process death, not merely a scan: a periodic worker is
 * handed a fresh process on almost every run, so an in-memory map would forget
 * the previous alert every time and re-notify on each scan — the exact spam the
 * rate limit exists to prevent.
 *
 * DataStore rather than a Room table because the shared database has an
 * exported schema and versioned migrations; a throttle ledger is disposable
 * bookkeeping, not history worth migrating, and adding a table for it would put
 * a migration in everyone else's way.
 *
 * Reads never throw. A corrupt or unreadable ledger costs at most one duplicate
 * notification, which is a far better outcome than a worker that keeps failing.
 */
class AlertThrottleStore(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.duckAlertThrottleStore)

    suspend fun lastAlerted(): Map<String, Long> {
        val prefs = store.data.catch { emit(emptyPreferences()) }.first()
        val out = LinkedHashMap<String, Long>()
        for ((key, value) in prefs.asMap()) {
            if (!key.name.startsWith(PREFIX)) continue
            val at = value as? Long ?: continue
            out[key.name.removePrefix(PREFIX)] = at
        }
        return out
    }

    /**
     * Stamps [keys] as alerted at [now] and evicts entries older than
     * [retainMillis], which is what keeps the ledger from growing one row per
     * port the device has ever briefly opened.
     */
    suspend fun record(
        keys: Collection<String>,
        now: Long,
        retainMillis: Long = RETAIN_MILLIS,
    ) {
        try {
            store.edit { prefs ->
                for (key in keys) {
                    if (key.isEmpty()) continue
                    prefs[longPreferencesKey(PREFIX + key)] = now
                }
                val stale = prefs.asMap().entries
                    .filter { (k, v) ->
                        k.name.startsWith(PREFIX) && v is Long && now - v > retainMillis
                    }
                    .map { it.key.name }
                for (name in stale) prefs.remove(longPreferencesKey(name))
            }
        } catch (e: Exception) {
            // A ledger that cannot be written means the next run may repeat one
            // notification; failing the worker over it would be worse.
        }
    }

    suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (e: Exception) {
            // nothing useful to do with an unwritable throttle ledger
        }
    }

    companion object {
        private const val PREFIX = "offBaseline:"

        /** Twice the alert window, so an evicted entry can never un-suppress one. */
        const val RETAIN_MILLIS = 2 * ServiceScanLogic.RATE_LIMIT_MILLIS
    }
}
