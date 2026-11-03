package com.matchday.app.domain

enum class SportType(val label: String) {
    FOOTBALL("Football"),
    BASKETBALL("Basketball"),
    TENNIS("Tennis"),
    BASEBALL("Baseball"),
    OTHER("Other"),
}

/**
 * A competition as The Odds API knows it ([key] = its sport key), linked to the
 * football-data.org competition code when that free source covers it.
 */
data class Competition(
    val key: String,
    val title: String,
    val sport: SportType,
    val fdCode: String?,
    val tier: Int,          // 1..10, drives "popular"
    val markets: String,    // markets requested from The Odds API (each costs 1 credit per call)
    val hasDraw: Boolean,
    val emblem: String?,
)

object Catalog {
    const val TENNIS_ALL = "tennis_all"
    private fun crest(code: String) = "https://crests.football-data.org/$code.png"

    val FIXED: List<Competition> = listOf(
        Competition("soccer_uefa_champs_league", "Champions League", SportType.FOOTBALL, "CL", 10, "h2h,totals", true, crest("CL")),
        Competition("soccer_epl", "Premier League", SportType.FOOTBALL, "PL", 9, "h2h,totals", true, crest("PL")),
        Competition("soccer_spain_la_liga", "La Liga", SportType.FOOTBALL, "PD", 9, "h2h,totals", true, crest("PD")),
        Competition("soccer_uefa_europa_league", "Europa League", SportType.FOOTBALL, null, 8, "h2h,totals", true, null),
        Competition("soccer_germany_bundesliga", "Bundesliga", SportType.FOOTBALL, "BL1", 8, "h2h,totals", true, crest("BL1")),
        Competition("soccer_italy_serie_a", "Serie A", SportType.FOOTBALL, "SA", 8, "h2h,totals", true, crest("SA")),
        Competition("soccer_france_ligue_one", "Ligue 1", SportType.FOOTBALL, "FL1", 7, "h2h,totals", true, crest("FL1")),
        Competition("soccer_fifa_world_cup", "FIFA World Cup", SportType.FOOTBALL, "WC", 10, "h2h,totals", true, crest("WC")),
        Competition("soccer_uefa_european_championship", "EURO", SportType.FOOTBALL, "EC", 9, "h2h,totals", true, crest("EC")),
        Competition("soccer_fifa_world_cup_qualifiers_europe", "WC Qualifiers (Europe)", SportType.FOOTBALL, null, 7, "h2h,totals", true, null),
        Competition("soccer_netherlands_eredivisie", "Eredivisie", SportType.FOOTBALL, "DED", 6, "h2h,totals", true, crest("DED")),
        Competition("soccer_portugal_primeira_liga", "Primeira Liga", SportType.FOOTBALL, "PPL", 6, "h2h,totals", true, crest("PPL")),
        Competition("soccer_efl_champ", "Championship", SportType.FOOTBALL, "ELC", 5, "h2h,totals", true, crest("ELC")),
        Competition("soccer_brazil_campeonato", "Brasileirão", SportType.FOOTBALL, "BSA", 5, "h2h,totals", true, crest("BSA")),
        Competition("basketball_nba", "NBA", SportType.BASKETBALL, null, 9, "h2h,spreads,totals", false, null),
        Competition("basketball_euroleague", "EuroLeague", SportType.BASKETBALL, null, 6, "h2h,totals", false, null),
        Competition("baseball_mlb", "MLB", SportType.BASEBALL, null, 7, "h2h,totals", false, null),
        // Tennis tournaments change weekly; TENNIS_ALL expands to every active tennis key from /sports.
        Competition(TENNIS_ALL, "Tennis (all active ATP/WTA)", SportType.TENNIS, null, 7, "h2h", false, null),
    )

    /** Fits the free 500-credit plan at the default 12 h interval (~360 credits/month + scores). */
    val DEFAULT_ENABLED: Set<String> = setOf(
        "soccer_uefa_champs_league", "soccer_epl", "soccer_spain_la_liga",
    )

    fun find(key: String, title: String? = null): Competition {
        FIXED.firstOrNull { it.key == key }?.let { return it }
        return when {
            key.startsWith("tennis_") -> {
                val major = listOf("aus_open", "french_open", "wimbledon", "us_open").any { key.contains(it) }
                Competition(key, title ?: prettify(key), SportType.TENNIS, null, if (major) 9 else 7, "h2h", false, null)
            }
            key.startsWith("soccer_") -> Competition(key, title ?: prettify(key), SportType.FOOTBALL, null, 4, "h2h,totals", true, null)
            key.startsWith("basketball_") -> Competition(key, title ?: prettify(key), SportType.BASKETBALL, null, 4, "h2h,totals", false, null)
            key.startsWith("baseball_") -> Competition(key, title ?: prettify(key), SportType.BASEBALL, null, 4, "h2h,totals", false, null)
            else -> Competition(key, title ?: prettify(key), SportType.OTHER, null, 3, "h2h", false, null)
        }
    }

    private fun prettify(key: String) =
        key.substringAfter('_').split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    /** Credits one odds refresh of this competition costs (1 region). */
    fun creditCost(c: Competition) = c.markets.split(',').size
}
