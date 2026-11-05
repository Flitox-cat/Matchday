package com.matchday.app.domain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Transparent prediction engine. No random numbers, no hidden "AI score".
 *
 * 1. Market: every bookmaker's 1X2 prices are de-vigged (margin removed proportionally)
 *    and averaged -> consensus probabilities.
 * 2. Model (football, when football-data.org stats exist): Poisson goals model with
 *    Dixon-Coles low-score correction. Team attack/defence strengths come from league
 *    goals for/against (home/away splits when there are enough games), shrunk towards
 *    league average for small samples, adjusted for recent form and reported absences.
 * 3. Final = blend. The model's weight (15%-45%) grows with how much data it had.
 *    Markets are hard to beat, so they always keep the majority weight.
 * 4. Value = final probability x best available real price - 1. Only real bookmaker
 *    prices are used; markets without a price are shown as N/A and never "picked".
 */
object PredictionEngine {

    private const val MAX_GOALS = 10
    private const val RHO = -0.08          // Dixon-Coles low-score correlation
    private const val SHRINK_GAMES = 6.0   // prior strength in games
    private const val MIN_EDGE = 0.03
    private const val MIN_PICK_PROB = 0.20

    fun predict(
        competition: Competition,
        homeName: String,
        awayName: String,
        odds: OddsSummary?,
        inputs: ModelInputs?,
    ): Prediction? {
        val market = marketProbs(odds, competition.hasDraw)
        val isFootball = competition.sport == SportType.FOOTBALL

        // ---- independent model (football only) ----
        val modelGoals: Pair<Double, Double>? =
            if (isFootball && inputs?.home != null && inputs.away != null) modelLambdas(inputs) else null
        val modelGrid = modelGoals?.let { grid(it.first, it.second) }
        val model = modelGrid?.let { outcomeProbs(it) }

        if (market == null && model == null) return null

        // ---- weights ----
        val quality = if (inputs != null) modelQuality(inputs) else 0.0
        val wModel = when {
            model == null -> 0.0
            market == null -> 1.0
            else -> 0.15 + 0.30 * quality
        }
        val final = blend(model, market, wModel)

        // ---- goals (football): blend model lambdas with market-implied lambdas ----
        var lambdas: Pair<Double, Double>? = null
        if (isFootball) {
            val marketGoals = market?.let { marketLambdas(it, odds) }
            lambdas = when {
                modelGoals != null && marketGoals != null -> Pair(
                    wModel * modelGoals.first + (1 - wModel) * marketGoals.first,
                    wModel * modelGoals.second + (1 - wModel) * marketGoals.second,
                )
                modelGoals != null -> modelGoals
                else -> marketGoals
            }
        }
        val g = lambdas?.let { grid(it.first, it.second) }

        // ---- injury impact: same weights, model goals without the absences ----
        val injuryImpact: InjuryImpact? = run {
            if (inputs == null || modelGoals == null || inputs.home == null || inputs.away == null) return@run null
            val inj = inputs.injuries
            if (!inj.known) return@run null
            if (inj.homeOut.size + inj.homeDoubtful.size + inj.awayOut.size + inj.awayDoubtful.size == 0) return@run null
            val base = modelLambdas(inputs, applyInjuries = false)
            val baseModel = outcomeProbs(grid(base.first, base.second))
            val baseFinal = blend(baseModel, market, wModel)
            val marketGoals = market?.let { marketLambdas(it, odds) }
            val baseLambdas = if (marketGoals != null) Pair(
                wModel * base.first + (1 - wModel) * marketGoals.first,
                wModel * base.second + (1 - wModel) * marketGoals.second,
            ) else base
            val bg = grid(baseLambdas.first, baseLambdas.second)
            InjuryImpact(
                homeOut = inj.homeOut.size, homeDoubtful = inj.homeDoubtful.size,
                awayOut = inj.awayOut.size, awayDoubtful = inj.awayDoubtful.size,
                before = baseFinal, after = final,
                xgBefore = base, xgAfter = modelGoals,
                over25Before = overProb(bg, 2.5), over25After = g?.let { overProb(it, 2.5) },
                bttsBefore = bttsProb(bg), bttsAfter = g?.let { bttsProb(it) },
                modelWeight = wModel,
            )
        }

        val over15 = g?.let { overProb(it, 1.5) }
        val over25 = g?.let { overProb(it, 2.5) }
        val over35 = g?.let { overProb(it, 3.5) }
        val btts = g?.let { bttsProb(it) }

        // ---- most likely result + selections ----
        val outcomes = buildList {
            add(Triple("home", homeName, final.home))
            final.draw?.let { add(Triple("draw", "Draw", it)) }
            add(Triple("away", awayName, final.away))
        }
        val top = outcomes.maxBy { it.third }
        val predictedWinner = if (top.first == "draw") null else top.second
        val topOdds = when (top.first) {
            "home" -> odds?.home
            "draw" -> odds?.draw
            else -> odds?.away
        }
        val mostLikely = selection("1X2", top.second, top.third, topOdds)

        val predictedScore = g?.let { scoreForOutcome(it, top.first) }

        // Candidate value selections: only those with a real price.
        val candidates = mutableListOf<Selection>()
        candidates += selection("1X2", homeName, final.home, odds?.home)
        if (final.draw != null) candidates += selection("1X2", "Draw", final.draw, odds?.draw)
        candidates += selection("1X2", awayName, final.away, odds?.away)
        if (odds != null && odds.totalsLine != null) {
            val line: Double = odds.totalsLine
            val pOver = if (g != null) {
                val modelOver = overProb(g, line)
                val mkt = odds.over?.consensusProb
                if (mkt != null) wModel * modelOver + (1 - wModel) * mkt else modelOver
            } else odds.over?.consensusProb
            if (pOver != null) {
                candidates += selection("Totals", "Over ${fmtLine(line)}", pOver, odds.over)
                candidates += selection("Totals", "Under ${fmtLine(line)}", 1 - pOver, odds.under)
            }
        }
        val bestValue = candidates
            .filter { it.edge != null && it.edge >= MIN_EDGE && it.probability >= MIN_PICK_PROB }
            .maxByOrNull { it.edge ?: 0.0 }

        // ---- confidence ----
        val disagreement = if (model != null && market != null) {
            maxOf(
                abs(model.home - market.home),
                abs(model.away - market.away),
                abs((model.draw ?: 0.0) - (market.draw ?: 0.0)),
            )
        } else null
        val books = odds?.bookmakerCount ?: 0
        val completeness = completeness(isFootball, market != null, quality, inputs?.injuries?.known == true, books)
        val (confidence, notes) = confidence(final.max(), completeness, disagreement, books, isFootball, model != null)

        val reasons = reasons(competition, homeName, awayName, inputs, market, model, odds, top, bestValue)

        val method = when {
            model != null && market != null ->
                "Blend: ${pct(wModel)} statistical model + ${pct(1 - wModel)} bookmaker consensus"
            model != null -> "Statistical model only (no bookmaker prices available)"
            isFootball -> "Bookmaker consensus only (no team statistics for this competition)"
            else -> "Bookmaker consensus only (no independent model for ${competition.sport.label.lowercase()})"
        }

        return Prediction(
            model = model, market = market, final = final, modelWeight = wModel,
            expectedHome = lambdas?.first, expectedAway = lambdas?.second,
            predictedScore = predictedScore,
            over15 = over15, over25 = over25, over35 = over35, btts = btts,
            predictedWinner = predictedWinner, mostLikely = mostLikely, bestValue = bestValue,
            confidence = confidence, confidenceNotes = notes, reasons = reasons,
            method = method, completeness = completeness, disagreement = disagreement,
            injuryImpact = injuryImpact,
        )
    }

