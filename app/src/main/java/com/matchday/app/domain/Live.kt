package com.matchday.app.domain

import com.matchday.app.data.repo.BookPrice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// =====================================================================
// Live data read from ESPN's match summary (free, no key)
// =====================================================================

data class LiveStatRow(val key: String, val label: String, val home: String, val away: String) {
    val h: Double? get() = home.trim().removeSuffix("%").toDoubleOrNull()
    val a: Double? get() = away.trim().removeSuffix("%").toDoubleOrNull()
}

data class LiveEvent(val clock: String?, val type: String, val team: String?, val text: String?, val scoring: Boolean)

data class LivePlayer(val team: String, val name: String, val line: String, val points: Double?)

data class LiveStats(
    val fetchedAt: Long,
    val rows: List<LiveStatRow>,
    val events: List<LiveEvent>,
    val homeLines: List<Int>,
    val awayLines: List<Int>,
    val period: Int?,
    val clock: String?,
    val detail: String?,
    val players: List<LivePlayer>,
) {
    fun stat(vararg keys: String): Pair<Double, Double>? {
        for (k in keys) {
            val r = rows.firstOrNull { it.key.equals(k, true) } ?: continue
            val h = r.h; val a = r.a
            if (h != null && a != null) return h to a
        }
        return null
    }

    companion object {
        private fun JsonElement?.obj() = this as? JsonObject
        private fun JsonElement?.arr() = this as? JsonArray
        private fun JsonElement?.str() = (this as? JsonPrimitive)?.content
        private fun JsonElement?.dbl() = (this as? JsonPrimitive)?.content?.toDoubleOrNull()

        /** Reads team stats, key events, line scores and top players. Every field is optional. */
        fun parse(raw: JsonObject, home: String, away: String, homeId: String?, awayId: String?): LiveStats {
            val now = System.currentTimeMillis()
            val comp = raw["header"].obj()?.get("competitions").arr()?.firstOrNull().obj()
            val competitors = comp?.get("competitors").arr().orEmpty().mapNotNull { it.obj() }
            fun sideOf(o: JsonObject?): String? {
                val ha = o?.get("homeAway").str()
                if (ha == "home" || ha == "away") return ha
                val t = o?.get("team").obj()
                val id = t?.get("id").str()
                val name = t?.get("displayName").str() ?: o?.get("athlete").obj()?.get("displayName").str()
                return when {
                    id != null && id == homeId -> "home"
                    id != null && id == awayId -> "away"
                    name != null && TeamMatcher.same(name, home) -> "home"
                    name != null && TeamMatcher.same(name, away) -> "away"
                    else -> null
                }
            }
            fun lines(side: String) = competitors.firstOrNull { sideOf(it) == side }?.get("linescores").arr().orEmpty()
                .mapNotNull { l -> (l.obj()?.get("displayValue") ?: l.obj()?.get("value")).dbl()?.toInt() }
            val status = comp?.get("status").obj()

            // Team statistics (boxscore)
            val teams = raw["boxscore"].obj()?.get("teams").arr().orEmpty().mapNotNull { it.obj() }
            val hTeam = teams.firstOrNull { sideOf(it) == "home" } ?: teams.getOrNull(1)
            val aTeam = teams.firstOrNull { sideOf(it) == "away" } ?: teams.getOrNull(0)
            fun stats(t: JsonObject?) = t?.get("statistics").arr().orEmpty().mapNotNull { s ->
                val o = s.obj() ?: return@mapNotNull null
                val name = o["name"].str() ?: o["abbreviation"].str() ?: return@mapNotNull null
                Triple(name, o["label"].str() ?: name, o["displayValue"].str() ?: o["value"].str() ?: return@mapNotNull null)
            }
            val hs = stats(hTeam); val asx = stats(aTeam)
            val rows = hs.mapNotNull { (k, label, hv) ->
                val av = asx.firstOrNull { it.first == k }?.third ?: return@mapNotNull null
                LiveStatRow(k, label, hv, av)
            }

            // Key events (football goals, cards, substitutions, VAR, penalties)
            val events = raw["keyEvents"].arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                LiveEvent(
                    clock = o["clock"].obj()?.get("displayValue").str(),
                    type = o["type"].obj()?.get("text").str() ?: "Event",
                    team = o["team"].obj()?.get("displayName").str(),
                    text = o["text"].str() ?: o["shortText"].str(),
                    scoring = o["scoringPlay"].str() == "true",
                )
            }.takeLast(40)

            // Top players (basketball/hockey box scores): first stat block, highest points first.
            val players = mutableListOf<LivePlayer>()
            raw["boxscore"].obj()?.get("players").arr().orEmpty().forEach { tp ->
                val t = tp.obj() ?: return@forEach
                val teamName = t["team"].obj()?.get("displayName").str() ?: return@forEach
                val block = t["statistics"].arr()?.firstOrNull().obj() ?: return@forEach
                val labels = block["labels"].arr().orEmpty().map { it.str() ?: "" }
                val ptsIdx = labels.indexOfFirst { it.equals("PTS", true) || it.equals("G", true) }
                block["athletes"].arr().orEmpty().forEach { a ->
                    val ao = a.obj() ?: return@forEach
                    val name = ao["athlete"].obj()?.get("displayName").str() ?: return@forEach
                    val st = ao["stats"].arr().orEmpty().map { it.str() ?: "" }
                    if (st.isEmpty()) return@forEach
                    val line = labels.zip(st).filter { (l, v) -> l.isNotBlank() && v.isNotBlank() && l.uppercase() in setOf("MIN", "PTS", "REB", "AST", "FG", "3PT", "PF", "G", "A", "SOG", "+/-", "TOI") }
                        .joinToString(" · ") { "${it.first} ${it.second}" }
                    players += LivePlayer(teamName, name, line, st.getOrNull(ptsIdx)?.toDoubleOrNull())
                }
            }
            return LiveStats(
                fetchedAt = now, rows = rows, events = events, homeLines = lines("home"), awayLines = lines("away"),
                period = status?.get("period").dbl()?.toInt(), clock = status?.get("displayClock").str(),
                detail = status?.get("type").obj()?.get("detail").str(),
                players = players.sortedByDescending { it.points ?: -1.0 }.take(10),
            )
        }
    }
}

// =====================================================================
// Live engine: projections, intelligence and ranked live markets
// =====================================================================

data class LiveProjection(val label: String, val value: String, val note: String? = null)

