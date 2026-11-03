package com.matchday.app.data.repo

import com.matchday.app.data.remote.ForecastResponse
import com.matchday.app.data.remote.GeoResponse
import com.matchday.app.data.remote.Network
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

data class WeatherInfo(
    val place: String,
    val temperatureC: Double?,
    val precipitationChance: Double?,
    val windKmh: Double?,
    val description: String,
)

/** Kick-off weather from Open-Meteo (free, no API key). Forecasts reach ~7 days ahead. */
class WeatherRepository(private val cache: JsonCache) {

    suspend fun atKickoff(city: String, kickoff: Long): WeatherInfo? {
        return try {
        val geo = cache.get("geo:$city", 30L * 24 * 3_600_000, GeoResponse.serializer()) {
            Network.geocoding.search(city)
        }.results?.firstOrNull() ?: return null
        val fc = cache.get(
            "wx:${"%.2f".format(geo.latitude)},${"%.2f".format(geo.longitude)}",
            3 * 3_600_000L, ForecastResponse.serializer(),
        ) { Network.weather.forecast(geo.latitude, geo.longitude) }
        val h = fc.hourly ?: return null
        val idx = h.time.indices.minByOrNull { i ->
            val t = try { LocalDateTime.parse(h.time[i]).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { 0L }
            abs(t - kickoff)
        } ?: return null
        val tIdx = try { LocalDateTime.parse(h.time[idx]).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { 0L }
        if (abs(tIdx - kickoff) > 2 * 3_600_000L) return null // kick-off outside the forecast window
        WeatherInfo(
            place = listOfNotNull(geo.name, geo.country).joinToString(", "),
            temperatureC = h.temperature.getOrNull(idx),
            precipitationChance = h.precipitation.getOrNull(idx),
            windKmh = h.wind.getOrNull(idx),
            description = describe(h.code.getOrNull(idx)?.toInt()),
        )
    } catch (_: Exception) {
        null
    }
    }

    private fun describe(code: Int?): String = when (code) {
        null -> "Unknown"
        0 -> "Clear sky"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> "Mixed"
    }

    @Suppress("unused")
    private fun now() = Instant.now()
}