    // -----------------------------------------------------------------------
    // Market
    // -----------------------------------------------------------------------

    fun marketProbs(odds: OddsSummary?, hasDraw: Boolean): Probs? {
        if (odds == null) return null
        val h = odds.home?.consensusProb ?: return null
        val a = odds.away?.consensusProb ?: return null
        val d = if (hasDraw) odds.draw?.consensusProb else null
        if (hasDraw && d == null) return null
        val sum = h + a + (d ?: 0.0)
        if (sum <= 0) return null
        return Probs(h / sum, d?.let { it / sum }, a / sum)
    }

    /** Finds Poisson lambdas that reproduce the market's home-win probability and goal line. */
    private fun marketLambdas(market: Probs, odds: OddsSummary?): Pair<Double, Double> {
        val line = odds?.totalsLine
        val overP = odds?.over?.consensusProb
        var bestErr = Double.MAX_VALUE
        var best = Pair(1.4, 1.1)
        var total = 1.6
        while (total <= 4.6) {
            var share = 0.15
            while (share <= 0.85) {
                val lh = total * share
                val la = total * (1 - share)
                val gr = grid(lh, la)
                val p = outcomeProbs(gr)
                var err = sq(p.home - market.home) + sq(p.away - market.away)
                if (line != null && overP != null) err += sq(overProb(gr, line) - overP)
                else err += 0.02 * sq(total - 2.6) // weak prior when there is no totals market
                if (err < bestErr) { bestErr = err; best = Pair(lh, la) }
                share += 0.02
            }
            total += 0.1
        }
        return best
    }

