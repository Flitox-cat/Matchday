package com.matchday.app

import com.matchday.app.data.repo.PickTracker
import com.matchday.app.domain.BttsEngine
import com.matchday.app.domain.Catalog
import com.matchday.app.domain.HistMatch
import com.matchday.app.domain.InjuryInfo
import com.matchday.app.domain.LeagueAverages
import com.matchday.app.domain.MarketEngine
import com.matchday.app.domain.MarketPick
import com.matchday.app.domain.ModelInputs
import com.matchday.app.domain.PredictionEngine
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.SportEngines
import com.matchday.app.domain.SportInputs
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamGame
import com.matchday.app.domain.TeamStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.exp
import kotlin.math.ln

/**
 * Back-test: replays a full past season through the app's own engines (model only - the free feed
 * keeps no pre-game odds for past games) and writes calibration tables. Runs only with BACKTEST=1.
 */
class Backtest {
    data class Game(
        val id: String, val date: Long, val homeId: String, val awayId: String, val home: String, val away: String,
        val hg: Int, val ag: Int, val ot: Boolean,
    )

    data class Rec(val sport: String, val market: String, val sel: String, val p: Double, val win: Boolean)

    private val json = Json { ignoreUnknownKeys = true }
    private val out = StringBuilder()
    private val recs = mutableListOf<Rec>()

