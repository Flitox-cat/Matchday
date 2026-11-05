package com.matchday.app.domain

import com.matchday.app.data.repo.BookPrice

/** One market line with a real price and our probability. */
data class MarketLine(
    val group: String,        // 1X2 DC DNB OU BTTS 1H
    val sel: String,          // HOME DRAW AWAY 1X X2 12 DNB_HOME DNB_AWAY OVER UNDER YES NO H1_*
    val point: Double?,
    val label: String,
    val odds: Double,
    val bookmaker: String,
    val probability: Double?, // AI probability; null when we have no model for that market
) {
    val implied: Double get() = 1.0 / odds
    val edge: Double? get() = probability?.let { it * odds - 1 }
    val isValue: Boolean get() = (edge ?: -1.0) >= VerdictEngine.VALUE_EDGE
}

enum class VerdictRating(val label: String) { WORTH("Worth Considering"), RISKY("Risky"), AVOID("Avoid Betting") }

data class Verdict(
    val rating: VerdictRating,
    val noBet: Boolean,
    val best: MarketLine?,
    val alternative: MarketLine?,
    val headline: String,
    val reasons: List<String>,
    val insightNotes: List<String>,
    val oddsSource: String?,
    val lines: List<MarketLine>,
)

/**
 * Advanced Betting Verdict. Probabilities come only from the prediction / BTTS engines;
 * prices only from a real bookmaker (1xBet when loaded, otherwise the free feed's bookmaker,
 * always labelled). Historical insights never change a probability: they can only lower the
 * rating when they contradict a pick, and they are listed as supporting/contradicting context.
 */
object VerdictEngine {
    const val VALUE_EDGE = 0.03
    private const val STRONG_EDGE = 0.05
    private const val SUSPICIOUS_EDGE = 0.25 // with low confidence, a gap this big usually means missing information

    private fun pct(x: Double) = "${Math.round(x * 100)}%"
    private fun signed(x: Double) = (if (x >= 0) "+" else "") + "${Math.round(x * 100)}%"

    /** Lines from genuine 1xBet prices. */
    fun linesFrom1xBet(prices: List<BookPrice>, bookmaker: String, p: Prediction?, btts: BttsAnalysis?): List<MarketLine> =
        prices.map { b -> MarketLine(b.market, b.sel, b.point, b.label, b.price, bookmaker, prob(b.market, b.sel, b.point, p, btts)) }

    /** Lines from the free feed (1X2 and the main total only). */
    fun linesFromFeed(o: OddsSummary?, p: Prediction?, btts: BttsAnalysis?): List<MarketLine> {
        if (o == null) return emptyList()
        val out = mutableListOf<MarketLine>()
        fun add(group: String, sel: String, point: Double?, oo: OutcomeOdds?) {
            val q = oo?.best ?: return
            out += MarketLine(group, sel, point, oo.label, q.price, q.bookmaker, prob(group, sel, point, p, btts))
        }
        add("1X2", "HOME", null, o.home); add("1X2", "DRAW", null, o.draw); add("1X2", "AWAY", null, o.away)
        if (o.totalsLine in listOf(1.5, 2.5, 3.5)) { add("OU", "OVER", o.totalsLine, o.over); add("OU", "UNDER", o.totalsLine, o.under) }
        return out
    }

    fun prob(group: String, sel: String, point: Double?, p: Prediction?, btts: BttsAnalysis?): Double? {
        if (p == null) return if (group == "BTTS") btts?.let { if (sel == "YES") it.yes else it.no } else null
        val f = p.final
        return when (group) {
            "1X2" -> when (sel) { "HOME" -> f.home; "DRAW" -> f.draw; "AWAY" -> f.away; else -> null }
            "DC" -> when (sel) { "1X" -> p.doubleChance1X; "X2" -> p.doubleChanceX2; "12" -> p.doubleChance12; else -> null }
            "DNB" -> {
                val s = f.home + f.away
                if (s <= 0) null else if (sel == "DNB_HOME") f.home / s else f.away / s
            }
            "OU" -> {
                val over = when (point) { 1.5 -> p.over15; 2.5 -> p.over25; 3.5 -> p.over35; else -> null } ?: return null
                if (sel == "OVER") over else 1 - over
            }
            "BTTS" -> {
                val y = btts?.yes ?: p.btts ?: return null
                if (sel == "YES") y else 1 - y
            }
            else -> null // first-half result: no model, price shown for information only
        }
    }

    private fun supports(l: MarketLine, lean: Lean): Int = when (lean) {
        Lean.HOME -> when (l.sel) { "HOME", "1X", "DNB_HOME" -> 1; "AWAY", "X2", "DNB_AWAY" -> -1; else -> 0 }
        Lean.AWAY -> when (l.sel) { "AWAY", "X2", "DNB_AWAY" -> 1; "HOME", "1X", "DNB_HOME" -> -1; else -> 0 }
        Lean.DRAW -> when (l.sel) { "DRAW" -> 1; "12" -> -1; else -> 0 }
        Lean.OVER -> if (l.group == "OU") (if (l.sel == "OVER") 1 else -1) else 0
        Lean.UNDER -> if (l.group == "OU") (if (l.sel == "UNDER") 1 else -1) else 0
        Lean.BTTS_YES -> if (l.group == "BTTS") (if (l.sel == "YES") 1 else -1) else 0
        Lean.BTTS_NO -> if (l.group == "BTTS") (if (l.sel == "NO") 1 else -1) else 0
    }

