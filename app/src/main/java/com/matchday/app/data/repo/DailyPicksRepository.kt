package com.matchday.app.data.repo

import android.content.Context
import android.util.Log
import com.matchday.app.data.local.MatchEntity
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.Confidence
import com.matchday.app.domain.MarketPick
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.domain.games
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** One selection on a bet slip. Odds are real bookmaker prices captured when the slip was built. */
@Serializable
data class SlipLeg(
    val matchId: String,
    val kickoff: Long,
    val competition: String,
    val home: String,
    val away: String,
    val market: String,
    val sel: String,
    val point: Double? = null,
    val label: String,
    val probability: Double,
    val odds: Double,
    val bookmaker: String,
    val reason: String,
    val result: Boolean? = null,   // null = not played yet
    val void: Boolean = false,     // refunded or can't be settled
    val score: String? = null,
    val sport: String = "FOOTBALL",   // SportType name
)

/** Sport groups offered in the Daily Picks filter. */
enum class PickSport(val label: String, val emoji: String) {
    FOOTBALL("Football", "⚽"), NBA("NBA (basketball)", "🏀"), TENNIS("Tennis", "🎾"), OTHER("Other sports", "🏒");
    companion object {
        fun of(t: SportType) = when (t) { SportType.FOOTBALL -> FOOTBALL; SportType.BASKETBALL -> NBA; SportType.TENNIS -> TENNIS; else -> OTHER }
        fun emojiOf(sport: String) = when (sport) { "FOOTBALL" -> "⚽"; "BASKETBALL" -> "🏀"; "TENNIS" -> "🎾"; "HOCKEY" -> "🏒"; else -> "🏅" }
    }
}

@Serializable
data class Slip(val size: Int, val minProbability: Double, val legs: List<SlipLeg>, val note: String? = null) {
    val totalOdds: Double get() = legs.filter { !it.void }.fold(1.0) { a, l -> a * l.odds }
    val combinedProbability: Double get() = legs.filter { !it.void }.fold(1.0) { a, l -> a * l.probability }
    /** true = all won, false = at least one lost, null = still pending. */
    val won: Boolean? get() = when {
        legs.any { it.result == false } -> false
        legs.all { it.result == true || it.void } && legs.isNotEmpty() -> true
        else -> null
    }
}

/** Which matches a set of slips covers, counted in Qatar calendar days. */
enum class PickRange(val label: String, val startDay: Long, val days: Long) {
    TODAY("Today", 0, 1), TOMORROW("Tomorrow", 1, 1), NEXT2("Next 2 days", 0, 2), NEXT3("Next 3 days", 0, 3), NEXT7("Next 7 days", 0, 7)
}

@Serializable
data class DailySlips(
    val date: String,                 // Qatar calendar day the slips were built for (start of the range)
    val range: String = "TODAY",      // PickRange name
    val sports: List<String> = listOf("FOOTBALL"),   // PickSport names used
    val createdAt: Long,
    val analysed: Int,
    val qualified: Int,
    val excluded: Map<String, Int>,   // reason -> number of matches left out
    val slips: List<Slip>,
)

/**
 * Daily accumulators (5, 10 and 20 matches). Every candidate match is analysed with the same data
 * as the match page (form, head-to-head, home/away and goal records, injuries, table, odds).
 * Risky matches are left out, and every leg needs a real bookmaker price - never an invented one.
 * Slips are saved in the app's private storage (kept across updates, readable offline).
 */
