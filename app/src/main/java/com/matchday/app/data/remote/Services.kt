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

    @GET("apis/v2/sports/{sport}/{league}/standings")
    suspend fun standings(
        @Path("sport") sport: String,
        @Path("league") league: String,
    ): EStandings
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
