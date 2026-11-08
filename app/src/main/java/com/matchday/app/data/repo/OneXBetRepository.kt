package com.matchday.app.data.repo

import android.util.Log
import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.Network
import com.matchday.app.data.remote.OBookmaker
import com.matchday.app.data.remote.OEvent
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/** One 1xBet price. [sel]: HOME DRAW AWAY OVER UNDER YES NO 1X X2 12 DNB_HOME DNB_AWAY H1_HOME H1_DRAW H1_AWAY. */
@Serializable
data class BookPrice(
    val market: String,      // 1X2, OU, BTTS, DC, DNB, 1H
    val sel: String,
    val label: String,
    val point: Double? = null,
    val price: Double,
)

/** [bookmaker] says where the prices came from: [OneXBetRepository.SOURCE_API] or [OneXBetRepository.SOURCE_MANUAL]. */
@Serializable
data class BookSnapshot(
    val bookmaker: String,
    val fetchedAt: Long,
    val bookUpdatedAt: Long,
    val prices: List<BookPrice>,
    val live: Boolean = false,       // downloaded while the match was in play
    val score: String? = null,       // score at download time (live snapshots)
) {
    fun find(market: String, sel: String, point: Double? = null): BookPrice? =
        prices.firstOrNull { it.market == market && it.sel == sel && (point == null || it.point == point) }
}

sealed class OneXBetState {
    data object NotLoaded : OneXBetState()
    data class Ok(val current: BookSnapshot, val history: List<BookSnapshot>) : OneXBetState()
}

/** Whether automatic loading through The Odds API can be used right now. */
sealed class ApiStatus {
    data object NoKey : ApiStatus()
    data class Ready(val creditsLeft: Int) : ApiStatus()
    data class Paused(val until: Long) : ApiStatus()      // free credits used up for this month
    data class Unsupported(val reason: String) : ApiStatus()
}

/** One input slot of the manual 1xBet entry form. */
data class OddsSlot(val market: String, val sel: String, val point: Double?, val label: String)

/**
 * Genuine 1xBet prices, two ways:
 *  1. Automatically through The Odds API (bookmaker "onexbet") with the user's own free key.
 *  2. Typed in by the user from the 1xBet app - always available, no API.
 * When the free credits run out (HTTP 429 or 0 remaining) API calls stop until the 1st of next
 * month and the app carries on with saved/manual prices and the free feed. Nothing is scraped,
 * and no other bookmaker's price is ever stored under the 1xBet name.
 */
class OneXBetRepository(private val cache: JsonCache, private val settings: SettingsStore) {
    private val api = Network.oddsApi
    private val history = ListSerializer(BookSnapshot.serializer())
    private val events = ListSerializer(OEvent.serializer())

