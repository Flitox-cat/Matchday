package com.matchday.app.data.repo

import com.matchday.app.data.local.AppDatabase
import com.matchday.app.data.local.AppSettings
import com.matchday.app.data.local.EventEntity
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.data.local.OddsEntity
import com.matchday.app.data.local.ScoreEntity
import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.AfFixtureItem
import com.matchday.app.data.remote.AfLineup
import com.matchday.app.data.remote.Network
import com.matchday.app.data.remote.OddsEventDto
import com.matchday.app.domain.Catalog
import com.matchday.app.domain.Competition
import com.matchday.app.domain.InjuryInfo
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.ModelInputs
import com.matchday.app.domain.OddsSummary
import com.matchday.app.domain.Prediction
import com.matchday.app.domain.PredictionEngine
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.TeamStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class RefreshResult(val ok: Boolean, val messages: List<String>, val creditsUsed: Int)

/** Everything the match screen shows beyond the list data. Filled progressively. */
data class DetailExtras(
    val loading: Boolean = true,
    val homeStats: TeamStats? = null,
    val awayStats: TeamStats? = null,
    val homeRecent: List<RecentResult> = emptyList(),
    val awayRecent: List<RecentResult> = emptyList(),
    val headToHead: List<RecentResult> = emptyList(),
    val injuries: InjuryInfo = InjuryInfo(known = false),
    val lineups: List<AfLineup> = emptyList(),
    val venue: String? = null,
    val city: String? = null,
    val weather: WeatherInfo? = null,
    val odds: OddsSummary? = null,
    val prediction: Prediction? = null,
    val notes: List<String> = emptyList(),
)

class MatchRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val fd: FootballDataRepository,
    private val af: ApiFootballRepository,
    private val weather: WeatherRepository,
    private val cache: JsonCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oddsApi = Network.oddsApi
    private val refreshMutex = Mutex()

    private val compStats = MutableStateFlow<Map<String, CompetitionStats>>(emptyMap())
    private val afFixtures = MutableStateFlow<List<AfFixtureItem>>(emptyList())
    private val afLive = MutableStateFlow<List<AfFixtureItem>>(emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages

    private val predictionMemo = ConcurrentHashMap<String, Prediction>()

    private companion object {
        const val HOUR = 3_600_000L
        const val REGIONS = "eu"
        const val SCORES_TTL = 10 * 60_000L
    }

    // -----------------------------------------------------------------------
    // Refresh (network -> Room)
    // -----------------------------------------------------------------------

    suspend fun refresh(force: Boolean): RefreshResult = refreshMutex.withLock {
        _refreshing.value = true
        try {
            val result = doRefresh(force)
            _messages.value = result.messages
            if (result.ok) scope.launch { loadContext() }
            result
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun doRefresh(force: Boolean): RefreshResult {
        val s = settings.current()
        val key = s.effectiveOddsKey
        val messages = mutableListOf<String>()
        if (key.isBlank()) {
            return RefreshResult(false, listOf("Add your The Odds API key in Settings to load fixtures and odds."), 0)
        }
        val now = System.currentTimeMillis()
        val comps = resolveCompetitions(s, key)
        var credits = 0
        var anyOk = false

        for (comp in comps) {
            val stampKey = "refresh:odds:${comp.key}"
            val age = cache.ageMs(stampKey)
            val due = force || age == null || age > s.refreshMinutes * 60_000L
            if (!due) { anyOk = true; continue }
            try {
                var resp = oddsApi.odds(comp.key, key, REGIONS, comp.markets, commenceTimeTo = isoIn(7 * 24 * HOUR))
                if (resp.code() == 422 && comp.markets != "h2h") {
                    resp = oddsApi.odds(comp.key, key, REGIONS, "h2h", commenceTimeTo = isoIn(7 * 24 * HOUR))
                }
                trackQuota(resp)
                when {
                    resp.code() == 401 -> return RefreshResult(false, listOf("The Odds API rejected the key (401). Check it in Settings."), credits)
                    resp.code() == 429 -> { messages += "The Odds API quota or rate limit reached (429). Showing cached data."; break }
                    !resp.isSuccessful -> { messages += "${comp.title}: The Odds API error ${resp.code()}"; continue }
                }
                val body = resp.body().orEmpty()
                credits += Catalog.creditCost(comp)
                store(comp, body, now)
                cache.touch(stampKey)
                anyOk = true
            } catch (e: Exception) {
                messages += "${comp.title}: ${e.message ?: "network error"}"
            }
        }

        // Scores only for sports with matches that started in the last ~30h (costs 2 credits each).
        val recent = db.events().since(now - 30 * HOUR).filter { it.commenceTime <= now }
        for (sportKey in recent.map { it.sportKey }.distinct()) {
            val stampKey = "refresh:scores:$sportKey"
            val age = cache.ageMs(stampKey)
            if (!force && age != null && age < SCORES_TTL) continue
            try {
                val resp = oddsApi.scores(sportKey, key, daysFrom = 1)
                trackQuota(resp)
                if (resp.isSuccessful) {
                    storeScores(resp.body().orEmpty().map { dto ->
                        ScoreEntity(
                            eventId = dto.id, sportKey = dto.sportKey, sportTitle = dto.sportTitle,
                            homeTeam = dto.homeTeam ?: "", awayTeam = dto.awayTeam ?: "",
                            commenceTime = parseIso(dto.commenceTime), completed = dto.completed,
                            homeScore = dto.scores?.firstOrNull { it.name == dto.homeTeam }?.score,
                            awayScore = dto.scores?.firstOrNull { it.name == dto.awayTeam }?.score,
                            updatedAt = now,
                        )
                    })
                    cache.touch(stampKey)
                    credits += 2
                }
            } catch (_: Exception) { /* scores are optional */ }
        }

        // Housekeeping
        db.events().deleteBefore(now - 36 * HOUR)
        db.odds().deleteBefore(now - 7 * 24 * HOUR)
        db.scores().deleteBefore(now - 3 * 24 * HOUR)
        db.notified().deleteBefore(now - 7 * 24 * HOUR)
        db.cache().deleteBefore(now - 30L * 24 * HOUR)
        predictionMemo.clear()
        settings.markRefreshed(now)

        return RefreshResult(anyOk, messages, credits)
    }

    private suspend fun resolveCompetitions(s: AppSettings, key: String): List<Competition> {
        val list = mutableListOf<Competition>()
        for (k in s.enabledSports) {
            if (k == Catalog.TENNIS_ALL) {
                try {
                    val r = oddsApi.sports(key)
                    if (r.isSuccessful) {
                        r.body().orEmpty()
                            .filter { it.group.equals("Tennis", true) && it.active && !it.hasOutrights }
                            .forEach { list += Catalog.find(it.key, it.title) }
                    }
                } catch (_: Exception) { }
            } else {
                list += Catalog.find(k)
            }
        }
        return list.sortedByDescending { it.tier }
    }

    private suspend fun store(comp: Competition, events: List<OddsEventDto>, now: Long) {
        val entities = events.mapNotNull { e ->
            val home = e.homeTeam ?: return@mapNotNull null
            val away = e.awayTeam ?: return@mapNotNull null
            EventEntity(e.id, e.sportKey, e.sportTitle.ifBlank { comp.title }, home, away, parseIso(e.commenceTime), now)
        }
        val rows = events.flatMap { e ->
            e.bookmakers.flatMap { b ->
                b.markets.flatMap { m ->
                    m.outcomes.map { o ->
                        OddsEntity(
                            eventId = e.id, bookmakerKey = b.key, bookmakerTitle = b.title, market = m.key,
                            outcome = o.name, point = o.point, price = o.price,
                            bookUpdatedAt = parseIso(m.lastUpdate ?: b.lastUpdate ?: ""), fetchedAt = now,
                        )
                    }
                }
            }
        }
        db.events().upsert(entities)
        if (rows.isNotEmpty()) db.odds().insertAll(rows)
        if (entities.isNotEmpty()) db.events().deleteMissing(comp.key, now, entities.map { it.id })
    }

    private suspend fun storeScores(rows: List<ScoreEntity>) {
        if (rows.isNotEmpty()) db.scores().upsert(rows.filter { it.homeTeam.isNotBlank() })
    }

    private suspend fun trackQuota(resp: Response<*>) {
        val remaining = resp.headers()["x-requests-remaining"]?.toDoubleOrNull()?.toInt()
        val used = resp.headers()["x-requests-used"]?.toDoubleOrNull()?.toInt()
        if (remaining != null || used != null) settings.setQuota(remaining, used)
    }

    /** Loads league tables and API-Football fixtures in the background (cached, rate limited). */
    suspend fun loadContext() {
        val s = settings.current()
        val codes = s.enabledSports.mapNotNull { Catalog.find(it).fdCode }.distinct()
        if (fd.hasKey()) {
            for (code in codes) {
                val stats = fd.competitionStats(code) ?: continue
                compStats.value = compStats.value + (code to stats)
                predictionMemo.clear()
            }
        }
        if (af.hasKey()) {
            val today = LocalDate.now(ZoneOffset.UTC)
            val all = mutableListOf<AfFixtureItem>()
            for (d in listOf(today, today.plusDays(1))) all += af.fixtures(d.toString())
            afFixtures.value = all
        }
    }

    suspend fun refreshLive(): List<String> {
        val notes = mutableListOf<String>()
        if (af.hasKey()) afLive.value = af.live() else notes += "Add an API-Football key for live minute-by-minute football scores."
        val s = settings.current()
        val key = s.effectiveOddsKey
        if (key.isBlank()) return notes + "Add The Odds API key for scores in other sports."
        val now = System.currentTimeMillis()
        val inPlay = db.events().since(now - 5 * HOUR).filter { it.commenceTime <= now }.map { it.sportKey }.distinct()
        for (sport in inPlay) {
            val stamp = "refresh:live:$sport"
            val age = cache.ageMs(stamp)
            if (age != null && age < 2 * 60_000L) continue
            try {
                val resp = oddsApi.scores(sport, key) // 1 credit: live + upcoming only
                trackQuota(resp)
                if (resp.isSuccessful) {
                    storeScores(resp.body().orEmpty().filter { it.scores != null }.map { dto ->
                        ScoreEntity(
                            eventId = dto.id, sportKey = dto.sportKey, sportTitle = dto.sportTitle,
                            homeTeam = dto.homeTeam ?: "", awayTeam = dto.awayTeam ?: "",
                            commenceTime = parseIso(dto.commenceTime), completed = dto.completed,
                            homeScore = dto.scores?.firstOrNull { it.name == dto.homeTeam }?.score,
                            awayScore = dto.scores?.firstOrNull { it.name == dto.awayTeam }?.score,
                            updatedAt = now,
                        )
                    })
                    cache.touch(stamp)
                }
            } catch (_: Exception) { }
        }
        return notes
    }

    // -----------------------------------------------------------------------
    // Observation (Room + memory -> UI models)
    // -----------------------------------------------------------------------

    private data class Base(
        val events: List<EventEntity>,
        val odds: Map<String, List<OddsEntity>>,
        val scores: List<ScoreEntity>,
        val favourites: List<FavouriteEntity>,
        val stats: Map<String, CompetitionStats>,
    )

    private data class Extra(val fixtures: List<AfFixtureItem>, val live: List<AfFixtureItem>)

    fun observeMatches(): Flow<List<MatchItem>> {
        val since = System.currentTimeMillis() - 36 * HOUR
        val base = combine(
            db.events().observeSince(since),
            db.odds().observeLatest().map { rows -> rows.groupBy { it.eventId } },
            db.scores().observeSince(since),
            db.favourites().observeAll(),
            compStats,
        ) { e, o, sc, fav, st -> Base(e, o, sc, fav, st) }
        val extra = combine(afFixtures, afLive) { f, l -> Extra(f, l) }
        return combine(base, extra) { b, x -> build(b, x) }.flowOn(Dispatchers.Default)
    }

    fun observeFavourites(): Flow<List<FavouriteEntity>> = db.favourites().observeAll()

    private fun build(b: Base, x: Extra): List<MatchItem> {
        val now = System.currentTimeMillis()
        val favTeams = b.favourites.filter { it.type == "TEAM" }.map { it.name }
        val favComps = b.favourites.filter { it.type == "COMPETITION" }.map { it.key.removePrefix("COMP:") }.toSet()
        val scoreById = b.scores.associateBy { it.eventId }
        val afAll = x.live + x.fixtures

        val items = b.events.map { e ->
            val comp = Catalog.find(e.sportKey, e.sportTitle)
            val odds = b.odds[e.id]?.let { OddsMapper.summarize(it, e.homeTeam, e.awayTeam, comp.sport) }
            toItem(comp, e.id, e.homeTeam, e.awayTeam, e.commenceTime, odds, scoreById[e.id], b.stats, afAll, favTeams, favComps, now)
        }.toMutableList()

        // Completed/live games that are no longer in the odds feed.
        val known = items.map { it.id }.toSet()
        for (sc in b.scores) {
            if (sc.eventId in known) continue
            val comp = Catalog.find(sc.sportKey, sc.sportTitle)
            items += toItem(comp, sc.eventId, sc.homeTeam, sc.awayTeam, sc.commenceTime, null, sc, b.stats, afAll, favTeams, favComps, now)
        }
        return items.sortedBy { it.kickoff }
    }

    private fun toItem(
        comp: Competition, id: String, home: String, away: String, kickoff: Long,
        odds: OddsSummary?, score: ScoreEntity?, stats: Map<String, CompetitionStats>,
        afAll: List<AfFixtureItem>, favTeams: List<String>, favComps: Set<String>, now: Long,
    ): MatchItem {
        val st = comp.fdCode?.let { stats[it] }
        val homeStats = st?.teamStats(home)
        val awayStats = st?.teamStats(away)
        val afMatch = if (comp.sport == SportType.FOOTBALL) af.find(afAll, home, away, kickoff) else null
        val afShort = afMatch?.fixture?.status?.short

        val window = when (comp.sport) {
            SportType.FOOTBALL -> 2 * HOUR + 15 * 60_000L
            SportType.BASKETBALL -> 3 * HOUR
            SportType.BASEBALL -> 3 * HOUR + 30 * 60_000L
            else -> 4 * HOUR
        }
        val status = when {
            score?.completed == true || afShort in setOf("FT", "AET", "PEN") -> MatchStatus.FINISHED
            afShort in setOf("1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT") -> MatchStatus.LIVE
            now >= kickoff && now < kickoff + window -> MatchStatus.LIVE
            now >= kickoff + window -> MatchStatus.FINISHED
            else -> MatchStatus.UPCOMING
        }
        val useAfScore = afMatch != null && afMatch.goals.home != null && status != MatchStatus.UPCOMING
        val homeScore = if (useAfScore) afMatch?.goals?.home?.toString() else score?.homeScore
        val awayScore = if (useAfScore) afMatch?.goals?.away?.toString() else score?.awayScore

        val memoKey = "$id|${odds?.fetchedAt}|${st?.fetchedAt}"
        val prediction = if (status == MatchStatus.UPCOMING || odds != null) {
            predictionMemo[memoKey] ?: PredictionEngine.predict(
                comp, home, away, odds,
                if (homeStats != null && awayStats != null) ModelInputs(homeStats, awayStats, st?.league, InjuryInfo(false)) else null,
            )?.also { predictionMemo[memoKey] = it }
        } else null

        val favourite = favTeams.any { TeamMatcher.same(it, home) || TeamMatcher.same(it, away) } || comp.key in favComps
        val venue = afMatch?.fixture?.venue?.let { v -> listOfNotNull(v.name, v.city).joinToString(", ").ifBlank { null } }
        return MatchItem(
            id = id, competition = comp, homeTeam = home, awayTeam = away, kickoff = kickoff,
            status = status, homeScore = homeScore, awayScore = awayScore,
            minute = afMatch?.fixture?.status?.elapsed?.takeIf { status == MatchStatus.LIVE },
            odds = odds, prediction = prediction,
            homeCrest = homeStats?.crest ?: afMatch?.teams?.home?.logo,
            awayCrest = awayStats?.crest ?: afMatch?.teams?.away?.logo,
            venue = venue,
            popular = comp.tier >= 8 || favourite,
            favourite = favourite,
        )
    }

    fun observeMatch(id: String): Flow<MatchItem?> = observeMatches().map { list -> list.firstOrNull { it.id == id } }

    /** Deep analysis for one match: all-competition form, H2H, injuries, line-ups, weather. */
    fun loadDetail(item: MatchItem): Flow<DetailExtras> = flow {
        val comp = item.competition
        var x = DetailExtras(loading = true)
        val notes = mutableListOf<String>()

        // Full odds history (current vs previous refresh) for movement arrows.
        val rows = db.odds().forEvent(item.id)
        val odds = OddsMapper.summarize(rows, item.homeTeam, item.awayTeam, comp.sport)
        x = x.copy(odds = odds)
        emit(x)

        var homeStats: TeamStats? = null
        var awayStats: TeamStats? = null
        var league: com.matchday.app.domain.LeagueAverages? = null
        if (comp.sport == SportType.FOOTBALL) {
            val code = comp.fdCode
            if (code != null && fd.hasKey()) {
                val st = compStats.value[code] ?: fd.competitionStats(code)?.also { compStats.value = compStats.value + (code to it) }
                homeStats = st?.teamStats(item.homeTeam)
                awayStats = st?.teamStats(item.awayTeam)
                league = st?.league
                x = x.copy(homeStats = homeStats, awayStats = awayStats)
                emit(x)
                val hr = homeStats?.fdId?.let { fd.teamRecent(it) }.orEmpty()
                val ar = awayStats?.fdId?.let { fd.teamRecent(it) }.orEmpty()
                if (hr.isNotEmpty()) homeStats = homeStats?.copy(recent = hr)
                if (ar.isNotEmpty()) awayStats = awayStats?.copy(recent = ar)
                x = x.copy(homeStats = homeStats, awayStats = awayStats, homeRecent = hr.ifEmpty { homeStats?.recent.orEmpty() },
                    awayRecent = ar.ifEmpty { awayStats?.recent.orEmpty() })
                emit(x)
                val h2h = fd.headToHead(code, item.homeTeam, item.awayTeam, item.kickoff)
                x = x.copy(headToHead = h2h)
                emit(x)
                if (homeStats == null || awayStats == null) notes += "Team statistics could not be matched for this fixture."
            } else if (code == null) {
                notes += "${comp.title} is not covered by football-data.org's free plan: prediction uses bookmaker prices only."
            } else {
                notes += "Add a football-data.org key in Settings for form, tables and head-to-head."
            }

            if (af.hasKey()) {
                val date = Instant.ofEpochMilli(item.kickoff).atZone(ZoneOffset.UTC).toLocalDate().toString()
                val fixture = af.find(af.fixtures(date), item.homeTeam, item.awayTeam, item.kickoff)
                if (fixture != null) {
                    val injuries = af.injuries(fixture.fixture.id, fixture.teams.home.id, fixture.teams.away.id)
                    val untilKickoff = item.kickoff - System.currentTimeMillis()
                    val lineups = if (untilKickoff < 90 * 60_000L) af.lineups(fixture.fixture.id) else emptyList()
                    val city = fixture.fixture.venue.city
                    x = x.copy(
                        injuries = injuries, lineups = lineups,
                        venue = fixture.fixture.venue.name, city = city,
                    )
                    emit(x)
                    if (city != null) {
                        x = x.copy(weather = weather.atKickoff(city, item.kickoff))
                        emit(x)
                    }
                    if (lineups.isEmpty() && untilKickoff > 0) notes += "Line-ups are usually published 20-60 minutes before kick-off."
                } else {
                    notes += "Fixture not found on API-Football: injuries and venue unavailable."
                }
            } else {
                notes += "Add an API-Football key for injuries, suspensions, line-ups, venue and weather."
            }
        } else {
            notes += "Team statistics and injuries are only available for football; ${comp.sport.label} predictions use bookmaker consensus."
        }

        val inputs = if (homeStats != null && awayStats != null) ModelInputs(homeStats, awayStats, league, x.injuries) else null
        val prediction = PredictionEngine.predict(comp, item.homeTeam, item.awayTeam, odds, inputs)
        emit(x.copy(loading = false, prediction = prediction, notes = notes))
    }.flowOn(Dispatchers.IO)

    // -----------------------------------------------------------------------
    // Favourites
    // -----------------------------------------------------------------------

    suspend fun toggleTeam(name: String) {
        val key = "TEAM:" + TeamMatcher.normalize(name)
        val existing = db.favourites().all().firstOrNull { it.type == "TEAM" && TeamMatcher.same(it.name, name) }
        if (existing != null) db.favourites().delete(existing.key)
        else db.favourites().insert(FavouriteEntity(key, "TEAM", name, System.currentTimeMillis()))
    }

    suspend fun toggleCompetition(comp: Competition) {
        val key = "COMP:${comp.key}"
        val existing = db.favourites().all().firstOrNull { it.key == key }
        if (existing != null) db.favourites().delete(key)
        else db.favourites().insert(FavouriteEntity(key, "COMPETITION", comp.title, System.currentTimeMillis()))
    }

    suspend fun removeFavourite(key: String) = db.favourites().delete(key)

    // -----------------------------------------------------------------------

    private fun isoIn(ms: Long): String =
        Instant.ofEpochMilli(System.currentTimeMillis() + ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    private fun parseIso(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }
}
