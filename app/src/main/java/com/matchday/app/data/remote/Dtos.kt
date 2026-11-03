package com.matchday.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------------------------------------------------------------------------
// The Odds API (https://the-odds-api.com) - fixtures + bookmaker prices, all sports
// ---------------------------------------------------------------------------

@Serializable
data class OddsSportDto(
    val key: String,
    val group: String = "",
    val title: String = "",
    val description: String = "",
    val active: Boolean = true,
    @SerialName("has_outrights") val hasOutrights: Boolean = false,
)

@Serializable
data class OddsEventDto(
    val id: String,
    @SerialName("sport_key") val sportKey: String,
    @SerialName("sport_title") val sportTitle: String = "",
    @SerialName("commence_time") val commenceTime: String,
    @SerialName("home_team") val homeTeam: String? = null,
    @SerialName("away_team") val awayTeam: String? = null,
    val bookmakers: List<BookmakerDto> = emptyList(),
)

@Serializable
data class BookmakerDto(
    val key: String,
    val title: String,
    @SerialName("last_update") val lastUpdate: String? = null,
    val markets: List<MarketDto> = emptyList(),
)

@Serializable
data class MarketDto(
    val key: String,
    @SerialName("last_update") val lastUpdate: String? = null,
    val outcomes: List<OutcomeDto> = emptyList(),
)

@Serializable
data class OutcomeDto(
    val name: String,
    val price: Double,
    val point: Double? = null,
)

@Serializable
data class ScoreEventDto(
    val id: String,
    @SerialName("sport_key") val sportKey: String,
    @SerialName("sport_title") val sportTitle: String = "",
    @SerialName("commence_time") val commenceTime: String,
    val completed: Boolean = false,
    @SerialName("home_team") val homeTeam: String? = null,
    @SerialName("away_team") val awayTeam: String? = null,
    val scores: List<ScoreDto>? = null,
    @SerialName("last_update") val lastUpdate: String? = null,
)

@Serializable
data class ScoreDto(val name: String, val score: String? = null)

// ---------------------------------------------------------------------------
// football-data.org v4 - standings, results, head-to-head (football only)
// ---------------------------------------------------------------------------

@Serializable
data class FdTeam(
    val id: Int? = null,
    val name: String? = null,
    val shortName: String? = null,
    val tla: String? = null,
    val crest: String? = null,
)

@Serializable
data class FdCompetition(
    val id: Int? = null,
    val name: String? = null,
    val code: String? = null,
    val emblem: String? = null,
)

@Serializable
data class FdTableRow(
    val position: Int = 0,
    val team: FdTeam = FdTeam(),
    val playedGames: Int = 0,
    val form: String? = null,
    val won: Int = 0,
    val draw: Int = 0,
    val lost: Int = 0,
    val points: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
    val goalDifference: Int = 0,
)

@Serializable
data class FdStanding(
    val stage: String? = null,
    val type: String? = null,
    val group: String? = null,
    val table: List<FdTableRow> = emptyList(),
)

@Serializable
data class FdStandingsResponse(
    val competition: FdCompetition? = null,
    val standings: List<FdStanding> = emptyList(),
)

@Serializable
data class FdScoreLine(val home: Int? = null, val away: Int? = null)

@Serializable
data class FdScore(val winner: String? = null, val fullTime: FdScoreLine = FdScoreLine())

@Serializable
data class FdMatch(
    val id: Int,
    val utcDate: String,
    val status: String = "",
    val stage: String? = null,
    val homeTeam: FdTeam = FdTeam(),
    val awayTeam: FdTeam = FdTeam(),
    val score: FdScore = FdScore(),
    val competition: FdCompetition? = null,
    val venue: String? = null,
)

@Serializable
data class FdMatchesResponse(val matches: List<FdMatch> = emptyList())

// ---------------------------------------------------------------------------
// API-Football (api-sports.io) - venues, logos, injuries, line-ups, live minute
// ---------------------------------------------------------------------------

@Serializable
data class AfVenue(val id: Int? = null, val name: String? = null, val city: String? = null)

@Serializable
data class AfStatus(val long: String? = null, val short: String? = null, val elapsed: Int? = null)

@Serializable
data class AfFixture(
    val id: Int,
    val date: String,
    val venue: AfVenue = AfVenue(),
    val status: AfStatus = AfStatus(),
)

@Serializable
data class AfLeague(
    val id: Int? = null,
    val name: String? = null,
    val country: String? = null,
    val logo: String? = null,
    val flag: String? = null,
    val season: Int? = null,
)

@Serializable
data class AfTeam(val id: Int? = null, val name: String? = null, val logo: String? = null)

@Serializable
data class AfTeams(val home: AfTeam = AfTeam(), val away: AfTeam = AfTeam())

@Serializable
data class AfGoals(val home: Int? = null, val away: Int? = null)

@Serializable
data class AfFixtureItem(
    val fixture: AfFixture,
    val league: AfLeague = AfLeague(),
    val teams: AfTeams = AfTeams(),
    val goals: AfGoals = AfGoals(),
)

@Serializable
data class AfFixturesResponse(
    val response: List<AfFixtureItem> = emptyList(),
    val errors: JsonElement? = null,
)

@Serializable
data class AfInjuryPlayer(
    val id: Int? = null,
    val name: String? = null,
    val type: String? = null,
    val reason: String? = null,
)

@Serializable
data class AfInjury(val player: AfInjuryPlayer = AfInjuryPlayer(), val team: AfTeam = AfTeam())

@Serializable
data class AfInjuriesResponse(
    val response: List<AfInjury> = emptyList(),
    val errors: JsonElement? = null,
)

@Serializable
data class AfLineupPlayerInner(
    val id: Int? = null,
    val name: String? = null,
    val number: Int? = null,
    val pos: String? = null,
)

@Serializable
data class AfLineupPlayer(val player: AfLineupPlayerInner = AfLineupPlayerInner())

@Serializable
data class AfLineup(
    val team: AfTeam = AfTeam(),
    val formation: String? = null,
    val startXI: List<AfLineupPlayer> = emptyList(),
)

@Serializable
data class AfLineupsResponse(
    val response: List<AfLineup> = emptyList(),
    val errors: JsonElement? = null,
)

// ---------------------------------------------------------------------------
// Open-Meteo (free, no key) - kick-off weather
// ---------------------------------------------------------------------------

@Serializable
data class GeoResult(
    val name: String = "",
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
)

@Serializable
data class GeoResponse(val results: List<GeoResult>? = null)

@Serializable
data class HourlyDto(
    val time: List<String> = emptyList(),
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipitation: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val wind: List<Double?> = emptyList(),
    @SerialName("weather_code") val code: List<Double?> = emptyList(),
)

@Serializable
data class ForecastResponse(val hourly: HourlyDto? = null)
