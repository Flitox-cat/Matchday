package com.matchday.app.data.repo

import com.matchday.app.data.local.MatchEntity
import com.matchday.app.domain.MarketFamily
import com.matchday.app.domain.MarketPick
import com.matchday.app.domain.MatchItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** One recommendation the app made, and (once played) whether it won. */
@Serializable
data class TrackedPick(
    val matchId: String,
    val kickoff: Long,
    val home: String,
    val away: String,
    val family: String,
    val market: String,
    val sel: String,
    val point: Double? = null,
    val label: String,
    val probability: Double,
    val odds: Double? = null,
    val source: String,            // "match" (match page top picks) or "daily" (Today's Best)
    val rank: Int,
    val createdAt: Long,
    val result: Boolean? = null,   // null = not settled
    val void: Boolean = false,     // refunded (draw no bet / handicap push) or can't be settled
    val note: String? = null,
    val score: String? = null,
)

/** Accuracy figures for one category. */
data class AccuracyRow(val category: String, val settled: Int, val won: Int, val avgPredicted: Double) {
    val hitRate: Double get() = if (settled == 0) 0.0 else won.toDouble() / settled
}

data class CalibrationRow(val range: String, val n: Int, val predicted: Double, val actual: Double)

data class AccuracyReport(
    val rows: List<AccuracyRow>,
    val calibration: List<CalibrationRow>,
    val brier: Double?,
    val pending: Int,
    val voided: Int,
    val recent: List<TrackedPick>,
)

/**
 * Stores every recommendation on the phone and settles it from the final (and half-time) score
 * published by the free feed. Nothing is settled from guesses: markets that need data we don't
 * have (e.g. who scored first) are marked void and left out of the accuracy figures.
 */
class PickTracker(private val cache: JsonCache) {
    private val ser = ListSerializer(TrackedPick.serializer())
    private val htSer = MapSerializer(String.serializer(), String.serializer())
    private val lock = kotlinx.coroutines.sync.Mutex()

    companion object {
        const val KEY = "track:picks"
        const val HT_KEY = "ht:scores"

        fun category(family: String, market: String): String = when {
            market == "1X2" -> "Match winners"
            market == "DC" || market == "DNB" -> "Double chance / Draw no bet"
            market == "OU" -> "Over/Under goals"
            market == "BTTS" -> "BTTS"
            market == "TT" -> "Individual team goals"
            market.startsWith("1H") -> "First-half markets"
            else -> "Other markets"
        }
    }

    suspend fun all(): List<TrackedPick> = runCatching { cache.peek(KEY, ser) }.getOrNull().orEmpty()

    /** Records (or updates, until kick-off) the given recommendations. */
    suspend fun record(item: MatchItem, picks: List<MarketPick>, source: String) {
        val now = System.currentTimeMillis()
        if (picks.isEmpty() || now >= item.kickoff) return
        lock.lock()
        try {
            val cur = all().toMutableList()
            picks.forEachIndexed { i, p ->
                val idx = cur.indexOfFirst { it.matchId == item.id && it.source == source && it.rank == i + 1 }
                val t = TrackedPick(
                    item.id, item.kickoff, item.homeTeam, item.awayTeam, p.family.name, p.market, p.sel, p.point, p.label,
                    p.probability, p.odds, source, i + 1, now,
                )
                if (idx >= 0) cur[idx] = t else cur += t
            }
            // ranks no longer recommended for this match are removed (before kick-off only)
            cur.removeAll { it.matchId == item.id && it.source == source && it.rank > picks.size && it.result == null }
            cache.put(KEY, ser, cur.sortedByDescending { it.kickoff }.take(3000))
        } finally {
            lock.unlock()
        }
    }

    suspend fun saveHalfTimes(ht: Map<String, String>) {
        if (ht.isEmpty()) return
        val cur = runCatching { cache.peek(HT_KEY, htSer) }.getOrNull().orEmpty()
        cache.put(HT_KEY, htSer, (cur + ht).entries.toList().takeLast(4000).associate { it.key to it.value })
    }