    companion object {
        private const val TAG = "OneXBet"
        private const val HOUR = 3_600_000L
        const val SOURCE_API = "1xBet (via The Odds API)"
        const val SOURCE_MANUAL = "1xBet (entered by you)"
        private const val MARKETS = "h2h,totals,alternate_totals,btts,draw_no_bet,double_chance,h2h_3_way_h1"
        private const val MARKETS_FALLBACK = "h2h,totals,btts,draw_no_bet"
        /** Extra markets, asked in a second call so an unsupported one never loses the main markets. */
        private const val MARKETS_EXTRA = "team_totals,totals_h1,btts_h1,spreads"
        const val CREDIT_ESTIMATE = 11

        /** ESPN league key -> The Odds API sport key (football only). */
        private val SPORT = mapOf(
            "soccer/uefa.champions" to "soccer_uefa_champs_league",
            "soccer/uefa.europa" to "soccer_uefa_europa_league",
            "soccer/uefa.europa.conf" to "soccer_uefa_europa_conference_league",
            "soccer/eng.1" to "soccer_epl",
            "soccer/eng.2" to "soccer_efl_champ",
            "soccer/esp.1" to "soccer_spain_la_liga",
            "soccer/ger.1" to "soccer_germany_bundesliga",
            "soccer/ita.1" to "soccer_italy_serie_a",
            "soccer/fra.1" to "soccer_france_ligue_one",
            "soccer/ned.1" to "soccer_netherlands_eredivisie",
            "soccer/por.1" to "soccer_portugal_primeira_liga",
            "soccer/usa.1" to "soccer_usa_mls",
            "soccer/ksa.1" to "soccer_saudi_arabia_pro_league",
            "soccer/fifa.world" to "soccer_fifa_world_cup",
            "soccer/esp.2" to "soccer_spain_segunda_division",
            "soccer/eng.fa" to "soccer_fa_cup",
            "soccer/eng.league_cup" to "soccer_england_efl_cup",
            "soccer/eng.3" to "soccer_england_league1",
            "soccer/eng.4" to "soccer_england_league2",
            "soccer/ita.2" to "soccer_italy_serie_b",
            "soccer/ger.2" to "soccer_germany_bundesliga2",
            "soccer/ger.dfb_pokal" to "soccer_germany_dfb_pokal",
            "soccer/fra.2" to "soccer_france_ligue_two",
            "soccer/esp.copa_del_rey" to "soccer_spain_copa_del_rey",
            "soccer/ita.coppa_italia" to "soccer_italy_coppa_italia",
            "soccer/fra.coupe_de_france" to "soccer_france_coupe_de_france",
            "soccer/sco.1" to "soccer_spl",
            "soccer/bel.1" to "soccer_belgium_first_div",
            "soccer/tur.1" to "soccer_turkey_super_league",
            "soccer/gre.1" to "soccer_greece_super_league",
            "soccer/aut.1" to "soccer_austria_bundesliga",
            "soccer/sui.1" to "soccer_switzerland_superleague",
            "soccer/den.1" to "soccer_denmark_superliga",
            "soccer/nor.1" to "soccer_norway_eliteserien",
            "soccer/swe.1" to "soccer_sweden_allsvenskan",
            "soccer/irl.1" to "soccer_league_of_ireland",
            "soccer/bra.1" to "soccer_brazil_campeonato",
            "soccer/bra.2" to "soccer_brazil_serie_b",
            "soccer/arg.1" to "soccer_argentina_primera_division",
            "soccer/mex.1" to "soccer_mexico_ligamx",
            "soccer/chi.1" to "soccer_chile_campeonato",
            "soccer/jpn.1" to "soccer_japan_j_league",
            "soccer/chn.1" to "soccer_china_superleague",
            "soccer/aus.1" to "soccer_australia_aleague",
            "soccer/conmebol.libertadores" to "soccer_conmebol_copa_libertadores",
            "soccer/conmebol.sudamericana" to "soccer_conmebol_copa_sudamericana",
            "soccer/uefa.nations" to "soccer_uefa_nations_league",
            "soccer/uefa.euro" to "soccer_uefa_european_championship",
            "soccer/uefa.euroq" to "soccer_uefa_euro_qualification",
            "soccer/conmebol.america" to "soccer_conmebol_copa_america",
            "soccer/fifa.worldq.uefa" to "soccer_fifa_world_cup_qualifiers_europe",
            "soccer/fifa.worldq.conmebol" to "soccer_fifa_world_cup_qualifiers_south_america",
            "soccer/fifa.cwc" to "soccer_fifa_club_world_cup",
            "soccer/concacaf.gold" to "soccer_concacaf_gold_cup",
            "basketball/nba" to "basketball_nba",
            "basketball/wnba" to "basketball_wnba",
            "basketball/mens-college-basketball" to "basketball_ncaab",
            "hockey/nhl" to "icehockey_nhl",
        )

        fun sportKey(leagueKey: String): String? = SPORT[leagueKey]

        /** Markets asked per sport: main call, then a best-effort call for extra markets. */
        fun marketsFor(sport: SportType): Pair<String, String> = when (sport) {
            SportType.FOOTBALL -> MARKETS to MARKETS_EXTRA
            SportType.BASKETBALL -> "h2h,spreads,totals" to "team_totals,h2h_h1,totals_h1,spreads_h1,h2h_q1,totals_q1"
            SportType.HOCKEY -> "h2h,spreads,totals" to "h2h_3_way,team_totals,h2h_p1,totals_p1"
            else -> "h2h,spreads,totals" to ""
        }

        fun creditEstimate(sport: SportType): Int = when (sport) {
            SportType.FOOTBALL -> CREDIT_ESTIMATE
            SportType.BASKETBALL -> 9
            SportType.HOCKEY -> 7
            else -> 3
        }


        fun slots(home: String, away: String): List<OddsSlot> = listOf(
            OddsSlot("1X2", "HOME", null, home), OddsSlot("1X2", "DRAW", null, "Draw"), OddsSlot("1X2", "AWAY", null, away),
            OddsSlot("OU", "OVER", 0.5, "Over 0.5"), OddsSlot("OU", "UNDER", 0.5, "Under 0.5"),
            OddsSlot("OU", "OVER", 1.5, "Over 1.5"), OddsSlot("OU", "UNDER", 1.5, "Under 1.5"),
            OddsSlot("OU", "OVER", 2.5, "Over 2.5"), OddsSlot("OU", "UNDER", 2.5, "Under 2.5"),
            OddsSlot("OU", "OVER", 3.5, "Over 3.5"), OddsSlot("OU", "UNDER", 3.5, "Under 3.5"),
            OddsSlot("OU", "OVER", 4.5, "Over 4.5"), OddsSlot("OU", "UNDER", 4.5, "Under 4.5"),
            OddsSlot("BTTS", "YES", null, "BTTS Yes"), OddsSlot("BTTS", "NO", null, "BTTS No"),
            OddsSlot("DC", "1X", null, "Double chance 1X"), OddsSlot("DC", "X2", null, "Double chance X2"), OddsSlot("DC", "12", null, "Double chance 12"),
            OddsSlot("DNB", "DNB_HOME", null, "$home (DNB)"), OddsSlot("DNB", "DNB_AWAY", null, "$away (DNB)"),
            OddsSlot("1H", "H1_HOME", null, "1st half: $home"), OddsSlot("1H", "H1_DRAW", null, "1st half: Draw"), OddsSlot("1H", "H1_AWAY", null, "1st half: $away"),
            OddsSlot("1HOU", "OVER", 0.5, "1H Over 0.5"), OddsSlot("1HOU", "UNDER", 0.5, "1H Under 0.5"),
            OddsSlot("1HOU", "OVER", 1.5, "1H Over 1.5"), OddsSlot("1HOU", "UNDER", 1.5, "1H Under 1.5"),
            OddsSlot("1HBTTS", "YES", null, "1H BTTS Yes"), OddsSlot("1HBTTS", "NO", null, "1H BTTS No"),
        ) + listOf(0.5, 1.5, 2.5).flatMap { l ->
            listOf(
                OddsSlot("TT", "HOME_OVER", l, "$home O$l"), OddsSlot("TT", "HOME_UNDER", l, "$home U$l"),
                OddsSlot("TT", "AWAY_OVER", l, "$away O$l"), OddsSlot("TT", "AWAY_UNDER", l, "$away U$l"),
            )
        }

        fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.01..1000.0 }

