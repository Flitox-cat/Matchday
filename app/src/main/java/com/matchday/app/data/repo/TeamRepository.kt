package com.matchday.app.data.repo

import android.util.Log
import com.matchday.app.data.local.AppDatabase
import com.matchday.app.data.remote.EEvent
import com.matchday.app.data.remote.ESchedule
import com.matchday.app.data.remote.ESearchResponse
import com.matchday.app.data.remote.ETeamResponse
import com.matchday.app.data.remote.ECoreTeam
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.Catalog
import com.matchday.app.domain.Competition
import com.matchday.app.domain.HistMatch
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamHistory
import com.matchday.app.domain.TeamMatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer

/** A search hit: a club or a competition. */
data class SearchHit(
    val kind: Kind,
    val id: String,               // ESPN team id, or competition key
    val name: String,
    val subtitle: String?,        // country / league
    val logo: String?,
    val leagueKey: String?,       // "soccer/eng.1" - where to load the team from
    val form: String? = null,     // recent form from the feed, e.g. "WWDLW"
) {
    enum class Kind { TEAM, COMPETITION }
}

/** One upcoming fixture on a team page. */
data class Fixture(
    val date: Long,
    val opponent: String,
    val opponentLogo: String?,
    val home: Boolean,
    val competition: String?,
    val venue: String?,
)

data class TeamPage(
    val id: String,
    val name: String,
    val logo: String?,
    val leagueKey: String?,
    val leagueName: String?,
    val country: String?,
    val stadium: String?,
    val standingSummary: String?,
    val table: LeagueTable?,
    val row: TableRow?,
    val upcoming: List<Fixture>,
    val updatedAt: Long?,
)

/**
 * Team search, team pages and multi-season match history. ESPN's free public feed only.
 * Requests are cached (past seasons for 30 days, the current season for 6 hours) and limited
 * to a few at a time to respect the source.
 */
class TeamRepository(private val db: AppDatabase, private val cache: JsonCache) {
    private val espn = Network.espn
    private val gate = Semaphore(4)
    private val histSer = ListSerializer(HistMatch.serializer())

    companion object {
        private const val TAG = "TeamRepository"
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        val EUROPE = listOf("uefa.champions", "uefa.europa", "uefa.europa.conf")
        const val SEASONS_BACK = 3

        /** Country of a domestic league slug (used when the feed gives no stadium address). */
        private val SLUG_COUNTRY = mapOf(
            "eng" to "England", "esp" to "Spain", "ger" to "Germany", "ita" to "Italy", "fra" to "France",
            "ned" to "Netherlands", "por" to "Portugal", "sco" to "Scotland", "bel" to "Belgium",
            "tur" to "Turkey", "aut" to "Austria", "sui" to "Switzerland", "gre" to "Greece",
            "den" to "Denmark", "nor" to "Norway", "swe" to "Sweden", "cze" to "Czech Republic",
            "usa" to "USA", "ksa" to "Saudi Arabia", "cro" to "Croatia", "srb" to "Serbia",
            "ukr" to "Ukraine", "pol" to "Poland", "rus" to "Russia", "cyp" to "Cyprus",
        )

        fun countryOfSlug(slug: String?): String? = slug?.substringBefore('.')?.let { SLUG_COUNTRY[it] }
    }

    private suspend fun <T> safe(key: String, ttl: Long, ser: kotlinx.serialization.KSerializer<T>, fetch: suspend () -> T): JsonCache.Timed<T>? =
        try { cache.getTimed(key, ttl, ser, fetch) } catch (e: Exception) {
            Log.w(TAG, "Unavailable: $key (${e.javaClass.simpleName})"); null
        }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /** Competitions + clubs from local data first (works offline). */
    suspend fun searchLocal(query: String): List<SearchHit> {
        val q = TeamMatcher.normalize(query)
        if (q.length < 2) return emptyList()
        val comps = Catalog.ALL.filter { TeamMatcher.normalize(it.title).contains(q) || it.key.contains(query.lowercase()) }
            .map { SearchHit(SearchHit.Kind.COMPETITION, it.key, it.title, it.sport.label, null, it.key) }
        val since = System.currentTimeMillis() - 2 * DAY
        val teams = LinkedHashMap<String, SearchHit>()
        for (e in db.matches().since(since)) {
            if (!e.leagueKey.startsWith("soccer/")) continue
            val comp = Catalog.find(e.leagueKey)
            val sides = listOf(
                listOf(e.homeId, e.homeTeam, e.homeLogo, e.homeForm),
                listOf(e.awayId, e.awayTeam, e.awayLogo, e.awayForm),
            )
            for ((id, name, logo, form) in sides) {
                if (id == null || name == null || teams.containsKey(id)) continue
                if (TeamMatcher.normalize(name).contains(q)) teams[id] = SearchHit(SearchHit.Kind.TEAM, id, name, comp.title, logo, e.leagueKey, form?.takeIf { it.isNotBlank() })
            }
        }
        return comps + teams.values.sortedBy { it.name.length }.take(10)
    }

