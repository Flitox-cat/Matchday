package com.matchday.app.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// ---------------------------------------------------------------------------
// ESPN public site API (no key). All fields optional: the feed varies by sport.
// ---------------------------------------------------------------------------

@Serializable
data class EHref(val href: String? = null)

@Serializable
data class ELeague(val name: String? = null, val logos: List<EHref> = emptyList())

@Serializable
data class EScoreboard(val events: List<EEvent> = emptyList(), val leagues: List<ELeague> = emptyList())

@Serializable
data class ESeasonInfo(val displayName: String? = null)

@Serializable
data class EEvent(
    val id: String = "",
    val date: String? = null,
    val name: String? = null,
    val season: ESeasonInfo? = null,
    val status: EStatus? = null,
    val competitions: List<EComp> = emptyList(),
    val groupings: List<EGrouping> = emptyList(), // tennis
    val venue: EVenue? = null,
)

@Serializable
data class EGroupingInfo(val displayName: String? = null)

@Serializable
data class EGrouping(val grouping: EGroupingInfo? = null, val competitions: List<EComp> = emptyList())

@Serializable
data class EStatusType(
    val name: String? = null,
    val state: String? = null,       // pre | in | post
    val completed: Boolean = false,
    val detail: String? = null,
    val shortDetail: String? = null,
)

@Serializable
data class EStatus(
    val clock: Double? = null,
    val displayClock: String? = null,
    val period: Int? = null,
    val type: EStatusType? = null,
)

@Serializable
data class EAddress(val city: String? = null, val country: String? = null, val state: String? = null)

@Serializable
data class EVenue(val fullName: String? = null, val address: EAddress? = null, val court: String? = null)

@Serializable
data class ETeam(
    val id: String? = null,
    val displayName: String? = null,
    val shortDisplayName: String? = null,
    val abbreviation: String? = null,
    val logo: String? = null,
)

@Serializable
data class EAthlete(val displayName: String? = null)

@Serializable
data class ERecord(val name: String? = null, val type: String? = null, val summary: String? = null)

@Serializable
data class ELine(val value: Double? = null, val tiebreak: Int? = null, val winner: Boolean? = null)

