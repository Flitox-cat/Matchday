package com.matchday.app.data.repo

import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.AfFixtureItem
import com.matchday.app.data.remote.AfFixturesResponse
import com.matchday.app.data.remote.AfInjuriesResponse
import com.matchday.app.data.remote.AfLineup
import com.matchday.app.data.remote.AfLineupsResponse
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.InjuryInfo
import com.matchday.app.domain.TeamMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import kotlin.math.abs

/**
 * API-Football (free plan: 100 requests/day). Used sparingly for things no free source
 * offers: venues, injuries/suspensions, confirmed line-ups and the live match minute.
 */
class ApiFootballRepository(
    private val cache: JsonCache,
    private val settings: SettingsStore,
) {
    private val api = Network.apiFootball

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private companion object {
        const val THREE_HOURS = 3 * 3_600_000L
        const val FOUR_HOURS = 4 * 3_600_000L
        const val TEN_MIN = 10 * 60_000L
        const val ONE_MIN = 60_000L
    }

    private suspend fun key(): String? = settings.current().effectiveAfKey.ifBlank { null }

    suspend fun hasKey(): Boolean = key() != null

    private fun errorText(errors: JsonElement?): String? = when (errors) {
        is JsonObject -> if (errors.isEmpty()) null else errors.entries.joinToString { "${it.key}: ${it.value}" }
        is JsonArray -> if (errors.isEmpty()) null else errors.joinToString()
        else -> null
    }

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        _lastError.value = "API-Football: ${e.message ?: e.javaClass.simpleName}"
        null
    }

    /** Fixtures for a UTC date (yyyy-MM-dd). */
    suspend fun fixtures(date: String): List<AfFixtureItem> {
        val k = key() ?: return emptyList()
        return safe {
            cache.get("af:fixtures:$date", THREE_HOURS, AfFixturesResponse.serializer()) {
                val r = api.fixturesByDate(k, date)
                errorText(r.errors)?.let { throw IllegalStateException(it) }
                r
            }.response
        } ?: emptyList()
    }

    suspend fun live(): List<AfFixtureItem> {
        val k = key() ?: return emptyList()
        return safe {
            cache.get("af:live", ONE_MIN, AfFixturesResponse.serializer()) {
                val r = api.liveFixtures(k)
                errorText(r.errors)?.let { throw IllegalStateException(it) }
                r
            }.response
        } ?: emptyList()
    }

    suspend fun injuries(fixtureId: Int, homeTeamId: Int?, awayTeamId: Int?): InjuryInfo {
        val k = key() ?: return InjuryInfo(known = false)
        val resp = safe {
            cache.get("af:injuries:$fixtureId", FOUR_HOURS, AfInjuriesResponse.serializer()) {
                val r = api.injuries(k, fixtureId)
                errorText(r.errors)?.let { throw IllegalStateException(it) }
                r
            }
        } ?: return InjuryInfo(known = false)
        fun names(teamId: Int?, doubtful: Boolean) = resp.response
            .filter { it.team.id == teamId && ((it.player.type == "Questionable") == doubtful) }
            .map { inj -> listOfNotNull(inj.player.name, inj.player.reason?.let { "($it)" }).joinToString(" ") }
            .distinct()
        return InjuryInfo(
            known = true,
            homeOut = names(homeTeamId, false), homeDoubtful = names(homeTeamId, true),
            awayOut = names(awayTeamId, false), awayDoubtful = names(awayTeamId, true),
        )
    }

    suspend fun lineups(fixtureId: Int): List<AfLineup> {
        val k = key() ?: return emptyList()
        return safe {
            cache.get("af:lineups:$fixtureId", TEN_MIN, AfLineupsResponse.serializer()) {
                val r = api.lineups(k, fixtureId)
                errorText(r.errors)?.let { throw IllegalStateException(it) }
                r
            }.response
        } ?: emptyList()
    }

    fun find(candidates: List<AfFixtureItem>, home: String, away: String, kickoff: Long): AfFixtureItem? =
        candidates.firstOrNull { f ->
            val t = parse(f.fixture.date)
            abs(t - kickoff) <= 3 * 3_600_000L &&
                TeamMatcher.same(home, f.teams.home.name ?: "") &&
                TeamMatcher.same(away, f.teams.away.name ?: "")
        }

    fun parse(s: String): Long = try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }
}
