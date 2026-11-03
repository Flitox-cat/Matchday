package com.matchday.app.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/** https://the-odds-api.com/liveapi/guides/v4/ */
interface OddsApiService {
    /** Free: does not use quota. */
    @GET("v4/sports")
    suspend fun sports(@Query("apiKey") apiKey: String): Response<List<OddsSportDto>>

    /** Cost = number of markets x number of regions. */
    @GET("v4/sports/{sport}/odds")
    suspend fun odds(
        @Path("sport") sport: String,
        @Query("apiKey") apiKey: String,
        @Query("regions") regions: String,
        @Query("markets") markets: String,
        @Query("oddsFormat") oddsFormat: String = "decimal",
        @Query("dateFormat") dateFormat: String = "iso",
        @Query("commenceTimeTo") commenceTimeTo: String? = null,
    ): Response<List<OddsEventDto>>

    /** Cost 1 (live/upcoming only) or 2 (with daysFrom). */
    @GET("v4/sports/{sport}/scores")
    suspend fun scores(
        @Path("sport") sport: String,
        @Query("apiKey") apiKey: String,
        @Query("daysFrom") daysFrom: Int? = null,
        @Query("dateFormat") dateFormat: String = "iso",
    ): Response<List<ScoreEventDto>>
}

/** https://www.football-data.org/documentation/api - header X-Auth-Token */
interface FootballDataService {
    @GET("v4/competitions/{code}/standings")
    suspend fun standings(
        @Header("X-Auth-Token") token: String,
        @Path("code") code: String,
    ): FdStandingsResponse

    @GET("v4/competitions/{code}/matches")
    suspend fun competitionMatches(
        @Header("X-Auth-Token") token: String,
        @Path("code") code: String,
        @Query("status") status: String? = null,
        @Query("dateFrom") dateFrom: String? = null,
        @Query("dateTo") dateTo: String? = null,
    ): FdMatchesResponse

    @GET("v4/teams/{id}/matches")
    suspend fun teamMatches(
        @Header("X-Auth-Token") token: String,
        @Path("id") teamId: Int,
        @Query("status") status: String = "FINISHED",
    ): FdMatchesResponse

    @GET("v4/matches/{id}/head2head")
    suspend fun headToHead(
        @Header("X-Auth-Token") token: String,
        @Path("id") matchId: Int,
        @Query("limit") limit: Int = 10,
    ): FdMatchesResponse
}

/** https://www.api-football.com/documentation-v3 - header x-apisports-key */
interface ApiFootballService {
    @GET("fixtures")
    suspend fun fixturesByDate(
        @Header("x-apisports-key") key: String,
        @Query("date") date: String,
        @Query("timezone") timezone: String = "UTC",
    ): AfFixturesResponse

    @GET("fixtures")
    suspend fun liveFixtures(
        @Header("x-apisports-key") key: String,
        @Query("live") live: String = "all",
    ): AfFixturesResponse

    @GET("injuries")
    suspend fun injuries(
        @Header("x-apisports-key") key: String,
        @Query("fixture") fixtureId: Int,
    ): AfInjuriesResponse

    @GET("fixtures/lineups")
    suspend fun lineups(
        @Header("x-apisports-key") key: String,
        @Query("fixture") fixtureId: Int,
    ): AfLineupsResponse
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