class DailyPicksRepository(
    context: Context,
    private val matches: MatchRepository,
    private val oneXBet: OneXBetRepository,
    private val teams: TeamRepository,
) {
    private val file = File(context.filesDir, "daily_picks.json")
    private val ser = ListSerializer(DailySlips.serializer())
    private val lock = Mutex()

    companion object {
        private const val TAG = "DailyPicks"
        val QATAR: ZoneId = ZoneId.of("Asia/Qatar")
        /** Slip size -> minimum probability per leg (smaller slip = stricter). */
        val SLIPS = listOf(5 to 0.70, 10 to 0.65, 20 to 0.60)
        const val MIN_ODDS = 1.20
        private val VOLATILE = setOf("soccer/club.friendly", "soccer/fifa.friendly")

        fun today(): String = LocalDate.now(QATAR).toString()
    }

    suspend fun all(): List<DailySlips> = lock.withLock { read() }

    private fun read(): List<DailySlips> = try {
        if (file.exists()) Network.json.decodeFromString(ser, file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "Saved picks unreadable", e); emptyList()
    }

    private fun write(list: List<DailySlips>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Network.json.encodeToString(ser, list.sortedByDescending { it.date }.take(400)))
        tmp.renameTo(file)
    }

    /** Slips for [range] starting today, building them the first time they're opened that day. */
    suspend fun todayOrBuild(range: PickRange = PickRange.TODAY, sports: Set<PickSport> = setOf(PickSport.FOOTBALL)): DailySlips? {
        val d = startDate(range)
        all().firstOrNull { it.date == d && it.range == range.name && it.sports.toSet() == sports.map { x -> x.name }.toSet() }?.let { return it }
        return build(range, sports, force = false)
    }

    private fun startDate(r: PickRange) = LocalDate.now(QATAR).plusDays(r.startDay).toString()

    /** Builds (or rebuilds, while no leg has started) the slips for [range]. */
    suspend fun build(range: PickRange = PickRange.TODAY, sports: Set<PickSport> = setOf(PickSport.FOOTBALL), force: Boolean): DailySlips? {
        val d = startDate(range)
        val sk = sports.map { it.name }.sorted()
        val existing = all().firstOrNull { it.date == d && it.range == range.name && it.sports.sorted() == sk }
        val now = System.currentTimeMillis()
        if (existing != null && (!force || existing.slips.flatMap { it.legs }.any { it.kickoff <= now })) return existing

        val first = LocalDate.now(QATAR).plusDays(range.startDay)
        val from = maxOf(now + 15 * 60_000L, first.atStartOfDay(QATAR).toInstant().toEpochMilli())
        // Today also takes the night's late games (until 06:00 Qatar), when European evenings end.
        val until = first.plusDays(range.days).atStartOfDay(QATAR).toInstant().toEpochMilli() + if (range == PickRange.TODAY) 6 * 3_600_000L else 0L
        val candidates = matches.observeMatchesOnce().filter {
            PickSport.of(it.competition.sport) in sports && it.status == MatchStatus.UPCOMING && it.kickoff in from until until &&
                it.competition.sport in setOf(SportType.FOOTBALL, SportType.BASKETBALL, SportType.HOCKEY, SportType.TENNIS)
        }
        val excluded = HashMap<String, Int>()
        fun skip(reason: String) { excluded[reason] = (excluded[reason] ?: 0) + 1 }

        val usable = candidates.filter { m ->
            when {
                m.competition.key in VOLATILE -> { skip("Friendlies (unpredictable line-ups)"); false }
                m.competition.tier <= 2 -> { skip("Low-tier or very volatile leagues"); false }
                else -> true
            }
        }.sortedWith(compareByDescending<MatchItem> { it.competition.tier }.thenBy { it.kickoff }).take(if (range.days > 2) 140 else 90)

        // Full analysis for each candidate (cached summaries/tables; limited parallelism in the repository).
        val football = usable.filter { it.competition.sport == SportType.FOOTBALL }
        val others = usable.filter { it.competition.sport != SportType.FOOTBALL }
        val analysed = coroutineScope {
            football.map { m ->
                async {
                    val saved = runCatching { oneXBet.state(m) }.getOrNull() as? OneXBetState.Ok
                    m to runCatching { matches.pickAnalysis(m, saved?.current?.prices, saved?.current?.bookmaker) }.getOrNull()
                }
            }.awaitAll()
        }
        val otherAnalysed = if (others.isEmpty()) emptyList() else otherSports(others)

        // Risk filter -> per-match candidate selections with real prices.
        val pool = mutableListOf<Pair<MatchItem, List<Pair<MarketPick, String>>>>()
        for ((m, a, why) in otherAnalysed) {
            val mk = a
            if (mk == null || mk.noPickReason?.startsWith("Not enough") == true || mk.noPickReason?.startsWith("Data unavailable") == true) {
                skip(mk?.noPickReason?.let { "Missing data (${PickSport.of(m.competition.sport).label})" } ?: "Missing data"); continue
            }
            val ml = mk.markets.firstOrNull { it.market == "ML" && it.sel == "HOME" }?.probability
            val even = ml != null && kotlin.math.abs(ml - 0.5) < 0.08
            val picks = mk.markets.filter { pk ->
                pk.odds != null && pk.odds >= MIN_ODDS && pk.probability >= 0.58 && pk.probability < 0.95 &&
                    (pk.edge ?: -1.0) >= -0.03 && !(even && pk.market in setOf("ML", "REG"))
            }
            if (picks.isEmpty()) { skip(if (even) "Evenly matched" else "No market with a fair real price above $MIN_ODDS"); continue }
            pool += m to picks.map { it to otherReason(it, why) }
        }
        for ((m, a) in analysed) {
            val p = a?.prediction
            val mk = a?.markets
            if (a == null || p == null || mk == null) { skip("Missing data (no table, odds or analysis)"); continue }
            if (p.completeness < 0.6) { skip("Too little team data"); continue }
            if (p.confidence == Confidence.LOW) { skip("Low model confidence"); continue }
            if ((p.disagreement ?: 0.0) > 0.15) { skip("Model and bookmaker disagree strongly"); continue }
            val even = abs(p.final.home - p.final.away) < 0.12
            val picks = mk.markets.filter { pk ->
                pk.odds != null && pk.odds >= MIN_ODDS && pk.probability >= 0.58 && pk.probability < 0.95 &&
                    (pk.edge ?: -1.0) >= -0.03 &&
                    !(even && pk.market in setOf("1X2", "DNB"))
            }
            if (picks.isEmpty()) {
                skip(if (even) "Evenly matched teams" else "No market with a fair price above ${MIN_ODDS} (odds too low or no value)")
                continue
            }
            pool += m to picks.map { it to reason(m, it, a) }
        }

        val slips = SLIPS.map { (size, minP) ->
            // For each match: the best price among selections reliable enough for this slip.
            val legs = pool.mapNotNull { (m, picks) ->
                picks.filter { it.first.probability >= minP }.maxByOrNull { it.first.odds!! }?.let { (pk, why) ->
                    SlipLeg(m.id, m.kickoff, m.competition.title, m.homeTeam, m.awayTeam, pk.market, pk.sel, pk.point, pk.label,
                        pk.probability, pk.odds!!, pk.bookmaker ?: "Bookmaker", why, sport = m.competition.sport.name)
                }
            }.sortedWith(compareByDescending<SlipLeg> { it.probability }.thenByDescending { it.odds }).take(size).sortedBy { it.kickoff }
            Slip(size, minP, legs, if (legs.size < size) "Only ${legs.size} match${if (legs.size == 1) "" else "es"} passed the risk filter at ${Math.round(minP * 100)}%+ today; the slip isn't padded with risky picks." else null)
        }
        val result = DailySlips(d, range.name, sk, now, analysed.size + otherAnalysed.size, pool.size, excluded, slips)
        lock.withLock { write(read().filter { !(it.date == d && it.range == range.name && it.sports.sorted() == sk) } + result) }
        return result
    }

    /** NBA / hockey / tennis: the sport's own engine with team history, injuries, rest, ranking and real prices. */
    private suspend fun otherSports(list: List<MatchItem>): List<Triple<MatchItem, com.matchday.app.domain.MarketAnalysis?, String>> {
        val ranks = HashMap<String, Map<String, Int>>()
        if (list.any { it.competition.sport == SportType.TENNIS }) {
            ranks["atp"] = runCatching { teams.tennisRanks("atp") }.getOrDefault(emptyMap())
            ranks["wta"] = runCatching { teams.tennisRanks("wta") }.getOrDefault(emptyMap())
        }
        return coroutineScope {
            list.map { m ->
                async {
                    runCatching {
                        val sport = m.competition.sport
                        val saved = runCatching { oneXBet.state(m) }.getOrNull() as? OneXBetState.Ok
                        val prices = saved?.current?.prices ?: feedPrices(m)
                        val source = saved?.current?.bookmaker ?: m.odds?.home?.best?.bookmaker
                        val (h2h, inj) = runCatching { matches.summaryBasics(m) }.getOrDefault(emptyList<com.matchday.app.domain.RecentResult>() to com.matchday.app.domain.InjuryInfo(false))
                        var hg = emptyList<com.matchday.app.domain.TeamGame>(); var ag = emptyList<com.matchday.app.domain.TeamGame>()
                        var homeRank: Int? = null; var awayRank: Int? = null
                        if (sport == SportType.TENNIS) {
                            val r = ranks[if (m.competition.key.endsWith("wta")) "wta" else "atp"].orEmpty()
                            fun rank(n: String) = r[n] ?: r.entries.firstOrNull { com.matchday.app.domain.TeamMatcher.same(it.key, n) }?.value
                            homeRank = rank(m.homeTeam); awayRank = rank(m.awayTeam)
                        } else {
                            val (hid, aid) = matches.teamIds(m.id)
                            val gh = hid?.let { id -> runCatching { teams.history(m.competition.key, id) }.getOrNull() }
                            val ga = aid?.let { id -> runCatching { teams.history(m.competition.key, id) }.getOrNull() }
                            hg = gh?.games().orEmpty().filter { it.m.date < m.kickoff }
                            ag = ga?.games().orEmpty().filter { it.m.date < m.kickoff }
                        }
                        val a = com.matchday.app.domain.SportEngines.analyze(com.matchday.app.domain.SportInputs(
                            sport, m.competition.key, m.homeTeam, m.awayTeam, m.kickoff, hg, ag, h2h, prices, source, m.odds, inj, false, homeRank, awayRank,
                        ))
                        val why = buildList {
                            if (sport == SportType.TENNIS) add("ranking #${homeRank ?: "N/A"} vs #${awayRank ?: "N/A"}")
                            else {
                                if (hg.isNotEmpty() && ag.isNotEmpty()) add("last 5 ${hg.take(5).joinToString("") { if (it.win) "W" else "L" }} vs ${ag.take(5).joinToString("") { if (it.win) "W" else "L" }}")
                                if (h2h.size >= 2) add("H2H ${h2h.count { it.points == 3 }}-${h2h.count { it.points != 3 }} (home side)")
                                if (inj.known) add("injuries ${inj.homeOut.size}-${inj.awayOut.size} out")
                                val restH = hg.firstOrNull()?.let { ((m.kickoff - it.m.date) / 86_400_000L).toInt() }
                                val restA = ag.firstOrNull()?.let { ((m.kickoff - it.m.date) / 86_400_000L).toInt() }
                                if (restH != null && restH <= 1) add("${m.homeTeam} on a back-to-back")
                                if (restA != null && restA <= 1) add("${m.awayTeam} on a back-to-back")
                            }
                        }.joinToString(" · ")
                        Triple(m, a, why)
                    }.getOrElse { Triple(m, null, "") }
                }
            }.awaitAll()
        }
    }

    /** The free feed's prices for moneyline, spread and total (same codes as the engines). */
    private fun feedPrices(m: MatchItem): List<BookPrice> {
        val o = m.odds ?: return emptyList()
        val out = mutableListOf<BookPrice>()
        o.home?.best?.let { out += BookPrice("ML", "HOME", m.homeTeam, null, it.price) }
        o.away?.best?.let { out += BookPrice("ML", "AWAY", m.awayTeam, null, it.price) }
        o.totalsLine?.let { line ->
            o.over?.best?.let { out += BookPrice("TOT", "OVER", "Over $line", line, it.price) }
            o.under?.best?.let { out += BookPrice("TOT", "UNDER", "Under $line", line, it.price) }
        }
        o.markets.firstOrNull { it.key == "spreads" }?.outcomes?.let { oc ->
            oc.getOrNull(0)?.let { h -> val pt = h.point; val q = h.best; if (pt != null && q != null) out += BookPrice("SPR", "HOME", h.label, pt, q.price) }
            oc.getOrNull(1)?.let { a -> val pt = a.point; val q = a.best; if (pt != null && q != null) out += BookPrice("SPR", "AWAY", a.label, pt, q.price) }
        }
        return out
    }

    private fun otherReason(pk: MarketPick, why: String): String =
        listOfNotNull("${Math.round(pk.probability * 100)}% estimated vs ${Math.round(100 / pk.odds!!)}% implied", why.ifBlank { null },
            pk.evidence.getOrNull(1)?.take(140)).joinToString(" · ")

    private fun reason(m: MatchItem, pk: MarketPick, a: MatchRepository.PickAnalysis): String {
        val parts = mutableListOf<String>()
        parts += "${Math.round(pk.probability * 100)}% estimated vs ${Math.round(100 / pk.odds!!)}% implied"
        val hs = a.homeStats; val aws = a.awayStats
        if (hs?.position != null && aws?.position != null) parts += "table ${ord(hs.position)} vs ${ord(aws.position)}"
        val hf = a.homeRecent.take(5).joinToString("") { "${it.letter}" }; val af = a.awayRecent.take(5).joinToString("") { "${it.letter}" }
        if (hf.isNotEmpty() && af.isNotEmpty()) parts += "form $hf vs $af"
        if (a.h2h.size >= 3) parts += "H2H ${a.h2h.count { it.points == 3 }}W-${a.h2h.count { it.points == 1 }}D-${a.h2h.count { it.points == 0 }}L (home side)"
        if (a.injuries.known) parts += "absences ${a.injuries.homeOut.size}-${a.injuries.awayOut.size}"
        pk.evidence.firstOrNull { it.startsWith("Happened") }?.let { parts += it.removeSuffix(".") }
        return parts.joinToString(" · ")
    }

    private fun ord(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    /** Marks legs won/lost from final scores (called after each refresh and when the tab opens). */
    suspend fun settle(finished: List<MatchEntity>) {
        val byId = finished.filter { it.state == "post" }.associateBy { it.id }
        if (byId.isEmpty()) return
        lock.withLock {
            val list = read()
            var changed = false
            val out = list.map { day ->
                day.copy(slips = day.slips.map { slip ->
                    slip.copy(legs = slip.legs.map { leg ->
                        if (leg.result != null || leg.void) return@map leg
                        val e = byId[leg.matchId] ?: return@map leg
                        val hg = e.homeScore?.trim()?.toIntOrNull() ?: return@map leg
                        val ag = e.awayScore?.trim()?.toIntOrNull() ?: return@map leg
                        changed = true
                        val r = if (leg.sport == "FOOTBALL") matches.tracker.outcome(leg.market, leg.sel, leg.point, hg, ag, null, null)
                            else settleOther(leg, hg, ag)
                        when (r) {
                            PickTracker.Outcome.WIN -> leg.copy(result = true, score = "$hg-$ag")
                            PickTracker.Outcome.LOSS -> leg.copy(result = false, score = "$hg-$ag")
                            else -> leg.copy(void = true, score = "$hg-$ag")
                        }
                    })
                })
            }
            if (changed) write(out)
        }
    }

    /** Basketball / hockey / tennis legs from the final score (tennis score = sets). */
    private fun settleOther(leg: SlipLeg, hs: Int, a: Int): PickTracker.Outcome {
        fun b(x: Boolean) = if (x) PickTracker.Outcome.WIN else PickTracker.Outcome.LOSS
        val tennis = leg.sport == "TENNIS"
        return when (leg.market) {
            "ML" -> b(if (leg.sel == "HOME") hs > a else a > hs)
            "SPR" -> if (tennis) PickTracker.Outcome.UNKNOWN else leg.point?.let { pt ->
                val d = (if (leg.sel == "HOME") hs - a else a - hs) + pt
                if (d > 0) PickTracker.Outcome.WIN else if (d == 0.0) PickTracker.Outcome.VOID else PickTracker.Outcome.LOSS
            } ?: PickTracker.Outcome.UNKNOWN
            "TOT" -> if (tennis) PickTracker.Outcome.UNKNOWN else leg.point?.let { b(if (leg.sel == "OVER") hs + a > it else hs + a < it) } ?: PickTracker.Outcome.UNKNOWN
            "TT" -> leg.point?.let { pt -> when (leg.sel) { "HOME_OVER" -> b(hs > pt); "HOME_UNDER" -> b(hs < pt); "AWAY_OVER" -> b(a > pt); "AWAY_UNDER" -> b(a < pt); else -> PickTracker.Outcome.UNKNOWN } } ?: PickTracker.Outcome.UNKNOWN
            "SETS" -> if (tennis) b(if (leg.sel == "2") (hs + a) == 2 else (hs + a) == 3) else PickTracker.Outcome.UNKNOWN
            "CSS" -> if (tennis) b("$hs-$a" == leg.sel) else PickTracker.Outcome.UNKNOWN
            "SETHC" -> if (tennis) b(if (leg.sel == "HOME") !(a == 2 && hs == 0) else !(hs == 2 && a == 0)) else PickTracker.Outcome.UNKNOWN
            else -> PickTracker.Outcome.UNKNOWN   // regulation-only, period and game markets need data the feed doesn't keep
        }
    }

    @Suppress("unused")
    private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(QATAR).toLocalDate().toString()
}
