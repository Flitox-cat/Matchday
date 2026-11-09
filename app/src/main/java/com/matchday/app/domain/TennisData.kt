package com.matchday.app.domain

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.pow

/** One completed singles match (ESPN scoreboards or the TML ATP database). Sets are winner-first, e.g. "7-6". */
@Serializable
data class TennisResult(
    val id: String,
    val date: Long,
    val tour: String,            // "atp" / "wta"
    val event: String,
    val city: String? = null,
    val surface: String? = null, // Hard / Clay / Grass, null = unknown
    val winner: String,
    val loser: String,
    val sets: List<String>,
    val retired: Boolean = false,
    val source: String = "ESPN",
)

/** Serve/return record of one player from the TML database (ATP only). */
@Serializable
data class ServeRecord(
    val matches: Int = 0,
    val svGames: Int = 0,
    val held: Int = 0,
    val rtGames: Int = 0,
    val broke: Int = 0,
) {
    val hold: Double? get() = if (svGames >= 40) held.toDouble() / svGames else null
    val brk: Double? get() = if (rtGames >= 40) broke.toDouble() / rtGames else null
    operator fun plus(o: ServeRecord) = ServeRecord(matches + o.matches, svGames + o.svGames, held + o.held, rtGames + o.rtGames, broke + o.broke)
}

@Serializable
data class ServeStats(val all: ServeRecord, val bySurface: Map<String, ServeRecord> = emptyMap())

data class TennisMeeting(val date: Long, val won: Boolean, val score: String, val event: String, val surface: String?)

/** Everything known about one player before a match. Null/empty = not available in the free sources. */
data class TennisProfile(
    val name: String,
    val rank: Int?,
    val elo: Double?,
    val eloMatches: Int,
    val last: List<TennisMeeting>,            // most recent first (opponent in event text)
    val lastOpponents: List<String>,
    val wins6m: Int, val losses6m: Int,
    val surfaceWins: Int, val surfaceLosses: Int,
    val gamesShare: Double?,                  // share of games won, last 15 completed matches
    val matches3d: Int, val sets3d: Int,
    val daysSinceLast: Int?,
    val hold: Double?, val brk: Double?,      // ATP only (TML), surface-specific when enough data
    val serveMatches: Int,
    val serveOnSurface: Boolean,
)

data class TennisContext(
    val surface: String?,
    val home: TennisProfile?,
    val away: TennisProfile?,
    val h2h: List<TennisMeeting>,             // from the home player's side
    val avgHold: Double?,                     // tour average hold (TML)
    val windowNote: String,
)

object TennisModel {
    /** Starting rating from the current world ranking (rank 1 ≈ 2050, rank 100 ≈ 1590). */
    fun eloStart(rank: Int?): Double = if (rank == null || rank <= 0) 1400.0 else 2050.0 - 100.0 * ln(rank.toDouble())

    fun eloWin(ra: Double, rb: Double): Double = 1.0 / (1.0 + 10.0.pow((rb - ra) / 400.0))

    /**
     * Elo over all results in date order, started from the current ranking. Retirements are skipped.
     * Returns rating and number of rated matches per normalized name.
     */
    fun elo(results: List<TennisResult>, ranks: Map<String, Int>): Map<String, Pair<Double, Int>> {
        val r = HashMap<String, Double>(); val n = HashMap<String, Int>()
        val rankByNorm = ranks.entries.associate { TeamMatcher.normalize(it.key) to it.value }
        fun get(k: String) = r.getOrPut(k) { eloStart(rankByNorm[k]) }
        for (m in results.sortedBy { it.date }) {
            if (m.retired) continue
            val w = TeamMatcher.normalize(m.winner); val l = TeamMatcher.normalize(m.loser)
            val rw = get(w); val rl = get(l)
            val e = eloWin(rw, rl)
            val kw = 250.0 / ((n[w] ?: 0) + 5.0).pow(0.4) * 0.5
            val kl = 250.0 / ((n[l] ?: 0) + 5.0).pow(0.4) * 0.5
            r[w] = rw + kw * (1 - e); r[l] = rl - kl * (1 - e)
            n[w] = (n[w] ?: 0) + 1; n[l] = (n[l] ?: 0) + 1
        }
        rankByNorm.forEach { (k, v) -> if (!r.containsKey(k)) r[k] = eloStart(v) }
        return r.mapValues { (k, v) -> v to (n[k] ?: 0) }
    }

    /** Games won by each side in one result: (winner games, loser games). */
    fun games(m: TennisResult): Pair<Int, Int> {
        var a = 0; var b = 0
        m.sets.forEach { s -> val p = s.split("-"); a += p.getOrNull(0)?.toIntOrNull() ?: 0; b += p.getOrNull(1)?.toIntOrNull() ?: 0 }
        return a to b
    }

    /** Tournament surface from its name or city and month (built from the TML database + a few fixed events). */
    fun surfaceOf(names: List<String>, month: Int, map: Map<String, List<Pair<Int, String>>>): String? {
        for (raw in names) {
            val k = TeamMatcher.normalize(raw)
            if (k.isBlank()) continue
            FIXED.entries.firstOrNull { k.contains(it.key) }?.let { return it.value }
            val hit = map[k] ?: map.entries.firstOrNull { (key, _) -> key.length >= 4 && (k.startsWith(key) || key.startsWith(k)) }?.value
            if (hit != null && hit.isNotEmpty()) {
                return hit.minByOrNull { (m, _) -> minOf(Math.abs(m - month), 12 - Math.abs(m - month)) }?.second
            }
        }
        return null
    }

    private val FIXED = mapOf(
        "roland garros" to "Clay", "french open" to "Clay", "wimbledon" to "Grass", "australian open" to "Hard",
        "us open" to "Hard", "indian wells" to "Hard", "miami" to "Hard", "monte carlo" to "Clay", "madrid" to "Clay",
        "rome" to "Clay", "internazionali" to "Clay", "cincinnati" to "Hard", "shanghai" to "Hard", "beijing" to "Hard",
        "wuhan" to "Hard", "doha" to "Hard", "dubai" to "Hard", "queen s" to "Grass", "halle" to "Grass", "eastbourne" to "Grass",
        "s hertogenbosch" to "Grass", "berlin" to "Grass", "bad homburg" to "Grass", "mallorca" to "Grass", "nottingham" to "Grass",
        "riyadh" to "Hard", "turin" to "Hard", "stockholm" to "Hard", "basel" to "Hard",
        "vienna" to "Hard", "metz" to "Hard", "athens" to "Hard", "belgrade" to "Hard",
    )
}
