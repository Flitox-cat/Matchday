package com.matchday.app.data.repo

import android.util.Log
import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.AfFixtureItem
import com.matchday.app.data.remote.AfFixturesResponse
import com.matchday.app.data.remote.AfInjuriesResponse
import com.matchday.app.data.remote.AfLineupsResponse
import com.matchday.app.data.remote.FdStandingsResponse
import com.matchday.app.data.remote.FdTableRow
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.InjuryInfo
import com.matchday.app.domain.LineupInfo
import com.matchday.app.domain.LineupPlayer
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.TeamStats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/** Result of an optional keyed source: [value] when it worked, otherwise a short [message] (null = silent). */
data class Sourced<T>(val value: T?, val message: String? = null)

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private fun nextUtcMidnight(): Long =
    LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private class SourceProblem(msg: String) : Exception(msg)

/**
 * football-data.org (optional free key): home and away league tables, which the free ESPN feed
 * doesn't have. They feed the goals model's home/away attack and defence. If the key is missing,
 * rejected, rate-limited or the competition isn't in the free plan, the app silently keeps the
 * ESPN figures.
 */
class FootballDataRepository(private val cache: JsonCache, private val settings: SettingsStore) {
    private val api = Network.footballData

    companion object {
        private const val TAG = "FootballData"
        /** ESPN league key -> football-data.org competition code (free plan). */
        private val CODE = mapOf(
            "soccer/uefa.champions" to "CL", "soccer/eng.1" to "PL", "soccer/esp.1" to "PD", "soccer/ger.1" to "BL1",
            "soccer/ita.1" to "SA", "soccer/fra.1" to "FL1", "soccer/ned.1" to "DED", "soccer/por.1" to "PPL",
            "soccer/eng.2" to "ELC", "soccer/bra.1" to "BSA", "soccer/fifa.world" to "WC",
        )
        fun covers(leagueKey: String) = CODE.containsKey(leagueKey)
    }

    /** Adds home/away splits to both teams' stats. Never throws. */
    suspend fun withSplits(leagueKey: String, home: TeamStats?, away: TeamStats?): Triple<TeamStats?, TeamStats?, String?> {
        val s = settings.current()
        val code = CODE[leagueKey]
        if (s.footballDataKey.isBlank() || code == null || (home == null && away == null)) return Triple(home, away, null)
        if (System.currentTimeMillis() < s.footballDataPausedUntil) return Triple(home, away, null)
        return try {
            val r = cache.get("fd:standings:$code", 6 * HOUR, FdStandingsResponse.serializer()) {
                val resp = api.standings(s.footballDataKey, code)
                when (resp.code()) {
                    in 200..299 -> resp.body() ?: throw SourceProblem("football-data.org: empty response.")
                    400, 401 -> throw SourceProblem("football-data.org rejected the key. Check it in Settings.")
                    403 -> throw SourceProblem("football-data.org's free plan doesn't cover this competition.")
                    429 -> { settings.setFootballDataPausedUntil(System.currentTimeMillis() + 2 * MIN); throw SourceProblem("football-data.org request limit reached; using free data for now.") }
                    else -> throw SourceProblem("football-data.org error ${resp.code()}.")
                }
            }
            // Group stages (Champions League) have several tables per type: merge them.
            fun rows(type: String) = r.standings.filter { it.type == type }.flatMap { it.table }
            val homeRows = rows("HOME"); val awayRows = rows("AWAY")
            if (homeRows.isEmpty() || awayRows.isEmpty()) return Triple(home, away, null)
            fun apply(t: TeamStats?): TeamStats? {
                t ?: return null
                val h = find(homeRows, t.name) ?: return t
                val a = find(awayRows, t.name) ?: return t
                return t.copy(
                    homePlayed = h.playedGames, homeFor = h.goalsFor, homeAgainst = h.goalsAgainst,
                    awayPlayed = a.playedGames, awayFor = a.goalsFor, awayAgainst = a.goalsAgainst,
                )
            }
            Triple(apply(home), apply(away), null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SourceProblem) {
            Triple(home, away, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "football-data.org unavailable", e)
            Triple(home, away, null)
        }
    }

    private fun find(rows: List<FdTableRow>, name: String): FdTableRow? = rows
        .map { r -> r to maxOf(TeamMatcher.score(name, r.team.name ?: ""), TeamMatcher.score(name, r.team.shortName ?: "")) }
        .filter { it.second >= 0.6 }
        .maxByOrNull { it.second }?.first
}

/**
 * API-Football (optional free key, 100 requests/day): injuries/suspensions and confirmed line-ups
 * for football. Each match costs at most 2-3 requests (fixture list for the day is shared and cached).
 * When the daily limit is reached, calls stop until 00:00 UTC and the app carries on without them.
 */
class ApiFootballRepository(private val cache: JsonCache, private val settings: SettingsStore) {
    private val api = Network.apiFootball

    companion object { private const val TAG = "ApiFootball" }

    private fun errorText(errors: JsonElement?): String? = when (errors) {
        is JsonObject -> if (errors.isEmpty()) null else errors.entries.joinToString { "${it.key}: ${it.value}" }
        is JsonArray -> if (errors.isEmpty()) null else errors.joinToString()
        else -> null
    }

