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
        Competition("soccer/esp.copa_del_rey", "Copa del Rey", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/eng.fa", "FA Cup", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/eng.league_cup", "Carabao Cup", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/ita.coppa_italia", "Coppa Italia", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/ger.dfb_pokal", "DFB-Pokal", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/fra.coupe_de_france", "Coupe de France", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/por.taca.portugal", "Taça de Portugal", SportType.FOOTBALL, 4, true, false, null),
        Competition("soccer/sco.tennents", "Scottish Cup", SportType.FOOTBALL, 4, true, false, null),
        Competition("soccer/sco.cis", "Scottish League Cup", SportType.FOOTBALL, 3, true, false, null),
        Competition("soccer/esp.super_cup", "Supercopa de España", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/ita.super_cup", "Supercoppa Italiana", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/ger.super_cup", "German Supercup", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/fra.super_cup", "Trophée des Champions", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/uefa.super_cup", "UEFA Super Cup", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/esp.2", "LaLiga 2", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/ita.2", "Serie B", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/ger.2", "2. Bundesliga", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/fra.2", "Ligue 2", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/eng.3", "League One", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/eng.4", "League Two", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/eng.5", "National League", SportType.FOOTBALL, 2, true, true, null),
        Competition("soccer/ned.2", "Eerste Divisie", SportType.FOOTBALL, 2, true, true, null),
        Competition("soccer/sco.1", "Scottish Premiership", SportType.FOOTBALL, 5, true, true, null),
        Competition("soccer/bel.1", "Belgian Pro League", SportType.FOOTBALL, 5, true, true, null),
        Competition("soccer/tur.1", "Turkish Süper Lig", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/gre.1", "Greek Super League", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/aut.1", "Austrian Bundesliga", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/sui.1", "Swiss Super League", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/den.1", "Danish Superliga", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/nor.1", "Eliteserien (Norway)", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/swe.1", "Allsvenskan (Sweden)", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/rus.1", "Russian Premier League", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/cze.1", "Czech First League", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/cyp.1", "Cypriot First Division", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/isr.1", "Israeli Premier League", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/rou.1", "Romanian Liga 1", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/irl.1", "League of Ireland", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/bra.1", "Brasileirão Série A", SportType.FOOTBALL, 7, true, true, null),
        Competition("soccer/bra.2", "Brasileirão Série B", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/bra.copa_do_brazil", "Copa do Brasil", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/arg.1", "Argentine Liga Profesional", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/arg.copa", "Copa Argentina", SportType.FOOTBALL, 4, true, false, null),
        Competition("soccer/mex.1", "Liga MX", SportType.FOOTBALL, 6, true, true, null),
        Competition("soccer/col.1", "Colombian Primera A", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/chi.1", "Chilean Primera División", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/uru.1", "Uruguayan Primera División", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/per.1", "Peruvian Liga 1", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/ecu.1", "LigaPro Ecuador", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/par.1", "Paraguayan Primera División", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/ven.1", "Venezuelan Primera División", SportType.FOOTBALL, 2, true, true, null),
        Competition("soccer/bol.1", "Bolivian División Profesional", SportType.FOOTBALL, 2, true, true, null),
        Competition("soccer/jpn.1", "J1 League (Japan)", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/chn.1", "Chinese Super League", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/aus.1", "A-League Men (Australia)", SportType.FOOTBALL, 4, true, true, null),
        Competition("soccer/ind.1", "Indian Super League", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/tha.1", "Thai League 1", SportType.FOOTBALL, 2, true, true, null),
        Competition("soccer/ksa.kings.cup", "Saudi King's Cup", SportType.FOOTBALL, 4, true, false, null),
        Competition("soccer/rsa.1", "South African Premiership", SportType.FOOTBALL, 3, true, true, null),
        Competition("soccer/nga.1", "Nigerian Professional League", SportType.FOOTBALL, 2, true, true, null),
        Competition("soccer/usa.open", "U.S. Open Cup", SportType.FOOTBALL, 3, true, false, null),
        Competition("soccer/conmebol.libertadores", "Copa Libertadores", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/conmebol.sudamericana", "Copa Sudamericana", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/concacaf.champions", "Concacaf Champions Cup", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/afc.champions", "AFC Champions League Elite", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/caf.champions", "CAF Champions League", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/fifa.cwc", "FIFA Club World Cup", SportType.FOOTBALL, 9, true, false, null),
        Competition("soccer/fifa.intercontinental_cup", "FIFA Intercontinental Cup", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/uefa.nations", "UEFA Nations League", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/uefa.euro", "UEFA Euro", SportType.FOOTBALL, 10, true, false, null),
        Competition("soccer/uefa.euroq", "Euro Qualifying", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/conmebol.america", "Copa América", SportType.FOOTBALL, 9, true, false, null),
        Competition("soccer/afc.asian.cup", "AFC Asian Cup", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/caf.nations", "Africa Cup of Nations", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/caf.nations_qual", "Africa Cup of Nations Qualifying", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/concacaf.gold", "Concacaf Gold Cup", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/concacaf.nations.league", "Concacaf Nations League", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/fifa.worldq.uefa", "World Cup Qualifying – Europe", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/fifa.worldq.conmebol", "World Cup Qualifying – South America", SportType.FOOTBALL, 8, true, false, null),
        Competition("soccer/fifa.worldq.afc", "World Cup Qualifying – Asia", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/fifa.worldq.caf", "World Cup Qualifying – Africa", SportType.FOOTBALL, 7, true, false, null),
        Competition("soccer/fifa.worldq.concacaf", "World Cup Qualifying – Concacaf", SportType.FOOTBALL, 6, true, false, null),
        Competition("soccer/fifa.friendly", "International Friendlies", SportType.FOOTBALL, 5, true, false, null),
        Competition("soccer/club.friendly", "Club Friendlies", SportType.FOOTBALL, 2, true, false, null),
        Competition("basketball/wnba", "WNBA", SportType.BASKETBALL, 5, false, false, null),
        Competition("basketball/mens-college-basketball", "NCAA Men's Basketball", SportType.BASKETBALL, 5, false, false, null),
        Competition("basketball/womens-college-basketball", "NCAA Women's Basketball", SportType.BASKETBALL, 3, false, false, null),
        Competition("hockey/mens-college-hockey", "NCAA Men's Hockey", SportType.HOCKEY, 3, false, false, null),
        Competition("basketball/nba", "NBA", SportType.BASKETBALL, 9, false, false, null),
        Competition("football/nfl", "NFL", SportType.AMERICAN_FOOTBALL, 8, false, false, null),
        Competition("baseball/mlb", "MLB", SportType.BASEBALL, 7, false, false, null),
        Competition("hockey/nhl", "NHL", SportType.HOCKEY, 6, false, false, null),
        Competition("hockey/womens-college-hockey", "NCAA Women's Hockey", SportType.HOCKEY, 3, false, false, null),
        Competition("hockey/olympics-mens-ice-hockey", "Olympic Men's Ice Hockey", SportType.HOCKEY, 8, false, false, null),
        Competition("hockey/olympics-womens-ice-hockey", "Olympic Women's Ice Hockey", SportType.HOCKEY, 6, false, false, null),
        Competition("hockey/hockey-world-cup", "World Cup of Hockey", SportType.HOCKEY, 8, false, false, null),
        Competition("tennis/atp", "ATP Tennis", SportType.TENNIS, 7, false, false, null),
        Competition("tennis/wta", "WTA Tennis", SportType.TENNIS, 7, false, false, null),
    )

    /** Added in version 3: switched on once for existing users too (see SettingsStore.addNewDefaults). */
    val NEW_DEFAULTS_V3: Set<String> = setOf(
        "soccer/esp.copa_del_rey", "soccer/eng.fa", "soccer/eng.league_cup", "soccer/ita.coppa_italia", "soccer/ger.dfb_pokal",
        "soccer/fra.coupe_de_france", "soccer/uefa.nations", "soccer/fifa.worldq.uefa", "soccer/fifa.worldq.afc",
        "soccer/conmebol.libertadores", "soccer/bra.1", "soccer/arg.1", "soccer/tur.1", "soccer/ned.1", "soccer/por.1",
        "soccer/ksa.1", "soccer/afc.champions", "soccer/fifa.cwc", "soccer/uefa.super_cup", "soccer/esp.super_cup",
    )

    /** Added in version 4 (switched on once for existing users too). */
    val NEW_DEFAULTS_V4: Set<String> = setOf("soccer/caf.nations_qual")

    /** Added in version 5: every hockey competition in the free feed (switched on once for existing users too). */
    val NEW_DEFAULTS_V5: Set<String> = setOf(
        "hockey/mens-college-hockey", "hockey/womens-college-hockey", "hockey/olympics-mens-ice-hockey",
        "hockey/olympics-womens-ice-hockey", "hockey/hockey-world-cup",
    )

    val DEFAULT_ENABLED: Set<String> = setOf(
        "soccer/uefa.champions", "soccer/eng.1", "soccer/esp.1", "soccer/uefa.europa",
        "soccer/ger.1", "soccer/ita.1", "soccer/fra.1", "basketball/nba", "tennis/atp", "tennis/wta", "hockey/nhl",
    ) + NEW_DEFAULTS_V3 + NEW_DEFAULTS_V4 + NEW_DEFAULTS_V5

    fun find(key: String): Competition = ALL.firstOrNull { it.key == key }
        ?: Competition(key, key.substringAfter('/'), SportType.OTHER, 3, false, false, null)
}
