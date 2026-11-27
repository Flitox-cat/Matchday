package com.matchday.app.domain

import kotlin.math.exp
import kotlin.math.sqrt

/** One player market with its estimated chance. */
data class PlayerMarket(val group: String, val label: String, val probability: Double)

/**
 * Player markets for football (scorer, goal number, assists, fouls, cards).
 *
 * Every player starts from the usual rate for his position per 90 minutes. Live, the rates move toward
 * what he has done in this match the longer he has played, fouls follow how often his team is fouling,
 * and goals follow his team's projected remaining goals and his own shots. Before kick-off the confirmed
 * line-up and the pre-match expected goals are used. The free sources have no season player stats or
 * player odds, so everything here is a model estimate.
 */
object PlayerModel {
    fun role(p: String?): String = (p ?: "").uppercase().let { x ->
        when {
            x == "G" || x == "GK" -> "GK"
            x.startsWith("CD") || x.startsWith("CB") || x == "D" -> "CB"
            x in setOf("LB", "RB", "LWB", "RWB") -> "FB"
            x.startsWith("DM") || x == "CDM" -> "DM"
            x.startsWith("AM") || x == "CAM" -> "AM"
            x in setOf("LW", "RW", "LF", "RF", "LM", "RM") -> "W"
            x == "F" || x.startsWith("CF") || x == "ST" || x == "FW" -> "ST"
            else -> "CM"
        }
    }

    fun roleName(r: String) = when (r) {
        "GK" -> "Goalkeeper"; "CB" -> "Centre-back"; "FB" -> "Full-back"; "DM" -> "Defensive midfielder"
        "CM" -> "Midfielder"; "AM" -> "Attacking midfielder"; "W" -> "Winger"; "ST" -> "Striker"; else -> r
    }

    // Typical rates per 90 minutes by role.
    private val FOUL_C = mapOf("GK" to 0.05, "CB" to 1.0, "FB" to 1.0, "DM" to 1.6, "CM" to 1.2, "AM" to 1.1, "W" to 1.0, "ST" to 1.3)
    private val FOUL_S = mapOf("GK" to 0.1, "CB" to 0.6, "FB" to 0.9, "DM" to 1.1, "CM" to 1.2, "AM" to 1.7, "W" to 1.6, "ST" to 1.4)
    private val CARD = mapOf("GK" to 0.05, "CB" to 0.17, "FB" to 0.17, "DM" to 0.25, "CM" to 0.18, "AM" to 0.13, "W" to 0.12, "ST" to 0.13)
    // Share of a team's goals / assists by role (relative weights).
    private val GOAL_W = mapOf("GK" to 0.0, "CB" to 0.04, "FB" to 0.03, "DM" to 0.03, "CM" to 0.06, "AM" to 0.12, "W" to 0.14, "ST" to 0.30)
    private val ASSIST_W = mapOf("GK" to 0.005, "CB" to 0.03, "FB" to 0.09, "DM" to 0.05, "CM" to 0.12, "AM" to 0.20, "W" to 0.20, "ST" to 0.12)
    private const val OWN_GOALS = 0.92       // ~8% of goals are own goals
    private const val ASSISTED = 0.75        // ~3 goals in 4 have an assist

    private fun p1(l: Double) = 1 - exp(-l)

    /** Live: players on the pitch, rest of the match. */
    /**
     * Starting rate per 90 for a player: his position's usual rate, moved toward his own average over
     * his last games (weight n / (n + 3)), when his game log is known.
     */
    private fun own(prior: Double, games: List<PlayerGame>?, pick: (PlayerGame) -> Int): Double {
        val g = games.orEmpty()
        if (g.isEmpty()) return prior
        return (prior * 3 + g.sumOf(pick)) / (3 + g.size)
    }

    fun live(all: List<LiveFootballer>, minute: Double, remaining: Double, lh: Double, la: Double, logs: Map<String, PlayerLog> = emptyMap()): List<PlayerWatch> {
        val fs = all.filter { it.onPitch }
        fun tempo(home: Boolean): Double {   // team's fouls so far vs a typical ~11 per 90
            val done = all.filter { it.home == home }.sumOf { it.foulsCommitted }
            return ((done + 5.5) / (11.0 * minute / 90.0 + 5.5)).coerceIn(0.6, 1.6)
        }
        val tH = tempo(true); val tA = tempo(false)
        fun gW(p: LiveFootballer) = (GOAL_W[role(p.pos)] ?: 0.05) * (1 + 0.5 * p.shots + 1.0 * p.onTarget)
        fun aW(p: LiveFootballer) = ASSIST_W[role(p.pos)] ?: 0.05
        val gSum = mapOf(true to fs.filter { it.home }.sumOf { gW(it) }, false to fs.filter { !it.home }.sumOf { gW(it) })
        val aSum = mapOf(true to fs.filter { it.home }.sumOf { aW(it) }, false to fs.filter { !it.home }.sumOf { aW(it) })
        val rem = remaining / 90.0
        return fs.map { p ->
            val r = role(p.pos)
            val on = (if (p.subMinute != null) minute - p.subMinute else if (p.pos == "SUB") minute / 2 else minute).coerceAtLeast(1.0)
            val g = on / 90.0
            val t = if (p.home) tH else tA
            val hist = p.athleteId?.let { logs[it]?.games }
            val rc = (own(FOUL_C[r] ?: 1.0, hist) { it.fouls } + p.foulsCommitted) / (1.0 + g) * sqrt(t)
            val rs = (own(FOUL_S[r] ?: 1.0, hist) { it.fouled } + p.foulsSuffered) / (1.0 + g)
            val team = if (p.home) lh else la
            val lG = team * OWN_GOALS * (gSum[p.home]?.takeIf { it > 0 }?.let { gW(p) / it } ?: 0.0)
            val lA = team * ASSISTED * (aSum[p.home]?.takeIf { it > 0 }?.let { aW(p) / it } ?: 0.0)
            val lF = rc * rem; val lS = rs * rem
            val lC = if (p.yellow > 0) 0.0 else own(CARD[r] ?: 0.15, hist) { it.yellow } * (1 + 0.3 * p.foulsCommitted) * sqrt(t) * rem
            PlayerWatch(
                p.home, p.team, p.name, r, on.toInt(), p.foulsCommitted, p.foulsSuffered, p.shots, p.onTarget, p.goals, p.yellow > 0,
                pFoul = p1(lF), pFouled = p1(lS), pScore = p1(lG), lGoal = lG, lAssist = lA, lFoul = lF, lFouled = lS, lCard = lC,
                athleteId = p.athleteId,
            )
        }
    }