    // -----------------------------------------------------------------------
    // Statistical model
    // -----------------------------------------------------------------------

    private fun shrink(rate: Double, n: Int, prior: Double): Double =
        (rate * n + prior * SHRINK_GAMES) / (n + SHRINK_GAMES)

    private fun modelLambdas(inputs: ModelInputs, applyInjuries: Boolean = true): Pair<Double, Double> {
        val h = inputs.home!!
        val a = inputs.away!!
        val avgH = inputs.league?.homeGoals ?: 1.5
        val avgA = inputs.league?.awayGoals ?: 1.2
        val avg = (avgH + avgA) / 2

        val (attH, defH) = if (h.homePlayed >= 4) Pair(
            shrink(h.homeFor.toDouble() / h.homePlayed, h.homePlayed, avgH) / avgH,
            shrink(h.homeAgainst.toDouble() / h.homePlayed, h.homePlayed, avgA) / avgA,
        ) else if (h.played > 0) Pair(
            shrink(h.goalsFor.toDouble() / h.played, h.played, avg) / avg,
            shrink(h.goalsAgainst.toDouble() / h.played, h.played, avg) / avg,
        ) else Pair(1.0, 1.0)

        val (attA, defA) = if (a.awayPlayed >= 4) Pair(
            shrink(a.awayFor.toDouble() / a.awayPlayed, a.awayPlayed, avgA) / avgA,
            shrink(a.awayAgainst.toDouble() / a.awayPlayed, a.awayPlayed, avgH) / avgH,
        ) else if (a.played > 0) Pair(
            shrink(a.goalsFor.toDouble() / a.played, a.played, avg) / avg,
            shrink(a.goalsAgainst.toDouble() / a.played, a.played, avg) / avg,
        ) else Pair(1.0, 1.0)

        var lh = attH * defA * avgH
        var la = attA * defH * avgA

        lh *= formFactor(h.recent)
        la *= formFactor(a.recent)

        val inj = inputs.injuries
        if (applyInjuries && inj.known) {
            // A depleted side scores less AND concedes more.
            lh *= injuryFactor(inj.homeOut.size, inj.homeDoubtful.size) * defenceFactor(inj.awayOut.size, inj.awayDoubtful.size)
            la *= injuryFactor(inj.awayOut.size, inj.awayDoubtful.size) * defenceFactor(inj.homeOut.size, inj.homeDoubtful.size)
        }
        return Pair(lh.coerceIn(0.2, 4.0), la.coerceIn(0.2, 4.0))
    }

    private fun formFactor(recent: List<RecentResult>): Double {
        val last = recent.take(6)
        if (last.size < 3) return 1.0
        val ppg = last.sumOf { it.points }.toDouble() / last.size
        return (1 + 0.05 * (ppg - 1.35)).coerceIn(0.93, 1.07)
    }

    /** Absences lower a side's own expected goals: 2.5% per confirmed absence, 1% per doubt, max 12%. */
    private fun injuryFactor(out: Int, doubtful: Int): Double =
        1 - min(0.12, 0.025 * out + 0.01 * doubtful)

    /** ...and raise the opponent's: 1.5% per confirmed absence, 0.6% per doubt, max 8%. */
    private fun defenceFactor(out: Int, doubtful: Int): Double =
        1 + min(0.08, 0.015 * out + 0.006 * doubtful)

    private fun modelQuality(inputs: ModelInputs): Double {
        val h = inputs.home ?: return 0.0
        val a = inputs.away ?: return 0.0
        val sample = min(1.0, (h.played + a.played) / 24.0)
        val form = if (h.recent.size >= 3 && a.recent.size >= 3) 0.15 else 0.0
        val inj = if (inputs.injuries.known) 0.15 else 0.0
        return (0.7 * sample + form + inj).coerceIn(0.0, 1.0)
    }