    /** ESPN's public search (football clubs). Empty on error / offline. */
    suspend fun searchRemote(query: String): List<SearchHit> {
        val q = query.trim()
        if (q.length < 3) return emptyList()
        val r = safe("search:${q.lowercase()}", 24 * HOUR, ESearchResponse.serializer()) {
            gate.withPermit { Network.espnSearch.search(q) }
        }?.value ?: return emptyList()
        return r.items.filter { it.type == "team" && (it.sport == "soccer" || it.sport == null) && it.id != null && it.displayName != null }
            .map {
                val slug = it.defaultLeagueSlug ?: it.league
                val league = slug?.let { s -> Catalog.ALL.firstOrNull { c -> c.key == "soccer/$s" }?.title }
                SearchHit(
                    SearchHit.Kind.TEAM, it.id!!, it.displayName!!,
                    listOfNotNull(it.location?.takeIf { l -> l != it.displayName }, league).joinToString(" · ").ifBlank { null },
                    "https://a.espncdn.com/i/teamlogos/soccer/500/${it.id}.png",
                    slug?.let { s -> "soccer/$s" },
                )
            }
    }

    // -----------------------------------------------------------------------
    // Team page
    // -----------------------------------------------------------------------

    suspend fun teamPage(leagueKey: String, teamId: String, tableFor: suspend (String) -> LeagueTable?): TeamPage? = coroutineScope {
        val sport = leagueKey.substringBefore('/')
        val league = leagueKey.substringAfter('/')
        val siteJob = async { safe("team:$sport:$teamId", 24 * HOUR, ETeamResponse.serializer()) { gate.withPermit { espn.team(sport, league, teamId) } } }
        val coreJob = async { safe("coreteam:$sport:$teamId", 24 * HOUR, ECoreTeam.serializer()) { gate.withPermit { Network.espnCore.team(sport, league, teamId) } } }
        val site = siteJob.await()
        val t = site?.value?.team
        val domestic = t?.defaultLeague?.slug ?: league
        val domesticKey = "$sport/$domestic"
        val tableJob = async { tableFor(domesticKey) }
        val fixturesJob = async { upcoming(sport, domestic, teamId) }
        val core = coreJob.await()?.value
        val table = tableJob.await()
        val name = t?.displayName ?: core?.displayName ?: return@coroutineScope null
        TeamPage(
            id = teamId, name = name, logo = t?.logos?.firstOrNull()?.href,
            leagueKey = domesticKey, leagueName = t?.defaultLeague?.name,
            country = core?.venue?.address?.country ?: countryOfSlug(domestic),
            stadium = core?.venue?.fullName,
            standingSummary = t?.standingSummary,
            table = table, row = table?.row(teamId, name),
            upcoming = fixturesJob.await(),
            updatedAt = site?.fetchedAt,
        )
    }

    /** Next fixtures across all competitions the feed lists for the club. */
    private suspend fun upcoming(sport: String, domestic: String, teamId: String): List<Fixture> {
        val now = System.currentTimeMillis()
        val slugs = listOf(domestic) + EUROPE
        return coroutineScope {
            slugs.map { slug ->
                async {
                    safe("fixtures:$sport/$slug:$teamId", 6 * HOUR, ESchedule.serializer()) {
                        gate.withPermit { espn.schedule(sport, slug, teamId, fixture = true) }
                    }?.value?.events.orEmpty().mapNotNull { e -> fixture(e, teamId, slug) }
                }
            }.awaitAll().flatten()
        }.filter { it.date >= now - 2 * HOUR }
            .distinctBy { it.date / HOUR }
            .sortedBy { it.date }
            .take(8)
    }

    private fun fixture(e: EEvent, teamId: String, slug: String): Fixture? {
        val c = e.competitions.firstOrNull() ?: return null
        val me = c.competitors.firstOrNull { it.team?.id == teamId } ?: return null
        val opp = c.competitors.firstOrNull { it !== me } ?: return null
        val st = c.status ?: e.status
        if (st?.type?.completed == true || st?.type?.state == "post") return null
        return Fixture(
            date = EspnMapper.parseTime(c.date ?: e.date),
            opponent = opp.team?.displayName ?: return null,
            opponentLogo = opp.team?.logo,
            home = me.homeAway == "home",
            competition = Catalog.ALL.firstOrNull { it.key == "soccer/$slug" }?.title ?: e.season?.displayName,
            venue = (c.venue ?: e.venue)?.fullName,
        )
    }

