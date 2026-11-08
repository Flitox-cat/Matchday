package com.matchday.app.domain

import com.matchday.app.data.repo.BookPrice
import kotlin.math.exp
import kotlin.math.min

enum class MarketFamily(val label: String) {
    RESULT("Match winner"),
    DOUBLE_CHANCE("Double chance / Draw no bet"),
    TOTALS("Over/Under goals"),
    BTTS("Both teams to score"),
    TEAM_GOALS("Individual team goals"),
    FIRST_HALF("First-half markets"),
    HANDICAP("Spread / handicap"),
    POINTS("Total points"),
    TEAM_POINTS("Individual team points"),
    PERIODS("Halves, quarters & periods"),
    SETS("Sets"),
    GAMES("Games"),
    PLAYER_GAMES("Player games"),
    OTHER("Other markets"),
}

/** One analysed selection. [probability] is ours; [odds] is a real price or null (never invented). */
data class MarketPick(
    val family: MarketFamily,
    val market: String,       // price key group: 1X2 DC DNB OU BTTS TT 1H 1HOU 1HBTTS AH CS FG LG WEH WTN
    val sel: String,
    val point: Double?,
    val label: String,
    val probability: Double,
    val modelProbability: Double,
    val empirical: Double?,    // share of real recent games where this happened
    val empiricalGames: Int,
    val odds: Double?,
    val bookmaker: String?,
    val injuryShift: Double?,  // probability change caused by reported absences
    val evidence: List<String>,
    val risks: List<String>,
) {
    val edge: Double? get() = odds?.let { probability * it - 1 }
    val implied: Double? get() = odds?.let { 1 / it }
    val key: String get() = "$market|$sel|${point ?: ""}"
}

/** Goal profile of a team from real results (null fields = not enough data). */
data class GoalProfile(
    val label: String,
    val games: Int,
    val avgFor: Double,
    val avgAgainst: Double,
    val over15: Double,
    val over25: Double,
    val over35: Double,
    val btts: Double,
    val cleanSheets: Double,
    val failedToScore: Double,
    val scored2plus: Double,
    val conceded2plus: Double,
    val from: Long?,
    val to: Long?,
) {
    companion object {
        fun of(label: String, g: List<TeamGame>): GoalProfile? {
            if (g.isEmpty()) return null
            val n = g.size.toDouble()
            fun r(p: (TeamGame) -> Boolean) = g.count(p) / n
            return GoalProfile(
                label, g.size, g.sumOf { it.gf } / n, g.sumOf { it.ga } / n,
                r { it.gf + it.ga >= 2 }, r { it.gf + it.ga >= 3 }, r { it.gf + it.ga >= 4 },
                r { it.gf > 0 && it.ga > 0 }, r { it.ga == 0 }, r { it.gf == 0 }, r { it.gf >= 2 }, r { it.ga >= 2 },
                g.minOfOrNull { it.m.date }, g.maxOfOrNull { it.m.date },
            )
        }
    }
}

data class MarketAnalysis(
    val markets: List<MarketPick>,
    val top: List<MarketPick>,
    val noPickReason: String?,
    val unavailable: List<Pair<String, String>>,
    val homeAtHome: GoalProfile?,
    val awayAway: GoalProfile?,
    val homeAll: GoalProfile?,
    val awayAll: GoalProfile?,
    val expectedHome: Double,
    val expectedAway: Double,
    val injuryNotes: List<String>,
    val priceSource: String?,
)

/**
 * Comprehensive market analysis. Every probability comes from:
 *  - the goals model (Poisson with Dixon-Coles, using the prediction's blended expected goals), and
 *  - for goal markets, the teams' real records (home side's home games, away side's away games, last 10),
 *    blended in by sample size (max 30%).
 * Match-result markets use the prediction's final probabilities. First-half figures assume the
 * usual ~44% of goals before half-time (stated in the UI). Prices are only real bookmaker prices.
 * Ranking is by probability of success; near-certainties (95%+) are skipped as unbettable.
 */
object MarketEngine {
    const val FIRST_HALF_SHARE = 0.44
    const val MIN_TOP_PROB = 0.60
    const val MAX_PROB = 0.95
    private const val EMP_WEIGHT = 0.30

