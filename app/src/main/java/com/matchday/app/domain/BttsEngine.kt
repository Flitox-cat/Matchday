package com.matchday.app.domain

import kotlin.math.abs

/** Both Teams To Score analysis. Probabilities are estimates; odds are only ever real bookmaker prices. */
data class BttsAnalysis(
    val yes: Double,
    val no: Double,
    val recommendation: String,          // "Yes" or "No"
    val confidence: Confidence,
    val oddsYes: Double?,                // real bookmaker price, null = not available
    val oddsNo: Double?,
    val reasons: List<String>,
    val inputs: List<Pair<String, String>>, // factor -> value shown in the table
    val full: Boolean,                   // true = full analysis (match page), false = quick list estimate
)

/**
 * Transparent BTTS estimate built from up to four independent signals:
 *  1. Poisson goals model (expected goals from the prediction engine)            weight 0.45
 *  2. Scoring/conceding rates: P(home scores) x P(away scores) from last 5-10    weight 0.25
 *  3. Each team's recent BTTS rate                                               weight 0.20
 *  4. Head-to-head BTTS rate (only with 3+ meetings)                             weight 0.10
 * Missing signals are dropped and the weights renormalised. Small samples are shrunk towards 50%.
 */
object BttsEngine {

    private fun shrink(rate: Double, n: Int, prior: Double = 0.5, k: Double = 4.0) = (rate * n + prior * k) / (n + k)
    private fun pct(p: Double) = "${Math.round(p * 100)}%"

    /** Quick estimate for lists and rankings: Poisson model only. */
    fun quick(p: Prediction?): BttsAnalysis? {
        if (p == null) return null
        val b = p.btts ?: return null
        val rec = if (b >= 0.5) "Yes" else "No"
        val conf = if (abs(b - 0.5) >= 0.15) Confidence.MEDIUM else Confidence.LOW
        return BttsAnalysis(
            yes = b, no = 1 - b, recommendation = rec, confidence = conf,
            oddsYes = null, oddsNo = null,
            reasons = listOf("Quick estimate from expected goals (${p.expectedHome?.let { String.format("%.1f", it) } ?: "?"} - " +
                "${p.expectedAway?.let { String.format("%.1f", it) } ?: "?"}). Open the match for the full BTTS analysis."),
            inputs = emptyList(), full = false,
        )
    }