        /** Free credits reset monthly: pause until 00:00 UTC on the 1st of next month. */
        fun startOfNextMonth(): Long =
            LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun histKey(matchId: String) = "1x:hist:$matchId"

    suspend fun state(item: MatchItem): OneXBetState {
        val h = runCatching { cache.peek(histKey(item.id), history) }.getOrNull().orEmpty()
        return if (h.isEmpty()) OneXBetState.NotLoaded else OneXBetState.Ok(h.last(), h)
    }

    suspend fun apiStatus(item: MatchItem?): ApiStatus {
        val s = settings.current()
        if (s.oneXBetKey.isBlank()) return ApiStatus.NoKey
        if (System.currentTimeMillis() < s.oddsApiPausedUntil) return ApiStatus.Paused(s.oddsApiPausedUntil)
        if (item != null) {
            if (item.competition.sport != SportType.TENNIS && sportKey(item.competition.key) == null)
                return ApiStatus.Unsupported("${item.competition.title} isn't covered by The Odds API: 1xBet odds unavailable (you can enter them by hand for football).")
        }
        return ApiStatus.Ready(s.oddsApiRemaining)
    }

    /** True when saved prices are missing or older than [maxAgeMs]. */
    suspend fun isStale(item: MatchItem, maxAgeMs: Long = 3 * HOUR): Boolean {
        val h = runCatching { cache.peek(histKey(item.id), history) }.getOrNull().orEmpty()
        return h.isEmpty() || System.currentTimeMillis() - h.last().fetchedAt > maxAgeMs
    }