    private fun pct(x: Double) = "${Math.round(x * 100)}%"
    private fun f1(x: Double) = String.format("%.1f", x)

    private class Grid(lh: Double, la: Double) {
        val g = PredictionEngine.grid(lh, la)
        fun p(pred: (Int, Int) -> Boolean): Double {
            var s = 0.0
            for (x in g.indices) for (y in g[x].indices) if (pred(x, y)) s += g[x][y]
            return s
        }
    }

    /** Probabilities from the goals model only (used for the daily ranking and as the base here). */
    private fun modelProbs(lh: Double, la: Double, home: String, away: String): List<Triple<Triple<MarketFamily, String, String>, Pair<Double?, String>, Double>> {
        val out = mutableListOf<Triple<Triple<MarketFamily, String, String>, Pair<Double?, String>, Double>>()
        fun add(f: MarketFamily, m: String, sel: String, point: Double?, label: String, p: Double) {
            out += Triple(Triple(f, m, sel), point to label, p.coerceIn(0.0, 1.0))
        }
        val g = Grid(lh, la)
        // Totals
        for (line in listOf(0.5, 1.5, 2.5, 3.5, 4.5)) {
            val o = g.p { x, y -> x + y > line }
            add(MarketFamily.TOTALS, "OU", "OVER", line, "Over ${fmt(line)} goals", o)
            add(MarketFamily.TOTALS, "OU", "UNDER", line, "Under ${fmt(line)} goals", 1 - o)
        }
        // BTTS
        val b = g.p { x, y -> x > 0 && y > 0 }
        add(MarketFamily.BTTS, "BTTS", "YES", null, "BTTS Yes", b)
        add(MarketFamily.BTTS, "BTTS", "NO", null, "BTTS No", 1 - b)
        // Team totals
        for (line in listOf(0.5, 1.5, 2.5)) {
            val ho = g.p { x, _ -> x > line }
            val ao = g.p { _, y -> y > line }
            add(MarketFamily.TEAM_GOALS, "TT", "HOME_OVER", line, "$home Over ${fmt(line)}", ho)
            add(MarketFamily.TEAM_GOALS, "TT", "HOME_UNDER", line, "$home Under ${fmt(line)}", 1 - ho)
            add(MarketFamily.TEAM_GOALS, "TT", "AWAY_OVER", line, "$away Over ${fmt(line)}", ao)
            add(MarketFamily.TEAM_GOALS, "TT", "AWAY_UNDER", line, "$away Under ${fmt(line)}", 1 - ao)
        }
        // Clean sheets, win to nil
        add(MarketFamily.OTHER, "CS", "HOME", null, "$home clean sheet", g.p { _, y -> y == 0 })
        add(MarketFamily.OTHER, "CS", "AWAY", null, "$away clean sheet", g.p { x, _ -> x == 0 })
        add(MarketFamily.OTHER, "WTN", "HOME", null, "$home to win to nil", g.p { x, y -> x > y && y == 0 })
        add(MarketFamily.OTHER, "WTN", "AWAY", null, "$away to win to nil", g.p { x, y -> y > x && x == 0 })
        // First / last goal (Poisson process): P(team first) = share x P(any goal)
        val anyGoal = 1 - exp(-(lh + la))
        add(MarketFamily.OTHER, "FG", "HOME", null, "$home to score first", lh / (lh + la) * anyGoal)
        add(MarketFamily.OTHER, "FG", "AWAY", null, "$away to score first", la / (lh + la) * anyGoal)
        add(MarketFamily.OTHER, "LG", "HOME", null, "$home to score last", lh / (lh + la) * anyGoal)
        add(MarketFamily.OTHER, "LG", "AWAY", null, "$away to score last", la / (lh + la) * anyGoal)
        // Halves
        val h1 = Grid(lh * FIRST_HALF_SHARE, la * FIRST_HALF_SHARE)
        val h2 = Grid(lh * (1 - FIRST_HALF_SHARE), la * (1 - FIRST_HALF_SHARE))
        val h1Home = h1.p { x, y -> x > y }; val h1Away = h1.p { x, y -> y > x }
        add(MarketFamily.FIRST_HALF, "1H", "H1_HOME", null, "1st half: $home", h1Home)
        add(MarketFamily.FIRST_HALF, "1H", "H1_DRAW", null, "1st half: Draw", 1 - h1Home - h1Away)
        add(MarketFamily.FIRST_HALF, "1H", "H1_AWAY", null, "1st half: $away", h1Away)
        for (line in listOf(0.5, 1.5)) {
            val o = h1.p { x, y -> x + y > line }
            add(MarketFamily.FIRST_HALF, "1HOU", "OVER", line, "1st half Over ${fmt(line)}", o)
            add(MarketFamily.FIRST_HALF, "1HOU", "UNDER", line, "1st half Under ${fmt(line)}", 1 - o)
        }
        val h1b = h1.p { x, y -> x > 0 && y > 0 }
        add(MarketFamily.FIRST_HALF, "1HBTTS", "YES", null, "1st half BTTS Yes", h1b)
        add(MarketFamily.FIRST_HALF, "1HBTTS", "NO", null, "1st half BTTS No", 1 - h1b)
        val h2Home = h2.p { x, y -> x > y }; val h2Away = h2.p { x, y -> y > x }
        add(MarketFamily.OTHER, "WEH", "HOME", null, "$home to win either half", 1 - (1 - h1Home) * (1 - h2Home))
        add(MarketFamily.OTHER, "WEH", "AWAY", null, "$away to win either half", 1 - (1 - h1Away) * (1 - h2Away))
        return out
    }

