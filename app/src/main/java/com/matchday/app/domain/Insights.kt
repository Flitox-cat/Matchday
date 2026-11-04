package com.matchday.app.domain

import kotlinx.serialization.Serializable

/** One completed historical match, as published by ESPN. Never synthesised. */
@Serializable
data class HistMatch(
    val id: String,
    val date: Long,
    val competition: String,
    val leagueSlug: String,
    val homeId: String?,
    val awayId: String?,
    val homeName: String,
    val awayName: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val venue: String? = null,
    val city: String? = null,
    val country: String? = null,
) {
    val european: Boolean get() = leagueSlug.startsWith("uefa.")
}

@Serializable
data class TeamHistory(
    val teamId: String,
    val matches: List<HistMatch>,      // newest first
    val seasonsFrom: Int?,
    val seasonsTo: Int?,
    val fetchedAt: Long,
)

/** A team's result in one historical match. */
data class TeamGame(val m: HistMatch, val home: Boolean, val gf: Int, val ga: Int) {
    val win get() = gf > ga
    val draw get() = gf == ga
    val loss get() = gf < ga
    val opponentId get() = if (home) m.awayId else m.homeId
    val opponent get() = if (home) m.awayName else m.homeName
}

fun TeamHistory.games(): List<TeamGame> = matches.mapNotNull { m ->
    when (teamId) {
        m.homeId -> TeamGame(m, true, m.homeGoals, m.awayGoals)
        m.awayId -> TeamGame(m, false, m.awayGoals, m.homeGoals)
        else -> null
    }
}

enum class InsightKind(val emoji: String) { WARNING("⚠️"), STAT("📊"), H2H("⚽"), FIRE("🔥"), INFO("ℹ️") }

enum class Lean { HOME, DRAW, AWAY, OVER, UNDER, BTTS_YES, BTTS_NO }

data class MarketLean(val lean: Lean, val strength: Double)

data class Insight(
    val kind: InsightKind,
    val title: String,
    val statement: String,
    val metrics: List<Pair<String, String>>,
    val matches: Int,
    val from: Long?,
    val to: Long?,
    val competitions: List<String>,
    val leans: List<MarketLean>,
    val impact: String,
    val smallSample: Boolean,
    val importance: Double,
    val source: String = "ESPN",
    val updatedAt: Long? = null,
)

data class TeamRecord(val n: Int, val w: Int, val d: Int, val l: Int, val gf: Int, val ga: Int) {
    val winRate get() = if (n == 0) 0.0 else w.toDouble() / n
    val lossRate get() = if (n == 0) 0.0 else l.toDouble() / n
    val gfAvg get() = if (n == 0) 0.0 else gf.toDouble() / n
    val gaAvg get() = if (n == 0) 0.0 else ga.toDouble() / n
    fun metrics(): List<Pair<String, String>> = listOf(
        "Matches" to "$n", "Wins" to "$w", "Draws" to "$d", "Losses" to "$l",
        "Goals scored" to "$gf", "Goals conceded" to "$ga",
    )
    companion object {
        fun of(g: List<TeamGame>) = TeamRecord(
            g.size, g.count { it.win }, g.count { it.draw }, g.count { it.loss }, g.sumOf { it.gf }, g.sumOf { it.ga },
        )
    }
}

/**
 * Builds "Historical Insights & Interesting Facts" from real match history.
 * Rules: every fact states its sample size, dates and competitions; samples under 6 matches are
 * flagged as small and given half weight; facts only *lean* towards markets - they never set
 * probabilities or the recommendation on their own.
 */
object InsightsEngine {

    private fun pct(x: Double) = "${Math.round(x * 100)}%"
    private fun weight(n: Int) = if (n >= 6) 1.0 else 0.5
    private fun comps(g: List<TeamGame>) = g.map { it.m.competition }.distinct().take(4)

    fun homeCountry(h: TeamHistory?): String? = h?.games()?.filter { it.home }
        ?.mapNotNull { it.m.country }?.groupingBy { it }?.eachCount()?.maxByOrNull { it.value }?.key

