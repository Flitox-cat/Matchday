package com.matchday.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** ESPN's public site API. No key, no sign-up. Undocumented, so the app tolerates missing fields. */
interface EspnService {
    @GET("apis/site/v2/sports/{sport}/{league}/scoreboard")
    suspend fun scoreboard(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Query("dates") dates: String? = null,
    ): EScoreboard

    @GET("apis/site/v2/sports/{sport}/{league}/summary")
    suspend fun summary(
        @Path("sport") sport: String,
        @Path("league") league: String,
        @Query("event") eventId: String,
    ): ESummary

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
