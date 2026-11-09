package com.matchday.app.data.repo

import android.util.Log
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.ServeRecord
import com.matchday.app.domain.ServeStats
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.TennisContext
import com.matchday.app.domain.TennisMeeting
import com.matchday.app.domain.TennisModel
import com.matchday.app.domain.TennisProfile
import com.matchday.app.domain.TennisResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Tennis player data from free sources, all cached on the phone:
 *  - ESPN weekly tournament boards (last 26 weeks): every completed singles result with set scores
 *    -> recent form, 6-month record, surface record, games won, fatigue, head-to-head, Elo rating.
 *  - TML ATP database on GitHub (2025 and 2026 files, non-commercial use): service games held and
 *    return games broken per player and surface, plus the surface of each tournament.
 * Nothing is estimated when a source has no data for a player: the field stays empty.
 */
class TennisRepository(private val cache: JsonCache, private val teams: TeamRepository) {
    private val espn = Network.espn
    private val gate = Semaphore(3)
    private val resSer = ListSerializer(TennisResult.serializer())
    private val lock = Mutex()
    private val loadLock = Mutex()
    private var memo: Pair<Long, Base>? = null
    private val eloMemo = HashMap<String, Pair<Long, Map<String, Pair<Double, Int>>>>()

    companion object {
        private const val TAG = "TennisRepository"
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        const val WEEKS = 26
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    @Serializable
    data class SurfaceAt(val month: Int, val surface: String)

    @Serializable
    data class TmlData(
        val results: List<TennisResult>,
        val serve: Map<String, ServeStats>,
        val surfaces: Map<String, List<SurfaceAt>>,
        val avgHold: Double?,
        val lastDate: Long,
    )

    private class Base(
        val results: List<TennisResult>,
        val index: Map<String, List<TennisResult>>,
        val tml: TmlData?,
        val espnWeeks: Int,
    )

    // -----------------------------------------------------------------------
    // Public
    // -----------------------------------------------------------------------

    /** Profiles of both players, head-to-head and the tournament surface for a tennis match. */
    suspend fun context(item: MatchItem): TennisContext? = runCatching {
        val tour = if (item.competition.key.endsWith("wta")) "wta" else "atp"
        val ranks = runCatching { teams.tennisRanks(tour) }.getOrDefault(emptyMap())
        val base = base() ?: return@runCatching null
        val elo = elo(tour, base, ranks)
        val month = Instant.ofEpochMilli(item.kickoff).atZone(ZoneOffset.UTC).monthValue
        val city = item.venue?.substringBefore(" · ")?.substringBefore(",")?.trim()
        val surface = TennisModel.surfaceOf(listOfNotNull(city), month, surfaceMap(base.tml))
        fun rank(n: String) = ranks[n] ?: ranks.entries.firstOrNull { TeamMatcher.same(it.key, n) }?.value
        val hk = key(item.homeTeam, base); val ak = key(item.awayTeam, base)
        val home = profile(item.homeTeam, hk, rank(item.homeTeam), item.kickoff, surface, base, elo, tour)
        val away = profile(item.awayTeam, ak, rank(item.awayTeam), item.kickoff, surface, base, elo, tour)
        val h2h = if (hk == null || ak == null) emptyList() else base.index[hk].orEmpty()
            .filter { it.date < item.kickoff && (TeamMatcher.normalize(it.winner) == ak || TeamMatcher.normalize(it.loser) == ak) }
            .sortedByDescending { it.date }.map { meeting(it, hk) }
        val note = buildString {
            append("Results: ESPN tournament boards (last ${base.espnWeeks} weeks)")
            if (tour == "atp" && base.tml != null) append(" + TML ATP database (2025 – ${Instant.ofEpochMilli(base.tml.lastDate).atZone(ZoneOffset.UTC).toLocalDate()}; serve/return records)")
            append(".")
        }
        TennisContext(surface, home, away, h2h, if (tour == "atp") base.tml?.avgHold else null, note)
    }.onFailure { Log.w(TAG, "Tennis context failed", it) }.getOrNull()

    // -----------------------------------------------------------------------
    // Profiles
    // -----------------------------------------------------------------------

    private fun key(name: String, b: Base): String? {
        val k = TeamMatcher.normalize(name)
        if (b.index.containsKey(k)) return k
        return TeamMatcher.best(name, b.index.keys.toList(), { it }, 0.75)
    }

    private fun meeting(r: TennisResult, me: String): TennisMeeting {
        val won = TeamMatcher.normalize(r.winner) == me
        val opp = if (won) r.loser else r.winner
        val sets = if (won) r.sets else r.sets.map { s -> s.split("-").reversed().joinToString("-") }
        return TennisMeeting(r.date, won, sets.joinToString(" ") + if (r.retired) " ret." else "", "vs $opp · ${r.event}", r.surface)
    }

    private fun profile(
        name: String, k: String?, rank: Int?, kickoff: Long, surface: String?, b: Base,
        elo: Map<String, Pair<Double, Int>>, tour: String,
    ): TennisProfile {
        val ms = k?.let { b.index[it] }.orEmpty().filter { it.date < kickoff }.sortedByDescending { it.date }
        val meetings = ms.map { meeting(it, k!!) }
        val since6m = kickoff - 182 * DAY
        val since12m = kickoff - 365 * DAY
        val recent6 = meetings.filter { it.date >= since6m }
        val onSurface = if (surface == null) emptyList() else meetings.filter { it.date >= since12m && it.surface == surface }
        val done = ms.filter { !it.retired }.take(15)
        val share = if (done.size >= 5) {
            var mine = 0; var all = 0
            done.forEach { r -> val (w, l) = TennisModel.games(r); val won = TeamMatcher.normalize(r.winner) == k; mine += if (won) w else l; all += w + l }
            if (all > 0) mine.toDouble() / all else null
        } else null
        val last3 = ms.filter { it.date >= kickoff - 3 * DAY }
        val serveAll = if (tour == "atp" && k != null) b.tml?.serve?.get(k) else null
        val surfRec = surface?.let { serveAll?.bySurface?.get(it) }?.takeIf { it.svGames >= 60 && it.rtGames >= 60 }
        val rec = surfRec ?: serveAll?.all
        val e = (k?.let { elo[it] } ?: elo[TeamMatcher.normalize(name)])?.takeIf { it.second > 0 || rank != null }
            ?: rank?.let { TennisModel.eloStart(it) to 0 }
        return TennisProfile(
            name = name, rank = rank, elo = e?.first, eloMatches = e?.second ?: 0,
            last = meetings.take(10), lastOpponents = emptyList(),
            wins6m = recent6.count { it.won }, losses6m = recent6.count { !it.won },
            surfaceWins = onSurface.count { it.won }, surfaceLosses = onSurface.count { !it.won },
            gamesShare = share, matches3d = last3.size, sets3d = last3.sumOf { it.sets.size },
            daysSinceLast = ms.firstOrNull()?.let { ((kickoff - it.date) / DAY).toInt() },
            hold = rec?.hold, brk = rec?.brk, serveMatches = rec?.matches ?: 0, serveOnSurface = surfRec != null,
        )
    }

    private suspend fun elo(tour: String, b: Base, ranks: Map<String, Int>): Map<String, Pair<Double, Int>> = lock.withLock {
        val now = System.currentTimeMillis()
        eloMemo[tour]?.takeIf { now - it.first < 30 * 60_000L && memo?.first?.let { m -> m <= it.first } == true }?.let { return@withLock it.second }
        val r = TennisModel.elo(b.results.filter { it.tour == tour }, ranks)
        eloMemo[tour] = now to r
        r
    }

    private fun surfaceMap(t: TmlData?): Map<String, List<Pair<Int, String>>> =
        t?.surfaces?.mapValues { (_, v) -> v.map { it.month to it.surface } }.orEmpty()

    // -----------------------------------------------------------------------
    // Data
    // -----------------------------------------------------------------------

    private suspend fun base(): Base? = loadLock.withLock { loadBase() }

    private suspend fun loadBase(): Base? {
        val now = System.currentTimeMillis()
        lock.withLock { memo?.takeIf { now - it.first < 30 * 60_000L }?.let { return it.second } }
        val (espnRes, tml) = coroutineScope {
            val e = async { espnResults() }
            val t = async { tml() }
            e.await() to t.await()
        }
        val sm = surfaceMap(tml)
        val withSurface = espnRes.map { r ->
            if (r.surface != null) r else {
                val month = Instant.ofEpochMilli(r.date).atZone(ZoneOffset.UTC).monthValue
                r.copy(surface = TennisModel.surfaceOf(listOfNotNull(r.event, r.city?.substringBefore(",")), month, sm))
            }
        }
        val all = (withSurface + tml?.results.orEmpty()).distinctBy { it.id }
        if (all.isEmpty()) return null
        val index = HashMap<String, MutableList<TennisResult>>()
        all.forEach { r ->
            index.getOrPut(TeamMatcher.normalize(r.winner)) { mutableListOf() } += r
            index.getOrPut(TeamMatcher.normalize(r.loser)) { mutableListOf() } += r
        }
        val b = Base(all, index, tml, WEEKS)
        lock.withLock { memo = now to b; eloMemo.clear() }
        return b
    }

    /** Completed singles from the weekly boards of both tours (past weeks cached 30 days). */
    private suspend fun espnResults(): List<TennisResult> = coroutineScope {
        val today = LocalDate.now(ZoneOffset.UTC)
        val wed0 = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
        (0 until WEEKS).flatMap { w -> listOf("atp", "wta").map { tour -> Triple(w, tour, wed0.minusWeeks(w.toLong())) } }.map { (w, tour, d) ->
            async {
                val ttl = if (w <= 1) 3 * HOUR else 30 * DAY
                runCatching {
                    cache.get("tennis:wk:$tour:${d.format(YMD)}", ttl, resSer) {
                        parseBoard(gate.withPermit { espn.scoreboard("tennis", tour, d.format(YMD), null) })
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().distinctBy { it.id }
    }

    private fun o(e: JsonElement?) = e as? JsonObject
    private fun a(e: JsonElement?) = (e as? JsonArray).orEmpty()
    private fun s(e: JsonElement?) = (e as? JsonPrimitive)?.content
    private fun b(e: JsonElement?) = s(e) == "true"

    private fun parseBoard(root: JsonObject): List<TennisResult> {
        val out = mutableListOf<TennisResult>()
        for (ev in a(root["events"])) {
            val e = o(ev) ?: continue
            val event = s(e["name"]) ?: ""
            val city = s(o(e["venue"])?.get("displayName"))
            for (g in a(e["groupings"])) {
                val gn = s(o(o(g)?.get("grouping"))?.get("displayName"))?.lowercase() ?: continue
                if (!gn.contains("singles")) continue
                val tour = if (gn.contains("women")) "wta" else "atp"
                for (cEl in a(o(g)?.get("competitions"))) {
                    val c = o(cEl) ?: continue
                    if (s(o(o(c["status"])?.get("type"))?.get("state")) != "post") continue
                    val comps = a(c["competitors"]).mapNotNull { o(it) }
                    if (comps.size != 2) continue
                    val w = comps.firstOrNull { b(it["winner"]) } ?: continue
                    val l = comps.firstOrNull { it !== w } ?: continue
                    val wn = s(o(w["athlete"])?.get("displayName")) ?: continue
                    val ln = s(o(l["athlete"])?.get("displayName")) ?: continue
                    val note = a(c["notes"]).joinToString(" ") { s(o(it)?.get("text")) ?: "" }.lowercase()
                    if (note.contains("w/o") || note.contains("walkover")) continue
                    val ws = a(w["linescores"]).mapNotNull { s(o(it)?.get("value"))?.toDoubleOrNull()?.toInt() }
                    val ls = a(l["linescores"]).mapNotNull { s(o(it)?.get("value"))?.toDoubleOrNull()?.toInt() }
                    if (ws.isEmpty() || ws.size != ls.size) continue
                    val sets = ws.indices.map { "${ws[it]}-${ls[it]}" }
                    val retired = Regex("\\bret\\b|retired|\\bdef\\b").containsMatchIn(note) || !complete(ws, ls)
                    out += TennisResult(
                        id = "espn:" + (s(c["id"]) ?: "$event$wn$ln"), date = EspnMapper.parseTime(s(c["date"])), tour = tour,
                        event = event, city = city, winner = wn, loser = ln, sets = sets, retired = retired,
                    )
                }
            }
        }
        return out
    }

    /** True when the last set was played to the end (6+ games with a 2-game lead, or 7-6). */
    private fun complete(w: List<Int>, l: List<Int>): Boolean {
        val a = w.last(); val b = l.last()
        return (a >= 6 && a - b >= 2) || (a == 7 && b == 6) || a >= 10 && a - b >= 2
    }

    /** TML ATP database: results, serve/return records and tournament surfaces (refreshed weekly). */
    private suspend fun tml(): TmlData? = runCatching {
        cache.get("tennis:tml:v1", 7 * DAY, TmlData.serializer()) { buildTml() }
    }.onFailure { Log.w(TAG, "TML database unavailable", it) }.getOrNull()

    private suspend fun buildTml(): TmlData {
        val year = LocalDate.now(ZoneOffset.UTC).year
        val texts = listOf(year - 1, year).mapNotNull { y ->
            runCatching { gate.withPermit { Network.raw.get("Tennismylife/TML-Database/master/$y.csv").string() } }.getOrNull()
        }
        if (texts.isEmpty()) throw IllegalStateException("TML files unavailable")
        val results = mutableListOf<TennisResult>()
        val serveAll = HashMap<String, ServeRecord>()
        val serveSurf = HashMap<String, HashMap<String, ServeRecord>>()
        val surfaces = HashMap<String, MutableSet<SurfaceAt>>()
        var held = 0L; var sv = 0L; var last = 0L
        for (text in texts) {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) continue
            val h = csv(lines[0]).withIndex().associate { it.value.trim() to it.index }
            fun col(r: List<String>, n: String) = h[n]?.let { r.getOrNull(it) }?.trim().orEmpty()
            fun num(r: List<String>, n: String) = col(r, n).toDoubleOrNull()?.toInt()
            for (line in lines.drop(1)) {
                val r = csv(line)
                val wn = col(r, "winner_name"); val ln = col(r, "loser_name")
                if (wn.isBlank() || ln.isBlank()) continue
                val surface = col(r, "surface").ifBlank { null }?.replaceFirstChar { it.uppercase() }
                val dStr = col(r, "tourney_date")
                val date = runCatching { LocalDate.parse(dStr, YMD).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull() ?: continue
                last = maxOf(last, date)
                val tName = col(r, "tourney_name")
                if (surface != null && tName.isNotBlank()) {
                    val month = LocalDate.parse(dStr, YMD).monthValue
                    surfaces.getOrPut(TeamMatcher.normalize(tName)) { mutableSetOf() } += SurfaceAt(month, surface)
                }
                val score = col(r, "score")
                val up = score.uppercase()
                if (up.contains("W/O") || up.contains("WALKOVER")) continue
                val sets = score.split(" ").map { it.substringBefore("(").trim() }.filter { Regex("^\\d+-\\d+$").matches(it) }
                if (sets.isEmpty()) continue
                val retired = up.contains("RET") || up.contains("DEF") || up.contains("ABD")
                results += TennisResult(
                    id = "tml:" + col(r, "tourney_id") + ":" + col(r, "match_num"), date = date + (num(r, "match_num") ?: 0) * 60_000L,
                    tour = "atp", event = tName, surface = surface, winner = wn, loser = ln, sets = sets, retired = retired, source = "TML",
                )
                val wSv = num(r, "w_SvGms"); val wF = num(r, "w_bpFaced"); val wS = num(r, "w_bpSaved")
                val lSv = num(r, "l_SvGms"); val lF = num(r, "l_bpFaced"); val lS = num(r, "l_bpSaved")
                if (wSv == null || wF == null || wS == null || lSv == null || lF == null || lS == null || wSv <= 0 || lSv <= 0) continue
                val wHeld = (wSv - (wF - wS)).coerceIn(0, wSv); val lHeld = (lSv - (lF - lS)).coerceIn(0, lSv)
                val wRec = ServeRecord(1, wSv, wHeld, lSv, lSv - lHeld)
                val lRec = ServeRecord(1, lSv, lHeld, wSv, wSv - wHeld)
                val wk = TeamMatcher.normalize(wn); val lk = TeamMatcher.normalize(ln)
                serveAll[wk] = (serveAll[wk] ?: ServeRecord()) + wRec
                serveAll[lk] = (serveAll[lk] ?: ServeRecord()) + lRec
                if (surface != null) {
                    serveSurf.getOrPut(wk) { HashMap() }.let { it[surface] = (it[surface] ?: ServeRecord()) + wRec }
                    serveSurf.getOrPut(lk) { HashMap() }.let { it[surface] = (it[surface] ?: ServeRecord()) + lRec }
                }
                held += wHeld + lHeld; sv += wSv + lSv
            }
        }
        return TmlData(
            results = results,
            serve = serveAll.mapValues { (k, v) -> ServeStats(v, serveSurf[k].orEmpty()) },
            surfaces = surfaces.mapValues { it.value.toList() },
            avgHold = if (sv > 0) held.toDouble() / sv else null,
            lastDate = last,
        )
    }

    /** Minimal CSV split (handles quoted fields). */
    private fun csv(line: String): List<String> {
        if (!line.contains('"')) return line.split(',')
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) {
            when {
                ch == '"' -> q = !q
                ch == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
        }
        out += sb.toString()
        return out
    }
}