    fun build(
        homeName: String,
        awayName: String,
        homeHist: TeamHistory?,
        awayHist: TeamHistory?,
        venue: String?,
        competitionKey: String,
        kickoff: Long,
        h2h: List<RecentResult>,
        homeCountryHint: String? = null,
        awayCountryHint: String? = null,
    ): List<Insight> {
        val out = mutableListOf<Insight>()
        val hg = homeHist?.games().orEmpty().filter { it.m.date < kickoff }
        val ag = awayHist?.games().orEmpty().filter { it.m.date < kickoff }
        val updated = listOfNotNull(homeHist?.fetchedAt, awayHist?.fetchedAt).minOrNull()
        val european = competitionKey.startsWith("soccer/uefa.")
        val hostCountry = homeCountry(homeHist) ?: homeCountryHint
        val awayCountry = homeCountry(awayHist) ?: awayCountryHint

        fun insight(
            kind: InsightKind, title: String, statement: String, games: List<TeamGame>,
            leans: List<MarketLean>, impact: String, importance: Double, metrics: List<Pair<String, String>>? = null,
        ) = Insight(
            kind = kind, title = title, statement = statement,
            metrics = metrics ?: TeamRecord.of(games).metrics(),
            matches = games.size,
            from = games.minOfOrNull { it.m.date }, to = games.maxOfOrNull { it.m.date },
            competitions = comps(games), leans = leans, impact = impact,
            smallSample = games.size < 6, importance = importance * weight(games.size), updatedAt = updated,
        )

        // 1. Away team's record in the host country (foreign trips only)
        if (awayHist != null && hostCountry != null && awayCountry != null && hostCountry != awayCountry) {
            val g = ag.filter { !it.home && it.m.country == hostCountry }
            if (g.isEmpty()) {
                out += Insight(
                    InsightKind.INFO, "First trip to $hostCountry in the data",
                    "$awayName have no away matches in $hostCountry in the seasons analysed (${seasons(awayHist)}).",
                    emptyList(), 0, null, null, emptyList(), emptyList(),
                    "No country-specific history to weigh; rely on current form and odds.", true, 0.2, updatedAt = updated,
                )
            } else {
                val r = TeamRecord.of(g)
                val (kind, title, leans, impact) = when {
                    r.n >= 3 && r.winRate <= 0.25 && r.lossRate >= 0.5 -> Quad(
                        InsightKind.WARNING, "$awayName struggle in $hostCountry",
                        listOf(MarketLean(Lean.HOME, weight(r.n))),
                        "Weakens the case for an $awayName win; slightly supports $homeName or double chance 1X.",
                    )
                    r.n >= 3 && r.winRate >= 0.6 -> Quad(
                        InsightKind.STAT, "$awayName travel well to $hostCountry",
                        listOf(MarketLean(Lean.AWAY, weight(r.n))),
                        "Supports the $awayName side of the result markets (away win, X2, draw no bet).",
                    )
                    else -> Quad(InsightKind.STAT, "$awayName in $hostCountry", emptyList<MarketLean>(),
                        "Mixed record: no clear country effect.")
                }
                out += insight(kind, title,
                    "$awayName have won ${r.w} of ${r.n} away matches in $hostCountry (D${r.d} L${r.l}, goals ${r.gf}-${r.ga}).",
                    g, leans, impact, 0.9)
            }
        }

        // 2. Away team at this stadium
        val v = venue?.let { normVenue(it) }
        if (v != null) {
            val g = ag.filter { !it.home && it.m.venue?.let { mv -> normVenue(mv) } == v }
            if (g.isNotEmpty()) {
                val r = TeamRecord.of(g)
                val leans = when {
                    r.w == 0 && r.n >= 2 -> listOf(MarketLean(Lean.HOME, weight(r.n)))
                    r.winRate >= 0.6 && r.n >= 2 -> listOf(MarketLean(Lean.AWAY, weight(r.n)))
                    else -> emptyList()
                }
                out += insight(
                    if (r.w == 0) InsightKind.WARNING else InsightKind.STAT,
                    if (r.w == 0) "$awayName have never won at $venue (in the data)" else "$awayName at $venue",
                    "$awayName have played ${r.n} time(s) at $venue: W${r.w} D${r.d} L${r.l}, goals ${r.gf}-${r.ga}.",
                    g, leans,
                    if (leans.isEmpty()) "No consistent stadium pattern." else "Venue history leans ${if (r.w == 0) "towards $homeName" else "towards $awayName"}; small samples are anecdotal.",
                    0.8,
                )
            }
        }

        // 3. Home team's home record (last 10, all competitions)
        val hh = hg.filter { it.home }.take(10)
        if (hh.size >= 4) {
            val r = TeamRecord.of(hh)
            val goalsAvg = (r.gf + r.ga).toDouble() / r.n
            val leans = mutableListOf<MarketLean>()
            if (r.winRate >= 0.7) leans += MarketLean(Lean.HOME, weight(r.n))
            if (r.winRate <= 0.3) leans += MarketLean(Lean.AWAY, 0.5 * weight(r.n))
            if (goalsAvg >= 3.2) leans += MarketLean(Lean.OVER, 0.5 * weight(r.n))
            if (goalsAvg <= 2.0) leans += MarketLean(Lean.UNDER, 0.5 * weight(r.n))
            out += insight(
                if (r.winRate >= 0.7) InsightKind.STAT else if (r.winRate <= 0.3) InsightKind.WARNING else InsightKind.STAT,
                if (r.winRate >= 0.7) "Stadium advantage: $homeName strong at home" else "$homeName home record",
                "$homeName have won ${r.w} of their last ${r.n} home matches, averaging ${fmt(r.gfAvg)} scored and ${fmt(r.gaAvg)} conceded.",
                hh, leans,
                when {
                    r.winRate >= 0.7 -> "Supports $homeName win / 1X. A ${fmt(r.gfAvg)} home scoring rate also helps home-goals and BTTS markets."
                    r.winRate <= 0.3 -> "Poor home form weakens the case for $homeName; consider X2 or draw no bet $awayName only if the odds agree."
                    else -> "Ordinary home record: no strong home-advantage signal."
                },
                1.0,
            )
        }

        // 4. Away team's away record (last 10)
        val aa = ag.filter { !it.home }.take(10)
        if (aa.size >= 4) {
            val r = TeamRecord.of(aa)
            val leans = mutableListOf<MarketLean>()
            if (r.winRate >= 0.6) leans += MarketLean(Lean.AWAY, weight(r.n))
            if (r.winRate <= 0.2) leans += MarketLean(Lean.HOME, weight(r.n))
            if (r.gfAvg >= 1.5 && r.gaAvg >= 1.2) leans += MarketLean(Lean.BTTS_YES, 0.5 * weight(r.n))
            out += insight(
                if (r.winRate <= 0.2) InsightKind.WARNING else InsightKind.STAT,
                if (r.winRate <= 0.2) "$awayName struggle away from home" else "$awayName away record",
                "$awayName have won ${r.w} of their last ${r.n} away matches (D${r.d} L${r.l}), scoring ${fmt(r.gfAvg)} and conceding ${fmt(r.gaAvg)} per game.",
                aa, leans,
                when {
                    r.winRate >= 0.6 -> "Strong travellers: supports $awayName win / X2."
                    r.winRate <= 0.2 -> "Weak on the road: lowers confidence in an $awayName win."
                    else -> "Average away form."
                },
                0.9,
            )
        }

        // 5. European away / home record (European ties only)
        if (european) {
            val ea = ag.filter { it.m.european && !it.home }.take(10)
            if (ea.size >= 3) {
                val r = TeamRecord.of(ea)
                val leans = when {
                    r.winRate <= 0.25 -> listOf(MarketLean(Lean.HOME, weight(r.n)))
                    r.winRate >= 0.6 -> listOf(MarketLean(Lean.AWAY, weight(r.n)))
                    else -> emptyList()
                }
                out += insight(
                    if (r.winRate <= 0.25) InsightKind.WARNING else InsightKind.STAT,
                    "$awayName in European away games",
                    "$awayName won ${r.w} of their last ${r.n} European away matches (D${r.d} L${r.l}, goals ${r.gf}-${r.ga}).",
                    ea, leans,
                    if (leans.isEmpty()) "No strong European away pattern." else "European away history ${if (r.winRate <= 0.25) "counts against" else "supports"} an $awayName result.",
                    0.85,
                )
            }
            val eh = hg.filter { it.m.european && it.home }.take(10)
            if (eh.size >= 3) {
                val r = TeamRecord.of(eh)
                val leans = if (r.winRate >= 0.7) listOf(MarketLean(Lean.HOME, weight(r.n))) else emptyList()
                out += insight(
                    InsightKind.STAT, "$homeName at home in Europe",
                    "$homeName won ${r.w} of their last ${r.n} European home matches (D${r.d} L${r.l}, goals ${r.gf}-${r.ga}).",
                    eh, leans,
                    if (leans.isEmpty()) "No strong European home pattern." else "Strong European home record supports $homeName / 1X.",
                    0.8,
                )
            }
        }

        // 6. Domestic vs European split (large gaps only)
        for ((name, games, isHome) in listOf(Triple(homeName, hg, true), Triple(awayName, ag, false))) {
            val dom = games.filter { !it.m.european }.take(20)
            val eu = games.filter { it.m.european }.take(20)
            if (dom.size >= 5 && eu.size >= 5) {
                val rd = TeamRecord.of(dom); val re = TeamRecord.of(eu)
                val gap = rd.winRate - re.winRate
                if (kotlin.math.abs(gap) >= 0.2) {
                    val worseInEurope = gap > 0
                    val leans = if (european && worseInEurope) listOf(MarketLean(if (isHome) Lean.AWAY else Lean.HOME, 0.5)) else emptyList()
                    out += insight(
                        InsightKind.STAT,
                        "$name: ${if (worseInEurope) "weaker in Europe" else "stronger in Europe"} than at home",
                        "Win rate ${pct(rd.winRate)} in domestic games (${rd.n}) vs ${pct(re.winRate)} in European games (${re.n}).",
                        dom + eu, leans,
                        if (european) "Relevant here: this is a European match." else "Less relevant for a domestic match.",
                        if (european) 0.6 else 0.3,
                        metrics = listOf("Domestic" to "W${rd.w} D${rd.d} L${rd.l}", "European" to "W${re.w} D${re.d} L${re.l}"),
                    )
                }
            }
        }

        // 7. Current streaks
        streak(homeName, hg)?.let { out += it.copy(updatedAt = updated) }
        streak(awayName, ag)?.let { out += it.copy(updatedAt = updated) }

        // 8. Rest days (midweek fixtures) and how each team copes historically
        for ((name, games, isHome) in listOf(Triple(homeName, hg, true), Triple(awayName, ag, false))) {
            val last = games.firstOrNull() ?: continue
            val restDays = ((kickoff - last.m.date) / 86_400_000L).toInt()
            if (restDays !in 0..3) continue
            // historical: results with <=3 days rest vs >=4
            val sorted = games.sortedBy { it.m.date }
            val short = mutableListOf<TeamGame>(); val long = mutableListOf<TeamGame>()
            for (i in 1 until sorted.size) {
                val d = (sorted[i].m.date - sorted[i - 1].m.date) / 86_400_000L
                if (d <= 3) short += sorted[i] else if (d <= 10) long += sorted[i]
            }
            val rs = TeamRecord.of(short.takeLast(20)); val rl = TeamRecord.of(long.takeLast(20))
            val dropOff = rs.n >= 5 && rl.n >= 5 && rl.winRate - rs.winRate >= 0.15
            out += insight(
                if (dropOff) InsightKind.WARNING else InsightKind.INFO,
                "$name on short rest ($restDays day${if (restDays == 1) "" else "s"})",
                "$name last played on ${day(last.m.date)} (${last.m.competition}). " +
                    if (rs.n >= 5 && rl.n >= 5) "Historically they win ${pct(rs.winRate)} with ≤3 days' rest vs ${pct(rl.winRate)} with more rest (${rs.n} and ${rl.n} games)."
                    else "Not enough short-rest games to measure an effect.",
                short.takeLast(20),
                if (dropOff) listOf(MarketLean(if (isHome) Lean.AWAY else Lean.HOME, 0.5)) else emptyList(),
                if (dropOff) "Possible fatigue effect on $name; weigh it lightly." else "No measurable fatigue effect in their history.",
                if (dropOff) 0.6 else 0.25,
                metrics = listOf("Rest days" to "$restDays", "Short-rest record" to "W${rs.w} D${rs.d} L${rs.l}", "Normal-rest record" to "W${rl.w} D${rl.d} L${rl.l}"),
            )
        }

        // 9. Away team vs clubs from the host country (European ties)
        if (european && hostCountry != null && awayCountry != null && hostCountry != awayCountry) {
            val countryOf = HashMap<String, String>()
            (hg + ag).forEach { g -> val id = g.m.homeId; val c = g.m.country; if (id != null && c != null) countryOf[id] = c }
            homeHist?.matches?.forEach { m -> val id = m.homeId; val c = m.country; if (id != null && c != null) countryOf[id] = c }
            val g = ag.filter { it.opponentId?.let { id -> countryOf[id] } == hostCountry }
            if (g.size >= 3) {
                val r = TeamRecord.of(g)
                val leans = when {
                    r.winRate <= 0.25 -> listOf(MarketLean(Lean.HOME, 0.5 * weight(r.n)))
                    r.winRate >= 0.65 -> listOf(MarketLean(Lean.AWAY, 0.5 * weight(r.n)))
                    else -> emptyList()
                }
                out += insight(
                    InsightKind.STAT, "$awayName vs clubs from $hostCountry",
                    "Against $hostCountry clubs (home and away): W${r.w} D${r.d} L${r.l}, goals ${r.gf}-${r.ga} in ${r.n} matches.",
                    g, leans,
                    if (leans.isEmpty()) "No clear pattern against clubs from this country." else "Lean ${if (r.winRate <= 0.25) "towards $homeName" else "towards $awayName"}, weighted lightly.",
                    0.5,
                )
            }
        }

        // 10. Head-to-head goal and result patterns
        if (h2h.size >= 3) {
            val n = h2h.size
            val hw = h2h.count { it.points == 3 }
            val over = h2h.count { it.goalsFor + it.goalsAgainst >= 3 }
            val btts = h2h.count { it.goalsFor > 0 && it.goalsAgainst > 0 }
            val leans = mutableListOf<MarketLean>()
            val w = weight(n)
            if (over.toDouble() / n >= 0.7) leans += MarketLean(Lean.OVER, 0.5 * w)
            if (over.toDouble() / n <= 0.3) leans += MarketLean(Lean.UNDER, 0.5 * w)
            if (btts.toDouble() / n >= 0.7) leans += MarketLean(Lean.BTTS_YES, 0.5 * w)
            if (btts.toDouble() / n <= 0.3) leans += MarketLean(Lean.BTTS_NO, 0.5 * w)
            if (hw.toDouble() / n >= 0.6) leans += MarketLean(Lean.HOME, 0.5 * w)
            out += Insight(
                InsightKind.H2H, "Head-to-head pattern",
                "$homeName won $hw of the last $n meetings; $over had 3+ goals and both teams scored in $btts.",
                listOf("Meetings" to "$n", "$homeName wins" to "$hw", "3+ goals" to "$over", "Both scored" to "$btts"),
                n, h2h.minOfOrNull { it.date }?.takeIf { it > 0 }, h2h.maxOfOrNull { it.date }?.takeIf { it > 0 },
                h2h.mapNotNull { it.competition }.distinct().take(4), leans,
                when {
                    over.toDouble() / n <= 0.3 -> "Repeated low-scoring meetings: supports Under 2.5 if the odds agree."
                    over.toDouble() / n >= 0.7 -> "Meetings tend to be high-scoring: supports Over 2.5 / BTTS."
                    else -> "No strong goals pattern in this fixture."
                },
                n < 6, 0.7 * w,
            )
        }

        // 11. Goal environment: home team's home games + away team's away games
        if (hh.size >= 5 && aa.size >= 5) {
            val ho = hh.count { it.gf + it.ga >= 3 }; val ao = aa.count { it.gf + it.ga >= 3 }
            val hb = hh.count { it.gf > 0 && it.ga > 0 }; val ab = aa.count { it.gf > 0 && it.ga > 0 }
            val overRate = (ho + ao).toDouble() / (hh.size + aa.size)
            val bttsRate = (hb + ab).toDouble() / (hh.size + aa.size)
            val leans = mutableListOf<MarketLean>()
            if (overRate >= 0.65) leans += MarketLean(Lean.OVER, 1.0)
            if (overRate <= 0.35) leans += MarketLean(Lean.UNDER, 1.0)
            if (bttsRate >= 0.65) leans += MarketLean(Lean.BTTS_YES, 1.0)
            if (bttsRate <= 0.35) leans += MarketLean(Lean.BTTS_NO, 1.0)
            if (leans.isNotEmpty()) {
                out += insight(
                    InsightKind.FIRE, "Goal environment",
                    "3+ goals in $ho of $homeName's last ${hh.size} home games and $ao of $awayName's last ${aa.size} away games; both teams scored in ${hb + ab} of those ${hh.size + aa.size}.",
                    hh + aa, leans,
                    "Context for the goals and BTTS markets: ${if (overRate >= 0.65) "supports Over 2.5" else if (overRate <= 0.35) "supports Under 2.5" else "neutral on totals"}, " +
                        if (bttsRate >= 0.65) "supports BTTS Yes." else if (bttsRate <= 0.35) "supports BTTS No." else "neutral on BTTS.",
                    0.8,
                    metrics = listOf("Over 2.5 rate" to pct(overRate), "BTTS rate" to pct(bttsRate)),
                )
            }
        }

        // 12. What could not be researched (stated, not guessed)
        out += Insight(
            InsightKind.INFO, "Not analysed (no reliable free data)",
            "Playing-style matchups (e.g. against high-pressing or defensive teams), records against the opposing manager and travel distance aren't available from free public sources, so they are not used.",
            emptyList(), 0, null, null, emptyList(), emptyList(), "None.", false, 0.0, source = "-",
        )

        return out.sortedByDescending { it.importance }
    }

