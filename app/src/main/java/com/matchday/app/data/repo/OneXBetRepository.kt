package com.matchday.app.data.repo

import com.matchday.app.domain.MatchItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One 1xBet price. [sel]: HOME DRAW AWAY OVER UNDER YES NO 1X X2 12 DNB_HOME DNB_AWAY H1_HOME H1_DRAW H1_AWAY. */
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
    data object NotLoaded : OneXBetState()
    data class Ok(val current: BookSnapshot, val history: List<BookSnapshot>) : OneXBetState()
}

/** One input slot of the 1xBet entry form. */
data class OddsSlot(val market: String, val sel: String, val point: Double?, val label: String)

/**
 * 1xBet prices typed in by the user from the 1xBet app or site. No API, no key, no scraping:
 * the prices are stored on the phone only, with the time they were entered, and every save is
 * kept so price movement can be shown.
 */
class OneXBetRepository(private val cache: JsonCache) {
    private val history = ListSerializer(BookSnapshot.serializer())
    private fun key(matchId: String) = "1x:manual:$matchId"

    companion object {
        const val SOURCE = "1xBet (entered by you)"

        fun slots(home: String, away: String): List<OddsSlot> = listOf(
            OddsSlot("1X2", "HOME", null, home), OddsSlot("1X2", "DRAW", null, "Draw"), OddsSlot("1X2", "AWAY", null, away),
            OddsSlot("OU", "OVER", 1.5, "Over 1.5"), OddsSlot("OU", "UNDER", 1.5, "Under 1.5"),
            OddsSlot("OU", "OVER", 2.5, "Over 2.5"), OddsSlot("OU", "UNDER", 2.5, "Under 2.5"),
            OddsSlot("OU", "OVER", 3.5, "Over 3.5"), OddsSlot("OU", "UNDER", 3.5, "Under 3.5"),
            OddsSlot("BTTS", "YES", null, "BTTS Yes"), OddsSlot("BTTS", "NO", null, "BTTS No"),
            OddsSlot("DC", "1X", null, "Double chance 1X"), OddsSlot("DC", "X2", null, "Double chance X2"), OddsSlot("DC", "12", null, "Double chance 12"),
            OddsSlot("DNB", "DNB_HOME", null, "$home (DNB)"), OddsSlot("DNB", "DNB_AWAY", null, "$away (DNB)"),
            OddsSlot("1H", "H1_HOME", null, "1st half: $home"), OddsSlot("1H", "H1_DRAW", null, "1st half: Draw"), OddsSlot("1H", "H1_AWAY", null, "1st half: $away"),
        )

        /** Decimal odds must be above 1.01 and sensible. */
        fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.01..1000.0 }
    }

    suspend fun state(item: MatchItem): OneXBetState {
        val h = cache.peek(key(item.id), history).orEmpty()
        return if (h.isEmpty()) OneXBetState.NotLoaded else OneXBetState.Ok(h.last(), h)
    }

    /** Saves a new snapshot; returns the new state. Empty input is ignored. */
    suspend fun save(item: MatchItem, prices: List<BookPrice>): OneXBetState {
        if (prices.isEmpty()) return state(item)
        val now = System.currentTimeMillis()
        val h = (cache.peek(key(item.id), history).orEmpty() + BookSnapshot("1xBet", now, now, prices)).takeLast(30)
        cache.put(key(item.id), history, h)
        return OneXBetState.Ok(h.last(), h)
    }

    suspend fun clear(item: MatchItem): OneXBetState {
        cache.put(key(item.id), history, emptyList())
        return OneXBetState.NotLoaded
    }
}
