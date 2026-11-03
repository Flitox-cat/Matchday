package com.matchday.app.data.repo

import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.FdMatch
import com.matchday.app.data.remote.FdMatchesResponse
import com.matchday.app.data.remote.FdStandingsResponse
import com.matchday.app.data.remote.FdTableRow
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.LeagueAverages
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.TeamStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** League table + results for one competition, used by the football model. */
data class CompetitionStats(
    val code: String,
    val total: List<FdTableRow>,
    val home: List<FdTableRow>,
    val away: List<FdTableRow>,
    val finished: List<FdMatch>,
    val league: LeagueAverages?,
    val fetchedAt: Long,
) {
    private fun row(rows: List<FdTableRow>, name: String): FdTableRow? =
        TeamMatcher.best(name, rows, { it.team.name }) ?: TeamMatcher.best(name, rows, { it.team.shortName })

    fun teamStats(name: String): TeamStats? {
        val t = row(total, name) ?: return null
        val id = t.team.id
        val h = home.firstOrNull { it.team.id == id }
        val a = away.firstOrNull { it.team.id == id }
        val recent = if (id != null) recentFor(finished, id) else emptyList()
        return TeamStats(
            name = t.team.name ?: name, fdId = id, crest = t.team.crest, position = t.position,
            played = t.playedGames, goalsFor = t.goalsFor, goalsAgainst = t.goalsAgainst, points = t.points,
            homePlayed = h?.playedGames ?: 0, homeFor = h?.goalsFor ?: 0, homeAgainst = h?.goalsAgainst ?: 0,
            awayPlayed = a?.playedGames ?: 0, awayFor = a?.goalsFor ?: 0, awayAgainst = a?.goalsAgainst ?: 0,
            recent = recent,
        )
    }

    companion object {
        fun recentFor(matches: List<FdMatch>, teamId: Int): List<RecentResult> =
            matches.asSequence()
                .filter { it.status == "FINISHED" && (it.homeTeam.id == teamId || it.awayTeam.id == teamId) }
                .mapNotNull { m ->
                    val hg = m.score.fullTime.home ?: return@mapNotNull null
                    val ag = m.score.fullTime.away ?: return@mapNotNull null
                    val isHome = m.homeTeam.id == teamId
                    RecentResult(
                        date = parseInstant(m.utcDate),
                        opponent = (if (isHome) m.awayTeam.shortName ?: m.awayTeam.name else m.homeTeam.shortName ?: m.homeTeam.name) ?: "?",
                        home = isHome,
                        goalsFor = if (isHome) hg else ag,
                        goalsAgainst = if (isHome) ag else hg,
                        competition = m.competition?.name,
                    )
                }
                .sortedByDescending { it.date }
                .toList()

        fun parseInstant(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }
    }
}

