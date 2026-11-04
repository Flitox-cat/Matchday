package com.matchday.app.domain

data class Pick(
    val match: MatchItem,
    val selection: Selection,
    val why: String,
)

data class AvoidItem(val match: MatchItem, val why: String)

data class BttsPick(val match: MatchItem, val analysis: BttsAnalysis)

data class BestPicks(
    val bestOverall: Pick?,
    val safest: List<Pick>,
    val bestValue: List<Pick>,
    val highRisk: List<Pick>,
    val avoid: List<AvoidItem>,
    val btts: List<BttsPick> = emptyList(),
)

/**
 * Ranks upcoming matches (next 48 h by default). Ranking inputs, in order:
 * probability, value vs. best real price, and confidence (which already folds in form,
 * absences, data completeness and model/market agreement).
 */
object BestPicksRanker {

    private fun confWeight(c: Confidence) = when (c) {
        Confidence.HIGH -> 1.0
        Confidence.MEDIUM -> 0.7
        Confidence.LOW -> 0.35
    }

    fun rank(matches: List<MatchItem>, now: Long = System.currentTimeMillis(), horizonMs: Long = 48 * 3_600_000L): BestPicks {
        val upcoming = matches.filter {
            it.status == MatchStatus.UPCOMING && it.kickoff in now..(now + horizonMs) && it.prediction != null
        }

        val valuePicks = upcoming.mapNotNull { m ->
            val p = m.prediction!!
            val v = p.bestValue ?: return@mapNotNull null
            Pick(m, v, valueWhy(m, p, v))
        }

        val bestOverall = valuePicks
            .filter { it.match.prediction!!.confidence != Confidence.LOW }
            .maxByOrNull { (it.selection.edge ?: 0.0) * confWeight(it.match.prediction!!.confidence) * Math.sqrt(it.selection.probability) }
            ?: valuePicks.maxByOrNull { (it.selection.edge ?: 0.0) * confWeight(it.match.prediction!!.confidence) }

        val safest = upcoming.mapNotNull { m ->
            val p = m.prediction!!
            val s = p.mostLikely
            if (s.probability < 0.62 || p.confidence == Confidence.LOW || s.odds == null) return@mapNotNull null
            val valueNote = when {
                (s.edge ?: -1.0) >= 0.0 -> "The best price is at or above fair value."
                else -> "Short price: likely, but the odds offer little or no value (${PredictionEngine.signedPct(s.edge ?: 0.0)})."
            }
            Pick(m, s, "Estimated ${PredictionEngine.pct(s.probability)} chance, ${p.confidence.name.lowercase()} confidence. $valueNote")
        }.sortedByDescending { it.selection.probability }.take(5)

        val bestValue = valuePicks.sortedByDescending { it.selection.edge ?: 0.0 }.take(5)

        val highRisk = valuePicks
            .filter { (it.selection.odds ?: 0.0) >= 3.5 && (it.selection.edge ?: 0.0) >= 0.05 }
            .sortedByDescending { it.selection.edge ?: 0.0 }
            .map { it.copy(why = "High risk: only ${PredictionEngine.pct(it.selection.probability)} likely, but priced at ${PredictionEngine.fmtOdds(it.selection.odds)}. " + it.why) }
            .take(5)

        val avoid = upcoming.mapNotNull { m ->
            val p = m.prediction!!
            val reasons = mutableListOf<String>()
            if (p.disagreement != null && p.disagreement >= 0.12) reasons += "model and market disagree by ${PredictionEngine.pct(p.disagreement)}"
            if (p.completeness < 0.4) reasons += "little reliable data (${PredictionEngine.pct(p.completeness)} complete)"
            if (p.final.max() < 0.42) reasons += "no clear favourite (top outcome ${PredictionEngine.pct(p.final.max())})"
            if (m.odds == null) reasons += "no bookmaker prices"
            if (reasons.isEmpty() || p.confidence != Confidence.LOW) null
            else AvoidItem(m, "Uncertain: " + reasons.joinToString("; ") + ".")
        }.take(8)

        // BTTS: strongest leans either way (no BTTS prices in the free source, so ranked by
        // probability margin and confidence, not by value).
        val btts = upcoming.mapNotNull { m -> m.btts?.let { BttsPick(m, it) } }
            .filter { it.analysis.confidence != Confidence.LOW }
            .sortedByDescending { kotlin.math.abs(it.analysis.yes - 0.5) * confWeight(it.analysis.confidence) }
            .take(6)

        return BestPicks(bestOverall, safest, bestValue, highRisk, avoid, btts)
    }

    private fun valueWhy(m: MatchItem, p: Prediction, v: Selection): String =
        "We estimate ${PredictionEngine.pct(v.probability)}; best price ${PredictionEngine.fmtOdds(v.odds)} at ${v.bookmaker} " +
            "implies ${PredictionEngine.pct(1 / (v.odds ?: 1.0))}. Expected return ${PredictionEngine.signedPct(v.edge ?: 0.0)} per unit, " +
            "${p.confidence.name.lowercase()} confidence. ${p.method}."
}