    // -----------------------------------------------------------------------
    // History (several seasons, domestic league + UEFA competitions)
    // -----------------------------------------------------------------------

    /** Real completed matches for a club over the current and last [SEASONS_BACK] seasons. */
    suspend fun history(leagueKey: String, teamId: String): TeamHistory? = coroutineScope {
        val sport = leagueKey.substringBefore('/')
        if (sport != "soccer") return@coroutineScope null
        val site = safe("team:$sport:$teamId", 24 * HOUR, ETeamResponse.serializer()) {
            gate.withPermit { espn.team(sport, leagueKey.substringAfter('/'), teamId) }
        }?.value?.team
        val domestic = site?.defaultLeague?.slug ?: leagueKey.substringAfter('/')
        val domesticName = site?.defaultLeague?.name

        // Current season of the domestic league tells us which years to ask for.
        val current = safe("sched:$sport/$domestic:$teamId", 6 * HOUR, ESchedule.serializer()) {
            gate.withPermit { espn.schedule(sport, domestic, teamId) }
        }?.value
        val year = current?.season?.year ?: guessSeasonYear()
        val years = (year - SEASONS_BACK)..year

        val jobs = (listOf(domestic) + EUROPE).flatMap { slug ->
            years.map { y ->
                async {
                    val ttl = if (y == year) 6 * HOUR else 30 * DAY
                    val name = if (slug == domestic) domesticName ?: Catalog.find("soccer/$slug").title
                    else Catalog.find("soccer/$slug").title
                    if (slug == domestic && y == year && current != null) {
                        HistChunk(mapHistory(current, slug, name), true)
                    } else {
                        val r = try {
                            cache.get("hist:$sport/$slug:$teamId:$y", ttl, histSer) {
                                val s = gate.withPermit { espn.schedule(sport, slug, teamId, season = y) }
                                mapHistory(s, slug, name)
                            }
                        } catch (e: retrofit2.HttpException) {
                            // 400/404: the club didn't play in that competition that season.
                            if (e.code() in 400..499) emptyList<HistMatch>().also { cache.put("hist:$sport/$slug:$teamId:$y", histSer, it) } else null
                        } catch (e: Exception) {
                            Log.w(TAG, "History unavailable $slug $y", e); null
                        }
                        HistChunk(r.orEmpty(), r != null)
                    }
                }
            }
        }.awaitAll()
        if (jobs.none { it.ok }) return@coroutineScope null
        val all = jobs.flatMap { it.matches }.distinctBy { it.id }.sortedByDescending { it.date }
        TeamHistory(teamId, all, years.first, years.last, System.currentTimeMillis())
    }

    private data class HistChunk(val matches: List<HistMatch>, val ok: Boolean)

    private fun guessSeasonYear(): Int {
        val d = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        return if (d.monthValue >= 7) d.year else d.year - 1
    }

    private fun mapHistory(s: ESchedule, slug: String, competition: String): List<HistMatch> =
        s.events.mapNotNull { e ->
            val c = e.competitions.firstOrNull() ?: return@mapNotNull null
            val st = c.status ?: e.status
            if (!(st?.type?.completed == true || st?.type?.state == "post")) return@mapNotNull null
            val h = c.competitors.firstOrNull { it.homeAway == "home" } ?: return@mapNotNull null
            val a = c.competitors.firstOrNull { it.homeAway == "away" } ?: return@mapNotNull null
            val hg = EspnMapper.goals(h.scoreText) ?: return@mapNotNull null
            val ag = EspnMapper.goals(a.scoreText) ?: return@mapNotNull null
            val v = c.venue ?: e.venue
            HistMatch(
                id = e.id.ifBlank { c.id ?: return@mapNotNull null },
                date = EspnMapper.parseTime(c.date ?: e.date),
                competition = competition,
                leagueSlug = slug,
                homeId = h.team?.id, awayId = a.team?.id,
                homeName = h.team?.displayName ?: "?", awayName = a.team?.displayName ?: "?",
                homeGoals = hg, awayGoals = ag,
                venue = v?.fullName, city = v?.address?.city,
                country = v?.address?.country ?: if (!slug.startsWith("uefa.")) countryOfSlug(slug) else null,
            )
        }

    @Suppress("unused")
    private fun isFootball(c: Competition) = c.sport == SportType.FOOTBALL
}