class FootballDataRepository(
    private val cache: JsonCache,
    private val settings: SettingsStore,
) {
    private val api = Network.footballData
    private val mutex = Mutex()
    private var lastCall = 0L

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private companion object {
        const val SIX_HOURS = 6 * 3_600_000L
        const val TWELVE_HOURS = 12 * 3_600_000L
        const val DAY = 24 * 3_600_000L
        const val SPACING_MS = 6_500L // free tier: 10 requests/minute
    }

    private suspend fun token(): String? = settings.current().effectiveFdKey.ifBlank { null }

    private suspend fun <T> throttled(block: suspend () -> T): T = mutex.withLock {
        val wait = lastCall + SPACING_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try { block() } finally { lastCall = System.currentTimeMillis() }
    }

    private suspend fun <T> safe(what: String, block: suspend () -> T): T? = try {
        block().also { _lastError.value = null }
    } catch (e: HttpException) {
        _lastError.value = when (e.code()) {
            400 -> "football-data.org: bad request ($what)"
            403 -> "football-data.org: $what is not included in your plan"
            429 -> "football-data.org: rate limit reached, retrying later"
            else -> "football-data.org error ${e.code()} ($what)"
        }
        null
    } catch (e: Exception) {
        _lastError.value = "football-data.org unreachable: ${e.message ?: e.javaClass.simpleName}"
        null
    }

    suspend fun hasKey(): Boolean = token() != null

    suspend fun standings(code: String): FdStandingsResponse? {
        val t = token() ?: return null
        return safe("standings $code") {
            cache.get("fd:standings:$code", SIX_HOURS, FdStandingsResponse.serializer()) {
                throttled { api.standings(t, code) }
            }
        }
    }

    suspend fun finished(code: String): List<FdMatch> {
        val t = token() ?: return emptyList()
        return safe("results $code") {
            cache.get("fd:finished:$code", SIX_HOURS, FdMatchesResponse.serializer()) {
                throttled { api.competitionMatches(t, code, status = "FINISHED") }
            }.matches
        } ?: emptyList()
    }

    suspend fun upcoming(code: String): List<FdMatch> {
        val t = token() ?: return emptyList()
        val today = LocalDate.now(ZoneOffset.UTC)
        return safe("fixtures $code") {
            cache.get("fd:upcoming:$code", SIX_HOURS, FdMatchesResponse.serializer()) {
                throttled {
                    api.competitionMatches(t, code, dateFrom = today.minusDays(1).toString(), dateTo = today.plusDays(8).toString())
                }
            }.matches
        } ?: emptyList()
    }

    /** All of a team's finished matches this season, across the competitions the plan covers. */
    suspend fun teamRecent(teamId: Int): List<RecentResult> {
        val t = token() ?: return emptyList()
        val matches = safe("team $teamId") {
            cache.get("fd:team:$teamId", TWELVE_HOURS, FdMatchesResponse.serializer()) {
                throttled { api.teamMatches(t, teamId) }
            }.matches
        } ?: return emptyList()
        return CompetitionStats.recentFor(matches, teamId)
    }

    /** Head-to-head from the home team's point of view. */
    suspend fun headToHead(code: String, home: String, away: String, kickoff: Long): List<RecentResult> {
        val t = token() ?: return emptyList()
        val fixture = upcoming(code).firstOrNull {
            val sameTeams = TeamMatcher.same(home, it.homeTeam.name ?: "") && TeamMatcher.same(away, it.awayTeam.name ?: "")
            sameTeams && kotlin.math.abs(CompetitionStats.parseInstant(it.utcDate) - kickoff) < DAY
        } ?: return emptyList()
        val homeId = fixture.homeTeam.id ?: return emptyList()
        val matches = safe("head-to-head") {
            cache.get("fd:h2h:${fixture.id}", DAY, FdMatchesResponse.serializer()) {
                throttled { api.headToHead(t, fixture.id) }
            }.matches
        } ?: return emptyList()
        return CompetitionStats.recentFor(matches.filter { it.id != fixture.id }, homeId)
    }

    suspend fun competitionStats(code: String): CompetitionStats? {
        val st = standings(code) ?: return null
        val fin = finished(code)
        val total = st.standings.filter { it.type == "TOTAL" }.flatMap { it.table }
        val home = st.standings.filter { it.type == "HOME" }.flatMap { it.table }
        val away = st.standings.filter { it.type == "AWAY" }.flatMap { it.table }
        if (total.isEmpty()) return null
        return CompetitionStats(
            code = code, total = total, home = home, away = away, finished = fin,
            league = leagueAverages(total, home, away),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun leagueAverages(total: List<FdTableRow>, home: List<FdTableRow>, away: List<FdTableRow>): LeagueAverages? {
        val hp = home.sumOf { it.playedGames }
        val ap = away.sumOf { it.playedGames }
        if (hp >= 10 && ap >= 10) {
            return LeagueAverages(home.sumOf { it.goalsFor }.toDouble() / hp, away.sumOf { it.goalsFor }.toDouble() / ap)
        }
        val tp = total.sumOf { it.playedGames }
        if (tp < 10) return null
        val perTeamGame = total.sumOf { it.goalsFor }.toDouble() / tp
        return LeagueAverages(perTeamGame * 1.12, perTeamGame * 0.88)
    }
}
