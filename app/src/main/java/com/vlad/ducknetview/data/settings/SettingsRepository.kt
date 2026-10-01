package com.vlad.ducknetview.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vlad.ducknetview.data.db.Codec
import com.vlad.ducknetview.domain.model.AppSettings
import com.vlad.ducknetview.domain.model.RateUnit
import com.vlad.ducknetview.domain.model.SearchMode
import com.vlad.ducknetview.domain.model.ThroughputMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.duckSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The whole of [AppSettings] in one DataStore(Preferences) file, written on
 * every change rather than on exit — a phone can be killed at any moment.
 *
 * Reading is total: any single stored value that is missing, of the wrong type,
 * or unparseable degrades to that field's default. Nothing here throws, because
 * a settings file that has been corrupted (or written by a newer build) must
 * cost the user their preferences, not the ability to start the app.
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.duckSettingsStore)

    val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { decode(it) }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val next = block(decode(prefs))
            encode(prefs, next)
        }
    }

    private object Keys {
        val intervalSeconds = intPreferencesKey("intervalSeconds")
        val paused = booleanPreferencesKey("paused")
        val rateUnit = stringPreferencesKey("rateUnit")
        val throughputMode = stringPreferencesKey("throughputMode")
        val searchMode = stringPreferencesKey("searchMode")
        val hideNoise = booleanPreferencesKey("hideNoise")
        val revDns = booleanPreferencesKey("revDns")
        val groupByHost = booleanPreferencesKey("groupByHost")
        val showClosed = booleanPreferencesKey("showClosed")
        val lastTab = stringPreferencesKey("lastTab")
        val connsSortCol = stringPreferencesKey("connsSortCol")
        val connsSortDesc = booleanPreferencesKey("connsSortDesc")
        val appsSortCol = stringPreferencesKey("appsSortCol")
        val appsSortDesc = booleanPreferencesKey("appsSortDesc")
        val servicesSortCol = stringPreferencesKey("servicesSortCol")
        val servicesSortDesc = booleanPreferencesKey("servicesSortDesc")
        val domainsSortCol = stringPreferencesKey("domainsSortCol")
        val domainsSortDesc = booleanPreferencesKey("domainsSortDesc")
        val externalIpEnabled = booleanPreferencesKey("externalIpEnabled")
        val latencyTargets = stringPreferencesKey("latencyTargets")
        val watchlist = stringPreferencesKey("watchlist")
        val baseline = stringPreferencesKey("baseline")
        val baselineAt = longPreferencesKey("baselineAt")
        val blockedUids = stringPreferencesKey("blockedUids")
        val excludedUids = stringPreferencesKey("excludedUids")
        val scanFullRange = booleanPreferencesKey("scanFullRange")
        val metricsEnabled = booleanPreferencesKey("metricsEnabled")
        val metricsPort = intPreferencesKey("metricsPort")
        val webhookUrl = stringPreferencesKey("webhookUrl")
        val broadcastOnAlert = booleanPreferencesKey("broadcastOnAlert")
        val alertAppBps = longPreferencesKey("alertAppBps")
        val alertConnBps = longPreferencesKey("alertConnBps")
        val alertRttMs = intPreferencesKey("alertRttMs")
        val alertConnGrowthPolls = intPreferencesKey("alertConnGrowthPolls")
        val alertFanoutHosts = intPreferencesKey("alertFanoutHosts")
        val alertsAckedAt = longPreferencesKey("alertsAckedAt")
        val onboardingShown = booleanPreferencesKey("onboardingShown")
    }

    private fun decode(p: Preferences): AppSettings {
        val d = DEFAULTS
        return AppSettings(
            intervalSeconds = p.int(Keys.intervalSeconds, d.intervalSeconds),
            paused = p.bool(Keys.paused, d.paused),
            rateUnit = p.parsed(Keys.rateUnit, d.rateUnit) { s -> RateUnit.entries.firstOrNull { it.name == s } },
            throughputMode = p.parsed(Keys.throughputMode, d.throughputMode) { s ->
                ThroughputMode.entries.firstOrNull { it.name == s }
            },
            searchMode = p.parsed(Keys.searchMode, d.searchMode) { s ->
                SearchMode.entries.firstOrNull { it.name == s }
            },
            hideNoise = p.bool(Keys.hideNoise, d.hideNoise),
            revDns = p.bool(Keys.revDns, d.revDns),
            groupByHost = p.bool(Keys.groupByHost, d.groupByHost),
            showClosed = p.bool(Keys.showClosed, d.showClosed),
            lastTab = p.text(Keys.lastTab, d.lastTab),
            connsSortCol = p.text(Keys.connsSortCol, d.connsSortCol),
            connsSortDesc = p.bool(Keys.connsSortDesc, d.connsSortDesc),
            appsSortCol = p.text(Keys.appsSortCol, d.appsSortCol),
            appsSortDesc = p.bool(Keys.appsSortDesc, d.appsSortDesc),
            servicesSortCol = p.text(Keys.servicesSortCol, d.servicesSortCol),
            servicesSortDesc = p.bool(Keys.servicesSortDesc, d.servicesSortDesc),
            domainsSortCol = p.text(Keys.domainsSortCol, d.domainsSortCol),
            domainsSortDesc = p.bool(Keys.domainsSortDesc, d.domainsSortDesc),
            externalIpEnabled = p.bool(Keys.externalIpEnabled, d.externalIpEnabled),
            latencyTargets = p.parsed(Keys.latencyTargets, d.latencyTargets, Codec::decodeList),
            watchlist = p.parsed(Keys.watchlist, d.watchlist, Codec::decodeList),
            baseline = p.parsed(Keys.baseline, d.baseline, Codec::decodeList),
            baselineAt = p.long(Keys.baselineAt, d.baselineAt),
            blockedUids = p.parsed(Keys.blockedUids, d.blockedUids, Codec::decodeIntSet),
            excludedUids = p.parsed(Keys.excludedUids, d.excludedUids, Codec::decodeIntSet),
            scanFullRange = p.bool(Keys.scanFullRange, d.scanFullRange),
            metricsEnabled = p.bool(Keys.metricsEnabled, d.metricsEnabled),
            metricsPort = p.int(Keys.metricsPort, d.metricsPort),
            webhookUrl = p.text(Keys.webhookUrl, d.webhookUrl),
            broadcastOnAlert = p.bool(Keys.broadcastOnAlert, d.broadcastOnAlert),
            alertAppBps = p.long(Keys.alertAppBps, d.alertAppBps),
            alertConnBps = p.long(Keys.alertConnBps, d.alertConnBps),
            alertRttMs = p.int(Keys.alertRttMs, d.alertRttMs),
            alertConnGrowthPolls = p.int(Keys.alertConnGrowthPolls, d.alertConnGrowthPolls),
            alertFanoutHosts = p.int(Keys.alertFanoutHosts, d.alertFanoutHosts),
            alertsAckedAt = p.long(Keys.alertsAckedAt, d.alertsAckedAt),
            onboardingShown = p.bool(Keys.onboardingShown, d.onboardingShown),
        )
    }

    private fun encode(p: MutablePreferences, s: AppSettings) {
        p[Keys.intervalSeconds] = s.intervalSeconds
        p[Keys.paused] = s.paused
        p[Keys.rateUnit] = s.rateUnit.name
        p[Keys.throughputMode] = s.throughputMode.name
        p[Keys.searchMode] = s.searchMode.name
        p[Keys.hideNoise] = s.hideNoise
        p[Keys.revDns] = s.revDns
        p[Keys.groupByHost] = s.groupByHost
        p[Keys.showClosed] = s.showClosed
        p[Keys.lastTab] = s.lastTab
        p[Keys.connsSortCol] = s.connsSortCol
        p[Keys.connsSortDesc] = s.connsSortDesc
        p[Keys.appsSortCol] = s.appsSortCol
        p[Keys.appsSortDesc] = s.appsSortDesc
        p[Keys.servicesSortCol] = s.servicesSortCol
        p[Keys.servicesSortDesc] = s.servicesSortDesc
        p[Keys.domainsSortCol] = s.domainsSortCol
        p[Keys.domainsSortDesc] = s.domainsSortDesc
        p[Keys.externalIpEnabled] = s.externalIpEnabled
        p[Keys.latencyTargets] = Codec.encodeList(s.latencyTargets)
        p[Keys.watchlist] = Codec.encodeList(s.watchlist)
        p[Keys.baseline] = Codec.encodeList(s.baseline)
        p[Keys.baselineAt] = s.baselineAt
        p[Keys.blockedUids] = Codec.encodeIntSet(s.blockedUids)
        p[Keys.excludedUids] = Codec.encodeIntSet(s.excludedUids)
        p[Keys.scanFullRange] = s.scanFullRange
        p[Keys.metricsEnabled] = s.metricsEnabled
        p[Keys.metricsPort] = s.metricsPort
        p[Keys.webhookUrl] = s.webhookUrl
        p[Keys.broadcastOnAlert] = s.broadcastOnAlert
        p[Keys.alertAppBps] = s.alertAppBps
        p[Keys.alertConnBps] = s.alertConnBps
        p[Keys.alertRttMs] = s.alertRttMs
        p[Keys.alertConnGrowthPolls] = s.alertConnGrowthPolls
        p[Keys.alertFanoutHosts] = s.alertFanoutHosts
        p[Keys.alertsAckedAt] = s.alertsAckedAt
        p[Keys.onboardingShown] = s.onboardingShown
    }

    private companion object {
        val DEFAULTS = AppSettings()

        /*
         * Preferences.get() casts unchecked, so a key whose stored type no longer
         * matches would blow up somewhere downstream. These readers go through
         * asMap() and use a real checked cast instead, which turns a type
         * mismatch into "field absent" — one lost preference, not a crash.
         */

        fun Preferences.bool(key: Preferences.Key<Boolean>, default: Boolean): Boolean =
            asMap()[key] as? Boolean ?: default

        fun Preferences.int(key: Preferences.Key<Int>, default: Int): Int =
            asMap()[key] as? Int ?: default

        fun Preferences.long(key: Preferences.Key<Long>, default: Long): Long =
            asMap()[key] as? Long ?: default

        fun Preferences.text(key: Preferences.Key<String>, default: String): String =
            asMap()[key] as? String ?: default

        fun <R> Preferences.parsed(
            key: Preferences.Key<String>,
            default: R,
            decode: (String) -> R?,
        ): R {
            val raw = asMap()[key] as? String ?: return default
            return try {
                decode(raw) ?: default
            } catch (t: Throwable) {
                default
            }
        }
    }
}
