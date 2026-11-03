package com.matchday.app.data.repo

import com.matchday.app.data.local.OddsEntity
import com.matchday.app.domain.MarketOdds
import com.matchday.app.domain.OddsSummary
import com.matchday.app.domain.OutcomeOdds
import com.matchday.app.domain.PriceQuote
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamMatcher
import kotlin.math.abs

/** Turns stored bookmaker price snapshots into display-ready odds with de-vigged consensus. */
object OddsMapper {

    private data class Key(val market: String, val outcome: String, val point: Double?)

    fun summarize(rows: List<OddsEntity>, home: String, away: String, sport: SportType): OddsSummary? {
        if (rows.isEmpty()) return null
        val latestAt = rows.maxOf { it.fetchedAt }
        val latest = rows.filter { it.fetchedAt == latestAt }
        val prevAt = rows.filter { it.fetchedAt < latestAt }.maxOfOrNull { it.fetchedAt }
        val prev = if (prevAt != null) rows.filter { it.fetchedAt == prevAt } else emptyList()

        fun previousPrice(r: OddsEntity): Double? = prev.firstOrNull {
            it.bookmakerKey == r.bookmakerKey && it.market == r.market &&
                it.outcome == r.outcome && it.point == r.point
        }?.price

        val consensus = consensusProbs(latest)

        val outcomes: Map<Key, OutcomeOdds> = latest.groupBy { Key(it.market, it.outcome, it.point) }
            .mapValues { (k, list) ->
                OutcomeOdds(
                    name = k.outcome,
                    label = label(k, home, away),
                    point = k.point,
                    quotes = list.map { PriceQuote(it.bookmakerTitle, it.price, previousPrice(it), it.bookUpdatedAt) }
                        .sortedByDescending { it.price },
                    consensusProb = consensus[k],
                )
            }

        val h2h = outcomes.filterKeys { it.market == "h2h" }.values.toList()
        val homeO = h2h.firstOrNull { it.name == home } ?: h2h.firstOrNull { it.name != "Draw" && TeamMatcher.same(it.name, home) }
        val awayO = h2h.firstOrNull { it.name == away } ?: h2h.firstOrNull { it.name != "Draw" && it != homeO && TeamMatcher.same(it.name, away) }
        val drawO = h2h.firstOrNull { it.name == "Draw" }

        // Main totals line: the line most bookmakers quote (closest to 2.5 for football on ties).
        val totalsByLine = outcomes.filterKeys { it.market == "totals" && it.point != null }.values.groupBy { it.point!! }
        val mainLine = totalsByLine.entries
            .sortedWith(compareByDescending<Map.Entry<Double, List<OutcomeOdds>>> { e -> e.value.sumOf { it.quotes.size } }
                .thenBy { e -> if (sport == SportType.FOOTBALL) abs(e.key - 2.5) else 0.0 })
            .firstOrNull()?.key
        val overO = mainLine?.let { l -> totalsByLine[l]?.firstOrNull { it.name == "Over" } }
        val underO = mainLine?.let { l -> totalsByLine[l]?.firstOrNull { it.name == "Under" } }

        val markets = outcomes.entries.groupBy({ it.key.market }, { it.value })
            .map { (mk, list) ->
                MarketOdds(
                    key = mk,
                    title = marketTitle(mk, sport),
                    outcomes = list.sortedWith(compareBy<OutcomeOdds> { it.point ?: 0.0 }.thenBy { orderOf(it, home, away) }),
                )
            }
            .sortedBy { listOf("h2h", "totals", "spreads").indexOf(it.key).let { i -> if (i < 0) 99 else i } }

        return OddsSummary(
            home = homeO, draw = drawO, away = awayO,
            totalsLine = mainLine, over = overO, under = underO,
            markets = markets,
            bookmakerCount = latest.map { it.bookmakerKey }.distinct().size,
            fetchedAt = latestAt,
            previousFetchedAt = prevAt,
        )
    }

    /** Per bookmaker: remove the margin proportionally within each complete market; then average. */
    private fun consensusProbs(latest: List<OddsEntity>): Map<Key, Double> {
        val sums = HashMap<Key, Double>()
        val counts = HashMap<Key, Int>()
        val groups = latest.groupBy { Triple(it.bookmakerKey, it.market, it.point) }
        for ((g, list) in groups) {
            val market = g.second
            val names = list.map { it.outcome }.distinct()
            val complete = when (market) {
                "h2h" -> names.size >= 2
                "totals" -> names.containsAll(listOf("Over", "Under"))
                else -> false // spreads are paired below (each side has its own point)
            }
            if (!complete) continue
            val inv = list.map { 1.0 / it.price }
            val total = inv.sum()
            list.forEachIndexed { i, r ->
                val k = Key(r.market, r.outcome, r.point)
                sums[k] = (sums[k] ?: 0.0) + inv[i] / total
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        // Spreads have opposite points per side, so pair them per bookmaker separately.
        for (list in latest.filter { it.market == "spreads" }.groupBy { it.bookmakerKey }.values) {
            if (list.size != 2) continue
            val inv = list.map { 1.0 / it.price }
            val total = inv.sum()
            list.forEachIndexed { i, r ->
                val k = Key(r.market, r.outcome, r.point)
                sums[k] = (sums[k] ?: 0.0) + inv[i] / total
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        return sums.mapValues { (k, s) -> s / (counts[k] ?: 1) }
    }

    private fun label(k: Key, home: String, away: String): String = when (k.market) {
        "h2h" -> k.outcome
        "totals" -> "${k.outcome} ${fmt(k.point)}"
        "spreads" -> "${k.outcome} ${k.point?.let { if (it > 0) "+${fmt(it)}" else fmt(it) } ?: ""}"
        else -> k.outcome
    }

    private fun orderOf(o: OutcomeOdds, home: String, away: String): Int = when (o.name) {
        home -> 0; "Draw" -> 1; away -> 2; "Over" -> 0; "Under" -> 1; else -> 3
    }

    private fun marketTitle(k: String, sport: SportType) = when (k) {
        "h2h" -> if (sport == SportType.FOOTBALL) "Match result (1X2)" else "Winner (moneyline)"
        "totals" -> if (sport == SportType.FOOTBALL) "Total goals (over/under)" else "Total points/runs (over/under)"
        "spreads" -> "Handicap / spread"
        else -> k
    }

    private fun fmt(p: Double?): String = when {
        p == null -> ""
        p % 1.0 == 0.0 -> p.toInt().toString()
        else -> p.toString()
    }
}