    // -----------------------------------------------------------------------
    // Poisson grid helpers
    // -----------------------------------------------------------------------

    /** P(X = k) for k = 0..MAX_GOALS, computed iteratively (fast enough to run per list refresh). */
    private fun pmf(l: Double): DoubleArray {
        val p = DoubleArray(MAX_GOALS + 1)
        p[0] = exp(-l)
        for (k in 1..MAX_GOALS) p[k] = p[k - 1] * l / k
        return p
    }

    private fun tau(x: Int, y: Int, lh: Double, la: Double): Double = when {
        x == 0 && y == 0 -> 1 - lh * la * RHO
        x == 0 && y == 1 -> 1 + lh * RHO
        x == 1 && y == 0 -> 1 + la * RHO
        x == 1 && y == 1 -> 1 - RHO
        else -> 1.0
    }

    fun grid(lh: Double, la: Double): Array<DoubleArray> {
        val g = Array(MAX_GOALS + 1) { DoubleArray(MAX_GOALS + 1) }
        val ph = pmf(lh)
        val pa = pmf(la)
        var sum = 0.0
        for (x in 0..MAX_GOALS) for (y in 0..MAX_GOALS) {
            val p = max(0.0, ph[x] * pa[y] * tau(x, y, lh, la))
            g[x][y] = p
            sum += p
        }
        if (sum > 0) for (x in 0..MAX_GOALS) for (y in 0..MAX_GOALS) g[x][y] /= sum
        return g
    }

    private fun outcomeProbs(g: Array<DoubleArray>): Probs {
        var h = 0.0; var d = 0.0; var a = 0.0
        for (x in g.indices) for (y in g[x].indices) {
            when {
                x > y -> h += g[x][y]
                x == y -> d += g[x][y]
                else -> a += g[x][y]
            }
        }
        return Probs(h, d, a)
    }

    private fun overProb(g: Array<DoubleArray>, line: Double): Double {
        var p = 0.0
        for (x in g.indices) for (y in g[x].indices) if (x + y > line) p += g[x][y]
        return p
    }

    private fun bttsProb(g: Array<DoubleArray>): Double {
        var p = 0.0
        for (x in 1 until g.size) for (y in 1 until g[x].size) p += g[x][y]
        return p
    }