    /** Asian handicap on [side] with handicap [h] (whole or half lines only): win and push probabilities. */
    private fun asian(lh: Double, la: Double, home: Boolean, h: Double): Pair<Double, Double>? {
        if ((h * 2) % 1.0 != 0.0) return null // quarter lines not modelled
        val g = Grid(lh, la)
        val win = g.p { x, y -> val d = (if (home) x - y else y - x) + h; d > 0 }
        val push = g.p { x, y -> val d = (if (home) x - y else y - x) + h; d == 0.0 }
        return win to push
    }

    private fun fmt(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()

    /** Real-game frequency for a goal market: home side's home games and away side's away games. */
    private fun empirical(m: String, sel: String, point: Double?, hh: List<TeamGame>, aa: List<TeamGame>): Pair<Double, Int>? {
        if (hh.size < 4 || aa.size < 4) return null
        // each test is written from the perspective of the match (home goals x, away goals y)
        val test: ((Int, Int) -> Boolean) = when (m) {
            "OU" -> { x, y -> if (sel == "OVER") x + y > point!! else x + y < point!! }
            "BTTS" -> { x, y -> if (sel == "YES") x > 0 && y > 0 else !(x > 0 && y > 0) }
            "TT" -> when (sel) {
                "HOME_OVER" -> { x, _ -> x > point!! }
                "HOME_UNDER" -> { x, _ -> x < point!! }
                "AWAY_OVER" -> { _, y -> y > point!! }
                "AWAY_UNDER" -> { _, y -> y < point!! }
                else -> return null
            }
            "CS" -> if (sel == "HOME") { _, y -> y == 0 } else { x, _ -> x == 0 }
            else -> return null
        }
        // Home team's home games: x = goals for, y = against. Away team's away games: x = against, y = for.
        val a = hh.count { test(it.gf, it.ga) }.toDouble() / hh.size
        val b = aa.count { test(it.ga, it.gf) }.toDouble() / aa.size
        return (a + b) / 2 to (hh.size + aa.size)
    }

    private fun priceFor(prices: List<BookPrice>?, m: String, sel: String, point: Double?): Double? =
        prices?.firstOrNull { it.market == m && it.sel == sel && (point == null || it.point == point) }?.price

    /** Quick, model-only market list (daily ranking). */
    fun quick(home: String, away: String, lh: Double, la: Double, p: Prediction?, prices: List<BookPrice>?, source: String?): List<MarketPick> =
        analyze(home, away, lh, la, p, null, emptyList(), emptyList(), emptyList(), emptyList(), null, prices, source,
            emptyList(), null, false).markets

    fun analyze(
        home: String,
        away: String,
        lh: Double,
        la: Double,
        p: Prediction?,
        btts: BttsAnalysis?,
        homeGames: List<TeamGame>,
        awayGames: List<TeamGame>,
        h2h: List<RecentResult>,
        insights: List<Insight>,
        injuries: InjuryInfo?,
        prices: List<BookPrice>?,
        priceSource: String?,
        feedPrices: List<BookPrice>,
        feedSource: String?,
        lineupsConfirmed: Boolean,
    ): MarketAnalysis {
        val hh = homeGames.filter { it.home }.take(10)
        val aa = awayGames.filter { !it.home }.take(10)
        // Expected goals before reported absences (to show their effect market by market).
        val im = p?.injuryImpact
        val before = im?.let {
            val w = it.modelWeight
            Pair(lh - w * (it.xgAfter.first - it.xgBefore.first), la - w * (it.xgAfter.second - it.xgBefore.second))
        }
        val beforeProbs = before?.let { b -> modelProbs(b.first, b.second, home, away).associate { "${it.first.second}|${it.first.third}|${it.second.first ?: ""}" to it.third } }

        val list = mutableListOf<MarketPick>()
        fun price(m: String, sel: String, point: Double?): Pair<Double?, String?> {
            priceFor(prices, m, sel, point)?.let { return it to priceSource }
            priceFor(feedPrices, m, sel, point)?.let { return it to feedSource }
            return null to null
        }

        // Result markets from the prediction's blended probabilities.
        if (p != null) {
            val f = p.final
            fun res(fam: MarketFamily, m: String, sel: String, label: String, prob: Double?, shift: Double?) {
                prob ?: return
                val (o, src) = price(m, sel, null)
                list += MarketPick(fam, m, sel, null, label, prob, prob, null, 0, o, src, shift, emptyList(), emptyList())
            }
            val ib = im?.before
            res(MarketFamily.RESULT, "1X2", "HOME", "$home to win", f.home, ib?.let { f.home - it.home })
            f.draw?.let { d -> res(MarketFamily.RESULT, "1X2", "DRAW", "Draw", d, ib?.draw?.let { d - it }) }
            res(MarketFamily.RESULT, "1X2", "AWAY", "$away to win", f.away, ib?.let { f.away - it.away })
            if (f.draw != null) {
                res(MarketFamily.DOUBLE_CHANCE, "DC", "1X", "Double chance 1X", p.doubleChance1X, ib?.draw?.let { (f.home + f.draw) - (ib.home + it) })
                res(MarketFamily.DOUBLE_CHANCE, "DC", "X2", "Double chance X2", p.doubleChanceX2, ib?.draw?.let { (f.away + f.draw) - (ib.away + it) })
                res(MarketFamily.DOUBLE_CHANCE, "DC", "12", "Double chance 12", p.doubleChance12, null)
                val s = f.home + f.away
                if (s > 0) {
                    res(MarketFamily.DOUBLE_CHANCE, "DNB", "DNB_HOME", "$home draw no bet", f.home / s, null)
                    res(MarketFamily.DOUBLE_CHANCE, "DNB", "DNB_AWAY", "$away draw no bet", f.away / s, null)
                }
            }
        }

        for ((k, pl, model) in modelProbs(lh, la, home, away)) {
            val (fam, m, sel) = k
            val (point, label) = pl
            val emp = empirical(m, sel, point, hh, aa)
            val w = if (emp == null) 0.0 else EMP_WEIGHT * min(1.0, emp.second / 20.0)
            var prob = (1 - w) * model + w * (emp?.first ?: 0.0)
            if (m == "BTTS" && btts != null) prob = if (sel == "YES") btts.yes else btts.no // keep in line with the BTTS tab
            val shift = beforeProbs?.get("$m|$sel|${point ?: ""}")?.let { model - it }
            val (o, src) = price(m, sel, point)
            list += MarketPick(fam, m, sel, point, label, prob, model, emp?.first, emp?.second ?: 0, o, src, shift, emptyList(), emptyList())
        }

        // Asian handicap: only for lines the bookmaker actually offers.
        (prices.orEmpty()).filter { it.market == "AH" }.forEach { b ->
            val pt = b.point ?: return@forEach
            val isHome = b.sel == "HOME"
            val (win, push) = asian(lh, la, isHome, pt) ?: return@forEach
            val prob = if (push > 0) win / (1 - push) else win // probability of winning when not refunded
            list += MarketPick(MarketFamily.OTHER, "AH", b.sel, pt, "${if (isHome) home else away} ${if (pt > 0) "+" else ""}${fmt(pt)} (Asian handicap)",
                prob, prob, null, 0, b.price, priceSource, null,
                if (push > 0) listOf("Stake refunded on a ${pct(push)} chance of a push; probability shown excludes the refund.") else emptyList(), emptyList())
        }

        // Evidence and risks for each selection.
        val hp = GoalProfile.of("$home at home", hh)
        val ap = GoalProfile.of("$away away", aa)
        val enriched = list.map { it.copy(evidence = it.evidence + evidence(it, home, away, lh, la, hp, ap, h2h, p, btts), risks = risks(it, insights, injuries, lineupsConfirmed, hp, ap)) }

        // Ranking: probability of success; skip near-certainties and prices too short to bet.
        val rankable = enriched.filter { it.probability < MAX_PROB && (it.odds == null || it.odds >= 1.10) && it.market != "AH" }
            .sortedByDescending { it.probability }
        val reliable = p != null && p.completeness >= 0.5
        val top = mutableListOf<MarketPick>()
        if (reliable) {
            for (c in rankable) {
                if (c.probability < MIN_TOP_PROB || top.size == 3) break
                if (top.any { it.family == c.family }) continue          // three different kinds of bet
                if ((c.edge ?: 0.0) < -0.08) continue            // price clearly worse than our estimate
                top += c
            }
        }
        val noPick = when {
            p == null -> "No prediction for this match (no table or odds in the free sources)."
            !reliable -> "Too little team data (${pct(p.completeness)} of model inputs) to recommend a market."
            top.isEmpty() -> "No market reaches ${pct(MIN_TOP_PROB)} at a fair price."
            else -> null
        }

        val unavailable = listOf(
            "Corners, cards, shots, goalscorers" to "No free data source for these markets.",
            "Second-half goal patterns" to "Half-time scores of past games aren't in the free feed, so half-by-half records can't be measured; first-half markets use the model.",
            "Asian handicap" to if (prices.orEmpty().any { it.market == "AH" }) "Shown for the lines 1xBet offers (whole and half lines)." else "Needs 1xBet handicap lines (loaded via The Odds API).",
        )
        val injuryNotes = injuryNotes(home, away, injuries, im, enriched)

        return MarketAnalysis(
            markets = enriched.sortedByDescending { it.probability }, top = top, noPickReason = noPick, unavailable = unavailable,
            homeAtHome = hp, awayAway = ap,
            homeAll = GoalProfile.of("$home, all games", homeGames.take(10)), awayAll = GoalProfile.of("$away, all games", awayGames.take(10)),
            expectedHome = lh, expectedAway = la, injuryNotes = injuryNotes, priceSource = priceSource ?: feedSource,
        )
    }

    private fun evidence(
        m: MarketPick, home: String, away: String, lh: Double, la: Double,
        hp: GoalProfile?, ap: GoalProfile?, h2h: List<RecentResult>, p: Prediction?, btts: BttsAnalysis?,
    ): List<String> {
        val e = mutableListOf<String>()
        e += "Goals model: $home ${f1(lh)} – ${f1(la)} $away expected goals."
        if (m.empirical != null) e += "Happened in ${pct(m.empirical)} of the last ${m.empiricalGames} relevant games ($home at home, $away away)."
        when (m.market) {
            "OU", "1HOU" -> if (hp != null && ap != null) {
                e += "$home home games: ${f1(hp.avgFor + hp.avgAgainst)} goals per game, ${pct(hp.over25)} over 2.5. $away away: ${f1(ap.avgFor + ap.avgAgainst)} per game, ${pct(ap.over25)} over 2.5."
            }
            "TT", "CS", "WTN", "FG", "LG" -> if (hp != null && ap != null) {
                e += if (m.sel.startsWith("HOME") || m.sel == "HOME")
                    "$home score ${f1(hp.avgFor)} per home game (blank in ${pct(hp.failedToScore)}); $away concede ${f1(ap.avgAgainst)} per away game (clean sheet in ${pct(ap.cleanSheets)})."
                else "$away score ${f1(ap.avgFor)} per away game (blank in ${pct(ap.failedToScore)}); $home concede ${f1(hp.avgAgainst)} per home game (clean sheet in ${pct(hp.cleanSheets)})."
            }
            "BTTS", "1HBTTS" -> {
                if (hp != null && ap != null) e += "Both scored in ${pct(hp.btts)} of $home's home games and ${pct(ap.btts)} of $away's away games."
                btts?.reasons?.take(1)?.let { e += it }
            }
            "1X2", "DC", "DNB", "1H", "WEH" -> p?.reasons?.take(2)?.let { e += it }
        }
        if (h2h.size >= 3 && m.market in setOf("OU", "BTTS", "1X2", "DC")) {
            val n = h2h.size
            e += "Head-to-head (last $n): ${h2h.count { it.goalsFor + it.goalsAgainst >= 3 }} with 3+ goals, both scored in ${h2h.count { it.goalsFor > 0 && it.goalsAgainst > 0 }}."
        }
        return e
    }

    private fun risks(m: MarketPick, insights: List<Insight>, inj: InjuryInfo?, lineups: Boolean, hp: GoalProfile?, ap: GoalProfile?): List<String> {
        val r = mutableListOf<String>()
        if (m.odds == null) r += "No 1xBet price loaded: value can't be checked."
        else if ((m.edge ?: 0.0) < 0) r += "Price is shorter than our estimate (implied ${pct(m.implied!!)} vs ${pct(m.probability)}): no value."
        if (m.empirical != null && m.empiricalGames < 12) r += "Only ${m.empiricalGames} recent games behind the team records."
        if (m.empirical != null && kotlin.math.abs(m.empirical - m.modelProbability) >= 0.15) r += "Team record (${pct(m.empirical)}) and goals model (${pct(m.modelProbability)}) disagree."
        if (inj == null || !inj.known) r += "Injuries/suspensions unknown (add an API-Football key)."
        if (!lineups) r += "Line-ups not confirmed yet."
        if (m.market.startsWith("1H") || m.market == "WEH") r += "First-half figures are model estimates (about 44% of goals come before half-time)."
        val contra = insights.filter { i -> i.leans.any { opposes(m, it.lean) } }
        if (contra.isNotEmpty()) r += "Contradicted by history: " + contra.take(2).joinToString("; ") { it.title } + "."
        if (hp == null || ap == null) r += "Home/away records unavailable: model only."
        return r
    }

    private fun opposes(m: MarketPick, lean: Lean): Boolean = when (lean) {
        Lean.OVER -> (m.market == "OU" && m.sel == "UNDER") || (m.market == "BTTS" && m.sel == "NO")
        Lean.UNDER -> (m.market == "OU" && m.sel == "OVER" && (m.point ?: 0.0) >= 2.5)
        Lean.BTTS_YES -> m.market == "BTTS" && m.sel == "NO" || m.market == "CS"
        Lean.BTTS_NO -> m.market == "BTTS" && m.sel == "YES"
        Lean.HOME -> m.sel in setOf("AWAY", "X2", "DNB_AWAY")
        Lean.AWAY -> m.sel in setOf("HOME", "1X", "DNB_HOME")
        Lean.DRAW -> m.sel == "12"
    }

    private fun injuryNotes(home: String, away: String, inj: InjuryInfo?, im: InjuryImpact?, ms: List<MarketPick>): List<String> {
        if (inj == null || !inj.known) return listOf("Injury and suspension lists aren't available for this match, so no market is adjusted for absences.")
        val n = mutableListOf<String>()
        n += "$home: ${inj.homeOut.size} out, ${inj.homeDoubtful.size} doubtful. $away: ${inj.awayOut.size} out, ${inj.awayDoubtful.size} doubtful."
        if (im == null) { n += "No absences that change the model."; return n }
        val moved = ms.filter { (it.injuryShift ?: 0.0).let { s -> kotlin.math.abs(s) >= 0.015 } }
            .sortedByDescending { kotlin.math.abs(it.injuryShift!!) }.take(5)
        moved.forEach { n += "${it.label}: ${if (it.injuryShift!! > 0) "+" else ""}${Math.round(it.injuryShift * 1000) / 10.0} pts because of absences." }
        n += "Each absence lowers that team's scoring and raises the opponent's. The sources don't say which positions are missing, so check whether strikers, defenders or the goalkeeper are out."
        return n
    }
}
