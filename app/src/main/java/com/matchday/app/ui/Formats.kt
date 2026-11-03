package com.matchday.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.matchday.app.data.local.AppSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val QATAR: ZoneId = ZoneId.of("Asia/Qatar")

fun AppSettings.zone(): ZoneId = if (useDeviceZone) ZoneId.systemDefault() else QATAR

val LocalZone = staticCompositionLocalOf { QATAR }

object Formats {
    private val time = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.UK)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    fun time(ms: Long, zone: ZoneId): String = time.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun dayTime(ms: Long, zone: ZoneId): String = dayTime.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun day(ms: Long, zone: ZoneId): String = day.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val mins = (System.currentTimeMillis() - ms) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 48 * 60 -> "${mins / 60} h ago"
            else -> "${mins / 1440} days ago"
        }
    }

    fun kickoffLabel(ms: Long, zone: ZoneId): String {
        val d = localDate(ms, zone)
        val today = LocalDate.now(zone)
        return when (d) {
            today -> "Today ${time(ms, zone)}"
            today.plusDays(1) -> "Tomorrow ${time(ms, zone)}"
            else -> dayTime(ms, zone)
        }
    }

    fun odds(o: Double?): String = if (o == null) "N/A" else String.format(Locale.UK, "%.2f", o)
    fun pct(p: Double?): String = if (p == null) "N/A" else "${Math.round(p * 100)}%"
}
