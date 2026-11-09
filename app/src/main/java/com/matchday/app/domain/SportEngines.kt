package com.matchday.app.domain

import com.matchday.app.data.repo.BookPrice
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Everything the basketball / hockey / tennis engines can use. Nothing here is invented. */
data class SportInputs(
    val sport: SportType,
    val leagueKey: String,
    val home: String,
    val away: String,
    val kickoff: Long,
    val homeGames: List<TeamGame>,
    val awayGames: List<TeamGame>,
    val h2h: List<RecentResult>,
    val prices: List<BookPrice>?,     // 1xBet (via The Odds API)
    val priceSource: String?,
    val feed: OddsSummary?,           // free feed's bookmaker (pre-match)
    val injuries: InjuryInfo?,
    val lineupsConfirmed: Boolean,
    val homeRank: Int? = null,        // tennis
    val awayRank: Int? = null,
    val live: Boolean = false,
    val tennis: TennisContext? = null, // tennis: player profiles, head-to-head, surface (free sources)
)

/**
 * Dedicated analysis engines for basketball (normal model of margin and total points), ice hockey
 * (Poisson goals, regulation vs overtime separated) and tennis (game-level hold model per set).
 * Inputs: real results, the bookmaker's own lines when available, injuries. The output is the same
 * ranked market list as football. Statistics the free sources don't publish are listed as unavailable.
 */