    private fun streak(name: String, games: List<TeamGame>): Insight? {
        if (games.size < 4) return null
        fun run(p: (TeamGame) -> Boolean) = games.takeWhile(p).size
        val scoring = run { it.gf > 0 }
        val unbeaten = run { !it.loss }
        val winless = run { !it.win }
        val wins = run { it.win }
        val clean = run { it.ga == 0 }
        val blank = run { it.gf == 0 }
        val candidates = listOfNotNull(
            if (wins >= 4) Triple("$name have won $wins in a row", games.take(wins), listOf(MarketLean(Lean.HOME, 0.0))) else null,
            if (unbeaten >= 6 && wins < 4) Triple("$name unbeaten in $unbeaten", games.take(unbeaten), emptyList<MarketLean>()) else null,
            if (winless >= 4) Triple("$name without a win in $winless", games.take(winless), emptyList<MarketLean>()) else null,
            if (scoring >= 6) Triple("$name have scored in $scoring consecutive matches", games.take(scoring), listOf(MarketLean(Lean.BTTS_YES, 0.5))) else null,
            if (clean >= 3) Triple("$name: $clean clean sheets in a row", games.take(clean), listOf(MarketLean(Lean.BTTS_NO, 0.5))) else null,
            if (blank >= 3) Triple("$name failed to score in $blank straight games", games.take(blank), listOf(MarketLean(Lean.BTTS_NO, 0.5))) else null,
        )
        val best = candidates.maxByOrNull { it.second.size } ?: return null
        val g = best.second
        val r = TeamRecord.of(g)
        return Insight(
            kind = if (best.first.contains("without") || best.first.contains("failed")) InsightKind.WARNING else InsightKind.FIRE,
            title = "Current form: ${best.first}",
            statement = "${best.first} (all competitions, ${day(g.last().m.date)} – ${day(g.first().m.date)}).",
            metrics = r.metrics(),
            matches = g.size, from = g.minOfOrNull { it.m.date }, to = g.maxOfOrNull { it.m.date },
            competitions = g.map { it.m.competition }.distinct().take(4),
            leans = best.third.filter { it.strength > 0 },
            impact = when {
                best.first.contains("scored in") -> "A long scoring run supports $name scoring again (BTTS Yes / team goals)."
                best.first.contains("clean sheets") || best.first.contains("failed to score") -> "Points towards a low-scoring side: supports BTTS No."
                best.first.contains("without a win") -> "Poor momentum: be cautious backing $name."
                else -> "Good momentum for $name, but streaks end; the odds usually already reflect this."
            },
            smallSample = g.size < 6, importance = 0.7,
        )
    }

    fun normVenue(s: String) = TeamMatcher.normalize(s).replace("stadium", "").replace("stadion", "").replace("estadio", "").trim()

    private fun seasons(h: TeamHistory): String =
        if (h.seasonsFrom != null && h.seasonsTo != null) "${h.seasonsFrom}–${h.seasonsTo}" else "recent seasons"

    private fun fmt(d: Double) = String.format("%.1f", d)
    private fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
