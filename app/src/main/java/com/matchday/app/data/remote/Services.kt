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