    private suspend fun append(item: MatchItem, snap: BookSnapshot): OneXBetState {
        val h = (runCatching { cache.peek(histKey(item.id), history) }.getOrNull().orEmpty() + snap).takeLast(30)
        cache.put(histKey(item.id), history, h)
        return OneXBetState.Ok(h.last(), h)
    }

    suspend fun saveManual(item: MatchItem, prices: List<BookPrice>): OneXBetState {
        if (prices.isEmpty()) return state(item)
        val now = System.currentTimeMillis()
        return append(item, BookSnapshot(SOURCE_MANUAL, now, now, prices))
    }

    suspend fun clear(item: MatchItem): OneXBetState {
        cache.put(histKey(item.id), history, emptyList())
        return OneXBetState.NotLoaded
    }

    private suspend fun pauseForMonth() {
        settings.setOddsApiPausedUntil(startOfNextMonth())
        settings.setOddsApiRemaining(0)
    }

    /**
     * Downloads 1xBet prices through The Odds API. Never throws. Returns the new state plus a
     * message when the API couldn't be used (the saved prices, if any, stay in place).
     */
    suspend fun loadFromApi(item: MatchItem): Pair<OneXBetState, String?> {
        val status = apiStatus(item)
        if (status !is ApiStatus.Ready) return state(item) to when (status) {
            is ApiStatus.NoKey -> null
            is ApiStatus.Paused -> "Free Odds API credits are used up until the 1st of next month."
            is ApiStatus.Unsupported -> status.reason
            else -> null
        }
        val key = settings.current().oneXBetKey
        return try {
            val sport = (if (item.competition.sport == SportType.TENNIS) tennisKey(key, item) else sportKey(item.competition.key))
                ?: return state(item) to "1xBet odds unavailable: The Odds API doesn't list this ${if (item.competition.sport == SportType.TENNIS) "tournament" else "competition"}."
            val list = cache.get("1x:events:$sport", 6 * HOUR, events) {
                val r = api.events(sport, key)
                when {
                    r.code() == 401 -> throw ApiProblem("The Odds API rejected the key. Check it in Settings.")
                    r.code() == 429 -> { pauseForMonth(); throw ApiProblem("Free Odds API credits are used up until the 1st of next month.") }
                    !r.isSuccessful -> throw ApiProblem("The Odds API returned error ${r.code()}.")
                }
                r.body().orEmpty()
            }
            val tennis = item.competition.sport == SportType.TENNIS
            val window = if (tennis) 12 * HOUR else 3 * HOUR
            val ev = list.firstOrNull { e ->
                abs(EspnMapper.parseTime(e.commenceTime) - item.kickoff) <= window &&
                    ((TeamMatcher.same(e.homeTeam ?: "", item.homeTeam) && TeamMatcher.same(e.awayTeam ?: "", item.awayTeam)) ||
                        (tennis && TeamMatcher.same(e.homeTeam ?: "", item.awayTeam) && TeamMatcher.same(e.awayTeam ?: "", item.homeTeam)))
            } ?: return state(item) to "1xBet odds unavailable: this match isn't listed by The Odds API."

            val (mainMarkets, extraMarkets) = marketsFor(item.competition.sport)
            var resp = api.eventOdds(sport, ev.id, key, mainMarkets)
            if (resp.code() == 422 && item.competition.sport == SportType.FOOTBALL) resp = api.eventOdds(sport, ev.id, key, MARKETS_FALLBACK)
            if (resp.code() == 422) resp = api.eventOdds(sport, ev.id, key, "h2h")
            val remaining = resp.headers()["x-requests-remaining"]?.toDoubleOrNull()?.toInt()
            if (remaining != null) {
                settings.setOddsApiRemaining(remaining)
                if (remaining <= 0) settings.setOddsApiPausedUntil(startOfNextMonth())
            }
            when {
                resp.code() == 401 -> return state(item) to "The Odds API rejected the key. Check it in Settings."
                resp.code() == 429 -> { pauseForMonth(); return state(item) to "Free Odds API credits are used up until the 1st of next month." }
                !resp.isSuccessful -> return state(item) to "The Odds API returned error ${resp.code()}."
            }
            val body = resp.body() ?: return state(item) to "Empty response from The Odds API."
            val bm = body.bookmakers.firstOrNull { it.key == "onexbet" }
                ?: return state(item) to "1xBet hasn't published prices for this match yet."
            // Our "home" side is always the app's home team (tennis listings may be reversed).
            val homeName = item.homeTeam
            val awayName = item.awayTeam
            val football = item.competition.sport == SportType.FOOTBALL
            var snap = if (football) parse(bm, homeName, awayName) else parseOther(bm, homeName, awayName)
            // Extra markets (team goals, first half, handicap): best effort, never fatal.
            if (snap.prices.isNotEmpty() && extraMarkets.isNotEmpty()) runCatching {
                val extra = api.eventOdds(sport, ev.id, key, extraMarkets)
                extra.headers()["x-requests-remaining"]?.toDoubleOrNull()?.toInt()?.let { settings.setOddsApiRemaining(it) }
                extra.body()?.bookmakers?.firstOrNull { it.key == "onexbet" }?.let { xb ->
                    val more = if (football) parse(xb, homeName, awayName) else parseOther(xb, homeName, awayName)
                    snap = snap.copy(prices = snap.prices + more.prices)
                }
            }
            val live = item.status == com.matchday.app.domain.MatchStatus.LIVE
            snap = snap.copy(live = live, score = if (live) "${item.homeScore ?: "-"}-${item.awayScore ?: "-"}" else null)
            if (snap.prices.isEmpty()) return state(item) to "1xBet hasn't published prices for this match yet."
            append(item, snap) to null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiProblem) {
            state(item) to e.message
        } catch (e: Exception) {
            Log.w(TAG, "1xBet load failed", e)
            state(item) to "1xBet odds couldn't be downloaded (offline or source error)."
        }
    }