    private fun get(url: String): JsonObject? {
        repeat(3) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 20_000; c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "Matchday-Backtest/1.0")
                if (c.responseCode != 200) { Thread.sleep(500); return@repeat }
                return json.parseToJsonElement(c.inputStream.bufferedReader().readText()) as? JsonObject
            } catch (e: Exception) { Thread.sleep(1000) }
        }
        return null
    }

    private fun o(e: JsonElement?) = e as? JsonObject
    private fun a(e: JsonElement?) = (e as? JsonArray).orEmpty()
    private fun s(e: JsonElement?) = (e as? JsonPrimitive)?.content

    private fun season(sport: String, league: String, year: Int, types: List<Int>): List<Game> {
        val teams = a(o(a(o(a(get("https://site.api.espn.com/apis/site/v2/sports/$sport/$league/teams?limit=100")?.get("sports"))
            .firstOrNull())?.get("leagues")).firstOrNull())?.get("teams")).mapNotNull { s(o(o(it)?.get("team"))?.get("id")) }
        val games = HashMap<String, Game>()
        for (t in teams) for (st in types) {
            val r = get("https://site.api.espn.com/apis/site/v2/sports/$sport/$league/teams/$t/schedule?season=$year${if (st > 0) "&seasontype=$st" else ""}") ?: continue
            for (ev in a(r["events"])) {
                val e = o(ev) ?: continue
                val c = o(a(e["competitions"]).firstOrNull()) ?: continue
                val type = o(o(c["status"])?.get("type"))
                if (s(type?.get("completed")) != "true") continue
                val comps = a(c["competitors"]).mapNotNull { o(it) }
                val h = comps.firstOrNull { s(it["homeAway"]) == "home" } ?: continue
                val w = comps.firstOrNull { s(it["homeAway"]) == "away" } ?: continue
                fun score(x: JsonObject) = (o(x["score"])?.let { s(it["value"]) } ?: s(x["score"]))?.toDoubleOrNull()?.toInt()
                val hg = score(h) ?: continue; val ag = score(w) ?: continue
                val detail = (s(type?.get("detail")) ?: "") + " " + (s(type?.get("shortDetail")) ?: "")
                val id = s(e["id"]) ?: continue
                val ds = s(e["date"]) ?: continue
                val date = runCatching { java.time.Instant.parse(ds).toEpochMilli() }
                    .recoverCatching { java.time.Instant.parse(ds.replace("Z", ":00Z")).toEpochMilli() }.getOrNull() ?: continue
                games[id] = Game(id, date, s(o(h["team"])?.get("id")) ?: continue, s(o(w["team"])?.get("id")) ?: continue,
                    s(o(h["team"])?.get("displayName")) ?: "", s(o(w["team"])?.get("displayName")) ?: "", hg, ag,
                    detail.contains("OT") || detail.contains("SO"))
            }
        }
        out.appendLine("$sport/$league $year: ${teams.size} teams, ${games.size} games")
        return games.values.sortedBy { it.date }
    }

    private fun teamGames(id: String, before: Long, all: List<Game>, league: String): List<TeamGame> =
        all.filter { it.date < before && (it.homeId == id || it.awayId == id) }.sortedByDescending { it.date }.take(40).map { g ->
            val home = g.homeId == id
            TeamGame(HistMatch(g.id, g.date, league, league, g.homeId, g.awayId, g.home, g.away, g.hg, g.ag), home, if (home) g.hg else g.ag, if (home) g.ag else g.hg)
        }

    private fun settleOther(sport: SportType, m: MarketPick, g: Game): Boolean? {
        val hs = g.hg; val a = g.ag
        // regulation score in hockey: overtime/shootout games were tied after 60 minutes
        val rh = if (sport == SportType.HOCKEY && g.ot) minOf(hs, a) else hs
        val ra = if (sport == SportType.HOCKEY && g.ot) minOf(hs, a) else a
        return when (m.market) {
            "ML" -> if (m.sel == "HOME") hs > a else a > hs
            "SPR" -> m.point?.let { pt -> val d = (if (m.sel == "HOME") hs - a else a - hs) + pt; if (d == 0.0) null else d > 0 }
            "TOT" -> m.point?.let { if (hs + a == it.toInt() && it % 1.0 == 0.0) null else if (m.sel == "OVER") hs + a > it else hs + a < it }
            "TT" -> m.point?.let { pt -> when { m.sel.startsWith("HOME") -> if (m.sel.endsWith("OVER")) hs > pt else hs < pt; else -> if (m.sel.endsWith("OVER")) a > pt else a < pt } }
            "REG" -> when (m.sel) { "HOME" -> rh > ra; "AWAY" -> ra > rh; else -> rh == ra }
            "DC" -> when (m.sel) { "1X" -> rh >= ra; "X2" -> ra >= rh; else -> rh != ra }
            "BTTS" -> (rh > 0 && ra > 0) == (m.sel == "YES")
            else -> null
        }
    }

    private fun runOther(sport: SportType, league: String, year: Int) {
        val games = season(sport.name.lowercase().let { if (sport == SportType.BASKETBALL) "basketball" else "hockey" }, league, year, listOf(2))
        var n = 0
        for (g in games) {
            val hgms = teamGames(g.homeId, g.date, games, league); val agms = teamGames(g.awayId, g.date, games, league)
            if (hgms.size < 8 || agms.size < 8) continue
            val an = SportEngines.analyze(SportInputs(sport, "${if (sport == SportType.BASKETBALL) "basketball" else "hockey"}/$league", g.home, g.away, g.date,
                hgms, agms, emptyList(), null, null, null, null, false)) ?: continue
            n++
            for (m in an.markets) {
                val w = settleOther(sport, m, g) ?: continue
                recs += Rec(sport.name, m.market, m.sel, m.probability, w)
            }
        }
        out.appendLine("  analysed $n games")
    }

    private fun stats(name: String, id: String, before: Long, all: List<Game>): TeamStats {
        val gs = all.filter { it.date < before && (it.homeId == id || it.awayId == id) }
        var gf = 0; var ga = 0; var pts = 0; var hp = 0; var hf = 0; var ha = 0; var ap = 0; var af = 0; var aa = 0
        for (g in gs) {
            val home = g.homeId == id
            val f = if (home) g.hg else g.ag; val ag = if (home) g.ag else g.hg
            gf += f; ga += ag; pts += if (f > ag) 3 else if (f == ag) 1 else 0
            if (home) { hp++; hf += f; ha += ag } else { ap++; af += f; aa += ag }
        }
        val recent = gs.sortedByDescending { it.date }.take(10).map { g ->
            val home = g.homeId == id
            RecentResult(g.date, if (home) g.away else g.home, home, if (home) g.hg else g.ag, if (home) g.ag else g.hg, null)
        }
        return TeamStats(name, id, null, null, gs.size, gf, ga, pts, hp, hf, ha, ap, af, aa, recent)
    }

    private fun runFootball(league: String, year: Int) {
        val games = season("soccer", league, year, listOf(0))
        val comp = Catalog.find("soccer/$league")
        var n = 0
        for (g in games) {
            val prior = games.filter { it.date < g.date }
            val hs = stats(g.home, g.homeId, g.date, games); val asx = stats(g.away, g.awayId, g.date, games)
            if (hs.played < 5 || asx.played < 5 || prior.size < 40) continue
            // positions from points
            val avg = LeagueAverages(prior.map { it.hg }.average(), prior.map { it.ag }.average())
            val pred = PredictionEngine.predict(comp, g.home, g.away, null, ModelInputs(hs, asx, avg, InjuryInfo(false))) ?: continue
            val lh = pred.expectedHome ?: continue; val la = pred.expectedAway ?: continue
            val btts = BttsEngine.analyze(g.home, g.away, pred, hs.recent, asx.recent, emptyList(), InjuryInfo(false))
            val hgms = teamGames(g.homeId, g.date, games, league); val agms = teamGames(g.awayId, g.date, games, league)
            val an = MarketEngine.analyze(g.home, g.away, lh, la, pred, btts, hgms, agms, emptyList(), emptyList(), InjuryInfo(false),
                null, null, emptyList(), null, false)
            n++
            for (m in an.markets) {
                val r = PickTracker.outcomeOf(m.market, m.sel, m.point, g.hg, g.ag, null, null)
                if (r == PickTracker.Outcome.WIN || r == PickTracker.Outcome.LOSS) recs += Rec("FOOTBALL", m.market, m.sel, m.probability, r == PickTracker.Outcome.WIN)
            }
        }
        out.appendLine("  analysed $n games")
    }

    private fun logit(p: Double) = ln(p / (1 - p))
    private fun sig(x: Double) = 1 / (1 + exp(-x))

    private fun report() {
        out.appendLine()
        out.appendLine("sport market n  hit  avgP  brier  ll   bestSlope ll'   | buckets (pred->actual n)")
        val groups = recs.groupBy { it.sport to it.market }.filter { it.value.size >= 200 }
        for ((k, v) in groups.toSortedMap(compareBy({ it.first }, { it.second }))) {
            fun ll(sl: Double) = v.map { r -> val p = sig(sl * logit(r.p.coerceIn(0.01, 0.99))); if (r.win) -ln(p) else -ln(1 - p) }.average()
            val slopes = (40..130).map { it / 100.0 }
            val best = slopes.minByOrNull { ll(it) }!!
            val brier = v.map { (it.p - if (it.win) 1.0 else 0.0).let { d -> d * d } }.average()
            val buckets = listOf(0.5 to 0.6, 0.6 to 0.7, 0.7 to 0.8, 0.8 to 0.9, 0.9 to 1.0).mapNotNull { (lo, hi) ->
                val b = v.filter { it.p >= lo && it.p < hi }
                if (b.size < 15) null else "%.2f->%.2f(%d)".format(b.map { it.p }.average(), b.count { it.win }.toDouble() / b.size, b.size)
            }
            out.appendLine("%s %s %d %.3f %.3f %.4f %.4f %.2f %.4f | %s".format(k.first, k.second, v.size, v.count { it.win }.toDouble() / v.size,
                v.map { it.p }.average(), brier, ll(1.0), best, ll(best), buckets.joinToString(" ")))
        }
        // pooled per sport
        for ((sp, v) in recs.groupBy { it.sport }) {
            fun ll(sl: Double) = v.map { r -> val p = sig(sl * logit(r.p.coerceIn(0.01, 0.99))); if (r.win) -ln(p) else -ln(1 - p) }.average()
            val best = (40..130).map { it / 100.0 }.minByOrNull { ll(it) }!!
            val hi = v.filter { it.p >= 0.7 }
            out.appendLine("POOLED $sp n=${v.size} slope=$best ll=%.4f->%.4f  picks>=70%%: n=%d predicted %.3f actual %.3f".format(ll(1.0), ll(best), hi.size,
                hi.map { it.p }.average(), hi.count { it.win }.toDouble() / maxOf(1, hi.size)))
        }
    }

    @Test
    fun backtest() {
        Assume.assumeTrue(System.getenv("BACKTEST") == "1")
        runOther(SportType.HOCKEY, "nhl", 2026)
        runOther(SportType.BASKETBALL, "nba", 2026)
        for (l in listOf("eng.1", "esp.1", "ger.1", "ita.1", "fra.1")) runFootball(l, 2025)
        report()
        File(System.getenv("BACKTEST_OUT") ?: "backtest.txt").writeText(out.toString())
        println(out)
    }
}