data class LiveAnalysis(
    val computedAt: Long,
    val score: String,
    val clockLabel: String,
    val actual: List<LiveProjection>,       // measured facts (xG only if the source provides it)
    val projections: List<LiveProjection>,  // model estimates (clearly labelled)
    val intelligence: List<String>,
    val markets: List<MarketPick>,
    val top: List<MarketPick>,
    val noBet: String?,
    val unavailable: List<String>,
    val oddsNote: String,
)

/** Pre-match context the live engine starts from (from the existing analysis). */
data class LivePrior(val expHome: Double?, val expAway: Double?, val holdHome: Double? = null, val holdAway: Double? = null)

object LiveEngine {
    private fun pct(x: Double) = "${Math.round(x * 100)}%"
    private fun f1(x: Double) = String.format("%.1f", x)
    private fun fmt(p: Double) = if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()
    private fun poisson(l: Double, k: Int): Double { var p = exp(-l); for (i in 1..k) p *= l / i; return p }
    private fun pOver(l: Double, need: Int): Double { var c = 0.0; for (k in 0 until need) c += poisson(l, k); return 1 - c } // P(X >= need)

    private fun phi(z: Double): Double {
        val t = 1 / (1 + 0.2316419 * abs(z))
        val d = 0.3989423 * exp(-z * z / 2)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (z > 0) 1 - p else p
    }

    private class Out(val prices: List<BookPrice>?, val source: String?) {
        val list = mutableListOf<MarketPick>()
        fun add(fam: MarketFamily, m: String, sel: String, point: Double?, label: String, p: Double, ev: List<String>) {
            if (p.isNaN()) return
            val o = prices?.firstOrNull { it.market == m && it.sel == sel && (point == null || it.point == point) }?.price
            list += MarketPick(fam, m, sel, point, label, p.coerceIn(0.001, 0.999), p, null, 0, o, if (o != null) source else null, null, ev,
                if (o == null) listOf("Market unavailable: no current live 1xBet price for this selection.") else emptyList())
        }
        fun ranked(): Pair<List<MarketPick>, List<MarketPick>> {
            val all = list.sortedByDescending { it.probability }
            val r = all.filter { it.probability < MarketEngine.MAX_PROB && (it.odds == null || it.odds >= 1.05) }
            val top = mutableListOf<MarketPick>()
            for (c in r) {
                if (c.probability < 0.65 || top.size == 3) break
                if (top.any { it.family == c.family }) continue
                if ((c.edge ?: 0.0) < -0.08) continue
                top += c
            }
            return all to top
        }
    }

    fun analyze(item: MatchItem, s: LiveStats?, prior: LivePrior, livePrices: List<BookPrice>?, priceSource: String?, previous: List<LiveStats>): LiveAnalysis? {
        val hs = item.homeScore?.trim()?.toIntOrNull() ?: s?.homeLines?.sum()
        val asc = item.awayScore?.trim()?.toIntOrNull() ?: s?.awayLines?.sum()
        return when (item.competition.sport) {
            SportType.FOOTBALL -> football(item, s, prior, livePrices, priceSource, previous, hs ?: 0, asc ?: 0)
            SportType.BASKETBALL -> basketball(item, s, prior, livePrices, priceSource, hs ?: 0, asc ?: 0, previous)
            SportType.HOCKEY -> hockey(item, s, prior, livePrices, priceSource, hs ?: 0, asc ?: 0)
            SportType.TENNIS -> tennis(item, s, prior, livePrices, priceSource)
            else -> null
        }
    }

    private fun oddsNote(prices: List<BookPrice>?) =
        if (prices == null) "1xBet live odds unavailable (load them in the Odds tab with your odds key). Probabilities are model estimates."
        else "Live 1xBet odds attached where 1xBet offers the market; other selections show \"Market unavailable\"."

    /** Football minute from the status text ("67'", "45'+2'", "HT"). */
    private fun footballMinute(detail: String?, clock: String?): Double? {
        val d = (clock ?: detail ?: return null).trim()
        if (d.equals("HT", true) || d.contains("half", true) && d.contains("time", true)) return 45.0
        val m = Regex("(\\d+)'?\\s*(\\+\\s*(\\d+))?").find(d) ?: return null
        val base = m.groupValues[1].toDoubleOrNull() ?: return null
        val extra = m.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
        return min(base + extra, 120.0)
    }