    /** Checks status codes, the "errors" field and the daily quota header. */
    private suspend fun <T> check(resp: Response<T>, errors: (T) -> JsonElement?): T {
        resp.headers()["x-ratelimit-requests-remaining"]?.toIntOrNull()?.let {
            settings.setApiFootballRemaining(it)
            if (it <= 0) settings.setApiFootballPausedUntil(nextUtcMidnight())
        }
        if (resp.code() == 401 || resp.code() == 403) throw SourceProblem("API-Football rejected the key. Check it in Settings.")
        if (resp.code() == 429) { settings.setApiFootballPausedUntil(nextUtcMidnight()); throw SourceProblem("API-Football daily limit reached; resumes tomorrow.") }
        if (!resp.isSuccessful) throw SourceProblem("API-Football error ${resp.code()}.")
        val body = resp.body() ?: throw SourceProblem("API-Football: empty response.")
        val err = errorText(errors(body))
        if (err != null) {
            val lower = err.lowercase()
            when {
                "request" in lower && ("limit" in lower || "day" in lower) -> {
                    settings.setApiFootballPausedUntil(nextUtcMidnight())
                    throw SourceProblem("API-Football daily limit reached; resumes tomorrow.")
                }
                "plan" in lower || "season" in lower -> throw SourceProblem("API-Football's free plan doesn't cover this season.")
                "token" in lower || "key" in lower -> throw SourceProblem("API-Football rejected the key. Check it in Settings.")
                else -> throw SourceProblem("API-Football: $err")
            }
        }
        return body
    }

    private suspend fun ready(): String? {
        val s = settings.current()
        if (s.apiFootballKey.isBlank()) return null
        if (System.currentTimeMillis() < s.apiFootballPausedUntil) return null
        return s.apiFootballKey
    }

    private suspend fun pausedMessage(): String? {
        val s = settings.current()
        return if (s.apiFootballKey.isNotBlank() && System.currentTimeMillis() < s.apiFootballPausedUntil)
            "API-Football daily limit reached; injuries and line-ups from it resume tomorrow." else null
    }

    private suspend fun fixture(key: String, item: MatchItem): AfFixtureItem? {
        val date = Instant.ofEpochMilli(item.kickoff).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val list = cache.get("af:fixtures:$date", 6 * HOUR, AfFixturesResponse.serializer()) {
            check(api.fixturesByDate(key, date)) { it.errors }
        }.response
        return list.firstOrNull { f ->
            abs(EspnMapper.parseTime(f.fixture.date) - item.kickoff) <= 3 * HOUR &&
                TeamMatcher.same(item.homeTeam, f.teams.home.name ?: "") && TeamMatcher.same(item.awayTeam, f.teams.away.name ?: "")
        }
    }

    /** Injuries and suspensions. Never throws. */
    suspend fun injuries(item: MatchItem): Sourced<InjuryInfo> {
        val key = ready() ?: return Sourced(null, pausedMessage())
        return try {
            val f = fixture(key, item) ?: return Sourced(null, null)
            val r = cache.get("af:injuries:${f.fixture.id}", 6 * HOUR, AfInjuriesResponse.serializer()) {
                check(api.injuries(key, f.fixture.id)) { it.errors }
            }
            fun names(teamId: Int?, doubtful: Boolean) = r.response
                .filter { it.team.id == teamId && ((it.player.type == "Questionable") == doubtful) }
                .map { inj -> listOfNotNull(inj.player.name, inj.player.reason?.let { "($it)" }).joinToString(" ") }
                .distinct()
            Sourced(
                InjuryInfo(
                    known = true,
                    homeOut = names(f.teams.home.id, false), homeDoubtful = names(f.teams.home.id, true),
                    awayOut = names(f.teams.away.id, false), awayDoubtful = names(f.teams.away.id, true),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SourceProblem) {
            Sourced(null, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "injuries unavailable", e); Sourced(null, null)
        }
    }

    /** Confirmed line-ups (published about an hour before kick-off). Never throws. */
    suspend fun lineups(item: MatchItem): Sourced<List<LineupInfo>> {
        val untilKickoff = item.kickoff - System.currentTimeMillis()
        if (untilKickoff > 90 * MIN || untilKickoff < -3 * HOUR) return Sourced(null, null)
        val key = ready() ?: return Sourced(null, pausedMessage())
        return try {
            val f = fixture(key, item) ?: return Sourced(null, null)
            val r = cache.get("af:lineups:${f.fixture.id}", 15 * MIN, AfLineupsResponse.serializer()) {
                check(api.lineups(key, f.fixture.id)) { it.errors }
            }
            Sourced(r.response.filter { it.startXI.isNotEmpty() }.map { l ->
                LineupInfo(
                    team = l.team.name ?: "?", logo = l.team.logo, formation = l.formation,
                    starters = l.startXI.map { LineupPlayer(it.player.name ?: "?", it.player.number?.toString(), it.player.pos) },
                    bench = l.substitutes.map { LineupPlayer(it.player.name ?: "?", it.player.number?.toString(), it.player.pos) },
                )
            }.ifEmpty { null })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SourceProblem) {
            Sourced(null, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "line-ups unavailable", e); Sourced(null, null)
        }
    }
}
