package com.matchday.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    )

    suspend fun toggleLeague(key: String, enabled: Boolean) {
        context.dataStore.edit {
            val cur = it[K.leagues] ?: Catalog.DEFAULT_ENABLED
            it[K.leagues] = if (enabled) cur + key else cur - key
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


    suspend fun markRefreshed(at: Long) { context.dataStore.edit { it[K.lastRefresh] = at } }
}