    private fun support(l: MarketLine, insights: List<Insight>): Pair<List<Insight>, List<Insight>> {
        val pro = insights.filter { i -> i.leans.any { supports(l, it.lean) > 0 } }
        val con = insights.filter { i -> i.leans.any { supports(l, it.lean) < 0 } }
        return pro to con
    }

    private fun strength(l: MarketLine, insights: List<Insight>): Double =
        insights.sumOf { i -> i.leans.sumOf { supports(l, it.lean) * it.strength } }

    fun build(
        item: MatchItem,
        p: Prediction?,
        btts: BttsAnalysis?,
        lines: List<MarketLine>,
        oddsSource: String?,
        insights: List<Insight>,
    ): Verdict {
        val reasons = mutableListOf<String>()

        // Reliability gate -> NO BET.
        val unreliable = when {
            item.status != MatchStatus.UPCOMING -> "The match has started or finished."
            p == null -> "No prediction: the free sources have neither a league table nor odds for this match."
            lines.isEmpty() -> "No real bookmaker prices are available, so value can't be measured."
            p.completeness < 0.5 -> "Too little team data (${pct(p.completeness)} of model inputs available)."
            else -> null
        }
        if (unreliable != null) {
            return Verdict(VerdictRating.AVOID, true, null, null, "NO BET", listOf(unreliable, "When the data isn't reliable the right call is not to bet."),
                emptyList(), oddsSource, lines)
        }
        p!!

        val candidates = lines.filter { it.probability != null && it.odds in 1.25..5.0 && it.isValue && !(p.confidence == Confidence.LOW && (it.edge ?: 0.0) > SUSPICIOUS_EDGE) }
            .sortedByDescending { (it.edge ?: 0.0) + 0.02 * strength(it, insights) }
        val best = candidates.firstOrNull()

        if (best == null) {
            val likeliest = lines.filter { it.probability != null }.maxByOrNull { it.probability!! }
            val ignored = lines.filter { it.isValue && it.probability != null && it !in candidates }
            reasons += if (ignored.isNotEmpty())
                "Prices that look like value (${ignored.take(2).joinToString { "${it.label} @ ${String.format("%.2f", it.odds)}" }}) were ignored: long odds or a gap too large to trust with low model confidence."
            else "No available price beats our probability by ${pct(VALUE_EDGE)} or more."
            likeliest?.let { reasons += "Likeliest outcome: ${it.label} (${pct(it.probability!!)}), but ${it.bookmaker}'s ${String.format("%.2f", it.odds)} already reflects it (implied ${pct(it.implied)})." }
            return Verdict(VerdictRating.AVOID, true, null, null, "NO BET - no value at current prices", reasons,
                insightNotes(lines, insights), oddsSource, lines)
        }

        val edge = best.edge ?: 0.0
        val (pro, con) = support(best, insights.filter { it.leans.isNotEmpty() })
        val net = strength(best, insights)
        val contradicted = net < -0.5
        val lowConfidence = p.confidence == Confidence.LOW
        val longShot = best.odds >= 3.5

        val rating = when {
            edge > SUSPICIOUS_EDGE -> VerdictRating.RISKY
            edge >= STRONG_EDGE && !lowConfidence && !contradicted && !longShot -> VerdictRating.WORTH
            else -> VerdictRating.RISKY
        }
        reasons += "AI probability ${pct(best.probability!!)} vs ${pct(best.implied)} implied by ${best.bookmaker}'s ${String.format("%.2f", best.odds)}: expected value ${signed(edge)} per unit."
        reasons += "Model confidence: ${p.confidence.name.lowercase()}."
        p.injuryImpact?.let { im ->
            val now = best.probability ?: 0.0
            val comparable = best.group in setOf("1X2", "DC", "DNB") || (best.group == "OU" && best.point == 2.5)
            val before = if (comparable) prob(best.group, best.sel, best.point, p.copy(final = im.before, over25 = im.over25Before ?: p.over25), null) else null
            if (before != null && kotlin.math.abs(before - now) >= 0.005) {
                reasons += "Reported absences moved this pick from ${pct(before)} to ${pct(now)}."
            } else if (before != null) reasons += "Reported absences barely change this pick."
        }
        if (lowConfidence) reasons += "Low confidence lowers the rating to Risky."
        if (longShot) reasons += "Odds of 3.50+ mean it loses more often than it wins, even with value."
        if (edge > SUSPICIOUS_EDGE) reasons += "An edge above ${pct(SUSPICIOUS_EDGE)} is unusually large: the bookmaker may know something the model doesn't (injuries, rotation)."
        if (edge < STRONG_EDGE) reasons += "The edge is small (under ${pct(STRONG_EDGE)}), so the price could easily be fair."
        if (pro.isNotEmpty()) reasons += "Supported by history: " + pro.take(3).joinToString("; ") { it.title } + "."
        if (con.isNotEmpty()) reasons += "Contradicted by history: " + con.take(3).joinToString("; ") { it.title } + (if (contradicted) " - this lowers the rating." else ".")
        if (pro.isEmpty() && con.isEmpty()) reasons += "Historical facts are neutral for this market."

        val alternative = candidates.drop(1).firstOrNull { it.group != best.group && strength(it, insights) > -0.5 }

        return Verdict(
            rating = rating, noBet = false, best = best, alternative = alternative,
            headline = "${best.label} @ ${String.format("%.2f", best.odds)}",
            reasons = reasons, insightNotes = insightNotes(lines, insights), oddsSource = oddsSource, lines = lines,
        )
    }

    private fun insightNotes(lines: List<MarketLine>, insights: List<Insight>): List<String> =
        insights.filter { it.leans.isNotEmpty() }.take(5).map { "${it.title}: ${it.impact}" }
}
