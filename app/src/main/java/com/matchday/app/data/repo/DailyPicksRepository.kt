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
)

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

@Serializable
data class DailySlips(
    val date: String,                 // Qatar calendar day
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

    /** Today's slips, building them the first time the day is opened. */
    suspend fun todayOrBuild(): DailySlips? {
        val d = today()
        all().firstOrNull { it.date == d }?.let { return it }
        return build(force = false)
    }

    /** Builds (or rebuilds, while no leg has started) today's slips. */
    suspend fun build(force: Boolean): DailySlips? {
        val d = today()
        val existing = all().firstOrNull { it.date == d }
        val now = System.currentTimeMillis()
        if (existing != null && (!force || existing.slips.flatMap { it.legs }.any { it.kickoff <= now })) return existing

        val endOfDay = LocalDate.now(QATAR).plusDays(1).atStartOfDay(QATAR).toInstant().toEpochMilli() + 6 * 3_600_000L
        val candidates = matches.observeMatchesOnce().filter {
            it.competition.sport == SportType.FOOTBALL && it.status == MatchStatus.UPCOMING &&
                it.kickoff in (now + 15 * 60_000L) until endOfDay
        }
        val excluded = HashMap<String, Int>()
        fun skip(reason: String) { excluded[reason] = (excluded[reason] ?: 0) + 1 }

        val usable = candidates.filter { m ->
            when {
                m.competition.key in VOLATILE -> { skip("Friendlies (unpredictable line-ups)"); false }
                m.competition.tier <= 2 -> { skip("Low-tier or very volatile leagues"); false }
                else -> true
            }
        }.sortedWith(compareByDescending<MatchItem> { it.competition.tier }.thenBy { it.kickoff }).take(80)

        // Full analysis for each candidate (cached summaries/tables; limited parallelism in the repository).
        val analysed = coroutineScope {
            usable.map { m ->
                async {
                    val saved = runCatching { oneXBet.state(m) }.getOrNull() as? OneXBetState.Ok
                    m to runCatching { matches.pickAnalysis(m, saved?.current?.prices, saved?.current?.bookmaker) }.getOrNull()
                }
            }.awaitAll()
        }

        // Risk filter -> per-match candidate selections with real prices.
        val pool = mutableListOf<Pair<MatchItem, List<Pair<MarketPick, String>>>>()
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
                        pk.probability, pk.odds!!, pk.bookmaker ?: "Bookmaker", why)
                }
            }.sortedWith(compareByDescending<SlipLeg> { it.probability }.thenByDescending { it.odds }).take(size).sortedBy { it.kickoff }
            Slip(size, minP, legs, if (legs.size < size) "Only ${legs.size} match${if (legs.size == 1) "" else "es"} passed the risk filter at ${Math.round(minP * 100)}%+ today; the slip isn't padded with risky picks." else null)
        }
        val result = DailySlips(d, now, analysed.size, pool.size, excluded, slips)
        lock.withLock { write(read().filter { it.date != d } + result) }
        return result
    }

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
                        when (matches.tracker.outcome(leg.market, leg.sel, leg.point, hg, ag, null, null)) {
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

    @Suppress("unused")
    private fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(QATAR).toLocalDate().toString()
}