    // ------------------------------------------------------------- football
    private fun football(item: MatchItem, s: LiveStats?, prior: LivePrior, prices: List<BookPrice>?, src: String?, prev: List<LiveStats>, hg: Int, ag: Int): LiveAnalysis {
        val o = Out(prices, src)
        val minute = footballMinute(item.statusDetail, s?.clock) ?: 0.0
        val isHT = item.statusDetail?.contains("HT") == true || item.statusDetail?.contains("Halftime", true) == true
        val remaining = max(1.0, (if (minute <= 45 && !isHT) 94.0 else 95.0) - minute)
        val baseH = prior.expHome ?: 1.40; val baseA = prior.expAway ?: 1.15
        val assumed = prior.expHome == null

        // Pressure from live stats: shots on target 55%, shots 25%, possession 10%, corners 10%.
        val sot = s?.stat("shotsOnTarget", "onTargetShots")
        val shots = s?.stat("totalShots", "shotsTotal")
        val poss = s?.stat("possessionPct")
        val corners = s?.stat("wonCorners", "cornerKicks", "corners")
        val yellow = s?.stat("yellowCards")
        val red = s?.stat("redCards")
        val fouls = s?.stat("foulsCommitted", "fouls")
        fun share(p: Pair<Double, Double>?, k: Double = 1.0) = p?.let { (it.first + k) / (it.first + it.second + 2 * k) }
        var w = 0.0; var acc = 0.0
        share(sot)?.let { acc += 0.55 * it; w += 0.55 }
        share(shots)?.let { acc += 0.25 * it; w += 0.25 }
        poss?.let { acc += 0.10 * (it.first / max(1.0, it.first + it.second)); w += 0.10 }
        share(corners)?.let { acc += 0.10 * it; w += 0.10 }
        val pressure = if (w > 0) acc / w else 0.5
        val influence = min(1.0, minute / 45.0)        // live stats matter more as the match goes on
        val adjH = 1 + influence * (pressure - 0.5) * 1.2
        val adjA = 1 + influence * (0.5 - pressure) * 1.2
        var lh = baseH * remaining / 90.0 * adjH
        var la = baseA * remaining / 90.0 * adjA
        if (hg < ag) { lh *= 1.12; la *= 0.92 } else if (hg > ag) { lh *= 0.92; la *= 1.12 }
        val rh = red?.first?.toInt() ?: 0; val ra = red?.second?.toInt() ?: 0
        if (rh > ra) { lh *= 0.75; la *= 1.25 } else if (ra > rh) { lh *= 1.25; la *= 0.75 }
        val lt = lh + la

        // Final-score distribution = current score + remaining goals.
        val g = PredictionEngine.grid(lh, la)
        fun p(pred: (Int, Int) -> Boolean): Double { var t = 0.0; for (x in g.indices) for (y in g[x].indices) if (pred(hg + x, ag + y)) t += g[x][y]; return t }
        val pH = p { x, y -> x > y }; val pD = p { x, y -> x == y }; val pA = 1 - pH - pD
        val any = 1 - exp(-lt)
        val ev = mutableListOf("Score ${hg}-${ag} at ${minute.toInt()}': projected remaining goals ${item.homeTeam} ${f1(lh)}, ${item.awayTeam} ${f1(la)}" + (if (assumed) " (league-average baseline: no pre-match model)." else "."))
        if (sot != null) ev += "Shots on target ${sot.first.toInt()}-${sot.second.toInt()}${shots?.let { ", shots ${it.first.toInt()}-${it.second.toInt()}" } ?: ""}${poss?.let { ", possession ${it.first.toInt()}%-${it.second.toInt()}%" } ?: ""}."

        val R = MarketFamily.RESULT
        o.add(R, "1X2", "HOME", null, "${item.homeTeam} to win (live)", pH, ev)
        o.add(R, "1X2", "DRAW", null, "Draw (live)", pD, ev)
        o.add(R, "1X2", "AWAY", null, "${item.awayTeam} to win (live)", pA, ev)
        o.add(MarketFamily.DOUBLE_CHANCE, "DC", "1X", null, "Double chance 1X", pH + pD, ev)
        o.add(MarketFamily.DOUBLE_CHANCE, "DC", "X2", null, "Double chance X2", pA + pD, ev)
        o.add(MarketFamily.DOUBLE_CHANCE, "DC", "12", null, "Double chance 12", pH + pA, ev)
        if (pH + pA > 0) {
            o.add(MarketFamily.DOUBLE_CHANCE, "DNB", "DNB_HOME", null, "${item.homeTeam} draw no bet", pH / (pH + pA), ev)
            o.add(MarketFamily.DOUBLE_CHANCE, "DNB", "DNB_AWAY", null, "${item.awayTeam} draw no bet", pA / (pH + pA), ev)
        }
        o.add(MarketFamily.OTHER, "NEXT", "HOME", null, "Next goal: ${item.homeTeam}", lh / lt * any, ev)
        o.add(MarketFamily.OTHER, "NEXT", "AWAY", null, "Next goal: ${item.awayTeam}", la / lt * any, ev)
        o.add(MarketFamily.OTHER, "NEXT", "NONE", null, "No more goals", 1 - any, ev)
        o.add(MarketFamily.TOTALS, "MORE", "YES", null, "Another goal in the match", any, ev)
        val cur = hg + ag
        listOf(0.5, 1.5, 2.5, 3.5, 4.5, 5.5).filter { it > cur }.forEach { line ->
            val over = p { x, y -> x + y > line }
            o.add(MarketFamily.TOTALS, "OU", "OVER", line, "Over ${fmt(line)} goals", over, ev)
            o.add(MarketFamily.TOTALS, "OU", "UNDER", line, "Under ${fmt(line)} goals", 1 - over, ev)
        }
        for ((name, home, have) in listOf(Triple(item.homeTeam, true, hg), Triple(item.awayTeam, false, ag))) {
            listOf(0.5, 1.5, 2.5).filter { it > have }.forEach { line ->
                val over = p { x, y -> (if (home) x else y) > line }
                val k = if (home) "HOME" else "AWAY"
                o.add(MarketFamily.TEAM_GOALS, "TT", "${k}_OVER", line, "$name Over ${fmt(line)}", over, ev)
                o.add(MarketFamily.TEAM_GOALS, "TT", "${k}_UNDER", line, "$name Under ${fmt(line)}", 1 - over, ev)
            }
        }
        val btts = p { x, y -> x > 0 && y > 0 }
        o.add(MarketFamily.BTTS, "BTTS", "YES", null, "BTTS Yes", btts, ev)
        o.add(MarketFamily.BTTS, "BTTS", "NO", null, "BTTS No", 1 - btts, ev)
        if (ag == 0) o.add(MarketFamily.OTHER, "CS", "HOME", null, "${item.homeTeam} clean sheet", exp(-la), ev)
        if (hg == 0) o.add(MarketFamily.OTHER, "CS", "AWAY", null, "${item.awayTeam} clean sheet", exp(-lh), ev)
        // Correct score (top 4)
        val cs = mutableListOf<Pair<String, Double>>()
        for (x in 0..5) for (y in 0..5) cs += "${hg + x}-${ag + y}" to g[x][y]
        cs.sortedByDescending { it.second }.take(4).forEach { (k, v) -> o.add(MarketFamily.OTHER, "CS_EXACT", k, null, "Correct score $k", v, ev) }
        // Half-time / full-time (only before half-time, when HT is still open)
        if (minute < 45 && !isHT) {
            val h1 = max(0.5, 47.0 - minute) / remaining
            val g1 = PredictionEngine.grid(lh * h1, la * h1)
            val g2 = PredictionEngine.grid(lh * (1 - h1), la * (1 - h1))
            val htft = HashMap<String, Double>()
            for (x1 in g1.indices) for (y1 in g1[x1].indices) {
                val ht = (hg + x1).compareTo(ag + y1)
                for (x2 in g2.indices) for (y2 in g2[x2].indices) {
                    val ft = (hg + x1 + x2).compareTo(ag + y1 + y2)
                    val key = "${res(ht)}/${res(ft)}"
                    htft[key] = (htft[key] ?: 0.0) + g1[x1][y1] * g2[x2][y2]
                }
            }
            htft.entries.sortedByDescending { it.value }.take(3).forEach { (k, v) ->
                o.add(MarketFamily.FIRST_HALF, "HTFT", k, null, "HT/FT ${k.replace("H", item.homeTeam).replace("A", item.awayTeam).replace("D", "Draw")}", v, ev)
            }
            val htH = (g1.indices).sumOf { x -> g1[x].indices.filter { y -> hg + x > ag + y }.sumOf { y -> g1[x][y] } }
            val htD = (g1.indices).sumOf { x -> g1[x].indices.filter { y -> hg + x == ag + y }.sumOf { y -> g1[x][y] } }
            o.add(MarketFamily.FIRST_HALF, "1H", "H1_HOME", null, "Half-time: ${item.homeTeam}", htH, ev)
            o.add(MarketFamily.FIRST_HALF, "1H", "H1_DRAW", null, "Half-time: Draw", htD, ev)
            o.add(MarketFamily.FIRST_HALF, "1H", "H1_AWAY", null, "Half-time: ${item.awayTeam}", 1 - htH - htD, ev)
        }

        // Corners and cards: projected from this match's own rate so far (needs 15+ minutes).
        val proj = mutableListOf<LiveProjection>()
        proj += LiveProjection("Projected remaining goals (estimate)", "${item.homeTeam} ${f1(lh)} · ${item.awayTeam} ${f1(la)}", "Not xG: a forecast of goals still to come.")
        proj += LiveProjection("Projected final total goals (estimate)", f1(cur + lt))
        proj += LiveProjection("Probability of another goal", pct(any))
        proj += LiveProjection("Most likely to score next", if (lh >= la) "${item.homeTeam} (${pct(lh / lt * any)})" else "${item.awayTeam} (${pct(la / lt * any)})")
        if (corners != null && minute >= 15) {
            val rateH = corners.first / minute; val rateA = corners.second / minute
            val remC = (rateH + rateA) * remaining
            val totC = corners.first + corners.second
            proj += LiveProjection("Projected corners (estimate)", "${item.homeTeam} ${f1(corners.first + rateH * remaining)} · ${item.awayTeam} ${f1(corners.second + rateA * remaining)} · total ${f1(totC + remC)}", "From this match's corner rate so far.")
            val cev = listOf("Corners ${corners.first.toInt()}-${corners.second.toInt()} after ${minute.toInt()}': about ${f1((rateH + rateA) * 10)} per 10 minutes.")
            listOf(totC + 0.5, totC + 1.5, totC + 2.5, totC + 3.5).forEach { line ->
                val need = (line - totC + 0.5).toInt()
                o.add(MarketFamily.OTHER, "CORNERS", "OVER", line, "Total corners Over ${fmt(line)}", pOver(remC, need), cev)
                o.add(MarketFamily.OTHER, "CORNERS", "UNDER", line, "Total corners Under ${fmt(line)}", 1 - pOver(remC, need), cev)
            }
            if (rateH + rateA > 0) {
                val anyC = 1 - exp(-remC)
                o.add(MarketFamily.OTHER, "NEXTCORNER", "HOME", null, "Next corner: ${item.homeTeam}", rateH / (rateH + rateA) * anyC, cev)
                o.add(MarketFamily.OTHER, "NEXTCORNER", "AWAY", null, "Next corner: ${item.awayTeam}", rateA / (rateH + rateA) * anyC, cev)
            }
        }
        if (yellow != null && minute >= 15) {
            val y = yellow.first + yellow.second
            val remY = max(0.02, y / minute) * remaining
            proj += LiveProjection("Projected yellow cards (estimate)", f1(y + remY), "From this match's card rate so far.")
            val yev = listOf("Yellow cards ${yellow.first.toInt()}-${yellow.second.toInt()}${fouls?.let { ", fouls ${it.first.toInt()}-${it.second.toInt()}" } ?: ""}.")
            listOf(y + 0.5, y + 1.5, y + 2.5).forEach { line ->
                val need = (line - y + 0.5).toInt()
                o.add(MarketFamily.OTHER, "CARDS", "OVER", line, "Total yellow cards Over ${fmt(line)}", pOver(remY, need), yev)
                o.add(MarketFamily.OTHER, "CARDS", "UNDER", line, "Total yellow cards Under ${fmt(line)}", 1 - pOver(remY, need), yev)
            }
        }

        // Actual (measured) figures
        val actual = mutableListOf<LiveProjection>()
        actual += LiveProjection("Expected goals (xG)", "Data unavailable", "The free feed doesn't publish xG; nothing is substituted.")
        listOf("possessionPct" to "Possession", "totalShots" to "Shots", "shotsOnTarget" to "Shots on target", "wonCorners" to "Corners",
            "foulsCommitted" to "Fouls", "yellowCards" to "Yellow cards", "redCards" to "Red cards", "saves" to "Saves", "offsides" to "Offsides").forEach { (k, l) ->
            s?.rows?.firstOrNull { it.key.equals(k, true) }?.let { actual += LiveProjection(l, "${it.home} – ${it.away}") }
        }

        // Intelligence
        val intel = mutableListOf<String>()
        if (s == null || w == 0.0) intel += "Live team statistics aren't available for this match yet, so pressure can't be measured; projections use the score and time only."
        else {
            val lead = if (pressure >= 0.5) item.homeTeam else item.awayTeam
            val other = if (pressure >= 0.5) item.awayTeam else item.homeTeam
            val strength = abs(pressure - 0.5)
            intel += when {
                strength < 0.05 -> "Evenly balanced: neither side has a clear edge in shots, shots on target, possession and corners."
                strength < 0.15 -> "$lead are slightly on top (pressure index ${pct(max(pressure, 1 - pressure))}); $other are holding their own."
                else -> "$lead are dominating (pressure index ${pct(max(pressure, 1 - pressure))}); $other are defending under pressure."
            }
            if (sot != null && shots != null) {
                val qh = if (shots.first > 0) sot.first / shots.first else 0.0
                val qa = if (shots.second > 0) sot.second / shots.second else 0.0
                intel += "Chance quality (share of shots on target): ${item.homeTeam} ${pct(qh)}, ${item.awayTeam} ${pct(qa)} - ${if (qh > qa + 0.1) item.homeTeam else if (qa > qh + 0.1) item.awayTeam else "neither side"} creating the better openings."
            }
            // Momentum: compare with a snapshot about 10 minutes old
            val old = prev.firstOrNull { System.currentTimeMillis() - it.fetchedAt in 8 * 60_000L..20 * 60_000L }
            val oldShots = old?.stat("totalShots", "shotsTotal"); val oldSot = old?.stat("shotsOnTarget")
            if (old != null && oldShots != null && shots != null) {
                val dh = shots.first - oldShots.first; val da = shots.second - oldShots.second
                val dsh = (sot?.first ?: 0.0) - (oldSot?.first ?: 0.0); val dsa = (sot?.second ?: 0.0) - (oldSot?.second ?: 0.0)
                intel += "Last ~10 minutes: shots ${dh.toInt()}-${da.toInt()}, on target ${dsh.toInt()}-${dsa.toInt()}. " + when {
                    dh + da <= 1 -> "The game has slowed down: becoming more cautious."
                    dh >= da + 3 -> "Momentum is with ${item.homeTeam}."
                    da >= dh + 3 -> "Momentum is with ${item.awayTeam}."
                    else -> "Momentum is even."
                }
            } else intel += "Recent momentum needs about 10 minutes of observation on this page (snapshots are taken every 30 seconds)."
            if (hg != ag) {
                val trailing = if (hg < ag) item.homeTeam else item.awayTeam
                val chasing = if (hg < ag) pressure > 0.55 else pressure < 0.45
                intel += if (chasing) "$trailing are pushing for an equaliser and creating pressure: a change in the result is possible (${pct(if (hg < ag) pD + pH else pD + pA)} they avoid defeat)."
                else "$trailing are trailing but not creating much: the current result looks fairly safe (${pct(if (hg > ag) pH else pA)} it stays a win for the leader)."
            }
        }
        if (rh + ra > 0) intel += "Red card${if (rh + ra > 1) "s" else ""}: ${item.homeTeam} $rh, ${item.awayTeam} $ra - goal expectations adjusted (-25% / +25%)."
        intel += "Another goal is ${if (any >= 0.65) "likely" else if (any >= 0.4) "about as likely as not" else "unlikely"} (${pct(any)})."
        intel += "Penalty probability: not estimated (no reliable model or data in free sources)."

        val (all, top) = o.ranked()
        return LiveAnalysis(
            System.currentTimeMillis(), "$hg-$ag", "${minute.toInt()}'", actual, proj, intel, all, top,
            if (top.isEmpty()) "No live selection reaches 65% at a fair price: no bet recommended right now." else null,
            listOf(
                "Penalty, player (goalscorer, shots, assists, cards) and booking-points markets: no free data or odds source.",
                "Expected goals (xG), big chances, dangerous attacks: not published by the free feed.",
                "Corner and card lines are estimates from this match's own rates; 1xBet corner/card prices aren't available through the odds source (Market unavailable).",
            ),
            oddsNote(prices),
        )
    }