object SportEngines {
    private fun pct(x: Double) = "${Math.round(x * 100)}%"
    private fun f1(x: Double) = String.format("%.1f", x)
    private fun fmt(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
    private fun sgn(p: Double) = (if (p > 0) "+" else "") + fmt(p)
    private fun half(x: Double) = Math.floor(x) + 0.5

    /** Standard normal CDF (Abramowitz-Stegun). */
    private fun phi(z: Double): Double {
        val t = 1 / (1 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z > 0) 1 - p else p
    }

    private fun price(prices: List<BookPrice>?, m: String, sel: String, point: Double?): Double? =
        prices?.firstOrNull { it.market == m && it.sel == sel && (point == null || it.point == point) }?.price

    /** Two-way probability without the bookmaker margin. */
    private fun fair(a: Double?, b: Double?): Double? = if (a == null || b == null) null else (1 / a) / (1 / a + 1 / b)

    fun analyze(i: SportInputs): MarketAnalysis? = when (i.sport) {
        SportType.BASKETBALL -> basketball(i)
        SportType.HOCKEY -> hockey(i)
        SportType.TENNIS -> tennis(i)
        else -> null
    }

    // =====================================================================
    // Shared output helpers
    // =====================================================================

    private class Builder(val i: SportInputs) {
        val list = mutableListOf<MarketPick>()
        fun add(fam: MarketFamily, m: String, sel: String, point: Double?, label: String, p: Double, evidence: List<String>, empirical: Pair<Double, Int>? = null) {
            if (p.isNaN()) return
            val prob = p.coerceIn(0.001, 0.999)
            val o = price(i.prices, m, sel, point)
            list += MarketPick(fam, m, sel, point, label, prob, prob, empirical?.first, empirical?.second ?: 0, o, if (o != null) i.priceSource else null, null, evidence, emptyList())
        }

        fun finish(reliable: Boolean, why: String?, risks: List<String>, unavailable: List<Pair<String, String>>, notes: List<String>, eh: Double, ea: Double): MarketAnalysis {
            val withRisks = list.map { m ->
                val r = mutableListOf<String>()
                if (m.odds == null) r += "1xBet odds unavailable for this market: value can't be checked."
                else if ((m.edge ?: 0.0) < 0) r += "1xBet price is shorter than our estimate (implied ${pct(m.implied!!)} vs ${pct(m.probability)})."
                m.copy(risks = r + risks)
            }
            val ranked = withRisks.filter { it.probability < MarketEngine.MAX_PROB && (it.odds == null || it.odds >= 1.10) }.sortedByDescending { it.probability }
            val top = mutableListOf<MarketPick>()
            if (reliable) for (c in ranked) {
                if (c.probability < MarketEngine.MIN_TOP_PROB || top.size == 3) break
                if (top.any { it.family == c.family }) continue
                if ((c.edge ?: 0.0) < -0.08) continue
                top += c
            }
            return MarketAnalysis(
                markets = withRisks.sortedByDescending { it.probability }, top = top,
                noPickReason = when { !reliable -> why; top.isEmpty() -> "No market reaches ${pct(MarketEngine.MIN_TOP_PROB)} at a fair price."; else -> null },
                unavailable = unavailable, homeAtHome = null, awayAway = null, homeAll = null, awayAll = null,
                expectedHome = eh, expectedAway = ea, injuryNotes = notes, priceSource = i.priceSource ?: i.feed?.home?.best?.bookmaker,
            )
        }
    }

    private fun restNote(name: String, games: List<TeamGame>, kickoff: Long): Pair<String?, Int?> {
        val last = games.firstOrNull { it.m.date < kickoff } ?: return null to null
        val days = ((kickoff - last.m.date) / 86_400_000L).toInt()
        return (if (days <= 1) "$name played yesterday (back-to-back)." else "$name rested $days days since their last game.") to days
    }

    private fun injuryCount(inj: InjuryInfo?, home: Boolean): Pair<Int, Int> =
        if (inj == null || !inj.known) 0 to 0 else if (home) inj.homeOut.size to inj.homeDoubtful.size else inj.awayOut.size to inj.awayDoubtful.size

    private fun injuryNotes(i: SportInputs, perOut: String): List<String> {
        val inj = i.injuries
        if (inj == null || !inj.known) return listOf("Injury reports aren't available for this game, so no adjustment is made.")
        return listOf(
            "${i.home}: ${inj.homeOut.size} out, ${inj.homeDoubtful.size} doubtful. ${i.away}: ${inj.awayOut.size} out, ${inj.awayDoubtful.size} doubtful (ESPN).",
            "Adjustment: $perOut The source doesn't rate player importance, so check whether stars are missing.",
        )
    }

    // =====================================================================
    // Basketball
    // =====================================================================

    private fun basketball(i: SportInputs): MarketAnalysis? {
        val b = Builder(i)
        val hh = i.homeGames.filter { it.home }.take(10)
        val aa = i.awayGames.filter { !it.home }.take(10)
        val hAll = i.homeGames.take(10)
        val aAll = i.awayGames.take(10)
        val wnba = i.leagueKey.endsWith("wnba")

        // Model from real results: home side's home games and away side's away games (fallback: all).
        val hs = hh.takeIf { it.size >= 4 } ?: hAll
        val asg = aa.takeIf { it.size >= 4 } ?: aAll
        val modelHome = if (hs.size >= 3 && asg.size >= 3) (hs.map { it.gf }.average() + asg.map { it.ga }.average()) / 2 else null
        val modelAway = if (hs.size >= 3 && asg.size >= 3) (hs.map { it.ga }.average() + asg.map { it.gf }.average()) / 2 else null

        // Market lines: 1xBet first, otherwise the free feed's bookmaker.
        val sprLine = i.prices?.firstOrNull { it.market == "SPR" && it.sel == "HOME" }?.point
            ?: i.feed?.markets?.firstOrNull { it.key == "spreads" }?.outcomes?.firstOrNull()?.label?.substringAfterLast(' ')?.toDoubleOrNull()
        val totLine = i.prices?.firstOrNull { it.market == "TOT" }?.point ?: i.feed?.totalsLine
        val mlHome = fair(price(i.prices, "ML", "HOME", null) ?: i.feed?.home?.best?.price, price(i.prices, "ML", "AWAY", null) ?: i.feed?.away?.best?.price)

        var margin: Double? = null   // expected home margin
        var total: Double? = null
        if (modelHome != null && modelAway != null) { margin = modelHome - modelAway; total = modelHome + modelAway }
        val sdM = if (wnba) 11.0 else 12.5
        val sdT = if (wnba) 15.0 else 17.5
        val marketMargin = sprLine?.let { -it } ?: mlHome?.let { inv(it) * sdM }
        if (marketMargin != null) margin = if (margin == null) marketMargin else 0.6 * marketMargin + 0.4 * margin
        if (totLine != null) total = if (total == null) totLine else 0.6 * totLine + 0.4 * total
        if (margin == null || total == null) {
            return b.finish(false, "Not enough data: no completed games and no bookmaker lines for this game.", emptyList(), unavailableBasketball(), injuryNotes(i, ""), 0.0, 0.0)
        }

        // Injuries and fatigue.
        val (ho, hd) = injuryCount(i.injuries, true)
        val (ao, ad) = injuryCount(i.injuries, false)
        val injAdj = min(6.0, 1.5 * ho + 0.5 * hd) - min(6.0, 1.5 * ao + 0.5 * ad)
        val (restH, dH) = restNote(i.home, i.homeGames, i.kickoff)
        val (restA, dA) = restNote(i.away, i.awayGames, i.kickoff)
        var fatigue = 0.0
        if (dH != null && dH <= 1) fatigue -= 1.5
        if (dA != null && dA <= 1) fatigue += 1.5
        // The bookmaker line already includes known news; only the model share gets these adjustments.
        val modelShare = if (marketMargin != null) 0.4 else 1.0
        margin -= modelShare * injAdj
        margin += modelShare * fatigue
        val eh = (total + margin) / 2
        val ea = (total - margin) / 2

        val ev = mutableListOf<String>()
        ev += "Expected score: ${i.home} ${f1(eh)} – ${f1(ea)} ${i.away} (margin ${sgn(Math.round(margin * 2) / 2.0)}, total ${f1(total)})."
        if (hs.isNotEmpty() && asg.isNotEmpty()) ev += "${i.home} ${if (hs === hh) "at home" else "overall"}: ${f1(hs.map { it.gf }.average())} scored, ${f1(hs.map { it.ga }.average())} allowed (${hs.size} games). ${i.away} ${if (asg === aa) "away" else "overall"}: ${f1(asg.map { it.gf }.average())} scored, ${f1(asg.map { it.ga }.average())} allowed (${asg.size} games)."
        if (sprLine != null || totLine != null) ev += "Bookmaker line: spread ${sprLine?.let { sgn(it) } ?: "N/A"}, total ${totLine?.let { fmt(it) } ?: "N/A"} (60% weight)."
        listOfNotNull(restH, restA).forEach { ev += it }

        // Match winner (overtime included: no ties).
        val pHome = phi(margin / sdM)
        b.add(MarketFamily.RESULT, "ML", "HOME", null, "${i.home} to win", pHome, ev)
        b.add(MarketFamily.RESULT, "ML", "AWAY", null, "${i.away} to win", 1 - pHome, ev)

        // Spreads: bookmaker lines + alternatives.
        val spreads = (listOfNotNull(sprLine) + i.prices.orEmpty().filter { it.market == "SPR" && it.sel == "HOME" }.mapNotNull { it.point } +
            listOf(-margin - 5, -margin, -margin + 5, -margin + 10).map { half(it) }).distinct()
        spreads.forEach { line ->
            val pCover = phi((margin + line) / sdM)
            b.add(MarketFamily.HANDICAP, "SPR", "HOME", line, "${i.home} ${sgn(line)}", pCover, ev)
            b.add(MarketFamily.HANDICAP, "SPR", "AWAY", -line, "${i.away} ${sgn(-line)}", 1 - pCover, ev)
        }
        // Totals.
        val totals = (listOfNotNull(totLine) + i.prices.orEmpty().filter { it.market == "TOT" }.mapNotNull { it.point } +
            listOf(-15.0, -8.0, 0.0, 8.0, 15.0).map { half(total + it) }).distinct()
        val emp = (hs.map { it.gf + it.ga } + asg.map { it.gf + it.ga })
        totals.forEach { line ->
            val pOver = 1 - phi((line - total) / sdT)
            val e = if (emp.size >= 6) emp.count { it > line }.toDouble() / emp.size to emp.size else null
            b.add(MarketFamily.POINTS, "TOT", "OVER", line, "Over ${fmt(line)} points", pOver, ev, e)
            b.add(MarketFamily.POINTS, "TOT", "UNDER", line, "Under ${fmt(line)} points", 1 - pOver, ev, e?.let { 1 - it.first to it.second })
        }
        // Team totals.
        for ((name, mean, sideKey) in listOf(Triple(i.home, eh, "HOME"), Triple(i.away, ea, "AWAY"))) {
            val lines = (i.prices.orEmpty().filter { it.market == "TT" && it.sel.startsWith(sideKey) }.mapNotNull { it.point } + listOf(-10.0, -5.0, 0.0).map { half(mean + it) }).distinct()
            lines.forEach { line ->
                val pOver = 1 - phi((line - mean) / 11.0)
                b.add(MarketFamily.TEAM_POINTS, "TT", "${sideKey}_OVER", line, "$name Over ${fmt(line)}", pOver, ev)
                b.add(MarketFamily.TEAM_POINTS, "TT", "${sideKey}_UNDER", line, "$name Under ${fmt(line)}", 1 - pOver, ev)
            }
        }
        // First half and first quarter: scoring share from real quarter scores when available.
        val qShare = quarterShare(hs + asg)
        val h1Share = qShare?.let { it[0] + it[1] } ?: 0.5
        val q1Share = qShare?.get(0) ?: 0.25
        val periodEv = ev + (qShare?.let { "Quarter scoring share from ${(hs + asg).size} real games: Q1 ${pct(it[0])}, Q2 ${pct(it[1])}, Q3 ${pct(it[2])}, Q4 ${pct(it[3])}." }
            ?: "No quarter-by-quarter scores available: half = 50%, quarter = 25% of the game (assumption).")
        for ((tag, share, sdShare) in listOf(Triple("H1", h1Share, 0.70), Triple("Q1", q1Share, 0.48))) {
            val m = margin * share; val t = total * share
            val pH = phi(m / (sdM * sdShare))
            val title = if (tag == "H1") "1st half" else "1st quarter"
            b.add(MarketFamily.PERIODS, "${tag}ML", "HOME", null, "$title: ${i.home}", pH, periodEv)
            b.add(MarketFamily.PERIODS, "${tag}ML", "AWAY", null, "$title: ${i.away}", 1 - pH, periodEv)
            val tl = (i.prices.orEmpty().filter { it.market == "${tag}TOT" }.mapNotNull { it.point } + listOf(half(t - 6), half(t))).distinct()
            tl.forEach { line ->
                val po = 1 - phi((line - t) / (sdT * sdShare))
                b.add(MarketFamily.PERIODS, "${tag}TOT", "OVER", line, "$title Over ${fmt(line)}", po, periodEv)
                b.add(MarketFamily.PERIODS, "${tag}TOT", "UNDER", line, "$title Under ${fmt(line)}", 1 - po, periodEv)
            }
        }

        val risks = mutableListOf<String>()
        if (i.injuries == null || i.injuries.known.not()) risks += "Injury report unknown."
        if (!i.lineupsConfirmed) risks += "Starting line-ups not confirmed."
        if (hs.size < 6 || asg.size < 6) risks += "Few completed games this season (${hs.size} and ${asg.size})."
        if (i.live) risks += "Game in play: probabilities are pre-game estimates, not live."
        val notes = injuryNotes(i, "each player out costs about 1.5 points (doubtful 0.5), capped at 6; back-to-back costs 1.5 points.")
        return b.finish(true, null, risks, unavailableBasketball(), notes, eh, ea)
    }

    /** Inverse of [phi] (approximate), used to turn a win probability into an expected margin. */
    private fun inv(p: Double): Double {
        val q = p.coerceIn(0.01, 0.99)
        var lo = -4.0; var hi = 4.0
        repeat(40) { val mid = (lo + hi) / 2; if (phi(mid) < q) lo = mid else hi = mid }
        return (lo + hi) / 2
    }

    /** Average share of points per quarter from real line scores (null when not available). */
    private fun quarterShare(g: List<TeamGame>): DoubleArray? {
        val q = DoubleArray(4); var n = 0
        g.forEach { t ->
            val h = t.m.homeLines; val a = t.m.awayLines
            if (h != null && a != null && h.size >= 4 && a.size >= 4) {
                val tot = (h.take(4).sum() + a.take(4).sum()).toDouble()
                if (tot > 0) { for (k in 0..3) q[k] += (h[k] + a[k]) / tot; n++ }
            }
        }
        if (n < 4) return null
        for (k in 0..3) q[k] /= n
        return q
    }

    private fun unavailableBasketball() = listOf(
        "Offensive/defensive efficiency, pace, rebounds, assists, steals, turnovers, 3-point shooting" to "Not in the free feed's schedule data; points scored/allowed and quarter scores are used instead.",
        "Player points/rebounds/assists markets" to "No free player-props odds source.",
        "Travel distance" to "Not available; rest days and back-to-backs are used.",
    )

    // =====================================================================
    // Ice hockey
    // =====================================================================

    private fun hockey(i: SportInputs): MarketAnalysis? {
        val b = Builder(i)
        val hh = i.homeGames.filter { it.home }.take(10)
        val aa = i.awayGames.filter { !it.home }.take(10)
        val hs = hh.takeIf { it.size >= 4 } ?: i.homeGames.take(10)
        val asg = aa.takeIf { it.size >= 4 } ?: i.awayGames.take(10)
        // Results include OT/SO goals; regulation goals are about 3% lower on average.
        var lh = if (hs.size >= 3 && asg.size >= 3) (hs.map { it.gf }.average() + asg.map { it.ga }.average()) / 2 * 0.97 else null
        var la = if (hs.size >= 3 && asg.size >= 3) (hs.map { it.ga }.average() + asg.map { it.gf }.average()) / 2 * 0.97 else null

        val totLine = i.prices?.firstOrNull { it.market == "TOT" }?.point ?: i.feed?.totalsLine
        val mlHome = fair(price(i.prices, "ML", "HOME", null) ?: i.feed?.home?.best?.price, price(i.prices, "ML", "AWAY", null) ?: i.feed?.away?.best?.price)
        if (totLine != null && mlHome != null) {
            // Solve the bookmaker's implied goal split, then blend 60/40 with the model.
            val t = totLine * 0.97
            var best = 0.5; var err = 9.0
            var r = 0.30
            while (r <= 0.70) {
                val g = PredictionEngine.grid(t * r, t * (1 - r))
                val pw = sum(g) { x, y -> x > y }; val pd = sum(g) { x, y -> x == y }
                val e = abs(pw + pd * r - mlHome)
                if (e < err) { err = e; best = r }
                r += 0.01
            }
            val mh = t * best; val ma = t * (1 - best)
            lh = if (lh == null) mh else 0.6 * mh + 0.4 * lh
            la = if (la == null) ma else 0.6 * ma + 0.4 * la
        }
        if (lh == null || la == null) {
            return b.finish(false, "Not enough data: no completed games and no bookmaker lines for this game.", emptyList(), unavailableHockey(), injuryNotes(i, ""), 0.0, 0.0)
        }
        val (ho, hd) = injuryCount(i.injuries, true)
        val (ao, ad) = injuryCount(i.injuries, false)
        val share = if (totLine != null) 0.4 else 1.0
        lh *= 1 - share * min(0.12, 0.03 * ho + 0.01 * hd)
        la *= 1 - share * min(0.12, 0.03 * ao + 0.01 * ad)
        val (restH, dH) = restNote(i.home, i.homeGames, i.kickoff)
        val (restA, dA) = restNote(i.away, i.awayGames, i.kickoff)
        if (dH != null && dH <= 1) lh *= 1 - 0.04 * share
        if (dA != null && dA <= 1) la *= 1 - 0.04 * share

        val g = PredictionEngine.grid(lh, la)
        val pW = sum(g) { x, y -> x > y }; val pD = sum(g) { x, y -> x == y }; val pL = 1 - pW - pD
        val otHome = lh / (lh + la)   // overtime/shootout split by strength
        val ev = mutableListOf<String>()
        ev += "Expected regulation goals: ${i.home} ${f1(lh)} – ${f1(la)} ${i.away}."
        if (hs.isNotEmpty() && asg.isNotEmpty()) ev += "${i.home} ${if (hs === hh) "at home" else "overall"}: ${f1(hs.map { it.gf }.average())} for, ${f1(hs.map { it.ga }.average())} against (${hs.size} games). ${i.away} ${if (asg === aa) "away" else "overall"}: ${f1(asg.map { it.gf }.average())} for, ${f1(asg.map { it.ga }.average())} against (${asg.size} games)."
        if (totLine != null) ev += "Bookmaker total ${fmt(totLine)} and moneyline blended in (60% weight)."
        ev += "Regulation draw (goes to overtime): ${pct(pD)}."
        listOfNotNull(restH, restA).forEach { ev += it }

        // Full game (incl. OT/SO) vs regulation (60 min) clearly separated.
        b.add(MarketFamily.RESULT, "ML", "HOME", null, "${i.home} to win (incl. OT/SO)", pW + pD * otHome, ev)
        b.add(MarketFamily.RESULT, "ML", "AWAY", null, "${i.away} to win (incl. OT/SO)", pL + pD * (1 - otHome), ev)
        b.add(MarketFamily.RESULT, "REG", "HOME", null, "Regulation: ${i.home}", pW, ev)
        b.add(MarketFamily.RESULT, "REG", "DRAW", null, "Regulation: Draw", pD, ev)
        b.add(MarketFamily.RESULT, "REG", "AWAY", null, "Regulation: ${i.away}", pL, ev)
        b.add(MarketFamily.DOUBLE_CHANCE, "DC", "1X", null, "Regulation double chance 1X", pW + pD, ev)
        b.add(MarketFamily.DOUBLE_CHANCE, "DC", "X2", null, "Regulation double chance X2", pL + pD, ev)
        b.add(MarketFamily.DOUBLE_CHANCE, "DC", "12", null, "Regulation double chance 12", pW + pL, ev)
        // Puck line (full game; an OT/SO win is by exactly one goal).
        val home2 = sum(g) { x, y -> x - y >= 2 }; val away2 = sum(g) { x, y -> y - x >= 2 }
        b.add(MarketFamily.HANDICAP, "SPR", "HOME", -1.5, "${i.home} -1.5 (puck line)", home2, ev)
        b.add(MarketFamily.HANDICAP, "SPR", "AWAY", 1.5, "${i.away} +1.5 (puck line)", 1 - home2, ev)
        b.add(MarketFamily.HANDICAP, "SPR", "AWAY", -1.5, "${i.away} -1.5 (puck line)", away2, ev)
        b.add(MarketFamily.HANDICAP, "SPR", "HOME", 1.5, "${i.home} +1.5 (puck line)", 1 - away2, ev)
        // Totals (full game: a regulation draw adds one deciding goal).
        fun fullTotal(line: Double) = sum(g) { x, y -> x + y + (if (x == y) 1 else 0) > line }
        val emp = hs.map { it.gf + it.ga } + asg.map { it.gf + it.ga }
        (listOf(4.5, 5.5, 6.5) + listOfNotNull(totLine) + i.prices.orEmpty().filter { it.market == "TOT" }.mapNotNull { it.point }).distinct().forEach { line ->
            val po = fullTotal(line)
            val e = if (emp.size >= 6) emp.count { it > line }.toDouble() / emp.size to emp.size else null
            b.add(MarketFamily.TOTALS, "TOT", "OVER", line, "Over ${fmt(line)} goals (incl. OT)", po, ev, e)
            b.add(MarketFamily.TOTALS, "TOT", "UNDER", line, "Under ${fmt(line)} goals (incl. OT)", 1 - po, ev, e?.let { 1 - it.first to it.second })
        }
        for ((name, home) in listOf(i.home to true, i.away to false)) {
            listOf(1.5, 2.5, 3.5).forEach { line ->
                val po = sum(g) { x, y -> (if (home) x else y) > line }
                val key = if (home) "HOME" else "AWAY"
                b.add(MarketFamily.TEAM_GOALS, "TT", "${key}_OVER", line, "$name Over ${fmt(line)} goals", po, ev)
                b.add(MarketFamily.TEAM_GOALS, "TT", "${key}_UNDER", line, "$name Under ${fmt(line)} goals", 1 - po, ev)
            }
        }
        val btts = sum(g) { x, y -> x > 0 && y > 0 }
        b.add(MarketFamily.BTTS, "BTTS", "YES", null, "Both teams to score (regulation)", btts, ev)
        b.add(MarketFamily.BTTS, "BTTS", "NO", null, "Not both teams to score (regulation)", 1 - btts, ev)
        // Periods: share of goals from real period scores when available.
        val ps = periodShare(hs + asg) ?: doubleArrayOf(0.31, 0.34, 0.35)
        val pEv = ev + "First period share of goals: ${pct(ps[0])}${if (periodShare(hs + asg) == null) " (league average assumption)" else " (from real period scores)"}."
        val p1 = PredictionEngine.grid(lh * ps[0], la * ps[0])
        val p1h = sum(p1) { x, y -> x > y }; val p1d = sum(p1) { x, y -> x == y }
        b.add(MarketFamily.PERIODS, "P1ML", "HOME", null, "1st period: ${i.home}", p1h, pEv)
        b.add(MarketFamily.PERIODS, "P1ML", "DRAW", null, "1st period: Draw", p1d, pEv)
        b.add(MarketFamily.PERIODS, "P1ML", "AWAY", null, "1st period: ${i.away}", 1 - p1h - p1d, pEv)
        listOf(0.5, 1.5).forEach { line ->
            val po = sum(p1) { x, y -> x + y > line }
            b.add(MarketFamily.PERIODS, "P1TOT", "OVER", line, "1st period Over ${fmt(line)}", po, pEv)
            b.add(MarketFamily.PERIODS, "P1TOT", "UNDER", line, "1st period Under ${fmt(line)}", 1 - po, pEv)
        }
        val risks = mutableListOf<String>()
        risks += "Starting goalies aren't confirmed by the free feed."
        if (i.injuries == null || !i.injuries.known) risks += "Injury report unknown."
        if (hs.size < 6 || asg.size < 6) risks += "Few completed games this season (${hs.size} and ${asg.size})."
        if (i.live) risks += "Game in play: probabilities are pre-game estimates, not live."
        return b.finish(true, null, risks, unavailableHockey(), injuryNotes(i, "each player out lowers that team's goals by 3% (doubtful 1%), capped at 12%; back-to-back -4%."), lh, la)
    }

    private fun sum(g: Array<DoubleArray>, p: (Int, Int) -> Boolean): Double {
        var s = 0.0
        for (x in g.indices) for (y in g[x].indices) if (p(x, y)) s += g[x][y]
        return s
    }

    private fun periodShare(g: List<TeamGame>): DoubleArray? {
        val q = DoubleArray(3); var n = 0
        g.forEach { t ->
            val h = t.m.homeLines; val a = t.m.awayLines
            if (h != null && a != null && h.size >= 3 && a.size >= 3) {
                val tot = (h.take(3).sum() + a.take(3).sum()).toDouble()
                if (tot > 0) { for (k in 0..2) q[k] += (h[k] + a[k]) / tot; n++ }
            }
        }
        if (n < 5) return null
        for (k in 0..2) q[k] /= n
        return q
    }

    private fun unavailableHockey() = listOf(
        "Shots on goal, power play / penalty kill, goaltender save %" to "Not in the free feed's schedule data; goals and period scores are used instead.",
        "Expected starting goalie" to "Not published by the free feed.",
        "Travel distance" to "Not available; rest days and back-to-backs are used.",
    )

    // =====================================================================
    // Tennis
    // =====================================================================

    private class SetDist(val win: Double, val games: DoubleArray, val gamesA: DoubleArray, val diff: Map<Int, Double>, val scores: Map<String, Double>)

    /** Exact set distribution for hold probabilities [ha], [hb] (A serves first), tiebreak at 6-6. */
    private fun set(ha: Double, hb: Double, aFirst: Boolean): SetDist {
        val games = DoubleArray(14); val gamesA = DoubleArray(8)
        val diff = HashMap<Int, Double>(); val scores = HashMap<String, Double>()
        var win = 0.0
        val tb = (0.5 + (ha - hb) * 0.9).coerceIn(0.05, 0.95)
        // dp[a][b] = probability of reaching score a-b
        val dp = Array(8) { DoubleArray(8) }
        dp[0][0] = 1.0
        for (tot in 0..13) for (a in 0..min(tot, 7)) {   // 13 = 7-6 after a tie-break
            val bb = tot - a
            if (bb < 0 || bb > 7) continue
            val p = dp[a][bb]
            if (p == 0.0) continue
            val finished = (a >= 6 || bb >= 6) && abs(a - bb) >= 2 || a == 7 || bb == 7
            if (finished) {
                val aWon = a > bb
                if (aWon) win += p
                games[a + bb] += p; gamesA[a] += p
                diff[a - bb] = (diff[a - bb] ?: 0.0) + p
                scores["$a-$bb"] = (scores["$a-$bb"] ?: 0.0) + p
                continue
            }
            if (a == 6 && bb == 6) { dp[7][6] += p * tb; dp[6][7] += p * (1 - tb); continue }
            val aServing = (tot % 2 == 0) == aFirst
            val pa = if (aServing) ha else 1 - hb
            dp[a + 1][bb] += p * pa
            dp[a][bb + 1] += p * (1 - pa)
        }
        return SetDist(win, games, gamesA, diff, scores)
    }

    private fun avgSet(ha: Double, hb: Double): SetDist {
        val s1 = set(ha, hb, true); val s2 = set(ha, hb, false)
        fun avg(x: DoubleArray, y: DoubleArray) = DoubleArray(x.size) { (x[it] + y[it]) / 2 }
        val diff = (s1.diff.keys + s2.diff.keys).associateWith { ((s1.diff[it] ?: 0.0) + (s2.diff[it] ?: 0.0)) / 2 }
        val sc = (s1.scores.keys + s2.scores.keys).associateWith { ((s1.scores[it] ?: 0.0) + (s2.scores[it] ?: 0.0)) / 2 }
        return SetDist((s1.win + s2.win) / 2, avg(s1.games, s2.games), avg(s1.gamesA, s2.gamesA), diff, sc)
    }

    private fun tennis(i: SportInputs): MarketAnalysis? {
        val b = Builder(i)
        val wta = i.leagueKey.endsWith("wta")
        // Match-win probability: 1xBet > free-feed odds > ranking. Never guessed without one of them.
        val odds1x = fair(price(i.prices, "ML", "HOME", null), price(i.prices, "ML", "AWAY", null))
        val oddsFeed = fair(i.feed?.home?.best?.price, i.feed?.away?.best?.price)
        val rankP = if (i.homeRank != null && i.awayRank != null) 1 / (1 + exp(-0.62 * (ln(i.awayRank.toDouble()) - ln(i.homeRank.toDouble())))) else null
        val tc = i.tennis
        val hp = tc?.home; val ap = tc?.away
        // Elo rating from recent results (started from the ranking).
        val eloP = if (hp?.elo != null && ap?.elo != null) TennisModel.eloWin(hp.elo, ap.elo) else null
        // Serve/return model (ATP, TML database): each player's hold rate adjusted by the opponent's return.
        val avgHold = tc?.avgHold ?: if (wta) 0.66 else 0.80
        val serveHolds = if (hp?.hold != null && hp.brk != null && ap?.hold != null && ap.brk != null) {
            val avgBrk = 1 - avgHold
            (hp.hold - (ap.brk - avgBrk)).coerceIn(0.35, 0.97) to (ap.hold - (hp.brk - avgBrk)).coerceIn(0.35, 0.97)
        } else null
        val serveP = serveHolds?.let { (x, y) -> val st = avgSet(x, y).win; st * st * (3 - 2 * st) }
        val bookName = i.feed?.home?.best?.bookmaker ?: "Bookmaker"
        val parts = listOfNotNull(
            (odds1x ?: oddsFeed)?.let { Triple(it, 3.0, if (odds1x != null) "1xBet price" else "$bookName price") },
            // Weights from a back-test on 1,538 ATP matches (2025-26): Elo + ranking + serve beat each one alone.
            eloP?.let { Triple(it, 1.0, "Elo rating from recent results") },
            rankP?.let { Triple(it, 1.0, "world ranking") },
            serveP?.let { Triple(it, 0.5, "serve/return records") },
        )
        val wSum = parts.sumOf { it.second }
        val pMatch = if (parts.isEmpty()) null else parts.sumOf { it.first * it.second } / wSum
        val sources = parts.map { "${it.third} (${Math.round(it.second / wSum * 100)}%)" }.toMutableList()
        val unavailable = listOfNotNull(
            if (serveHolds == null) "Serve & return stats (holds, breaks)" to (if (wta) "No free WTA serve database; the model uses the WTA average hold rate (66%) adjusted to the players' strength."
                else "Not in the TML database for one of these players; the model uses the ATP average hold rate adjusted to their strength.") else null,
            "Per-match serve details (1st serve %, aces, double faults this season)" to "Not published by the free sources for the current season.",
            if (tc == null) "Recent form, surface record, head-to-head" to "Couldn't be loaded (offline or source error)." else null,
            "Injuries / withdrawals" to "No reliable free tennis injury feed.",
            "Best of five sets" to "The model assumes best of three sets (Grand Slam men's matches are best of five).",
        )
        if (pMatch == null) {
            return b.finish(false, "Data unavailable: no odds, ranking or recent results for these players, so no estimate is made.", emptyList(), unavailable,
                listOf("No injury information for tennis in the free sources."), 0.0, 0.0)
        }
        // Per-set win probability for best of three: P = s^2 (3 - 2s).
        var lo = 0.01; var hi = 0.99
        repeat(50) { val s = (lo + hi) / 2; if (s * s * (3 - 2 * s) < pMatch) lo = s else hi = s }
        val pSet = (lo + hi) / 2
        // Find hold rates that give that set probability.
        // Overall hold level from the players' real serve records when known (sets more/fewer tie-breaks).
        val base = serveHolds?.let { (x, y) -> (x + y) / 2 } ?: avgHold
        var dLo = -0.3; var dHi = 0.3
        repeat(40) {
            val d = (dLo + dHi) / 2
            val s = avgSet((base + d).coerceIn(0.2, 0.98), (base - d).coerceIn(0.2, 0.98)).win
            if (s < pSet) dLo = d else dHi = d
        }
        val d = (dLo + dHi) / 2
        val ha = (base + d).coerceIn(0.2, 0.98); val hb = (base - d).coerceIn(0.2, 0.98)
        val sd = avgSet(ha, hb)
        val s = sd.win

        val ev = mutableListOf<String>()
        ev += "Match-win estimate ${pct(pMatch)} for ${i.home} from ${sources.joinToString()}."
        if (i.homeRank != null || i.awayRank != null) ev += "Ranking: ${i.home} #${i.homeRank ?: "N/A"}, ${i.away} #${i.awayRank ?: "N/A"} (ESPN)."
        ev += "Implied service holds: ${i.home} ${pct(ha)}, ${i.away} ${pct(hb)}; set-win ${pct(s)}."
        if (tc != null) {
            tc.surface?.let { ev += "Surface: $it." }
            if (eloP != null) ev += "Elo: ${i.home} ${Math.round(hp!!.elo!!)} vs ${i.away} ${Math.round(ap!!.elo!!)} -> ${pct(eloP)} (${hp.eloMatches} and ${ap.eloMatches} rated matches)."
            for (p in listOfNotNull(hp, ap)) {
                if (p.last.isNotEmpty()) ev += "${p.name} last ${p.last.size}: ${p.last.joinToString("") { if (it.won) "W" else "L" }} · 6 months ${p.wins6m}-${p.losses6m}" +
                    (if (tc.surface != null) " · on ${tc.surface} (12 months) ${p.surfaceWins}-${p.surfaceLosses}" else "") +
                    (p.gamesShare?.let { " · games won ${pct(it)} (last 15)" } ?: "") + "."
                else ev += "${p.name}: no recent results in the free sources."
                if (p.hold != null && p.brk != null) ev += "${p.name} serve: holds ${pct(p.hold)}, breaks ${pct(p.brk)} (${p.serveMatches} matches${if (p.serveOnSurface) " on ${tc.surface}" else ", all surfaces"}, TML)."
            }
            serveP?.let { ev += "Serve/return matchup: ${i.home} holds ${pct(serveHolds!!.first)}, ${i.away} ${pct(serveHolds.second)} -> ${pct(it)}." }
            if (tc.h2h.isNotEmpty()) ev += "Head-to-head: ${i.home} ${tc.h2h.count { it.won }}-${tc.h2h.count { !it.won }}" +
                " (last: ${tc.h2h.first().score}, ${if (tc.h2h.first().won) "won" else "lost"}, ${tc.h2h.first().event.substringAfter(" · ")})."
            else ev += "Head-to-head: no meeting in the data window."
            listOfNotNull(hp, ap).forEach { p -> p.daysSinceLast?.let { d -> if (p.matches3d > 0) ev += "${p.name}: ${p.matches3d} match${if (p.matches3d == 1) "" else "es"} (${p.sets3d} sets) in the last 3 days." else if (d <= 30) ev += "${p.name}: last played $d days ago." } }
            ev += tc.windowNote
        }

        // Match, sets
        val p20 = s * s; val p02 = (1 - s) * (1 - s); val p21 = 2 * s * s * (1 - s); val p12 = 2 * s * (1 - s) * (1 - s)
        b.add(MarketFamily.RESULT, "ML", "HOME", null, "${i.home} to win", p20 + p21, ev)
        b.add(MarketFamily.RESULT, "ML", "AWAY", null, "${i.away} to win", p02 + p12, ev)
        b.add(MarketFamily.SETS, "SET1", "HOME", null, "1st set: ${i.home}", s, ev)
        b.add(MarketFamily.SETS, "SET1", "AWAY", null, "1st set: ${i.away}", 1 - s, ev)
        b.add(MarketFamily.SETS, "SETS", "2", null, "Total sets: 2 (straight sets)", p20 + p02, ev)
        b.add(MarketFamily.SETS, "SETS", "3", null, "Total sets: 3 / both players win a set", p21 + p12, ev)
        b.add(MarketFamily.SETS, "CSS", "2-0", null, "Correct score ${i.home} 2-0", p20, ev)
        b.add(MarketFamily.SETS, "CSS", "2-1", null, "Correct score ${i.home} 2-1", p21, ev)
        b.add(MarketFamily.SETS, "CSS", "1-2", null, "Correct score ${i.away} 2-1", p12, ev)
        b.add(MarketFamily.SETS, "CSS", "0-2", null, "Correct score ${i.away} 2-0", p02, ev)
        b.add(MarketFamily.SETS, "SETHC", "HOME", 1.5, "${i.home} +1.5 sets", 1 - p02, ev)
        b.add(MarketFamily.SETS, "SETHC", "AWAY", 1.5, "${i.away} +1.5 sets", 1 - p20, ev)

        // Games: distributions over 2 or 3 sets (convolution, sets independent).
        val total = DoubleArray(40); val gamesA = DoubleArray(24); val gamesB = DoubleArray(24); val diff = HashMap<Int, Double>()
        // enumerate set winners sequences (W/L) of a best-of-3
        val seqs = listOf("WW", "LL", "WLW", "LWW", "WLL", "LWL")
        for (sq in seqs) {
            var prob = 1.0
            for (c in sq) prob *= if (c == 'W') s else 1 - s
            jointGames(sq, sd, s).forEach { (k, p) ->
                val (a, bb) = k
                val pp = prob * p
                if (a + bb < total.size) total[a + bb] += pp
                if (a < gamesA.size) gamesA[a] += pp
                if (bb < gamesB.size) gamesB[bb] += pp
                diff[a - bb] = (diff[a - bb] ?: 0.0) + pp
            }
        }
        val meanTotal = total.indices.sumOf { it * total[it] }
        ev += "Expected total games ${f1(meanTotal)}."
        fun over(dist: DoubleArray, line: Double) = dist.indices.filter { it > line }.sumOf { dist[it] }
        val totLines = (i.prices.orEmpty().filter { it.market == "TOT" }.mapNotNull { it.point } + listOf(-3.0, 0.0, 3.0).map { half(meanTotal + it) }).distinct()
        totLines.forEach { line ->
            val po = over(total, line)
            b.add(MarketFamily.GAMES, "TOT", "OVER", line, "Over ${fmt(line)} games", po, ev)
            b.add(MarketFamily.GAMES, "TOT", "UNDER", line, "Under ${fmt(line)} games", 1 - po, ev)
        }
        for ((name, dist, key) in listOf(Triple(i.home, gamesA, "HOME"), Triple(i.away, gamesB, "AWAY"))) {
            val mean = dist.indices.sumOf { it * dist[it] }
            listOf(-2.0, 0.0).map { half(mean + it) }.distinct().forEach { line ->
                val po = over(dist, line)
                b.add(MarketFamily.PLAYER_GAMES, "PG", "${key}_OVER", line, "$name Over ${fmt(line)} games", po, ev)
                b.add(MarketFamily.PLAYER_GAMES, "PG", "${key}_UNDER", line, "$name Under ${fmt(line)} games", 1 - po, ev)
            }
        }
        // Games handicap (home perspective): bookmaker lines + alternatives.
        val hcLines = (i.prices.orEmpty().filter { it.market == "SPR" && it.sel == "HOME" }.mapNotNull { it.point } + listOf(-4.5, -2.5, 2.5, 4.5)).distinct()
        hcLines.forEach { line ->
            val pc = diff.filter { (k, _) -> k + line > 0 }.values.sum()
            b.add(MarketFamily.HANDICAP, "SPR", "HOME", line, "${i.home} ${sgn(line)} games", pc, ev)
            b.add(MarketFamily.HANDICAP, "SPR", "AWAY", -line, "${i.away} ${sgn(-line)} games", 1 - pc, ev)
        }
        // First set total games.
        val s1 = sd.games
        listOf(8.5, 9.5, 10.5).forEach { line ->
            val po = over(s1, line)
            b.add(MarketFamily.SETS, "S1TOT", "OVER", line, "1st set Over ${fmt(line)} games", po, ev)
            b.add(MarketFamily.SETS, "S1TOT", "UNDER", line, "1st set Under ${fmt(line)} games", 1 - po, ev)
        }
        val risks = listOfNotNull(
            if (tc == null) "Recent form, surface and serve data couldn't be loaded: the estimate rests on ${sources.joinToString()}." else "Estimate from ${sources.joinToString()}.",
            listOfNotNull(hp, ap).firstOrNull { it.matches3d >= 3 || it.sets3d >= 8 }?.let { "${it.name} has played ${it.matches3d} matches (${it.sets3d} sets) in 3 days: fatigue risk." },
            listOfNotNull(hp, ap).firstOrNull { it.last.size < 5 }?.let { "Few recent results for ${it.name} in the free sources: less reliable." },
            "Fitness and retirements can't be checked (no free injury feed).",
        ) + (if (i.live) listOf("Match in play: probabilities are pre-match estimates, not live.") else emptyList())
        return b.finish(parts.isNotEmpty(), null, risks, unavailable,
            listOf("No reliable free injury/withdrawal source for tennis."), ha, hb)
    }

    /** Joint (A games, B games) distribution for a sequence of set winners. */
    private fun jointGames(seq: String, sd: SetDist, s: Double): Map<Pair<Int, Int>, Double> {
        var acc = mapOf((0 to 0) to 1.0)
        for (c in seq) {
            val w = c == 'W'
            val next = HashMap<Pair<Int, Int>, Double>()
            sd.scores.forEach { (k, p) ->
                val (a, bb) = k.split("-").map { it.toInt() }
                if ((a > bb) != w) return@forEach
                val cp = p / (if (w) s else 1 - s)
                acc.forEach { (key, q) -> val nk = (key.first + a) to (key.second + bb); next[nk] = (next[nk] ?: 0.0) + q * cp }
            }
            acc = next
        }
        return acc
    }

    @Suppress("unused")
    private fun clamp(x: Double) = max(0.0, min(1.0, x)) + 0 * sqrt(0.0)
}
