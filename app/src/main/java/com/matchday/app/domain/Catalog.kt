package com.matchday.app.domain

enum class SportType(val label: String) {
    FOOTBALL("Football"),
    BASKETBALL("Basketball"),
    TENNIS("Tennis"),
    BASEBALL("Baseball"),
    AMERICAN_FOOTBALL("NFL"),
    HOCKEY("Hockey"),
    OTHER("Other"),
}

/** A competition in ESPN's public feed. [key] = "sport/league", e.g. "soccer/eng.1". */
data class Competition(
    val key: String,
    val title: String,
    val sport: SportType,
    val tier: Int,               // 1..10, drives "popular"
    val hasDraw: Boolean,
    val hasTable: Boolean,       // league table usable by the football model
    val emblem: String?,         // filled from the feed's own league logo
) {
    val espnSport: String get() = key.substringBefore('/')
    val espnLeague: String get() = key.substringAfter('/')
}

object Catalog {
    val ALL: List<Competition> = listOf(
        Competition("soccer/uefa.champions", "Champions League", SportType.FOOTBALL, 10, true, true, null),
        Competition("soccer/eng.1", "Premier League", SportType.FOOTBALL, 9, true, true, null),
        Competition("soccer/esp.1", "La Liga", SportType.FOOTBALL, 9, true, true, null),
        Competition("soccer/uefa.europa", "Europa League", SportType.FOOTBALL, 8, true, true, null),
        Competition("soccer/ger.1", "Bundesliga", SportType.FOOTBALL, 8, true, true, null),
        Competition("soccer/ita.1", "Serie A", SportType.FOOTBALL, 8, true, true, null),
        Competition("soccer/fra.1", "Ligue 1", SportType.FOOTBALL, 7, true, true, null),
        Competition("soccer/uefa.europa.conf", "Conference League", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/fifa.world", "FIFA World Cup", SportType.FOOTBALL, 10, true, false, null),
        Competition("soccer/ned.1", "Eredivisie", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/por.1", "Primeira Liga", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/ksa.1", "Saudi Pro League", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/eng.2", "Championship", SportType.FOOTBALL, 5, true, true, null),
        Competition("soccer/usa.1", "MLS", SportType.FOOTBALL, 5, true, true, null),
        Competition("basketball/nba", "NBA", SportType.BASKETBALL, 9, false, false, null),
        Competition("football/nfl", "NFL", SportType.AMERICAN_FOOTBALL, 8, false, false, null),
        Competition("baseball/mlb", "MLB", SportType.BASEBALL, 7, false, false, null),
        Competition("hockey/nhl", "NHL", SportType.HOCKEY, 6, false, false, null),
        Competition("tennis/atp", "ATP Tennis", SportType.TENNIS, 7, false, false, null),
        Competition("tennis/wta", "WTA Tennis", SportType.TENNIS, 7, false, false, null),
    )

    val DEFAULT_ENABLED: Set<String> = setOf(
        "soccer/uefa.champions", "soccer/eng.1", "soccer/esp.1", "soccer/uefa.europa",
        "soccer/ger.1", "soccer/ita.1", "soccer/fra.1", "basketball/nba", "tennis/atp", "tennis/wta", "hockey/nhl",
    )

    fun find(key: String): Competition = ALL.firstOrNull { it.key == key }
        ?: Competition(key, key.substringAfter('/'), SportType.OTHER, 3, false, false, null)
}