@Serializable
data class ECompetitor(
    val id: String? = null,
    val homeAway: String? = null,
    val winner: Boolean? = null,
    val form: String? = null,
    val score: JsonElement? = null, // usually "2", sometimes an object with displayValue
    val records: List<ERecord> = emptyList(),
    val team: ETeam? = null,
    val athlete: EAthlete? = null,
    val linescores: List<ELine> = emptyList(),
) {
    val scoreText: String?
        get() = when (val s = score) {
            is JsonPrimitive -> s.contentOrNull
            is JsonObject -> (s["displayValue"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
}

@Serializable
data class ELineOdds(val line: String? = null, val odds: String? = null)

@Serializable
data class EOpenClose(val open: ELineOdds? = null, val close: ELineOdds? = null)

@Serializable
data class EMoneyline(val home: EOpenClose? = null, val away: EOpenClose? = null, val draw: EOpenClose? = null)

@Serializable
data class ETotal(val over: EOpenClose? = null, val under: EOpenClose? = null)

@Serializable
data class ESpread(val home: EOpenClose? = null, val away: EOpenClose? = null)

@Serializable
data class EProvider(val name: String? = null, val displayName: String? = null)

@Serializable
data class ESideOdds(val moneyLine: Double? = null, val favorite: Boolean? = null)

@Serializable
data class EDrawOdds(val moneyLine: Double? = null)

@Serializable
data class EOdds(
    val provider: EProvider? = null,
    val details: String? = null,
    val overUnder: Double? = null,
    val spread: Double? = null,
    val overOdds: Double? = null,
    val underOdds: Double? = null,
    val drawOdds: EDrawOdds? = null,
    val homeTeamOdds: ESideOdds? = null,
    val awayTeamOdds: ESideOdds? = null,
    val moneyline: EMoneyline? = null,
    val total: ETotal? = null,
    val pointSpread: ESpread? = null,
)

@Serializable
data class EComp(
    val id: String? = null,
    val date: String? = null,
    val status: EStatus? = null,
    val venue: EVenue? = null,
    val competitors: List<ECompetitor> = emptyList(),
    val odds: List<EOdds>? = null,
)

// ---- summary?event=ID ----

@Serializable
data class ELastEvent(
    val id: String? = null,
    val gameDate: String? = null,
    val homeTeamId: String? = null,
    val awayTeamId: String? = null,
    val homeTeamScore: String? = null,
    val awayTeamScore: String? = null,
    val gameResult: String? = null,
    val opponent: ETeam? = null,
    val leagueName: String? = null,
    val competitionName: String? = null,
)

@Serializable
data class ELastFive(val team: ETeam? = null, val events: List<ELastEvent> = emptyList())

@Serializable
data class ESeriesCompetitor(val homeAway: String? = null, val team: ETeam? = null, val score: String? = null)

@Serializable
data class ESeriesEvent(
    val id: String? = null,
    val date: String? = null,
    val competitors: List<ESeriesCompetitor> = emptyList(),
    val competitionName: String? = null,
)

@Serializable
data class ESeries(val type: String? = null, val title: String? = null, val events: List<ESeriesEvent> = emptyList())

@Serializable
data class EPosition(val abbreviation: String? = null, val displayName: String? = null)

@Serializable
data class ERosterAthlete(val displayName: String? = null, val position: EPosition? = null)

@Serializable
data class ERosterEntry(
    val starter: Boolean? = null,
    val jersey: String? = null,
    val athlete: ERosterAthlete? = null,
    val position: EPosition? = null,
)

@Serializable
data class ERoster(
    val homeAway: String? = null,
    val team: ETeam? = null,
    val formation: String? = null,
    val roster: List<ERosterEntry> = emptyList(),
)

@Serializable
data class EInjuryDetails(val type: String? = null, val detail: String? = null, val returnDate: String? = null)

@Serializable
data class EInjury(
    val status: String? = null,
    val athlete: ERosterAthlete? = null,
    val details: EInjuryDetails? = null,
)

@Serializable
data class EInjuryTeam(val team: ETeam? = null, val injuries: List<EInjury> = emptyList())

@Serializable
data class EGameInfo(val venue: EVenue? = null)

@Serializable
data class ESummary(
    val lastFiveGames: List<ELastFive> = emptyList(),
    val seasonseries: List<ESeries> = emptyList(),
    val rosters: List<ERoster> = emptyList(),
    val injuries: List<EInjuryTeam> = emptyList(),
    val gameInfo: EGameInfo? = null,
    val pickcenter: List<EOdds> = emptyList(),
)

// ---- teams/{id} and teams/{id}/schedule ----

@Serializable
data class EDefaultLeague(val slug: String? = null, val name: String? = null, val abbreviation: String? = null)

@Serializable
data class ETeamFull(
    val id: String? = null,
    val displayName: String? = null,
    val logos: List<EHref> = emptyList(),
    val defaultLeague: EDefaultLeague? = null,
    val standingSummary: String? = null,
)

@Serializable
data class ETeamResponse(val team: ETeamFull? = null)

@Serializable
data class EScheduleTeam(val standingSummary: String? = null)

@Serializable
data class ESchedule(
    val events: List<EEvent> = emptyList(),
    val team: EScheduleTeam? = null,
    val season: ESeasonYear? = null,
)

@Serializable
data class ESeasonYear(val year: Int? = null, val displayName: String? = null)

/** sports.core.api.espn.com team record: used for the club's country (stadium address). */
@Serializable
data class ECoreTeam(val displayName: String? = null, val venue: EVenue? = null, val form: String? = null)

// ---- standings ----

@Serializable
data class EStat(val name: String? = null, val value: Double? = null)

@Serializable
data class EStandingEntry(val team: ETeam? = null, val stats: List<EStat> = emptyList())

@Serializable
data class EStandingTable(val entries: List<EStandingEntry> = emptyList())

@Serializable
data class EStandingGroup(val name: String? = null, val standings: EStandingTable? = null)

@Serializable
data class EStandings(
    val standings: EStandingTable? = null,
    val children: List<EStandingGroup> = emptyList(),
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
    @kotlinx.serialization.SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @kotlinx.serialization.SerialName("precipitation_probability") val precipitation: List<Double?> = emptyList(),
    @kotlinx.serialization.SerialName("wind_speed_10m") val wind: List<Double?> = emptyList(),
    @kotlinx.serialization.SerialName("weather_code") val code: List<Double?> = emptyList(),
)

@Serializable
data class ForecastResponse(val hourly: HourlyDto? = null)

// ---------------------------------------------------------------------------
// ESPN search (site.web.api.espn.com/apis/common/v3/search) - no key
// ---------------------------------------------------------------------------

@Serializable
data class ESearchItem(
    val id: String? = null,
    val displayName: String? = null,
    val type: String? = null,
    val sport: String? = null,
    val league: String? = null,
    val defaultLeagueSlug: String? = null,
    val location: String? = null,
)

@Serializable
data class ESearchResponse(val items: List<ESearchItem> = emptyList())

// ---------------------------------------------------------------------------
// The Odds API (the-odds-api.com) - used ONLY for genuine 1xBet prices (bookmaker key "onexbet").
// Optional: needs the user's own free key.
// ---------------------------------------------------------------------------

@Serializable
data class OEvent(
    val id: String = "",
    @kotlinx.serialization.SerialName("sport_key") val sportKey: String? = null,
    @kotlinx.serialization.SerialName("commence_time") val commenceTime: String? = null,
    @kotlinx.serialization.SerialName("home_team") val homeTeam: String? = null,
    @kotlinx.serialization.SerialName("away_team") val awayTeam: String? = null,
    val bookmakers: List<OBookmaker> = emptyList(),
)

@Serializable
data class OBookmaker(
    val key: String = "",
    val title: String = "",
    @kotlinx.serialization.SerialName("last_update") val lastUpdate: String? = null,
    val markets: List<OMarket> = emptyList(),
)

@Serializable
data class OMarket(
    val key: String = "",
    @kotlinx.serialization.SerialName("last_update") val lastUpdate: String? = null,
    val outcomes: List<OOutcome> = emptyList(),
)

@Serializable
data class OOutcome(
    val name: String = "",
    val price: Double = 0.0,
    val point: Double? = null,
    val description: String? = null,
)

// ---------------------------------------------------------------------------
// football-data.org v4 (optional free key) - home/away league tables
// ---------------------------------------------------------------------------

@Serializable
data class FdTeam(val id: Int? = null, val name: String? = null, val shortName: String? = null, val tla: String? = null, val crest: String? = null)

@Serializable
data class FdTableRow(
    val position: Int = 0,
    val team: FdTeam = FdTeam(),
    val playedGames: Int = 0,
    val won: Int = 0,
    val draw: Int = 0,
    val lost: Int = 0,
    val points: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
)

@Serializable
data class FdStanding(val stage: String? = null, val type: String? = null, val group: String? = null, val table: List<FdTableRow> = emptyList())

@Serializable
data class FdStandingsResponse(val standings: List<FdStanding> = emptyList())

// ---------------------------------------------------------------------------
// API-Football v3 (optional free key, 100 requests/day) - injuries and line-ups
// ---------------------------------------------------------------------------

@Serializable
data class AfTeam(val id: Int? = null, val name: String? = null, val logo: String? = null)

@Serializable
data class AfTeams(val home: AfTeam = AfTeam(), val away: AfTeam = AfTeam())

@Serializable
data class AfFixture(val id: Int = 0, val date: String = "")

@Serializable
data class AfFixtureItem(val fixture: AfFixture = AfFixture(), val teams: AfTeams = AfTeams())

@Serializable
data class AfFixturesResponse(val response: List<AfFixtureItem> = emptyList(), val errors: JsonElement? = null)

@Serializable
data class AfInjuryPlayer(val id: Int? = null, val name: String? = null, val type: String? = null, val reason: String? = null)

@Serializable
data class AfInjury(val player: AfInjuryPlayer = AfInjuryPlayer(), val team: AfTeam = AfTeam())

@Serializable
data class AfInjuriesResponse(val response: List<AfInjury> = emptyList(), val errors: JsonElement? = null)

@Serializable
data class AfLineupPlayerInner(val id: Int? = null, val name: String? = null, val number: Int? = null, val pos: String? = null)

@Serializable
data class AfLineupPlayer(val player: AfLineupPlayerInner = AfLineupPlayerInner())

@Serializable
data class AfLineup(
    val team: AfTeam = AfTeam(),
    val formation: String? = null,
    val startXI: List<AfLineupPlayer> = emptyList(),
    val substitutes: List<AfLineupPlayer> = emptyList(),
)

@Serializable
data class AfLineupsResponse(val response: List<AfLineup> = emptyList(), val errors: JsonElement? = null)