    fun analyze(
        home: String,
        away: String,
        prediction: Prediction?,
        homeRecent: List<RecentResult>,
        awayRecent: List<RecentResult>,
        h2h: List<RecentResult>,
        injuries: InjuryInfo,
    ): BttsAnalysis? {
        val signals = mutableListOf<Pair<Double, Double>>() // (probability, weight)
        val inputs = mutableListOf<Pair<String, String>>()
        val reasons = mutableListOf<String>()

        // 1. Poisson model
        prediction?.btts?.let {
            signals += it to 0.45
            inputs += "Goals model (expected ${fmt(prediction?.expectedHome)} - ${fmt(prediction?.expectedAway)})" to pct(it)
        }

        val h = homeRecent.take(10)
        val a = awayRecent.take(10)

        // 2. Scoring / conceding rates (home team's home games and away team's away games when there are enough)
        if (h.size >= 3 && a.size >= 3) {
            val hHome = h.filter { it.home }.takeIf { it.size >= 3 } ?: h
            val aAway = a.filter { !it.home }.takeIf { it.size >= 3 } ?: a
            val homeScored = shrink(hHome.count { it.goalsFor > 0 }.toDouble() / hHome.size, hHome.size, 0.7)
            val homeConceded = shrink(hHome.count { it.goalsAgainst > 0 }.toDouble() / hHome.size, hHome.size, 0.65)
            val awayScored = shrink(aAway.count { it.goalsFor > 0 }.toDouble() / aAway.size, aAway.size, 0.65)
            val awayConceded = shrink(aAway.count { it.goalsAgainst > 0 }.toDouble() / aAway.size, aAway.size, 0.7)
            val pHome = (homeScored + awayConceded) / 2
            val pAway = (awayScored + homeConceded) / 2
            val p = pHome * pAway
            signals += p to 0.25
            val homeLabel = if (hHome !== h) "at home" else "recent"
            val awayLabel = if (aAway !== a) "away" else "recent"
            inputs += "$home scored in ($homeLabel)" to "${hHome.count { it.goalsFor > 0 }}/${hHome.size}"
            inputs += "$away scored in ($awayLabel)" to "${aAway.count { it.goalsFor > 0 }}/${aAway.size}"
            inputs += "$home conceded in ($homeLabel)" to "${hHome.count { it.goalsAgainst > 0 }}/${hHome.size}"
            inputs += "$away conceded in ($awayLabel)" to "${aAway.count { it.goalsAgainst > 0 }}/${aAway.size}"
            inputs += "Scoring-rate signal" to pct(p)
            reasons += "$home score in ${pct(homeScored)} and $away in ${pct(awayScored)} of comparable games (adjusted for sample size)."
        }

        // 3. Recent BTTS rate
        val hb = h.count { it.goalsFor > 0 && it.goalsAgainst > 0 }
        val ab = a.count { it.goalsFor > 0 && it.goalsAgainst > 0 }
        if (h.size >= 3 || a.size >= 3) {
            val rates = listOfNotNull(
                if (h.size >= 3) shrink(hb.toDouble() / h.size, h.size) else null,
                if (a.size >= 3) shrink(ab.toDouble() / a.size, a.size) else null,
            )
            val p = rates.average()
            signals += p to 0.20
            if (h.isNotEmpty()) inputs += "$home BTTS in last ${h.size}" to "$hb/${h.size}"
            if (a.isNotEmpty()) inputs += "$away BTTS in last ${a.size}" to "$ab/${a.size}"
            reasons += "Both teams scored in $hb of $home's last ${h.size} and $ab of $away's last ${a.size} games."
        }

        // 4. Head-to-head
        if (h2h.size >= 3) {
            val n = h2h.size
            val k = h2h.count { it.goalsFor > 0 && it.goalsAgainst > 0 }
            val p = shrink(k.toDouble() / n, n)
            signals += p to 0.10
            inputs += "Head-to-head BTTS" to "$k/$n meetings"
            reasons += "Both scored in $k of the last $n meetings."
        } else {
            inputs += "Head-to-head BTTS" to if (h2h.isEmpty()) "No meetings found" else "Only ${h2h.size} meeting(s) - not used"
        }

        if (signals.isEmpty()) return null

        // Attack / defence summary from the averages
        if (h.isNotEmpty() && a.isNotEmpty()) {
            val hgf = h.sumOf { it.goalsFor }.toDouble() / h.size
            val hga = h.sumOf { it.goalsAgainst }.toDouble() / h.size
            val agf = a.sumOf { it.goalsFor }.toDouble() / a.size
            val aga = a.sumOf { it.goalsAgainst }.toDouble() / a.size
            inputs += "$home avg scored / conceded" to "${fmt(hgf)} / ${fmt(hga)}"
            inputs += "$away avg scored / conceded" to "${fmt(agf)} / ${fmt(aga)}"
            when {
                hga >= 1.3 && aga >= 1.3 -> reasons += "Both defences are leaking goals (${fmt(hga)} and ${fmt(aga)} conceded per game)."
                hgf < 1.0 || agf < 1.0 -> reasons += "One attack is weak (${fmt(minOf(hgf, agf))} goals per game), which lowers BTTS chances."
                hga <= 0.8 || aga <= 0.8 -> reasons += "A strong defence (${fmt(minOf(hga, aga))} conceded per game) works against BTTS."
            }
        }

        // Injuries: football injury lists aren't in the free source
        if (injuries.known) {
            val missing = injuries.homeOut.size + injuries.awayOut.size
            inputs += "Reported absences" to "$missing out"
            reasons += "Absences are already reflected in the goals model (lower expected goals for depleted sides)."
        } else {
            inputs += "Injuries / unavailable players" to "Data unavailable"
        }

        val wSum = signals.sumOf { it.second }
        val yes = (signals.sumOf { it.first * it.second } / wSum).coerceIn(0.03, 0.97)
        val rec = if (yes >= 0.5) "Yes" else "No"
        val margin = abs(yes - 0.5)
        val sample = minOf(h.size, a.size)
        val confidence = when {
            margin >= 0.15 && signals.size >= 3 && sample >= 8 -> Confidence.HIGH
            margin >= 0.07 && signals.size >= 2 && sample >= 5 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        reasons.add(0, "Estimated ${pct(yes)} chance that both teams score, so the lean is BTTS $rec (${signals.size} of 4 signals available).")
        if (confidence == Confidence.LOW) reasons += "Low confidence: the estimate is close to 50/50 or based on limited data."

        return BttsAnalysis(
            yes = yes, no = 1 - yes, recommendation = rec, confidence = confidence,
            oddsYes = null, oddsNo = null, // the free source publishes no BTTS prices
            reasons = reasons, inputs = inputs, full = true,
        )
    }

    private fun fmt(d: Double?) = d?.let { String.format("%.1f", it) } ?: "?"
}
