package com.matchday.app.data.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import com.matchday.app.data.remote.ELeague
import com.matchday.app.data.remote.EEvent
import android.util.Log
import com.matchday.app.data.local.AppDatabase
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.data.local.MatchEntity
import com.matchday.app.data.local.OddsSnap
import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.remote.EScoreboard
import com.matchday.app.data.remote.EStandings
import com.matchday.app.data.remote.ESummary
import com.matchday.app.data.remote.Network
import com.matchday.app.domain.BttsAnalysis
import com.matchday.app.domain.BttsEngine
import com.matchday.app.domain.Catalog
import com.matchday.app.domain.Competition
import com.matchday.app.domain.InjuryInfo
import com.matchday.app.domain.LineupInfo
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

data class RefreshResult(val ok: Boolean, val messages: List<String>)

/** Everything the match screen shows beyond the list data. Filled progressively. */
data class DetailExtras(
    val loading: Boolean = true,
    val homeStats: TeamStats? = null,
    val awayStats: TeamStats? = null,
    val homeRecent: List<RecentResult> = emptyList(),
    val awayRecent: List<RecentResult> = emptyList(),
    val headToHead: List<RecentResult> = emptyList(),
    val injuries: InjuryInfo = InjuryInfo(known = false),
    val lineups: List<LineupInfo> = emptyList(),
    val venue: String? = null,
    val city: String? = null,
    val weather: WeatherInfo? = null,
    val prediction: Prediction? = null,
    val notes: List<String> = emptyList(),
    val homeProfile: TeamProfile? = null,
    val awayProfile: TeamProfile? = null,
    val summaryUpdatedAt: Long? = null,
    val btts: BttsAnalysis? = null,
)

/** Everything the team sections show for one club. Null fields = data unavailable. */
data class TeamProfile(
    val name: String,
    val logo: String?,
    val teamId: String?,
    val country: String?,
    val stadium: String?,
    val leagueName: String?,
    val leagueKey: String?,
    val table: LeagueTable?,
    val row: TableRow?,
    val standingSummary: String?,
    val leagueForm: List<RecentResult>,
    val recent: List<RecentResult>,
    val updatedAt: Long?,
)

/**
 * Single source of truth for matches. Data comes from ESPN's free public feed
 * (no API key): scoreboards per league and day, match summaries and league tables.
 */
class MatchRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val weather: WeatherRepository,
    private val cache: JsonCache,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val footballData = FootballDataRepository(cache, settings)
    val tracker = PickTracker(cache)
    private val apiFootball = ApiFootballRepository(cache, settings)
    private val espn = Network.espn
    private val refreshMutex = Mutex()
    private val gate = Semaphore(6) // be polite to the public feed

    private val tables = MutableStateFlow<Map<String, LeagueTable>>(emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    private val _messages = MutableStateFlow<List<String>>(emptyList())
    val messages: StateFlow<List<String>> = _messages

    private val predictionMemo = ConcurrentHashMap<String, Prediction>()
    // ConcurrentHashMap rejects null values, so "no odds" is stored as ParsedOdds(null).
    private class ParsedOdds(val snap: OddsSnap?)
    private val oddsMemo = ConcurrentHashMap<String, ParsedOdds>()

    private companion object {
        const val TAG = "MatchRepository"
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    // -----------------------------------------------------------------------
    // Refresh (network -> Room)
    // -----------------------------------------------------------------------

    suspend fun refresh(force: Boolean): RefreshResult = refreshMutex.withLock {
        _refreshing.value = true
        try {
            val r = doRefresh(force)
            _messages.value = r.messages
            scope.launch { loadContext() }
            r
        } finally {
            _refreshing.value = false
        }
    }

    private suspend fun doRefresh(force: Boolean): RefreshResult {
        val s = settings.current()
        val now = System.currentTimeMillis()
        val comps = s.enabledLeagues.map { Catalog.find(it) }
        val messages = mutableListOf<String>()
        var anyOk = false
        val today = LocalDate.now(ZoneOffset.UTC)
        val days = (-1..6).map { today.plusDays(it.toLong()) }

        val inPlay = db.matches().since(now - 8 * HOUR)
            .filter { it.state == "in" || (it.state == "pre" && it.kickoff <= now) }
            .map { it.leagueKey }.toSet()

        coroutineScope {
            comps.map { comp ->
                async {
                    val stamp = "refresh:${comp.key}"
                    val age = cache.ageMs(stamp)
                    if (!force && comp.key !in inPlay && age != null && age < s.refreshMinutes * 60_000L) return@async true
                    val ok = refreshLeague(comp, days, now)
                    if (ok) cache.touch(stamp)
                    ok
                }
            }.awaitAll().forEachIndexed { i, ok ->
                if (ok) anyOk = true else messages += "${comps[i].title}: couldn't load (check your connection)."
            }
        }

        // Settle tracked recommendations before old matches are removed.
        runCatching { tracker.settle(db.matches().since(now - 3 * DAY).filter { it.state == "post" }) }
        db.matches().deleteBefore(now - 2 * DAY)
        db.notified().deleteBefore(now - 7 * DAY)
        db.cache().deleteBefore(now - 30 * DAY)
        predictionMemo.clear()
        oddsMemo.clear()
        if (anyOk) settings.markRefreshed(now)
        return RefreshResult(anyOk || comps.isEmpty(), messages)
    }

    /** Downloads one league for the given days; returns false if nothing could be fetched. */
    private suspend fun refreshLeague(comp: Competition, days: List<LocalDate>, now: Long): Boolean = coroutineScope {
        val boards: List<EScoreboard?> = if (comp.sport == SportType.TENNIS) {
            listOf(fetchBoard(comp, null))
        } else {
            days.map { d -> async { fetchBoard(comp, d.format(YMD)) } }.awaitAll()
        }
        val okBoards = boards.filterNotNull()
        if (okBoards.isEmpty()) return@coroutineScope false
        val rows = okBoards.flatMap { b ->
            val logo = b.leagues.firstOrNull()?.logos?.firstOrNull()?.href
            b.events.flatMap { e -> EspnMapper.matches(comp, e, logo, now) }
        }.filter { comp.sport != SportType.TENNIS || it.kickoff >= now - 2 * DAY }
            .distinctBy { it.id }
        if (rows.isNotEmpty()) db.matches().upsert(rows)
        if (rows.isEmpty() && okBoards.any { it.events.isNotEmpty() }) Log.w(TAG, "${comp.key}: events found but none could be listed")
        if (false) Log.i("MatchdayDiag", "load ${comp.key} days=${days.first()}..${days.last()} boards=${okBoards.size}/${boards.size} rows=${rows.size} " +
            rows.groupingBy { it.state }.eachCount() + " soon=" + rows.filter { it.kickoff in (now - 4 * HOUR)..(now + 12 * HOUR) }.joinToString { "${it.homeTeam}-${it.awayTeam}@${it.kickoff}/${it.state}" })
        // Half-time scores of finished football matches (to settle first-half picks).
        if (comp.sport == SportType.FOOTBALL) runCatching {
            val ht = HashMap<String, String>()
            okBoards.flatMap { it.events }.forEach { e ->
                val c = e.competitions.firstOrNull() ?: return@forEach
                val done = (c.status ?: e.status)?.type?.let { it.completed || it.state == "post" } == true
                if (!done) return@forEach
                val h = c.competitors.firstOrNull { it.homeAway == "home" }?.linescores?.firstOrNull()?.value
                val a = c.competitors.firstOrNull { it.homeAway == "away" }?.linescores?.firstOrNull()?.value
                val id = rows.firstOrNull { it.eventId == e.id }?.id
                if (h != null && a != null && id != null) ht[id] = "${h.toInt()}-${a.toInt()}"
            }
            tracker.saveHalfTimes(ht)
        }
        // Drop matches removed from the feed (only when every day loaded, so nothing is lost on errors).
        if (okBoards.size == boards.size && comp.sport != SportType.TENNIS) {
            val from = days.first().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val to = days.last().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val keep = rows.map { it.id }
            if (keep.isNotEmpty()) db.matches().deleteMissing(comp.key, from, to, keep)
        }
        true
    }

    private suspend fun fetchBoard(comp: Competition, date: String?): EScoreboard? = gate.withPermit {
        val raw = try {
            espn.scoreboard(comp.espnSport, comp.espnLeague, date)
        } catch (e: Exception) {
            Log.w(TAG, "Scoreboard ${comp.key} $date unavailable: ${e.javaClass.simpleName}")
            return@withPermit null
        }
        decodeBoard(comp, date, raw)
    }

    /**
     * Decodes a scoreboard match by match. A field the app can't read (the feed changes without
     * notice) only removes that optional field - never the match, and never the whole day.
     */
    private fun decodeBoard(comp: Competition, date: String?, raw: JsonObject): EScoreboard {
        val json = Network.json
        val leagues = (raw["leagues"] as? JsonArray)?.mapNotNull {
            runCatching { json.decodeFromJsonElement(ELeague.serializer(), it) }.getOrNull()
        }.orEmpty()
        val events = (raw["events"] as? JsonArray)?.mapNotNull { el ->
            runCatching { json.decodeFromJsonElement(EEvent.serializer(), el) }.getOrElse { first ->
                Log.w(TAG, "Event in ${comp.key} $date needed repair: ${first.message?.take(200)}")
                runCatching { json.decodeFromJsonElement(EEvent.serializer(), strip(el)) }
                    .onFailure { Log.e(TAG, "Event in ${comp.key} $date skipped", it) }
                    .getOrNull()
            }
        }.orEmpty()
        return EScoreboard(events, leagues)
    }

    /** Keeps only the fields needed to list a match (teams, time, status, score, venue). */
    private fun strip(el: JsonElement): JsonElement {
        val e = el as? JsonObject ?: return el
        fun obj(o: JsonElement?, keep: Set<String>) = (o as? JsonObject)?.let { JsonObject(it.filterKeys { k -> k in keep }) }
        val statusKeep = setOf("clock", "displayClock", "period", "type")
        fun status(s: JsonElement?) = (s as? JsonObject)?.let { st ->
            JsonObject(st.filterKeys { it in statusKeep }.mapValues { (k, v) ->
                if (k == "type") obj(v, setOf("name", "state", "completed", "detail", "shortDetail")) ?: v else v
            })
        }
        fun competitor(c: JsonElement): JsonElement {
            val o = c as? JsonObject ?: return c
            val m = o.filterKeys { it in setOf("id", "homeAway", "winner", "form", "score") }.toMutableMap()
            obj(o["team"], setOf("id", "displayName", "shortDisplayName", "abbreviation", "logo"))?.let { m["team"] = it }
            obj(o["athlete"], setOf("displayName"))?.let { m["athlete"] = it }
            (o["linescores"] as? JsonArray)?.let { ls ->
                m["linescores"] = JsonArray(ls.mapNotNull { l ->
                    (l as? JsonObject)?.get("value")?.let { v -> (v as? JsonPrimitive)?.doubleOrNull?.let { JsonObject(mapOf("value" to JsonPrimitive(it))) } }
                })
            }
            return JsonObject(m)
        }
        fun comp(c: JsonElement): JsonElement {
            val o = c as? JsonObject ?: return c
            val m = o.filterKeys { it in setOf("id", "date") }.toMutableMap()
            status(o["status"])?.let { m["status"] = it }
            obj(o["venue"], setOf("fullName", "address", "court"))?.let { m["venue"] = it }
            (o["competitors"] as? JsonArray)?.let { m["competitors"] = JsonArray(it.map(::competitor)) }
            return JsonObject(m)
        }
        val m = e.filterKeys { it in setOf("id", "date", "name") }.toMutableMap()
        status(e["status"])?.let { m["status"] = it }
        obj(e["venue"], setOf("fullName", "address", "court"))?.let { m["venue"] = it }
        (e["competitions"] as? JsonArray)?.let { m["competitions"] = JsonArray(it.map(::comp)) }
        (e["groupings"] as? JsonArray)?.let { gs ->
            m["groupings"] = JsonArray(gs.mapNotNull { g ->
                (g as? JsonObject)?.let { go ->
                    JsonObject(mapOf(
                        "grouping" to (obj(go["grouping"], setOf("displayName")) ?: JsonObject(emptyMap())),
                        "competitions" to JsonArray((go["competitions"] as? JsonArray).orEmpty().map(::comp)),
                    ))
                }
            })
        }
        return JsonObject(m)
    }

    /** Loads league tables (cached 6 h) for the football model. */
    suspend fun loadContext() {
        val s = settings.current()
        for (key in s.enabledLeagues) {
            val comp = Catalog.find(key)
            if (!comp.hasTable) continue
            val t = table(comp) ?: continue
            tables.value = tables.value + (comp.key to t)
            predictionMemo.clear()
        }
    }

    private suspend fun table(comp: Competition): LeagueTable? = tableFor(comp.key)

    /** Table already in memory or on disk (never hits the network). */
    suspend fun cachedTable(leagueKey: String): LeagueTable? = tables.value[leagueKey]
        ?: cache.peek("standings:$leagueKey", EStandings.serializer())?.let { EspnMapper.table(leagueKey, it, cache.ageMs("standings:$leagueKey")?.let { a -> System.currentTimeMillis() - a } ?: 0L) }

    /** One-off snapshot of the match list. */
    suspend fun observeMatchesOnce(): List<MatchItem> = observeMatches().first()

    /** Public access to a cached league table (team pages). */
    suspend fun leagueTable(leagueKey: String): LeagueTable? = tableFor(leagueKey)

    /** ESPN team id of both sides (null when unknown). */
    suspend fun teamIds(matchId: String): Pair<String?, String?> =
        db.matches().byId(matchId)?.let { it.homeId to it.awayId } ?: (null to null)

    /** League table for "sport/league" (cached 6 h; stale copy served offline). */
    private suspend fun tableFor(leagueKey: String): LeagueTable? {
        val sport = leagueKey.substringBefore('/')
        val league = leagueKey.substringAfter('/')
        val timed = safeCache("standings:$leagueKey", 6 * HOUR, EStandings.serializer()) {
            gate.withPermit { espn.standings(sport, league) }
        } ?: return null
        return EspnMapper.table(leagueKey, timed.value, timed.fetchedAt)
    }

    /** Cached network read that returns null (and logs) instead of throwing. */
    private suspend fun <T> safeCache(
        key: String,
        ttl: Long,
        serializer: kotlinx.serialization.KSerializer<T>,
        fetch: suspend () -> T,
    ): JsonCache.Timed<T>? = try {
        cache.getTimed(key, ttl, serializer, fetch)
    } catch (e: Exception) {
        Log.w(TAG, "Unavailable: $key (${e.javaClass.simpleName}: ${e.message})")
        null
    }

    /**
     * Club profile: domestic league + table position, country, and last results across
     * competitions (domestic league schedule + this competition's schedule + ESPN's last-5).
     */
    private suspend fun profile(
        comp: Competition,
        teamId: String?,
        name: String,
        logo: String?,
        summaryRecent: List<RecentResult>,
    ): TeamProfile? {
        if (teamId == null) return null
        val sport = comp.espnSport
        return coroutineScope {
            val siteTeamJob = async {
                safeCache("team:$sport:$teamId", 24 * HOUR, com.matchday.app.data.remote.ETeamResponse.serializer()) {
                    gate.withPermit { espn.team(sport, comp.espnLeague, teamId) }
                }
            }
            val coreTeamJob = async {
                safeCache("coreteam:$sport:$teamId", 24 * HOUR, com.matchday.app.data.remote.ECoreTeam.serializer()) {
                    gate.withPermit { Network.espnCore.team(sport, comp.espnLeague, teamId) }
                }
            }
            val compScheduleJob = async {
                safeCache("sched:${comp.key}:$teamId", 3 * HOUR, com.matchday.app.data.remote.ESchedule.serializer()) {
                    gate.withPermit { espn.schedule(sport, comp.espnLeague, teamId) }
                }
            }
            val siteTeam = siteTeamJob.await()?.value?.team
            val domesticSlug = siteTeam?.defaultLeague?.slug ?: if (comp.hasTable) comp.espnLeague else null
            val domesticKey = domesticSlug?.let { "$sport/$it" }
            val tableJob = domesticKey?.let { k -> async { tableFor(k) } }
            val leagueScheduleJob = if (domesticSlug != null && domesticSlug != comp.espnLeague) async {
                safeCache("sched:$domesticKey:$teamId", 3 * HOUR, com.matchday.app.data.remote.ESchedule.serializer()) {
                    gate.withPermit { espn.schedule(sport, domesticSlug, teamId) }
                }
            } else null

            val compSchedule = compScheduleJob.await()
            val leagueSchedule = leagueScheduleJob?.await() ?: if (domesticSlug == comp.espnLeague) compSchedule else null
            val table = tableJob?.await()
            val core = coreTeamJob.await()?.value
            val leagueName = siteTeam?.defaultLeague?.name ?: if (comp.hasTable) comp.title else null

            val leagueResults = EspnMapper.scheduleResults(leagueSchedule?.value, teamId, leagueName)
            val compResults = EspnMapper.scheduleResults(compSchedule?.value, teamId, comp.title)
            val recent = (leagueResults + compResults + summaryRecent)
                .distinctBy { "${it.date / 86_400_000L}|${TeamMatcher.normalize(it.opponent)}" }
                .sortedByDescending { it.date }
                .take(10)
            val row = table?.row(teamId, name)
            val times = listOfNotNull(siteTeamJob.await()?.fetchedAt, table?.fetchedAt, compSchedule?.fetchedAt, leagueSchedule?.fetchedAt)
            TeamProfile(
                name = siteTeam?.displayName ?: name,
                logo = logo ?: siteTeam?.logos?.firstOrNull()?.href,
                teamId = teamId,
                country = core?.venue?.address?.country,
                stadium = core?.venue?.fullName,
                leagueName = leagueName,
                leagueKey = domesticKey,
                table = table,
                row = row,
                standingSummary = siteTeam?.standingSummary ?: leagueSchedule?.value?.team?.standingSummary,
                leagueForm = leagueResults.take(5),
                recent = recent,
                updatedAt = times.minOrNull(),
            )
        }
    }

    /** Re-downloads today's scoreboard for leagues with games in play (free; called by the Live tab). */
    suspend fun refreshLive(): List<String> {
        val now = System.currentTimeMillis()
        val inPlay = db.matches().since(now - 8 * HOUR)
            .filter { it.state == "in" || (it.state == "pre" && it.kickoff <= now + 15 * 60_000L) }
            .map { it.leagueKey }.distinct()
        if (inPlay.isEmpty()) return emptyList()
        val today = LocalDate.now(ZoneOffset.UTC)
        val days = listOf(today.minusDays(1), today)
        var failed = 0
        coroutineScope {
            inPlay.map { key -> async { refreshLeague(Catalog.find(key), days, now) } }.awaitAll()
                .forEach { if (!it) failed++ }
        }
        predictionMemo.clear()
        oddsMemo.clear()
        return if (failed > 0) listOf("Some live scores couldn't be updated. Retrying automatically.") else emptyList()
    }

    // -----------------------------------------------------------------------
    // Observation (Room + memory -> UI models)
    // -----------------------------------------------------------------------

    fun observeMatches(): Flow<List<MatchItem>> {
        val since = System.currentTimeMillis() - 2 * DAY
        return combine(
            db.matches().observeSince(since),
            db.favourites().observeAll(),
            tables,
            settings.flow,
        ) { rows, favs, t, s -> build(rows.filter { it.leagueKey in s.enabledLeagues }, favs, t) }
            .flowOn(Dispatchers.Default)
    }

    fun observeFavourites(): Flow<List<FavouriteEntity>> = db.favourites().observeAll()

    fun observeMatch(id: String): Flow<MatchItem?> = observeMatches().map { list -> list.firstOrNull { it.id == id } }

    fun oddsOf(e: MatchEntity): OddsSnap? {
        val key = "${e.id}|${e.updatedAt}"
        oddsMemo[key]?.let { return it.snap }
        val snap = e.oddsJson?.let {
            try {
                Network.json.decodeFromString(OddsSnap.serializer(), it)
            } catch (ex: Exception) {
                Log.w(TAG, "Unreadable odds for ${e.id}", ex)
                null
            }
        }
        oddsMemo[key] = ParsedOdds(snap)
        return snap
    }

    private fun build(rows: List<MatchEntity>, favs: List<FavouriteEntity>, t: Map<String, LeagueTable>): List<MatchItem> {
        val favTeams = favs.filter { it.type == "TEAM" }.map { it.name }
        val favComps = favs.filter { it.type == "COMPETITION" }.map { it.key.removePrefix("COMP:") }.toSet()
        return rows.mapNotNull { row ->
            try {
                toItem(row, t[row.leagueKey], favTeams, favComps)
            } catch (ex: Exception) {
                // One malformed match must not take down the whole list; it is logged and skipped.
                Log.e(TAG, "Skipping match ${row.id}", ex)
                null
            }
        }.sortedBy { it.kickoff }
    }

    /** Longest realistic duration from kick-off, per sport (incl. extra time / penalties / delays). */
    private fun maxDurationMs(sport: SportType): Long = when (sport) {
        SportType.FOOTBALL -> 3 * HOUR
        SportType.BASKETBALL, SportType.HOCKEY -> 4 * HOUR
        else -> 8 * HOUR
    }

    private fun toItem(e: MatchEntity, table: LeagueTable?, favTeams: List<String>, favComps: Set<String>): MatchItem {
        val base = Catalog.find(e.leagueKey)
        val comp = if (e.leagueLogo != null) base.copy(emblem = e.leagueLogo) else base
        // A game still marked "in play" long after it must have ended means the feed couldn't be
        // re-read (e.g. offline): don't keep showing it as live.
        val now = System.currentTimeMillis()
        val stale = e.state == "in" && now - e.kickoff > maxDurationMs(base.sport)
        // Start time passed but the feed hasn't confirmed it yet: never keep it in the upcoming list.
        val overdue = e.state == "pre" && now >= e.kickoff
        val status = when {
            stale -> MatchStatus.FINISHED
            e.state == "in" || overdue -> MatchStatus.LIVE
            e.state == "post" -> MatchStatus.FINISHED
            else -> MatchStatus.UPCOMING
        }
        val odds = EspnMapper.summary(oddsOf(e), e.homeTeam, e.awayTeam, comp.sport, comp.hasDraw)
        val homeStats = if (comp.sport == SportType.FOOTBALL) EspnMapper.teamStats(table, e.homeId, e.homeTeam, e.homeLogo, e.homeForm) else null
        val awayStats = if (comp.sport == SportType.FOOTBALL) EspnMapper.teamStats(table, e.awayId, e.awayTeam, e.awayLogo, e.awayForm) else null

        val memoKey = "${e.id}|${e.updatedAt}|${table?.fetchedAt}"
        val prediction = if (status == MatchStatus.UPCOMING) {
            predictionMemo[memoKey] ?: PredictionEngine.predict(
                comp, e.homeTeam, e.awayTeam, odds,
                if (homeStats != null && awayStats != null) ModelInputs(homeStats, awayStats, table?.averages, InjuryInfo(false)) else null,
            )?.also { predictionMemo[memoKey] = it }
        } else null

        val favourite = favTeams.any { TeamMatcher.same(it, e.homeTeam) || TeamMatcher.same(it, e.awayTeam) } || comp.key in favComps
        return MatchItem(
            id = e.id, competition = comp, homeTeam = e.homeTeam, awayTeam = e.awayTeam, kickoff = e.kickoff,
            status = status, homeScore = e.homeScore, awayScore = e.awayScore,
            statusDetail = when {
                stale -> "Ended? Final score not confirmed yet"
                overdue -> if (base.sport == SportType.TENNIS) "Scheduled time passed · waiting to start" else "Start time passed · awaiting live data"
                else -> e.statusDetail
            },
            odds = odds, prediction = prediction,
            homeCrest = e.homeLogo, awayCrest = e.awayLogo,
            venue = listOfNotNull(e.venue, e.city).joinToString(", ").ifBlank { null },
            popular = comp.tier >= 8 || favourite,
            favourite = favourite,
            btts = if (comp.sport == SportType.FOOTBALL && status == MatchStatus.UPCOMING) BttsEngine.quick(prediction) else null,
        )
    }

    /** Deep analysis for one match: last 5, head-to-head, line-ups, injuries, weather. */
    /** Fresh live statistics for an in-play match (ESPN summary, read field by field). Null when unavailable. */
    suspend fun liveStats(item: MatchItem): com.matchday.app.domain.LiveStats? {
        val e = db.matches().byId(item.id) ?: return null
        val comp = item.competition
        return try {
            val raw = gate.withPermit { espn.summaryRaw(comp.espnSport, comp.espnLeague, e.eventId) }
            com.matchday.app.domain.LiveStats.parse(raw, item.homeTeam, item.awayTeam, e.homeId, e.awayId)
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w(TAG, "Live stats unavailable for ${item.id}: ${ex.javaClass.simpleName}")
            null
        }
    }

    fun loadDetail(item: MatchItem): Flow<DetailExtras> = flow {
        val comp = item.competition
        val entity = db.matches().byId(item.id)
        var x = DetailExtras(loading = true)
        val notes = mutableListOf<String>()
        emit(x)

        val table = if (comp.hasTable) tables.value[comp.key] ?: table(comp)?.also { tables.value = tables.value + (comp.key to it) } else null
        var homeStats = if (comp.sport == SportType.FOOTBALL) EspnMapper.teamStats(table, entity?.homeId, item.homeTeam, item.homeCrest, entity?.homeForm) else null
        var awayStats = if (comp.sport == SportType.FOOTBALL) EspnMapper.teamStats(table, entity?.awayId, item.awayTeam, item.awayCrest, entity?.awayForm) else null
        if (comp.sport == SportType.FOOTBALL && FootballDataRepository.covers(comp.key)) {
            val (h, a, msg) = footballData.withSplits(comp.key, homeStats, awayStats)
            if (h !== homeStats || a !== awayStats) notes += "Home/away league tables: football-data.org."
            homeStats = h; awayStats = a
            msg?.let { notes += it }
        }
        x = x.copy(homeStats = homeStats, awayStats = awayStats)
        emit(x)

        val summaryTimed = if (entity != null && comp.sport != SportType.TENNIS) {
            val ttl = if (item.status == MatchStatus.UPCOMING && item.kickoff - System.currentTimeMillis() > 2 * HOUR) 3 * HOUR else 5 * 60_000L
            safeCache("summary:${item.id}", ttl, ESummary.serializer()) {
                gate.withPermit { espn.summary(comp.espnSport, comp.espnLeague, entity.eventId) }
            }.also { if (it == null) notes += "Match details are unavailable right now (offline or source error)." }
        } else null
        val summary: ESummary? = summaryTimed?.value

        if (summary != null) {
            val hr = EspnMapper.lastFive(summary.lastFiveGames, entity?.homeId, item.homeTeam)
            val ar = EspnMapper.lastFive(summary.lastFiveGames, entity?.awayId, item.awayTeam)
            if (hr.size >= 3) homeStats = homeStats?.copy(recent = hr)
            if (ar.size >= 3) awayStats = awayStats?.copy(recent = ar)
            val h2h = EspnMapper.headToHead(summary.seasonseries, entity?.homeId, item.homeTeam)
            val lineups = EspnMapper.lineups(summary.rosters)
            val injuries = injuriesOf(summary, item, entity?.homeId, entity?.awayId)
            val venue = summary.gameInfo?.venue
            x = x.copy(
                homeStats = homeStats, awayStats = awayStats, homeRecent = hr, awayRecent = ar,
                headToHead = h2h, lineups = lineups, injuries = injuries,
                venue = venue?.fullName ?: entity?.venue, city = venue?.address?.city ?: entity?.city,
                summaryUpdatedAt = summaryTimed?.fetchedAt,
            )
            emit(x)
        } else {
            x = x.copy(venue = entity?.venue, city = entity?.city)
        }

        if (comp.sport == SportType.FOOTBALL) {
            if (!x.injuries.known) {
                val inj = apiFootball.injuries(item)
                inj.value?.let { x = x.copy(injuries = it); notes += "Injuries and suspensions: API-Football." }
                inj.message?.let { notes += it }
            }
            if (x.lineups.isEmpty()) {
                val lu = apiFootball.lineups(item)
                lu.value?.let { x = x.copy(lineups = it); notes += "Confirmed line-ups: API-Football." }
                lu.message?.takeIf { it !in notes }?.let { notes += it }
            }
            emit(x)
            val (hp, ap) = coroutineScope {
                val h = async { profile(comp, entity?.homeId, item.homeTeam, item.homeCrest, x.homeRecent) }
                val a = async { profile(comp, entity?.awayId, item.awayTeam, item.awayCrest, x.awayRecent) }
                Pair(h.await(), a.await())
            }
            x = x.copy(homeProfile = hp, awayProfile = ap)
            emit(x)
        }

        val city = x.city
        if (city != null && item.status == MatchStatus.UPCOMING) {
            x = x.copy(weather = weather.atKickoff(city, item.kickoff))
            emit(x)
        }

        if (comp.sport == SportType.FOOTBALL) {
            if (!comp.hasTable) notes += "No league table for ${comp.title}: prediction uses bookmaker odds only."
            if (item.odds == null) notes += "No bookmaker odds published for this match yet."
        } else if (comp.sport == SportType.TENNIS) {
            notes += "Tennis: fixtures and live scores only (the free feed has no tennis odds or stats)."
        } else {
            notes += "${comp.sport.label} predictions use the bookmaker's margin-free odds (no independent model)."
        }

        val inputs = if (homeStats != null && awayStats != null) ModelInputs(homeStats, awayStats, table?.averages, x.injuries) else null
        val prediction = if (item.status == MatchStatus.UPCOMING) PredictionEngine.predict(comp, item.homeTeam, item.awayTeam, item.odds, inputs) else null
        val btts = if (comp.sport == SportType.FOOTBALL) {
            BttsEngine.analyze(
                home = item.homeTeam, away = item.awayTeam, prediction = prediction ?: item.prediction,
                homeRecent = x.homeProfile?.recent?.takeIf { it.isNotEmpty() } ?: x.homeRecent,
                awayRecent = x.awayProfile?.recent?.takeIf { it.isNotEmpty() } ?: x.awayRecent,
                h2h = x.headToHead, injuries = x.injuries,
            )
        } else null
        emit(x.copy(loading = false, prediction = prediction, notes = notes, btts = btts))
    }.flowOn(Dispatchers.IO)

    private fun injuriesOf(s: ESummary, item: MatchItem, homeId: String?, awayId: String?): InjuryInfo {
        if (s.injuries.isEmpty()) return InjuryInfo(known = false)
        fun forTeam(id: String?, name: String) = s.injuries.firstOrNull {
            (id != null && it.team?.id == id) || TeamMatcher.same(it.team?.displayName ?: "", name)
        }?.injuries.orEmpty()
        fun label(i: com.matchday.app.data.remote.EInjury): String =
            listOfNotNull(
                i.athlete?.displayName,
                i.athlete?.position?.abbreviation?.let { "($it)" },
                listOfNotNull(i.details?.type, i.details?.detail).joinToString(" ").ifBlank { null },
            ).joinToString(" ")
        fun isOut(i: com.matchday.app.data.remote.EInjury) = (i.status ?: "").contains("out", true)
        val h = forTeam(homeId, item.homeTeam)
        val a = forTeam(awayId, item.awayTeam)
        return InjuryInfo(
            known = true,
            homeOut = h.filter { isOut(it) }.map { label(it) }, homeDoubtful = h.filterNot { isOut(it) }.map { label(it) },
            awayOut = a.filter { isOut(it) }.map { label(it) }, awayDoubtful = a.filterNot { isOut(it) }.map { label(it) },
        )
    }

    /** Line-ups for alerts (bypasses the long summary cache). */
    suspend fun lineupsNow(item: MatchItem): List<LineupInfo> {
        val e = db.matches().byId(item.id) ?: return emptyList()
        val comp = item.competition
        return try {
            val s = cache.get("summary:${item.id}", 60_000L, ESummary.serializer()) {
                espn.summary(comp.espnSport, comp.espnLeague, e.eventId)
            }
            EspnMapper.lineups(s.rosters)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Current odds of every upcoming match, keyed by id (used to detect price moves). */
    suspend fun oddsSnapshot(): Map<String, OddsSnap> {
        val now = System.currentTimeMillis()
        return db.matches().since(now).mapNotNull { e -> oddsOf(e)?.let { e.id to it } }.toMap()
    }

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

    @Suppress("unused")
    private fun summaryOdds(o: OddsSummary?) = o
}