    /** Settles picks of finished matches still in the database. */
    suspend fun settle(finished: List<MatchEntity>) {
        val byId = finished.filter { it.state == "post" }.associateBy { it.id }
        if (byId.isEmpty()) return
        val ht = runCatching { cache.peek(HT_KEY, htSer) }.getOrNull().orEmpty()
        lock.lock()
        try {
            val cur = all()
            if (cur.none { it.result == null && !it.void && byId.containsKey(it.matchId) }) return
            val out = cur.map { t ->
                if (t.result != null || t.void) return@map t
                val e = byId[t.matchId] ?: return@map t
                val hg = e.homeScore?.trim()?.toIntOrNull() ?: return@map t
                val ag = e.awayScore?.trim()?.toIntOrNull() ?: return@map t
                val half = ht[t.matchId]?.split("-")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 2 }
                val r = outcome(t.market, t.sel, t.point, hg, ag, half?.get(0), half?.get(1))
                when (r) {
                    Outcome.WIN -> t.copy(result = true, score = "$hg-$ag")
                    Outcome.LOSS -> t.copy(result = false, score = "$hg-$ag")
                    Outcome.VOID -> t.copy(void = true, note = "Refunded", score = "$hg-$ag")
                    Outcome.UNKNOWN -> t.copy(void = true, note = "Can't be settled from the free feed", score = "$hg-$ag")
                }
            }
            cache.put(KEY, ser, out)
        } finally {
            lock.unlock()
        }
    }

    private enum class Outcome { WIN, LOSS, VOID, UNKNOWN }

    private fun b(x: Boolean) = if (x) Outcome.WIN else Outcome.LOSS

    private fun outcome(m: String, sel: String, pt: Double?, hg: Int, ag: Int, hh: Int?, ha: Int?): Outcome = when (m) {
        "1X2" -> b(when (sel) { "HOME" -> hg > ag; "AWAY" -> ag > hg; else -> hg == ag })
        "DC" -> b(when (sel) { "1X" -> hg >= ag; "X2" -> ag >= hg; else -> hg != ag })
        "DNB" -> if (hg == ag) Outcome.VOID else b(if (sel == "DNB_HOME") hg > ag else ag > hg)
        "OU" -> pt?.let { b(if (sel == "OVER") hg + ag > it else hg + ag < it) } ?: Outcome.UNKNOWN
        "BTTS" -> b((hg > 0 && ag > 0) == (sel == "YES"))
        "TT" -> pt?.let {
            when (sel) {
                "HOME_OVER" -> b(hg > it); "HOME_UNDER" -> b(hg < it)
                "AWAY_OVER" -> b(ag > it); "AWAY_UNDER" -> b(ag < it)
                else -> Outcome.UNKNOWN
            }
        } ?: Outcome.UNKNOWN
        "CS" -> b(if (sel == "HOME") ag == 0 else hg == 0)
        "WTN" -> b(if (sel == "HOME") hg > ag && ag == 0 else ag > hg && hg == 0)
        "AH" -> pt?.let {
            val d = (if (sel == "HOME") hg - ag else ag - hg) + it
            if (d > 0) Outcome.WIN else if (d == 0.0) Outcome.VOID else Outcome.LOSS
        } ?: Outcome.UNKNOWN
        "1H" -> if (hh == null || ha == null) Outcome.UNKNOWN else b(when (sel) { "H1_HOME" -> hh > ha; "H1_AWAY" -> ha > hh; else -> hh == ha })
        "1HOU" -> if (hh == null || ha == null || pt == null) Outcome.UNKNOWN else b(if (sel == "OVER") hh + ha > pt else hh + ha < pt)
        "1HBTTS" -> if (hh == null || ha == null) Outcome.UNKNOWN else b((hh > 0 && ha > 0) == (sel == "YES"))
        "WEH" -> if (hh == null || ha == null) Outcome.UNKNOWN else {
            val sh = hg - hh; val sa = ag - ha
            b(if (sel == "HOME") hh > ha || sh > sa else ha > hh || sa > sh)
        }
        else -> Outcome.UNKNOWN // first/last goal: no goal timeline in the free feed
    }

    suspend fun report(): AccuracyReport {
        val all = all()
        val settled = all.filter { it.result != null }
        val rows = settled.groupBy { category(it.family, it.market) }.map { (c, l) ->
            AccuracyRow(c, l.size, l.count { it.result == true }, l.map { it.probability }.average())
        }.sortedByDescending { it.settled }
        val buckets = listOf(0.0 to 0.6, 0.6 to 0.7, 0.7 to 0.8, 0.8 to 0.9, 0.9 to 1.01)
        val calibration = buckets.mapNotNull { (lo, hi) ->
            val l = settled.filter { it.probability >= lo && it.probability < hi }
            if (l.isEmpty()) null else CalibrationRow(
                "${Math.round(lo * 100)}–${Math.round(minOf(hi, 1.0) * 100)}%", l.size,
                l.map { it.probability }.average(), l.count { it.result == true }.toDouble() / l.size,
            )
        }
        val brier = if (settled.isEmpty()) null else settled.map { val o = if (it.result == true) 1.0 else 0.0; (it.probability - o) * (it.probability - o) }.average()
        return AccuracyReport(
            rows, calibration, brier,
            pending = all.count { it.result == null && !it.void }, voided = all.count { it.void },
            recent = all.filter { it.result != null || it.void }.sortedByDescending { it.kickoff }.take(20),
        )
    }

    @Suppress("unused")
    private fun familyOf(name: String) = runCatching { MarketFamily.valueOf(name) }.getOrNull()
}
