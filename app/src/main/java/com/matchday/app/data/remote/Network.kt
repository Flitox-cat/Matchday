package com.matchday.app.data.remote

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

object Network {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Matchday-Android/2.0").build())
            }
            .addInterceptor(logging)
            .build()
    }

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val espn: EspnService by lazy { retrofit("https://site.api.espn.com/").create(EspnService::class.java) }
    val espnCore: EspnCoreService by lazy { retrofit("https://sports.core.api.espn.com/").create(EspnCoreService::class.java) }
    val espnSearch: EspnSearchService by lazy { retrofit("https://site.web.api.espn.com/").create(EspnSearchService::class.java) }
    val oddsApi: OddsApiService by lazy { retrofit("https://api.the-odds-api.com/").create(OddsApiService::class.java) }
    val footballData: FootballDataService by lazy { retrofit("https://api.football-data.org/").create(FootballDataService::class.java) }
    val apiFootball: ApiFootballService by lazy { retrofit("https://v3.football.api-sports.io/").create(ApiFootballService::class.java) }
    val geocoding: GeocodingService by lazy { retrofit("https://geocoding-api.open-meteo.com/").create(GeocodingService::class.java) }
    val weather: WeatherService by lazy { retrofit("https://api.open-meteo.com/").create(WeatherService::class.java) }
}
