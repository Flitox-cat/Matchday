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
        )

        fun sportKey(leagueKey: String): String? = SPORT[leagueKey]

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
            if (item.competition.sport != SportType.FOOTBALL) return ApiStatus.Unsupported("Automatic 1xBet odds are for football only.")
            if (sportKey(item.competition.key) == null) return ApiStatus.Unsupported("${item.competition.title} isn't covered by The Odds API: enter 1xBet prices by hand.")
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
        val sport = sportKey(item.competition.key) ?: return state(item) to null
        return try {
            val list = cache.get("1x:events:$sport", 6 * HOUR, events) {
                val r = api.events(sport, key)
                when {
                    r.code() == 401 -> throw ApiProblem("The Odds API rejected the key. Check it in Settings.")
                    r.code() == 429 -> { pauseForMonth(); throw ApiProblem("Free Odds API credits are used up until the 1st of next month.") }
                    !r.isSuccessful -> throw ApiProblem("The Odds API returned error ${r.code()}.")
                }
                r.body().orEmpty()
            }
            val ev = list.firstOrNull { e ->
                abs(EspnMapper.parseTime(e.commenceTime) - item.kickoff) <= 3 * HOUR &&
                    TeamMatcher.same(e.homeTeam ?: "", item.homeTeam) && TeamMatcher.same(e.awayTeam ?: "", item.awayTeam)
            } ?: return state(item) to "This match isn't listed by The Odds API yet."

            var resp = api.eventOdds(sport, ev.id, key, MARKETS)
            if (resp.code() == 422) resp = api.eventOdds(sport, ev.id, key, MARKETS_FALLBACK)
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
            val homeName = body.homeTeam ?: ev.homeTeam ?: ""
            val awayName = body.awayTeam ?: ev.awayTeam ?: ""
            var snap = parse(bm, homeName, awayName)
            // Extra markets (team goals, first half, handicap): best effort, never fatal.
            if (snap.prices.isNotEmpty()) runCatching {
                val extra = api.eventOdds(sport, ev.id, key, MARKETS_EXTRA)
                extra.headers()["x-requests-remaining"]?.toDoubleOrNull()?.toInt()?.let { settings.setOddsApiRemaining(it) }
                extra.body()?.bookmakers?.firstOrNull { it.key == "onexbet" }?.let { xb ->
                    snap = snap.copy(prices = snap.prices + parse(xb, homeName, awayName).prices)
                }
            }
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