    private class ApiProblem(msg: String) : Exception(msg)

    private val sportsSer = ListSerializer(com.matchday.app.data.remote.OSport.serializer())

    /** Finds the tennis tournament key whose event list contains this match (listing is free). */
    private suspend fun tennisKey(key: String, item: MatchItem): String? {
        val tour = if (item.competition.key.endsWith("wta")) "tennis_wta" else "tennis_atp"
        val sports = cache.get("1x:sports", 6 * HOUR, sportsSer) {
            val r = api.sports(key)
            if (!r.isSuccessful) throw ApiProblem("The Odds API returned error ${r.code()}.")
            r.body().orEmpty()
        }.filter { it.key.startsWith(tour) && it.active }
        for (s in sports) {
            val evs = runCatching {
                cache.get("1x:events:${s.key}", 6 * HOUR, events) { api.events(s.key, key).body().orEmpty() }
            }.getOrNull().orEmpty()
            val hit = evs.any { e ->
                val names = setOf(e.homeTeam ?: "", e.awayTeam ?: "")
                names.any { TeamMatcher.same(it, item.homeTeam) } && names.any { TeamMatcher.same(it, item.awayTeam) }
            }
            if (hit) return s.key
        }
        return null
    }

    /** Basketball, hockey and tennis markets (codes: ML REG SPR TOT TT H1ML H1TOT H1SPR Q1ML Q1TOT P1ML P1TOT). */
    private fun parseOther(bm: OBookmaker, home: String, away: String): BookSnapshot {
        val now = System.currentTimeMillis()
        val out = mutableListOf<BookPrice>()
        fun side(n: String): String? = when {
            n.equals("Draw", true) || n.equals("Tie", true) -> "DRAW"
            n == home || TeamMatcher.same(n, home) -> "HOME"
            n == away || TeamMatcher.same(n, away) -> "AWAY"
            else -> null
        }
        fun sgn(p: Double) = (if (p > 0) "+" else "") + fmt(p)
        val codes = mapOf(
            "h2h" to "ML", "h2h_3_way" to "REG", "spreads" to "SPR", "alternate_spreads" to "SPR",
            "totals" to "TOT", "alternate_totals" to "TOT", "team_totals" to "TT",
            "h2h_h1" to "H1ML", "totals_h1" to "H1TOT", "spreads_h1" to "H1SPR",
            "h2h_q1" to "Q1ML", "totals_q1" to "Q1TOT", "h2h_p1" to "P1ML", "totals_p1" to "P1TOT",
        )
        for (m in bm.markets) {
            val code = codes[m.key] ?: continue
            m.outcomes.forEach { o ->
                val pt = o.point
                when (code) {
                    "ML", "REG", "H1ML", "Q1ML", "P1ML" -> side(o.name)?.let { s ->
                        val who = when (s) { "HOME" -> home; "AWAY" -> away; else -> "Draw" }
                        val prefix = when (code) { "REG" -> "Regulation: "; "H1ML" -> "1st half: "; "Q1ML" -> "1st quarter: "; "P1ML" -> "1st period: "; else -> "" }
                        out += BookPrice(code, s, prefix + who, null, o.price)
                    }
                    "SPR", "H1SPR" -> if (pt != null) side(o.name)?.takeIf { it != "DRAW" }?.let { s ->
                        out += BookPrice(code, s, "${if (code == "H1SPR") "1st half: " else ""}${if (s == "HOME") home else away} ${sgn(pt)}", pt, o.price)
                    }
                    "TOT", "H1TOT", "Q1TOT", "P1TOT" -> if (pt != null) {
                        val dir = if (o.name.equals("Over", true)) "OVER" else if (o.name.equals("Under", true)) "UNDER" else null
                        val prefix = when (code) { "H1TOT" -> "1st half "; "Q1TOT" -> "1st quarter "; "P1TOT" -> "1st period "; else -> "" }
                        if (dir != null && out.none { it.market == code && it.sel == dir && it.point == pt })
                            out += BookPrice(code, dir, "$prefix${o.name} ${fmt(pt)}", pt, o.price)
                    }
                    "TT" -> if (pt != null) {
                        val s = side(o.description ?: "")
                        val dir = if (o.name.equals("Over", true)) "OVER" else if (o.name.equals("Under", true)) "UNDER" else null
                        if (s != null && s != "DRAW" && dir != null)
                            out += BookPrice("TT", "${s}_$dir", "${if (s == "HOME") home else away} ${o.name} ${fmt(pt)}", pt, o.price)
                    }
                }
            }
        }
        return BookSnapshot(SOURCE_API, now, EspnMapper.parseTime(bm.lastUpdate).takeIf { it > 0 } ?: now, out)
    }

