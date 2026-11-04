package com.matchday.app.data.repo

import android.util.Log
import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.Network
import com.matchday.app.data.remote.OEvent
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamMatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.math.abs

/** One genuine 1xBet price. [sel]: HOME DRAW AWAY OVER UNDER YES NO 1X X2 12 DNB_HOME DNB_AWAY H1_HOME H1_DRAW H1_AWAY. */
@Serializable
data class BookPrice(
    val market: String,      // 1X2, OU, BTTS, DC, DNB, 1H
    val sel: String,
    val label: String,
    val point: Double? = null,
    val price: Double,
)

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
    data object NotConfigured : OneXBetState()
    data object NotLoaded : OneXBetState()
    data class Unsupported(val reason: String) : OneXBetState()
    data class Unavailable(val reason: String) : OneXBetState()
    data class Ok(val current: BookSnapshot, val history: List<BookSnapshot>) : OneXBetState()
}

/**
 * Genuine 1xBet prices via The Odds API (bookmaker key "onexbet", EU region), using the
 * user's own free key. Nothing is scraped from 1xBet's website; no other bookmaker's
 * prices are ever stored or shown under the 1xBet name.
 */
class OneXBetRepository(private val cache: JsonCache, private val settings: SettingsStore) {
    private val api = Network.oddsApi
    private val history = ListSerializer(BookSnapshot.serializer())
    private val events = ListSerializer(OEvent.serializer())

    companion object {
        private const val TAG = "OneXBet"
        private const val HOUR = 3_600_000L
        const val MARKETS = "h2h,totals,alternate_totals,btts,draw_no_bet,double_chance,h2h_3_way_h1"
        private const val MARKETS_FALLBACK = "h2h,totals,btts,draw_no_bet"
        const val CREDIT_ESTIMATE = 7

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
    }

    private fun histKey(matchId: String) = "1x:hist:$matchId"

    /** Cached prices only - never spends credits. */
    suspend fun cachedState(item: MatchItem): OneXBetState {
        if (settings.current().oneXBetKey.isBlank()) return OneXBetState.NotConfigured
        val h = cache.peek(histKey(item.id), history).orEmpty()
        return if (h.isEmpty()) OneXBetState.NotLoaded else OneXBetState.Ok(h.last(), h)
    }

    /** True when cached prices are missing or older than [maxAgeMs]. */
    suspend fun isStale(item: MatchItem, maxAgeMs: Long = 3 * HOUR): Boolean {
        val h = cache.peek(histKey(item.id), history).orEmpty()
        return h.isEmpty() || System.currentTimeMillis() - h.last().fetchedAt > maxAgeMs
    }

    /** Downloads current 1xBet prices for one match (costs ~[CREDIT_ESTIMATE] credits). */
    suspend fun load(item: MatchItem): OneXBetState {
        val key = settings.current().oneXBetKey
        if (key.isBlank()) return OneXBetState.NotConfigured
        if (item.competition.sport != SportType.FOOTBALL) return OneXBetState.Unsupported("1xBet odds are loaded for football only.")
        val sport = sportKey(item.competition.key)
            ?: return OneXBetState.Unsupported("${item.competition.title} isn't covered by the 1xBet odds source.")
        return try {
            val list = cache.get("1x:events:$sport", 6 * HOUR, events) {
                val r = api.events(sport, key)
                if (r.code() == 401) throw IllegalStateException("The 1xBet odds key was rejected. Check it in Settings.")
                if (!r.isSuccessful) throw IllegalStateException("Odds source error ${r.code()}")
                r.body().orEmpty()
            }
            val ev = list.firstOrNull { e ->
                val t = EspnMapper.parseTime(e.commenceTime)
                abs(t - item.kickoff) <= 3 * HOUR &&
                    TeamMatcher.same(e.homeTeam ?: "", item.homeTeam) && TeamMatcher.same(e.awayTeam ?: "", item.awayTeam)
            } ?: return OneXBetState.Unavailable("This match isn't listed by the 1xBet odds source yet.")

            var resp = api.eventOdds(sport, ev.id, key, MARKETS)
            if (resp.code() == 422) resp = api.eventOdds(sport, ev.id, key, MARKETS_FALLBACK)
            resp.headers()["x-requests-remaining"]?.toDoubleOrNull()?.let { settings.setOddsApiRemaining(it.toInt()) }
            when {
                resp.code() == 401 -> return OneXBetState.Unavailable("The 1xBet odds key was rejected. Check it in Settings.")
                resp.code() == 429 -> return OneXBetState.Unavailable("Monthly 1xBet odds credits used up (free plan). Showing saved prices if any.")
                !resp.isSuccessful -> return OneXBetState.Unavailable("Odds source error ${resp.code()}.")
            }
            val body = resp.body() ?: return OneXBetState.Unavailable("Empty response from the odds source.")
            val bm = body.bookmakers.firstOrNull { it.key == "onexbet" }
                ?: return OneXBetState.Unavailable("1xBet hasn't published prices for this match yet.")
            val snap = parse(bm, body.homeTeam ?: ev.homeTeam ?: "", body.awayTeam ?: ev.awayTeam ?: "")
            if (snap.prices.isEmpty()) return OneXBetState.Unavailable("1xBet hasn't published prices for this match yet.")
            val h = (cache.peek(histKey(item.id), history).orEmpty() + snap).takeLast(30)
            cache.put(histKey(item.id), history, h)
            OneXBetState.Ok(snap, h)
        } catch (e: Exception) {
            Log.w(TAG, "1xBet load failed", e)
            val h = cache.peek(histKey(item.id), history).orEmpty()
            if (h.isNotEmpty()) OneXBetState.Ok(h.last(), h)
            else OneXBetState.Unavailable(e.message ?: "1xBet odds unavailable (offline or source error).")
        }
    }

    private fun parse(bm: com.matchday.app.data.remote.OBookmaker, home: String, away: String): BookSnapshot {
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
                "h2h_3_way_h1" -> m.outcomes.forEach { o ->
                    when {
                        o.name == "Draw" -> out += BookPrice("1H", "H1_DRAW", "1st half: Draw", null, o.price)
                        isHome(o.name) -> out += BookPrice("1H", "H1_HOME", "1st half: ${o.name}", null, o.price)
                        isAway(o.name) -> out += BookPrice("1H", "H1_AWAY", "1st half: ${o.name}", null, o.price)
                    }
                }
            }
        }
        return BookSnapshot(
            bookmaker = bm.title.ifBlank { "1xBet" },
            fetchedAt = now,
            bookUpdatedAt = EspnMapper.parseTime(bm.lastUpdate).takeIf { it > 0 } ?: now,
            prices = out,
        )
    }

    private fun fmt(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
}
