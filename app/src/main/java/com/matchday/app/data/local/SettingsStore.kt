package com.matchday.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.matchday.app.domain.Catalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val enabledLeagues: Set<String> = Catalog.DEFAULT_ENABLED,
    val refreshMinutes: Int = 60,
    val useDeviceZone: Boolean = false,
    val darkTheme: Boolean = true,
    val notifyUpcoming: Boolean = true,
    val notifyOdds: Boolean = true,
    val oddsMoveThreshold: Int = 8,
    val notifyLineups: Boolean = true,
    val notifyInjuries: Boolean = true,
    val notifyPredictions: Boolean = true,
    val lastRefresh: Long = 0,
    val oneXBetKey: String = "",          // user's own free The Odds API key (for genuine 1xBet prices)
    val autoLoadOneXBet: Boolean = true,  // load 1xBet odds automatically when a football match opens
    val oddsApiRemaining: Int = -1,       // credits left this month, from the API's response headers
    val oddsApiPausedUntil: Long = 0,     // free credits used up: no API calls before this time (start of next month)
    val footballDataKey: String = "",     // optional football-data.org key (home/away tables)
    val footballDataPausedUntil: Long = 0,
    val apiFootballKey: String = "",      // optional API-Football key (injuries, line-ups)
    val apiFootballPausedUntil: Long = 0,
    val apiFootballRemaining: Int = -1,   // from the x-ratelimit-requests-remaining header (remaining today)
)

class SettingsStore(private val context: Context) {
    private object K {
        val leagues = stringSetPreferencesKey("leagues_v2")
        val refresh = intPreferencesKey("refresh_minutes_v2")
        val deviceZone = booleanPreferencesKey("device_zone")
        val dark = booleanPreferencesKey("dark")
        val nUpcoming = booleanPreferencesKey("n_upcoming")
        val nOdds = booleanPreferencesKey("n_odds")
        val oddsThreshold = intPreferencesKey("odds_threshold")
        val nLineups = booleanPreferencesKey("n_lineups")
        val nInjuries = booleanPreferencesKey("n_injuries")
        val nPredictions = booleanPreferencesKey("n_predictions")
        val lastRefresh = longPreferencesKey("last_refresh")
        val oneXKey = stringPreferencesKey("onexbet_key")
        val oneXAuto = booleanPreferencesKey("onexbet_auto")
        val oddsRemaining = intPreferencesKey("odds_api_remaining")
        val oddsPaused = longPreferencesKey("odds_api_paused_until")
        val fdKey = stringPreferencesKey("fd_key")
        val fdPaused = longPreferencesKey("fd_paused_until")
        val afKey = stringPreferencesKey("af_key")
        val afPaused = longPreferencesKey("af_paused_until")
        val afRemaining = intPreferencesKey("af_remaining")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        enabledLeagues = this[K.leagues] ?: Catalog.DEFAULT_ENABLED,
        refreshMinutes = this[K.refresh] ?: 60,
        useDeviceZone = this[K.deviceZone] ?: false,
        darkTheme = this[K.dark] ?: true,
        notifyUpcoming = this[K.nUpcoming] ?: true,
        notifyOdds = this[K.nOdds] ?: true,
        oddsMoveThreshold = this[K.oddsThreshold] ?: 8,
        notifyLineups = this[K.nLineups] ?: true,
        notifyInjuries = this[K.nInjuries] ?: true,
        notifyPredictions = this[K.nPredictions] ?: true,
        lastRefresh = this[K.lastRefresh] ?: 0L,
        oneXBetKey = this[K.oneXKey] ?: "",
        autoLoadOneXBet = this[K.oneXAuto] ?: true,
        oddsApiRemaining = this[K.oddsRemaining] ?: -1,
        oddsApiPausedUntil = this[K.oddsPaused] ?: 0L,
        footballDataKey = this[K.fdKey] ?: "",
        footballDataPausedUntil = this[K.fdPaused] ?: 0L,
        apiFootballKey = this[K.afKey] ?: "",
        apiFootballPausedUntil = this[K.afPaused] ?: 0L,
        apiFootballRemaining = this[K.afRemaining] ?: -1,
    )

    suspend fun toggleLeague(key: String, enabled: Boolean) {
        context.dataStore.edit {
            val cur = it[K.leagues] ?: Catalog.DEFAULT_ENABLED
            it[K.leagues] = if (enabled) cur + key else cur - key
        }
    }

    /** Switches on newly added competitions once, without undoing the user's own choices. */
    suspend fun addNewDefaults(version: String, keys: Set<String>) {
        val flag = booleanPreferencesKey("defaults_added_$version")
        context.dataStore.edit {
            if (it[flag] == true) return@edit
            val cur = it[K.leagues]
            if (cur != null) it[K.leagues] = cur + keys
            it[flag] = true
        }
    }

    suspend fun setRefreshMinutes(m: Int) { context.dataStore.edit { it[K.refresh] = m } }
    suspend fun setDeviceZone(v: Boolean) { context.dataStore.edit { it[K.deviceZone] = v } }
    suspend fun setDark(v: Boolean) { context.dataStore.edit { it[K.dark] = v } }
    suspend fun setOddsThreshold(v: Int) { context.dataStore.edit { it[K.oddsThreshold] = v } }

    suspend fun setNotify(kind: String, v: Boolean) {
        val key = when (kind) {
            "upcoming" -> K.nUpcoming
            "odds" -> K.nOdds
            "lineups" -> K.nLineups
            "injuries" -> K.nInjuries
            "predictions" -> K.nPredictions
            else -> null
        } ?: return
        context.dataStore.edit { it[key] = v }
    }

    /** A new key clears any "credits used up" pause. */
    suspend fun setOneXBetKey(v: String) { context.dataStore.edit { it[K.oneXKey] = v.trim(); it[K.oddsPaused] = 0L; it[K.oddsRemaining] = -1 } }
    suspend fun setAutoLoadOneXBet(v: Boolean) { context.dataStore.edit { it[K.oneXAuto] = v } }
    suspend fun setOddsApiRemaining(v: Int) { context.dataStore.edit { it[K.oddsRemaining] = v } }
    suspend fun setOddsApiPausedUntil(v: Long) { context.dataStore.edit { it[K.oddsPaused] = v } }
    suspend fun setFootballDataKey(v: String) { context.dataStore.edit { it[K.fdKey] = v.trim(); it[K.fdPaused] = 0L } }
    suspend fun setFootballDataPausedUntil(v: Long) { context.dataStore.edit { it[K.fdPaused] = v } }
    suspend fun setApiFootballKey(v: String) { context.dataStore.edit { it[K.afKey] = v.trim(); it[K.afPaused] = 0L; it[K.afRemaining] = -1 } }
    suspend fun setApiFootballPausedUntil(v: Long) { context.dataStore.edit { it[K.afPaused] = v } }
    suspend fun setApiFootballRemaining(v: Int) { context.dataStore.edit { it[K.afRemaining] = v } }

    suspend fun markRefreshed(at: Long) { context.dataStore.edit { it[K.lastRefresh] = at } }
}
