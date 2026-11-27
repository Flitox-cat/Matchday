package com.matchday.app.data.repo

import com.matchday.app.data.remote.Network
import com.matchday.app.domain.PlayerGame
import com.matchday.app.domain.PlayerLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Football players' recent games from the free ESPN game log: fouls committed and suffered, cards,
 * shots and goals per game. Cached on the phone for 12 hours per player.
 */
class PlayerRepository(private val cache: JsonCache) {
    private val gate = Semaphore(4)

    @Serializable
    private data class G(val d: Long, val o: String, val v: String, val s: String, val r: String, val l: String,
                         val fc: Int, val fa: Int, val yc: Int, val rc: Int, val g: Int, val sh: Int)

    @Serializable
    private data class Log(val games: List<G>)

    /** Last [n] games of each player (ids without a log are left out). */
    suspend fun logs(ids: Collection<String>, n: Int = 5): Map<String, PlayerLog> = coroutineScope {
        ids.distinct().map { id -> async { id to runCatching { log(id, n) }.getOrNull() } }.awaitAll()
            .mapNotNull { (id, l) -> l?.let { id to it } }.toMap()
    }

    suspend fun log(id: String, n: Int = 5): PlayerLog? {
        val l = cache.get("plog:$id", 12 * 3_600_000L, Log.serializer()) {
            Log(parse(gate.withPermit { Network.espn.soccerGamelog(id) }))
        }
        if (l.games.isEmpty()) return null
        return PlayerLog(id, l.games.sortedByDescending { it.d }.take(n).map {
            PlayerGame(it.d, it.o, it.v, it.s, it.r, it.l, it.fc, it.fa, it.yc, it.rc, it.g, it.sh)
        })
    }

    private fun o(e: JsonElement?) = e as? JsonObject
    private fun a(e: JsonElement?) = (e as? JsonArray).orEmpty()
    private fun s(e: JsonElement?) = (e as? JsonPrimitive)?.content

    private fun parse(root: JsonObject): List<G> {
        val names = a(root["names"]).mapNotNull { s(it) }
        fun idx(n: String) = names.indexOf(n)
        val iFc = idx("foulsCommitted"); val iFa = idx("foulsSuffered"); val iYc = idx("yellowCards"); val iRc = idx("redCards")
        val iG = idx("totalGoals"); val iSh = idx("totalShots")
        val meta = o(root["events"]) ?: JsonObject(emptyMap())
        val out = mutableListOf<G>()
        for (st in a(root["seasonTypes"])) for (cat in a(o(st)?.get("categories"))) for (ev in a(o(cat)?.get("events"))) {
            val e = o(ev) ?: continue
            val id = s(e["eventId"]) ?: continue
            val stats = a(e["stats"]).map { s(it)?.toDoubleOrNull()?.toInt() ?: 0 }
            fun v(i: Int) = if (i >= 0) stats.getOrElse(i) { 0 } else 0
            val m = o(meta[id])
            val date = runCatching { java.time.Instant.parse(s(m?.get("gameDate"))!!.let { if (it.length == 17) it.replace("Z", ":00Z") else it }).toEpochMilli() }.getOrNull() ?: continue
            out += G(
                date, s(o(m?.get("opponent"))?.get("displayName")) ?: "?", s(m?.get("atVs")) ?: "vs", s(m?.get("score")) ?: "",
                s(m?.get("gameResult")) ?: "", s(m?.get("leagueShortName")) ?: s(m?.get("leagueName")) ?: "",
                v(iFc), v(iFa), v(iYc), v(iRc), v(iG), v(iSh),
            )
        }
        return out.distinctBy { it.d to it.o }
    }
}