    private fun res(c: Int) = if (c > 0) "H" else if (c < 0) "A" else "D"

    // ----------------------------------------------------------- basketball
    private fun basketball(item: MatchItem, s: LiveStats?, prior: LivePrior, prices: List<BookPrice>?, src: String?, hp: Int, ap: Int, prev: List<LiveStats>): LiveAnalysis {
        val o = Out(prices, src)
        val wnba = item.competition.key.endsWith("wnba"); val college = item.competition.key.contains("college")
        val periods = if (college) 2 else 4
        val plen = if (college) 20.0 else if (wnba) 10.0 else 12.0
        val full = periods * plen
        val period = s?.period ?: Regex("(\\d)(st|nd|rd|th)").find(item.statusDetail ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val clock = parseClock(s?.clock ?: Regex("(\\d+:\\d+)").find(item.statusDetail ?: "")?.value)
        val elapsed = min(full, (period - 1) * plen + (plen - (clock ?: plen)))
        val rem = max(0.5, full - elapsed)
        val preTotal = (prior.expHome ?: 0.0) + (prior.expAway ?: 0.0)
        val preMargin = (prior.expHome ?: 0.0) - (prior.expAway ?: 0.0)
        val liveRate = if (elapsed > 3) (hp + ap) / elapsed else null
        val preRate = if (preTotal > 0) preTotal / full else null
        val wLive = min(0.8, elapsed / full)
        val rate = when { liveRate != null && preRate != null -> wLive * liveRate + (1 - wLive) * preRate; liveRate != null -> liveRate; preRate != null -> preRate; else -> 4.5 }
        val remTotal = rate * rem
        val remMargin = if (preTotal > 0) preMargin * rem / full else 0.0
        val sdM = 12.5 * sqrt(rem / full); val sdT = 17.5 * sqrt(rem / full)
        val margin = hp - ap + remMargin; val total = hp + ap + remTotal
        val pH = phi(margin / max(0.5, sdM))
        val ev = listOf("Score $hp-$ap with ${f1(rem)} minutes left: projected final ${f1((total + margin) / 2)}–${f1((total - margin) / 2)} (scoring rate ${f1(rate * plen)} per ${if (college) "half" else "quarter"}).")
        o.add(MarketFamily.RESULT, "ML", "HOME", null, "${item.homeTeam} to win (live)", pH, ev)
        o.add(MarketFamily.RESULT, "ML", "AWAY", null, "${item.awayTeam} to win (live)", 1 - pH, ev)
        val sprLines = (prices.orEmpty().filter { it.market == "SPR" && it.sel == "HOME" }.mapNotNull { it.point } + listOf(Math.floor(-margin) + 0.5 - 4, Math.floor(-margin) + 0.5 + 4)).distinct()
        sprLines.forEach { l -> val pc = phi((margin + l) / max(0.5, sdM)); o.add(MarketFamily.HANDICAP, "SPR", "HOME", l, "${item.homeTeam} ${if (l > 0) "+" else ""}${fmt(l)}", pc, ev); o.add(MarketFamily.HANDICAP, "SPR", "AWAY", -l, "${item.awayTeam} ${if (-l > 0) "+" else ""}${fmt(-l)}", 1 - pc, ev) }
        val totLines = (prices.orEmpty().filter { it.market == "TOT" }.mapNotNull { it.point } + listOf(Math.floor(total) + 0.5 - 8, Math.floor(total) + 0.5 + 8)).distinct()
        totLines.forEach { l -> val po = 1 - phi((l - total) / max(0.5, sdT)); o.add(MarketFamily.POINTS, "TOT", "OVER", l, "Over ${fmt(l)} points", po, ev); o.add(MarketFamily.POINTS, "TOT", "UNDER", l, "Under ${fmt(l)} points", 1 - po, ev) }
        for ((name, cur, sign, key) in listOf(Quad4(item.homeTeam, hp, 1, "HOME"), Quad4(item.awayTeam, ap, -1, "AWAY"))) {
            val mean = cur + remTotal / 2 + sign * remMargin / 2
            val l = Math.floor(mean) + 0.5 - 5
            val po = 1 - phi((l - mean) / max(0.5, 11.0 * sqrt(rem / full)))
            o.add(MarketFamily.TEAM_POINTS, "TT", "${key}_OVER", l, "$name Over ${fmt(l)}", po, ev)
        }
        val nextHome = if (hp + ap > 0) (hp.toDouble() / (hp + ap)) * 0.5 + 0.25 else 0.5
        o.add(MarketFamily.OTHER, "NEXT", "HOME", null, "Next team to score: ${item.homeTeam}", nextHome, ev + "Based on each team's share of points so far (close to a coin flip).")
        o.add(MarketFamily.OTHER, "NEXT", "AWAY", null, "Next team to score: ${item.awayTeam}", 1 - nextHome, ev)
        // Current quarter winner
        val qh = s?.homeLines?.getOrNull(period - 1); val qa = s?.awayLines?.getOrNull(period - 1)
        if (qh != null && qa != null && clock != null) {
            val qRem = clock
            val qMargin = qh - qa + remMargin * qRem / rem
            val pq = phi(qMargin / max(0.5, 12.5 * sqrt(qRem / full)))
            val nm = if (college) "half" else "quarter"
            o.add(MarketFamily.PERIODS, "QCUR", "HOME", null, "Current $nm (${period}): ${item.homeTeam}", pq, ev)
            o.add(MarketFamily.PERIODS, "QCUR", "AWAY", null, "Current $nm (${period}): ${item.awayTeam}", 1 - pq, ev)
        }
        val actual = mutableListOf<LiveProjection>()
        if (s != null && s.homeLines.isNotEmpty()) actual += LiveProjection("Points by ${if (college) "half" else "quarter"}", "${item.homeTeam} ${s.homeLines.joinToString(" | ")} · ${item.awayTeam} ${s.awayLines.joinToString(" | ")}")
        s?.rows?.filter { it.key in setOf("fieldGoalPct", "threePointFieldGoalPct", "freeThrowPct", "totalRebounds", "assists", "turnovers", "fouls", "steals", "blocks", "fastBreakPoints", "pointsInPaint") }
            ?.forEach { actual += LiveProjection(it.label, "${it.home} – ${it.away}") }
        s?.players?.take(6)?.forEach { actual += LiveProjection("${it.name} (${it.team})", it.line) }
        val intel = mutableListOf<String>()
        val fg = s?.stat("fieldGoalPct"); val tp = s?.stat("threePointFieldGoalPct"); val to = s?.stat("turnovers"); val rb = s?.stat("totalRebounds")
        if (fg != null) intel += "Shooting: FG ${f1(fg.first)}% vs ${f1(fg.second)}%${tp?.let { ", 3PT ${f1(it.first)}% vs ${f1(it.second)}%" } ?: ""}: ${if (fg.first > fg.second + 3) "${item.homeTeam} shooting better" else if (fg.second > fg.first + 3) "${item.awayTeam} shooting better" else "similar efficiency"}."
        if (rb != null && to != null) intel += "Rebounds ${rb.first.toInt()}-${rb.second.toInt()}, turnovers ${to.first.toInt()}-${to.second.toInt()}."
        val old = prev.firstOrNull { System.currentTimeMillis() - it.fetchedAt in 4 * 60_000L..15 * 60_000L }
        if (old != null && old.homeLines.isNotEmpty()) {
            val dh = hp - old.homeLines.sum(); val da = ap - old.awayLines.sum()
            intel += "Recent run (last few minutes on this page): ${item.homeTeam} $dh-$da ${item.awayTeam}${if (abs(dh - da) >= 6) " - ${if (dh > da) item.homeTeam else item.awayTeam} on a run" else ""}."
        }
        intel += "Pace: ${f1(rate * 48)} points per 48 minutes (live blend); projected final total ${f1(total)}."
        intel += "${if (pH >= 0.5) item.homeTeam else item.awayTeam} are ${pct(max(pH, 1 - pH))} to win from here."
        val (all, top) = o.ranked()
        return LiveAnalysis(System.currentTimeMillis(), "$hp-$ap", "${if (college) "H" else "Q"}$period ${s?.clock ?: ""}".trim(), actual,
            listOf(LiveProjection("Projected final score (estimate)", "${item.homeTeam} ${f1((total + margin) / 2)} – ${f1((total - margin) / 2)} ${item.awayTeam}"),
                LiveProjection("Projected remaining points (estimate)", f1(remTotal))),
            intel, all, top, if (top.isEmpty()) "No live selection reaches 65% at a fair price: no bet recommended right now." else null,
            listOf("Player points/rebounds/assists and three-pointer markets: no free odds source.", "Offensive/defensive efficiency ratings: not in the free feed."), oddsNote(prices))
    }

    private data class Quad4(val a: String, val b: Int, val c: Int, val d: String)

    private fun parseClock(c: String?): Double? {
        val m = Regex("(\\d+):(\\d+)").find(c ?: return null) ?: return c.toDoubleOrNull()?.div(60)
        return m.groupValues[1].toDouble() + m.groupValues[2].toDouble() / 60
    }

    // --------------------------------------------------------------- hockey
    private fun hockey(item: MatchItem, s: LiveStats?, prior: LivePrior, prices: List<BookPrice>?, src: String?, hg: Int, ag: Int): LiveAnalysis {
        val o = Out(prices, src)
        val period = s?.period ?: Regex("(\\d)(st|nd|rd)").find(item.statusDetail ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val clock = parseClock(s?.clock ?: Regex("(\\d+:\\d+)").find(item.statusDetail ?: "")?.value) ?: 20.0
        val inOT = period > 3
        val rem = if (inOT) 0.0 else max(0.2, (3 - period) * 20 + clock)
        val baseH = prior.expHome ?: 3.0; val baseA = prior.expAway ?: 2.8
        val sog = s?.stat("shotsTotal", "shots", "shotsOnGoal")
        val shareH = sog?.let { (it.first + 2) / (it.first + it.second + 4) } ?: 0.5
        val infl = min(1.0, (60 - rem) / 30)
        var lh = baseH * rem / 60 * (1 + infl * (shareH - 0.5) * 1.0)
        var la = baseA * rem / 60 * (1 + infl * (0.5 - shareH) * 1.0)
        if (hg < ag && rem < 10) lh *= 1.4  // pulled goalie / late push
        if (ag < hg && rem < 10) la *= 1.4
        val g = PredictionEngine.grid(max(0.01, lh), max(0.01, la))
        fun p(pred: (Int, Int) -> Boolean): Double { var t = 0.0; for (x in g.indices) for (y in g[x].indices) if (pred(hg + x, ag + y)) t += g[x][y]; return t }
        val pW = if (inOT) 0.0 else p { x, y -> x > y }; val pD = if (inOT) 1.0 else p { x, y -> x == y }; val pL = 1 - pW - pD
        val ot = baseH / (baseH + baseA)
        val ev = listOf("Score $hg-$ag, ${if (inOT) "overtime/shootout" else "${f1(rem)} minutes of regulation left"}: projected remaining goals ${f1(lh)}-${f1(la)}${sog?.let { "; shots on goal ${it.first.toInt()}-${it.second.toInt()}" } ?: ""}.")
        o.add(MarketFamily.RESULT, "ML", "HOME", null, "${item.homeTeam} to win (incl. OT/SO)", pW + pD * ot, ev)
        o.add(MarketFamily.RESULT, "ML", "AWAY", null, "${item.awayTeam} to win (incl. OT/SO)", pL + pD * (1 - ot), ev)
        if (!inOT) {
            o.add(MarketFamily.RESULT, "REG", "HOME", null, "Regulation: ${item.homeTeam}", pW, ev)
            o.add(MarketFamily.RESULT, "REG", "DRAW", null, "Regulation: Draw (goes to OT)", pD, ev)
            o.add(MarketFamily.RESULT, "REG", "AWAY", null, "Regulation: ${item.awayTeam}", pL, ev)
            val any = 1 - exp(-(lh + la))
            o.add(MarketFamily.OTHER, "NEXT", "HOME", null, "Next goal: ${item.homeTeam}", lh / (lh + la) * any, ev)
            o.add(MarketFamily.OTHER, "NEXT", "AWAY", null, "Next goal: ${item.awayTeam}", la / (lh + la) * any, ev)
            o.add(MarketFamily.TOTALS, "MORE", "YES", null, "Another goal in regulation", any, ev)
            val cur = hg + ag
            (listOf(4.5, 5.5, 6.5) + prices.orEmpty().filter { it.market == "TOT" }.mapNotNull { it.point }).distinct().filter { it > cur }.forEach { l ->
                val po = p { x, y -> x + y + (if (x == y) 1 else 0) > l }
                o.add(MarketFamily.TOTALS, "TOT", "OVER", l, "Over ${fmt(l)} goals (incl. OT)", po, ev)
                o.add(MarketFamily.TOTALS, "TOT", "UNDER", l, "Under ${fmt(l)} goals (incl. OT)", 1 - po, ev)
            }
            val h2 = p { x, y -> x - y >= 2 }; val a2 = p { x, y -> y - x >= 2 }
            o.add(MarketFamily.HANDICAP, "SPR", "HOME", -1.5, "${item.homeTeam} -1.5", h2, ev)
            o.add(MarketFamily.HANDICAP, "SPR", "AWAY", 1.5, "${item.awayTeam} +1.5", 1 - h2, ev)
            o.add(MarketFamily.HANDICAP, "SPR", "AWAY", -1.5, "${item.awayTeam} -1.5", a2, ev)
            o.add(MarketFamily.HANDICAP, "SPR", "HOME", 1.5, "${item.homeTeam} +1.5", 1 - a2, ev)
            // Current period
            val ph = s?.homeLines?.getOrNull(period - 1); val pa = s?.awayLines?.getOrNull(period - 1)
            if (ph != null && pa != null) {
                val gp = PredictionEngine.grid(max(0.01, lh * clock / rem), max(0.01, la * clock / rem))
                var w = 0.0; var d = 0.0
                for (x in gp.indices) for (y in gp[x].indices) { if (ph + x > pa + y) w += gp[x][y] else if (ph + x == pa + y) d += gp[x][y] }
                o.add(MarketFamily.PERIODS, "PCUR", "HOME", null, "Period $period: ${item.homeTeam}", w, ev)
                o.add(MarketFamily.PERIODS, "PCUR", "DRAW", null, "Period $period: Draw", d, ev)
                o.add(MarketFamily.PERIODS, "PCUR", "AWAY", null, "Period $period: ${item.awayTeam}", 1 - w - d, ev)
            }
        }
        val actual = mutableListOf<LiveProjection>()
        if (s != null && s.homeLines.isNotEmpty()) actual += LiveProjection("Goals by period", "${item.homeTeam} ${s.homeLines.joinToString(" | ")} · ${item.awayTeam} ${s.awayLines.joinToString(" | ")}")
        s?.rows?.filter { it.key in setOf("shotsTotal", "shots", "powerPlayGoals", "powerPlayOpportunities", "powerPlayPct", "penaltyMinutes", "penalties", "saves", "savePct", "hits", "blockedShots", "faceoffsWon", "faceoffPercent", "takeaways", "giveaways") }
            ?.forEach { actual += LiveProjection(it.label, "${it.home} – ${it.away}") }
        s?.players?.take(6)?.forEach { actual += LiveProjection("${it.name} (${it.team})", it.line) }
        val intel = mutableListOf<String>()
        if (sog != null) intel += "Shots on goal ${sog.first.toInt()}-${sog.second.toInt()}: ${if (shareH > 0.58) "${item.homeTeam} applying more pressure" else if (shareH < 0.42) "${item.awayTeam} applying more pressure" else "even pressure"}."
        val ppg = s?.stat("powerPlayGoals"); val ppo = s?.stat("powerPlayOpportunities")
        if (ppg != null && ppo != null) intel += "Power plays: ${item.homeTeam} ${ppg.first.toInt()}/${ppo.first.toInt()}, ${item.awayTeam} ${ppg.second.toInt()}/${ppo.second.toInt()}."
        if (!inOT && rem < 10 && hg != ag) intel += "Late in the game: the trailing side may pull the goalie, raising the chance of another goal (either way)."
        intel += "Starting/current goalie isn't identified by the free feed; save stats shown where available."
        val (all, top) = o.ranked()
        return LiveAnalysis(System.currentTimeMillis(), "$hg-$ag", if (inOT) "OT/SO" else "P$period ${s?.clock ?: ""}".trim(), actual,
            listOf(LiveProjection("Projected remaining goals (estimate)", "${item.homeTeam} ${f1(lh)} · ${item.awayTeam} ${f1(la)}"),
                LiveProjection("Projected final total (estimate)", f1(hg + ag + lh + la))),
            intel, all, top, if (top.isEmpty()) "No live selection reaches 65% at a fair price: no bet recommended right now." else null,
            listOf("Next goalscorer, player shots and other player markets: no free odds source.", "Time in the attacking zone: not in the free feed."), oddsNote(prices))
    }

    // --------------------------------------------------------------- tennis
    private fun tennis(item: MatchItem, s: LiveStats?, prior: LivePrior, prices: List<BookPrice>?, src: String?): LiveAnalysis {
        val o = Out(prices, src)
        val ha = prior.holdHome; val hb = prior.holdAway
        val hl = s?.homeLines.orEmpty(); val al = s?.awayLines.orEmpty()
        val actual = mutableListOf<LiveProjection>()
        if (hl.isNotEmpty()) actual += LiveProjection("Set scores", hl.zip(al).joinToString("  ") { "${it.first}-${it.second}" })
        s?.rows?.forEach { actual += LiveProjection(it.label, "${it.home} – ${it.away}") }
        val intel = mutableListOf<String>()
        if (ha == null || hb == null || hl.isEmpty()) {
            intel += "Live tennis analysis needs the pre-match strength estimate and the set scores; ${if (hl.isEmpty()) "set scores aren't in the feed yet" else "no odds or ranking were available before the match"}."
            return LiveAnalysis(System.currentTimeMillis(), item.homeScore + "-" + item.awayScore, "Live", actual, emptyList(), intel, emptyList(), emptyList(),
                "Not enough data for live tennis estimates: no bet recommended.",
                listOf("Point-by-point score, serve/return stats, break points, aces, double faults: not in the free feed."), oddsNote(prices))
        }
        // Completed sets and the current set's games
        var setsA = 0; var setsB = 0
        for (k in 0 until hl.size - 1) { if (hl[k] > al.getOrElse(k) { 0 }) setsA++ else setsB++ }
        val ga = hl.last(); val gb = al.getOrElse(hl.size - 1) { 0 }
        val curDone = (ga >= 6 || gb >= 6) && abs(ga - gb) >= 2 || ga == 7 || gb == 7
        if (curDone) { if (ga > gb) setsA++ else setsB++ }
        val pSetNow = if (curDone) null else setFrom(ga, gb, ha, hb)
        val pSet = setFrom(0, 0, ha, hb)
        fun matchFrom(a: Int, b: Int): Double = when { a == 2 -> 1.0; b == 2 -> 0.0; else -> pSet * matchFrom(a + 1, b) + (1 - pSet) * matchFrom(a, b + 1) }
        val pMatch = if (pSetNow == null) matchFrom(setsA, setsB) else pSetNow * matchFrom(setsA + 1, setsB) + (1 - pSetNow) * matchFrom(setsA, setsB + 1)
        val ev = listOf("Sets $setsA-$setsB, current set ${if (curDone) "finished" else "$ga-$gb"}; model service holds ${pct(ha)} / ${pct(hb)} from the pre-match estimate (server unknown: averaged).")
        o.add(MarketFamily.RESULT, "ML", "HOME", null, "${item.homeTeam} to win (live)", pMatch, ev)
        o.add(MarketFamily.RESULT, "ML", "AWAY", null, "${item.awayTeam} to win (live)", 1 - pMatch, ev)
        if (pSetNow != null) {
            o.add(MarketFamily.SETS, "SETCUR", "HOME", null, "Current set: ${item.homeTeam}", pSetNow, ev)
            o.add(MarketFamily.SETS, "SETCUR", "AWAY", null, "Current set: ${item.awayTeam}", 1 - pSetNow, ev)
            val nextA = (ha + (1 - hb)) / 2
            o.add(MarketFamily.GAMES, "NEXTGAME", "HOME", null, "Next game: ${item.homeTeam}", nextA, ev + "Server unknown in the free feed: average of serving and returning.")
            o.add(MarketFamily.GAMES, "NEXTGAME", "AWAY", null, "Next game: ${item.awayTeam}", 1 - nextA, ev)
            if (ga == 6 && gb == 6 || (ga >= 5 && gb >= 5)) intel += "Set is close: a tie-break is possible."
        }
        intel += "${if (pMatch >= 0.5) item.homeTeam else item.awayTeam} ${pct(max(pMatch, 1 - pMatch))} to win the match from here."
        if (hl.size >= 2 && hl.last() + al.last() >= 2) {
            val recent = hl.last() - al.last()
            intel += if (recent >= 2) "${item.homeTeam} have the momentum in this set." else if (recent <= -2) "${item.awayTeam} have the momentum in this set." else "Current set is tight."
        }
        val (all, top) = o.ranked()
        return LiveAnalysis(System.currentTimeMillis(), "$setsA-$setsB", if (curDone) "Between sets" else "Set ${hl.size}: $ga-$gb", actual,
            listOf(LiveProjection("Match-win probability (estimate)", "${item.homeTeam} ${pct(pMatch)} · ${item.awayTeam} ${pct(1 - pMatch)}")),
            intel, all, top, if (top.isEmpty()) "No live selection reaches 65% at a fair price: no bet recommended right now." else null,
            listOf("Point score, serve/return stats, aces, double faults, break points, next-point and tie-break markets: not in the free feed."), oddsNote(prices))
    }

    /** P(player A wins the set) from games a-b with hold rates (server unknown, averaged). */
    private fun setFrom(a: Int, b: Int, ha: Double, hb: Double): Double {
        val memo = HashMap<Triple<Int, Int, Boolean>, Double>()
        fun f(x: Int, y: Int, aServes: Boolean): Double {
            if ((x >= 6 || y >= 6) && abs(x - y) >= 2 || x == 7 || y == 7) return if (x > y) 1.0 else 0.0
            if (x == 6 && y == 6) return (0.5 + (ha - hb) * 0.9).coerceIn(0.05, 0.95)
            val k = Triple(x, y, aServes)
            memo[k]?.let { return it }
            val pa = if (aServes) ha else 1 - hb
            val r = pa * f(x + 1, y, !aServes) + (1 - pa) * f(x, y + 1, !aServes)
            memo[k] = r
            return r
        }
        return (f(a, b, true) + f(a, b, false)) / 2
    }
}
