package com.matchday.app.domain

import kotlin.math.exp
import kotlin.math.ln

/**
 * Probability correction applied to every market before ranking, in two layers:
 *  1. Back-test layer (fixed): slopes fitted by replaying a full past season through the engines.
 *     A slope below 1 pulls over-confident estimates toward 50%, above 1 pushes under-confident ones out.
 *  2. Self-correcting layer (learned on the phone): the app's own settled picks, grouped by sport and
 *     kind of bet and by probability range. When a range keeps winning less (or more) often than
 *     predicted, its estimates are moved toward the real hit rate. The shift grows with the number of
 *     settled picks (n / (n + 60)), needs at least 30 of them and is capped at 10 points.
 * Probabilities under 50% are corrected through their complement so both sides of a market stay consistent.
 */
object Calibration {
    data class Bucket(val lo: Double, val hi: Double, val n: Int, val predicted: Double, val actual: Double)

    /**
     * sport|market -> logit slope from the back-test (2025-26 seasons, model only, no odds):
     * NHL 1,180 games, NBA 1,107 games, top-5 European football leagues 1,482 games.
     * Biggest corrections: NHL winner (64% picks won 55%) and football BTTS (63% picks won 52%).
     */
    private val STATIC: Map<String, Double> = mapOf(
        "HOCKEY|ML" to 0.50, "HOCKEY|REG" to 0.89, "HOCKEY|DC" to 0.89, "HOCKEY|SPR" to 0.82, "HOCKEY|TT" to 0.90,
        "HOCKEY|TOT" to 1.0, "HOCKEY|BTTS" to 1.0,
        "BASKETBALL|ML" to 1.0, "BASKETBALL|SPR" to 0.84, "BASKETBALL|TOT" to 0.91, "BASKETBALL|TT" to 0.89,
        "FOOTBALL|1X2" to 0.93, "FOOTBALL|DC" to 0.93, "FOOTBALL|DNB" to 0.90, "FOOTBALL|OU" to 0.92, "FOOTBALL|TT" to 0.90,
        "FOOTBALL|BTTS" to 0.40, "FOOTBALL|CS" to 1.0, "FOOTBALL|WTN" to 1.0,
    )
    /** Pooled slope for the sport's other markets (periods, halves, ...). */
    private val SPORT_DEFAULT = mapOf("HOCKEY" to 0.92, "BASKETBALL" to 0.90, "FOOTBALL" to 0.92)

    @Volatile var learned: Map<String, List<Bucket>> = emptyMap()

    const val MIN_N = 30

    fun group(sport: String, market: String): String = "$sport|" + when (market) {
        "1X2", "ML", "REG", "SET1", "P1ML" -> "Winner"
        "DC", "DNB" -> "Double chance / Draw no bet"
        "OU", "TOT", "S1TOT", "P1TOT", "PG", "1HOU" -> "Totals"
        "BTTS", "1HBTTS" -> "Both teams to score"
        "TT" -> "Team totals"
        "SPR", "AH", "SETHC" -> "Handicaps"
        else -> "Other"
    }

    private fun logit(p: Double) = ln(p / (1 - p))
    private fun sig(x: Double) = 1 / (1 + exp(-x))

    /**
     * Back-test layer only. [modelShare] = share of the estimate that comes from the model (1 = no odds);
     * the back-test measured the model alone, so the correction shrinks when real odds are blended in.
     */
    fun base(sport: String, market: String, p: Double, modelShare: Double = 1.0): Double {
        val raw = STATIC["$sport|$market"] ?: SPORT_DEFAULT[sport] ?: return p
        val a = 1 - (1 - raw) * modelShare.coerceIn(0.0, 1.0)
        return sig(a * logit(p.coerceIn(0.001, 0.999)))
    }

    /** Self-correcting layer on a probability that already went through [base]. */
    fun learn(sport: String, market: String, q: Double): Double {
        if (q < 0.5) return 1 - learn(sport, market, 1 - q)
        val b = learned[group(sport, market)]?.firstOrNull { q >= it.lo && q < it.hi } ?: return q
        if (b.n < MIN_N) return q
        val w = b.n / (b.n + 60.0)
        return (q + (w * (b.actual - b.predicted)).coerceIn(-0.10, 0.10)).coerceIn(0.5, 0.97)
    }

    fun adjust(sport: String, market: String, p: Double, modelShare: Double = 1.0): Double = learn(sport, market, base(sport, market, p, modelShare))

    /** Returns the markets with corrected probabilities; [MarketPick.baseProbability] keeps the value before the learned layer. */
    fun apply(sport: String, list: List<MarketPick>, modelShare: Double = 1.0): List<MarketPick> = list.map { m ->
        val b = base(sport, m.market, m.probability, modelShare)
        val q = learn(sport, m.market, b)
        if (b == m.probability && q == b) m.copy(baseProbability = b) else m.copy(probability = q.coerceIn(0.001, 0.999), baseProbability = b)
    }

    /** Short description of what the learned layer currently does (Accuracy screen). */
    fun summary(): List<String> = learned.flatMap { (g, bs) ->
        bs.filter { it.n >= MIN_N }.map { b ->
            val w = b.n / (b.n + 60.0)
            val shift = (w * (b.actual - b.predicted)).coerceIn(-0.10, 0.10)
            "${g.replace("|", " · ")} ${Math.round(b.lo * 100)}–${Math.round(b.hi * 100)}%: predicted ${Math.round(b.predicted * 100)}%, won ${Math.round(b.actual * 100)}% (${b.n} picks) → ${if (shift >= 0) "+" else ""}${Math.round(shift * 100)} pts"
        }
    }
}