    /** Most likely exact score consistent with the most likely result. */
    private fun scoreForOutcome(g: Array<DoubleArray>, outcome: String): String {
        var bx = 0; var by = 0; var bp = -1.0
        for (x in 0..5) for (y in 0..5) {
            val ok = when (outcome) { "home" -> x > y; "away" -> y > x; else -> x == y }
            if (ok && g[x][y] > bp) { bp = g[x][y]; bx = x; by = y }
        }
        return "$bx-$by"
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private fun blend(model: Probs?, market: Probs?, w: Double): Probs {
        if (model == null) return market!!
        if (market == null) return model
        val d = if (model.draw != null && market.draw != null) w * model.draw + (1 - w) * market.draw else market.draw
        val h = w * model.home + (1 - w) * market.home
        val a = w * model.away + (1 - w) * market.away
        val s = h + a + (d ?: 0.0)
        return Probs(h / s, d?.let { it / s }, a / s)
    }

    private fun selection(market: String, label: String, p: Double, oo: OutcomeOdds?): Selection {
        val best = oo?.best
        return Selection(
            market = market, label = label, probability = p,
            odds = best?.price, bookmaker = best?.bookmaker,
            edge = best?.let { p * it.price - 1 },
        )
    }

    private fun completeness(isFootball: Boolean, hasMarket: Boolean, quality: Double, injuries: Boolean, books: Int): Double {
        val bookScore = if (books > 0) 1.0 else 0.0 // the free feed carries one bookmaker
        return if (isFootball) {
            (if (hasMarket) 0.4 * (0.5 + 0.5 * bookScore) else 0.0) + 0.4 * quality + (if (injuries) 0.2 else 0.0)
        } else {
            if (hasMarket) 0.6 * (0.5 + 0.5 * bookScore) else 0.0
        }
    }

    private fun confidence(
        maxP: Double, completeness: Double, disagreement: Double?, books: Int,
        isFootball: Boolean, hasModel: Boolean,
    ): Pair<Confidence, List<String>> {
        val notes = mutableListOf<String>()
        notes += "Likeliest outcome has a ${pct(maxP)} estimated chance."
        notes += "Data completeness ${pct(completeness)}" + if (books > 0) " (bookmaker prices available)." else " (no bookmaker prices)."
        if (disagreement != null) {
            notes += if (disagreement <= 0.06) "Model and market agree (max gap ${pct(disagreement)})."
            else "Model and market disagree by up to ${pct(disagreement)}."
        }
        if (isFootball && !hasModel) notes += "No team statistics: relying on bookmaker prices."
        val agree = disagreement == null || disagreement <= 0.06
        val roughlyAgree = disagreement == null || disagreement <= 0.12
        val level = when {
            maxP >= 0.60 && completeness >= 0.70 && agree && books >= 1 -> Confidence.HIGH
            maxP >= 0.45 && completeness >= 0.45 && roughlyAgree -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        if (!isFootball && level == Confidence.HIGH) {
            // Without an independent model we cap confidence at Medium.
            return Pair(Confidence.MEDIUM, notes + "Capped at Medium: market-only estimate.")
        }
        return Pair(level, notes)
    }

    private fun reasons(
        c: Competition, home: String, away: String, inputs: ModelInputs?,
        market: Probs?, model: Probs?, odds: OddsSummary?,
        top: Triple<String, String, Double>, value: Selection?,
    ): List<String> {
        val r = mutableListOf<String>()
        if (market != null) {
            val fav = if (market.home >= market.away) home else away
            r += "The bookmaker makes $fav favourite (${pct(max(market.home, market.away))} after removing margin)."
        }
        val h = inputs?.home
        val a = inputs?.away
        if (h != null && a != null) {
            if (h.homePlayed >= 3) r += "$home at home: ${h.homeFor} scored, ${h.homeAgainst} conceded in ${h.homePlayed}."
            if (a.awayPlayed >= 3) r += "$away away: ${a.awayFor} scored, ${a.awayAgainst} conceded in ${a.awayPlayed}."
            val fh = h.recent.take(6); val fa = a.recent.take(6)
            if (fh.size >= 3 && fa.size >= 3) {
                r += "Recent form: $home ${fh.joinToString("") { it.letter.toString() }} vs $away ${fa.joinToString("") { it.letter.toString() }}."
            }
            if (h.position != null && a.position != null) r += "Table: $home ${ordinal(h.position)}, $away ${ordinal(a.position)} in ${c.title}."
        }
        inputs?.injuries?.let { inj ->
            if (inj.known) {
                r += "Reported absences: $home ${inj.homeOut.size} out/${inj.homeDoubtful.size} doubtful, " +
                    "$away ${inj.awayOut.size} out/${inj.awayDoubtful.size} doubtful."
            }
        }
        if (model != null && market != null) {
            val gap = when (top.first) {
                "home" -> model.home - market.home
                "away" -> model.away - market.away
                else -> (model.draw ?: 0.0) - (market.draw ?: 0.0)
            }
            if (abs(gap) >= 0.05) {
                r += "Our model rates ${top.second} ${if (gap > 0) "higher" else "lower"} than the market (${signedPct(gap)})."
            }
        }
        if (value != null) {
            r += "Best value: ${value.label} at ${fmtOdds(value.odds)} (${value.bookmaker}) vs our ${pct(value.probability)} " +
                "-> expected return ${signedPct(value.edge ?: 0.0)} per unit staked."
        } else {
            r += "No selection beats the best available price by 3% or more - no value pick."
        }
        if (odds == null) r += "No bookmaker prices available yet (N/A)."
        return r
    }

    private fun sq(x: Double) = x * x
    fun pct(p: Double) = "${(p * 100).toInt().coerceIn(0, 100)}%"
    fun signedPct(p: Double): String = (if (p >= 0) "+" else "") + String.format("%.1f%%", p * 100)
    fun fmtOdds(o: Double?): String = if (o == null) "N/A" else String.format("%.2f", o)
    fun fmtLine(l: Double): String = if (l % 1.0 == 0.0) l.toInt().toString() else l.toString()
    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    @Suppress("unused")
    private fun stdErr(p: Double, n: Int) = sqrt(p * (1 - p) / max(1, n))
}