    /** Before kick-off: confirmed starting line-ups and the pre-match expected goals. */
    fun prematch(lineups: List<LineupInfo>, home: String, away: String, lh: Double, la: Double, logs: Map<String, PlayerLog> = emptyMap()): List<PlayerWatch> {
        if (lineups.isEmpty()) return emptyList()
        val sides = lineups.mapIndexed { i, l ->
            val isHome = when {
                TeamMatcher.same(l.team, home) -> true
                TeamMatcher.same(l.team, away) -> false
                else -> i == 0
            }
            isHome to l
        }
        val out = mutableListOf<PlayerWatch>()
        for ((isHome, l) in sides) {
            val team = if (isHome) lh else la
            val roles = l.starters.map { role(it.position) }
            val gSum = roles.sumOf { GOAL_W[it] ?: 0.05 }
            val aSum = roles.sumOf { ASSIST_W[it] ?: 0.05 }
            l.starters.forEachIndexed { idx, pl ->
                val r = roles[idx]
                val mins = if (r == "GK") 1.0 else 0.85      // outfield starters are often subbed late
                val lG = team * OWN_GOALS * 0.88 * (if (gSum > 0) (GOAL_W[r] ?: 0.05) / gSum else 0.0)   // ~12% of goals come from subs
                val lA = team * ASSISTED * 0.88 * (if (aSum > 0) (ASSIST_W[r] ?: 0.05) / aSum else 0.0)
                val hist = pl.id?.let { logs[it]?.games }
                val lF = own(FOUL_C[r] ?: 1.0, hist) { it.fouls } * mins; val lS = own(FOUL_S[r] ?: 1.0, hist) { it.fouled } * mins
                val lC = own(CARD[r] ?: 0.15, hist) { it.yellow } * mins
                out += PlayerWatch(
                    isHome, l.team.ifBlank { if (isHome) home else away }, pl.name, r, 0, 0, 0, 0, 0, 0, false,
                    pFoul = p1(lF), pFouled = p1(lS), pScore = p1(lG), lGoal = lG, lAssist = lA, lFoul = lF, lFouled = lS, lCard = lC,
                    photo = pl.photo, number = pl.number, athleteId = pl.id,
                )
            }
        }
        return out
    }

    private fun atLeast(l: Double, k: Int): Double {
        var cdf = 0.0; var term = exp(-l)
        for (i in 0 until k) { cdf += term; term *= l / (i + 1) }
        return (1 - cdf).coerceIn(0.0, 1.0)
    }

    /** Market list for one player. [totalRemaining] = expected goals still to come in the match (both teams). */
    fun markets(p: PlayerWatch, totalRemaining: Double, goalsSoFar: Int): List<PlayerMarket> {
        val out = mutableListOf<PlayerMarket>()
        val again = p.goals > 0
        out += PlayerMarket("Goals", if (again) "To score again" else "To score a goal at any time", atLeast(p.lGoal, 1))
        out += PlayerMarket("Goals", if (again) "To score 2 or more more goals" else "To score 2 or more goals", atLeast(p.lGoal, 2))
        out += PlayerMarket("Goals", if (again) "To score 3 or more more goals" else "To score a hat-trick", atLeast(p.lGoal, 3))
        if (totalRemaining > 0 && p.lGoal > 0) for (n in 1..3) {
            // Goal number N exists (enough goals still to come) and is his: each goal is his with chance lGoal / total.
            val share = (p.lGoal / totalRemaining).coerceAtMost(1.0)
            out += PlayerMarket("Goals", "To score goal number ${goalsSoFar + n}", atLeast(totalRemaining, n) * share)
        }
        out += PlayerMarket("Assists", "To provide an assist", atLeast(p.lAssist, 1))
        out += PlayerMarket("Assists", "To score or provide an assist", atLeast(p.lGoal + p.lAssist, 1))
        out += PlayerMarket("Fouls", "To commit a foul", atLeast(p.lFoul, 1))
        out += PlayerMarket("Fouls", "To commit 2 or more fouls", atLeast(p.lFoul, 2))
        out += PlayerMarket("Fouls", "To be fouled", atLeast(p.lFouled, 1))
        out += PlayerMarket("Fouls", "To be fouled 2 or more times", atLeast(p.lFouled, 2))
        if (!p.booked) out += PlayerMarket("Cards", "To be booked", atLeast(p.lCard, 1))
        return out
    }
}
