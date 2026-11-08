package com.matchday.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** ESPN's public site API. No key, no sign-up. Undocumented, so the app tolerates missing fields. */
interface EspnService {
    @GET("apis/site/v2/sports/{sport}/{league}/scoreboard")
    suspend fun scoreboard(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Query("dates") dates: String? = null,
    ): kotlinx.serialization.json.JsonObject

    @GET("apis/site/v2/sports/{sport}/{league}/summary")
    suspend fun summary(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Query("event") eventId: String,
    ): ESummary

    /** Same as [summary] but untyped, so live stats are read field by field and never break the page. */
    @GET("apis/site/v2/sports/{sport}/{league}/summary")
    suspend fun summaryRaw(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Query("event") eventId: String,
    ): kotlinx.serialization.json.JsonObject

    @GET("apis/site/v2/sports/{sport}/{league}/teams/{id}")
    suspend fun team(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Path("id") teamId: String,
    ): ETeamResponse

    @GET("apis/site/v2/sports/{sport}/{league}/teams/{id}/schedule")
    suspend fun schedule(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Path("id") teamId: String,
        @Query("season") season: Int? = null,
        @Query("fixture") fixture: Boolean? = null,
    ): ESchedule

    @GET("apis/site/v2/sports/tennis/{tour}/rankings")
    suspend fun tennisRankings(@Path("tour") tour: String): kotlinx.serialization.json.JsonObject

    @GET("apis/v2/sports/{sport}/{league}/standings")
    suspend fun standings(
        @Path("sport") sport: String,
        @Path("league") league: String,
    ): EStandings
}

/** ESPN core API (public, no key) - team details such as the stadium address. */
interface EspnCoreService {
    @GET("v2/sports/{sport}/leagues/{league}/teams/{id}")
    suspend fun team(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Path("id") teamId: String,
    ): ECoreTeam
}

/** ESPN public search (no key). */
interface EspnSearchService {
    @GET("apis/common/v3/search")
    suspend fun search(
        @Query("query") query: String,
        @Query("type") type: String = "team",
        @Query("limit") limit: Int = 15,
    ): ESearchResponse
}

/** The Odds API v4 - only used for 1xBet ("onexbet") prices with the user's own free key. */
interface OddsApiService {
    /** Free: list of in-season sports (used to find the right tennis tournament). */
    @GET("v4/sports")
    suspend fun sports(@Query("apiKey") apiKey: String): retrofit2.Response<List<OSport>>

    /** Free: listing events does not use quota. */
    @GET("v4/sports/{sport}/events")
    suspend fun events(
        @Path("sport") sport: String,
        @Query("apiKey") apiKey: String,
        @Query("dateFormat") dateFormat: String = "iso",
    ): retrofit2.Response<List<OEvent>>

    /** Cost = number of markets returned x regions (one region here). */
    @GET("v4/sports/{sport}/events/{eventId}/odds")
    suspend fun eventOdds(
        @Path("sport") sport: String,
        @Path("eventId") eventId: String,
        @Query("apiKey") apiKey: String,
        @Query("markets") markets: String,
        @Query("bookmakers") bookmakers: String = "onexbet",
        @Query("oddsFormat") oddsFormat: String = "decimal",
        @Query("dateFormat") dateFormat: String = "iso",
    ): retrofit2.Response<OEvent>
}

interface GeocodingService {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 1,
    ): GeoResponse
}

interface WeatherService {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") lat: Double,
        @Query("longitude") lon: Double,
        @Query("hourly") hourly: String = "temperature_2m,precipitation_probability,wind_speed_10m,weather_code",
        @Query("timezone") timezone: String = "UTC",
        @Query("forecast_days") days: Int = 7,
    ): ForecastResponse
}

/** football-data.org v4 - header X-Auth-Token. Free plan: 12 competitions, 10 requests/minute. */
interface FootballDataService {
    @GET("v4/competitions/{code}/standings")
    suspend fun standings(
        @Header("X-Auth-Token") token: String,
        @Path("code") code: String,
    ): retrofit2.Response<FdStandingsResponse>
}

/** API-Football v3 - header x-apisports-key. Free plan: 100 requests/day. */
interface ApiFootballService {
    @GET("fixtures")
    suspend fun fixturesByDate(
        @Header("x-apisports-key") key: String,
        @Query("date") date: String,
        @Query("timezone") timezone: String = "UTC",
    ): retrofit2.Response<AfFixturesResponse>

    @GET("injuries")
    suspend fun injuries(
        @Header("x-apisports-key") key: String,
        @Query("fixture") fixtureId: Int,
    ): retrofit2.Response<AfInjuriesResponse>

    @GET("fixtures/lineups")
    suspend fun lineups(
        @Header("x-apisports-key") key: String,
        @Query("fixture") fixtureId: Int,
    ): retrofit2.Response<AfLineupsResponse>
}