    private fun parse(bm: OBookmaker, home: String, away: String): BookSnapshot {
        val now = System.currentTimeMillis()
        val out = mutableListOf<BookPrice>()
        fun isHome(n: String) = n == home || (n != "Draw" && TeamMatcher.same(n, home))
        fun isAway(n: String) = n == away || (n != "Draw" && TeamMatcher.same(n, away))
        for (m in bm.markets) {
            when (m.key) {
                "h2h" -> m.outcomes.forEach { o ->
                    when {
                        o.name == "Draw" -> out += BookPrice("1X2", "DRAW", "Draw", null, o.price)
                        isHome(o.name) -> out += BookPrice("1X2", "HOME", o.name, null, o.price)
                        isAway(o.name) -> out += BookPrice("1X2", "AWAY", o.name, null, o.price)
                    }
                }
                "totals", "alternate_totals" -> m.outcomes.forEach { o ->
                    val pt = o.point ?: return@forEach
                    if (pt !in listOf(0.5, 1.5, 2.5, 3.5, 4.5)) return@forEach
                    val sel = if (o.name.equals("Over", true)) "OVER" else if (o.name.equals("Under", true)) "UNDER" else return@forEach
                    if (out.none { it.market == "OU" && it.sel == sel && it.point == pt }) {
                        out += BookPrice("OU", sel, "${o.name} ${fmt(pt)}", pt, o.price)
                    }
                }
                "btts" -> m.outcomes.forEach { o ->
                    when {
                        o.name.equals("Yes", true) -> out += BookPrice("BTTS", "YES", "BTTS Yes", null, o.price)
                        o.name.equals("No", true) -> out += BookPrice("BTTS", "NO", "BTTS No", null, o.price)
                    }
                }
                "draw_no_bet" -> m.outcomes.forEach { o ->
                    when {
                        isHome(o.name) -> out += BookPrice("DNB", "DNB_HOME", "${o.name} (DNB)", null, o.price)
                        isAway(o.name) -> out += BookPrice("DNB", "DNB_AWAY", "${o.name} (DNB)", null, o.price)
                    }
                }
                "double_chance" -> m.outcomes.forEach { o ->
                    val parts = o.name.split("/", " or ", ignoreCase = true).map { it.trim() }
                    val hasDraw = parts.any { it.equals("Draw", true) }
                    val hasHome = parts.any { isHome(it) }
                    val hasAway = parts.any { isAway(it) }
                    val sel = when {
                        hasHome && hasDraw -> "1X"
                        hasAway && hasDraw -> "X2"
                        hasHome && hasAway -> "12"
                        else -> null
                    }
                    if (sel != null) out += BookPrice("DC", sel, "Double chance $sel", null, o.price)
                }
                "team_totals", "alternate_team_totals" -> m.outcomes.forEach { o ->
                    val pt = o.point ?: return@forEach
                    if (pt !in listOf(0.5, 1.5, 2.5)) return@forEach
                    val team = o.description ?: return@forEach
                    val side = if (isHome(team)) "HOME" else if (isAway(team)) "AWAY" else return@forEach
                    val dir = if (o.name.equals("Over", true)) "OVER" else if (o.name.equals("Under", true)) "UNDER" else return@forEach
                    if (out.none { it.market == "TT" && it.sel == "${side}_$dir" && it.point == pt })
                        out += BookPrice("TT", "${side}_$dir", "$team ${o.name} ${fmt(pt)}", pt, o.price)
                }
                "totals_h1" -> m.outcomes.forEach { o ->
                    val pt = o.point ?: return@forEach
                    val sel = if (o.name.equals("Over", true)) "OVER" else if (o.name.equals("Under", true)) "UNDER" else return@forEach
                    out += BookPrice("1HOU", sel, "1st half ${o.name} ${fmt(pt)}", pt, o.price)
                }
                "btts_h1" -> m.outcomes.forEach { o ->
                    when {
                        o.name.equals("Yes", true) -> out += BookPrice("1HBTTS", "YES", "1st half BTTS Yes", null, o.price)
                        o.name.equals("No", true) -> out += BookPrice("1HBTTS", "NO", "1st half BTTS No", null, o.price)
                    }
                }
                "spreads" -> m.outcomes.forEach { o ->
                    val pt = o.point ?: return@forEach
                    when {
                        isHome(o.name) -> out += BookPrice("AH", "HOME", "${o.name} ${if (pt > 0) "+" else ""}${fmt(pt)}", pt, o.price)
                        isAway(o.name) -> out += BookPrice("AH", "AWAY", "${o.name} ${if (pt > 0) "+" else ""}${fmt(pt)}", pt, o.price)
                    }
                }
                "h2h_3_way_h1" -> m.outcomes.forEach { o ->
                    when {
                        o.name == "Draw" -> out += BookPrice("1H", "H1_DRAW", "1st half: Draw", null, o.price)
                        isHome(o.name) -> out += BookPrice("1H", "H1_HOME", "1st half: ${o.name}", null, o.price)
                        isAway(o.name) -> out += BookPrice("1H", "H1_AWAY", "1st half: ${o.name}", null, o.price)
                    }
                }
            }
        }
        return BookSnapshot(SOURCE_API, now, EspnMapper.parseTime(bm.lastUpdate).takeIf { it > 0 } ?: now, out)
    }

    private fun fmt(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
}
