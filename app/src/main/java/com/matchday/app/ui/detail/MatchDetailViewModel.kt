package com.matchday.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.data.repo.DetailExtras
import com.matchday.app.data.repo.ApiStatus
import com.matchday.app.data.repo.OneXBetState
import com.matchday.app.domain.Insight
import com.matchday.app.domain.InsightsEngine
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamHistory
import com.matchday.app.domain.games
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.Verdict
import com.matchday.app.domain.VerdictEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryState(
    val loading: Boolean = false,
    val home: TeamHistory? = null,
    val away: TeamHistory? = null,
    val failed: Boolean = false,
)

data class DetailUiState(
    val item: MatchItem? = null,
    val extras: DetailExtras = DetailExtras(),
    val favourites: List<FavouriteEntity> = emptyList(),
    val history: HistoryState = HistoryState(),
    val insights: List<Insight> = emptyList(),
    val oneXBet: OneXBetState = OneXBetState.NotLoaded,
    val api: OneXApi = OneXApi(),
    val verdict: Verdict? = null,
    val markets: com.matchday.app.domain.MarketAnalysis? = null,
    val homeId: String? = null,
    val awayId: String? = null,
) {
    fun isFavTeam(name: String) = favourites.any { it.type == "TEAM" && TeamMatcher.same(it.name, name) }
    fun isFavCompetition() = item != null && favourites.any { it.key == "COMP:${item.competition.key}" }
}

/** Automatic 1xBet loading through The Odds API. */
data class OneXApi(
    val status: ApiStatus = ApiStatus.NoKey,
    val loading: Boolean = false,
    val message: String? = null,
)

private data class Research(
    val history: HistoryState,
    val oneX: OneXBetState,
    val ids: Pair<String?, String?>,
    val api: OneXApi,
    val ranks: Map<String, Int> = emptyMap(),
)

class MatchDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val extras = MutableStateFlow(DetailExtras())
    private val history = MutableStateFlow(HistoryState())
    private val oneX = MutableStateFlow<OneXBetState>(OneXBetState.NotLoaded)
    private val ids = MutableStateFlow<Pair<String?, String?>>(null to null)
    private var loadJob: Job? = null
    private var histJob: Job? = null
    private val item = c.matches.observeMatch(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val api = MutableStateFlow(OneXApi())
    private val ranks = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val research = combine(history, oneX, ids, api, ranks) { h, o, i, a, r -> Research(h, o, i, a, r) }

    val state: StateFlow<DetailUiState> = combine(item, extras, c.matches.observeFavourites(), research) { i, x, f, r ->
        val insights = if (i != null && i.competition.sport == SportType.FOOTBALL && (r.history.home != null || r.history.away != null || x.headToHead.isNotEmpty())) {
            runCatching {
                InsightsEngine.build(
                    homeName = i.homeTeam, awayName = i.awayTeam,
                    homeHist = r.history.home, awayHist = r.history.away,
                    venue = x.venue, competitionKey = i.competition.key, kickoff = i.kickoff,
                    h2h = x.headToHead,
                    homeCountryHint = x.homeProfile?.country, awayCountryHint = x.awayProfile?.country,
                )
            }.getOrDefault(emptyList())
        } else emptyList()
        val markets = when {
            i == null -> null
            i.competition.sport == SportType.FOOTBALL -> runCatching { marketsFor(i, x, insights, r.oneX, r.history) }.getOrNull()
            else -> runCatching { otherMarkets(i, x, r.oneX, r.history, r.ranks) }.getOrNull()
        }
        val verdict = if (i != null && i.competition.sport == SportType.FOOTBALL && !x.loading) {
            runCatching { verdictFor(i, x, insights, r.oneX) }.getOrNull()
        } else null
        DetailUiState(
            item = i, extras = x, favourites = f, history = r.history, insights = insights,
            oneXBet = r.oneX, api = r.api, verdict = verdict, markets = markets,
            homeId = r.ids.first, awayId = r.ids.second,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            item.collect { i -> if (i != null && loadJob == null) load(i) }
        }
        // Track the match page's recommendations (updated until kick-off) for the accuracy report.
        viewModelScope.launch {
            state.map { st -> st.item to st.markets?.top.orEmpty() }
                .distinctUntilChanged { a, b -> a.first?.id == b.first?.id && a.second.map { it.key } == b.second.map { it.key } && a.second.map { it.probability } == b.second.map { it.probability } }
                .collect { (i, top) -> if (i != null && top.isNotEmpty()) runCatching { c.matches.tracker.record(i, top, "match") } }
        }
    }

    private fun marketsFor(i: MatchItem, x: DetailExtras, insights: List<Insight>, o: OneXBetState, h: HistoryState): com.matchday.app.domain.MarketAnalysis? {
        val p = x.prediction ?: i.prediction ?: return null
        val lh = p.expectedHome ?: return null
        val la = p.expectedAway ?: return null
        val ok = o as? OneXBetState.Ok
        return com.matchday.app.domain.MarketEngine.analyze(
            home = i.homeTeam, away = i.awayTeam, lh = lh, la = la, p = p, btts = x.btts,
            homeGames = h.home?.let { it.games() }.orEmpty().filter { it.m.date < i.kickoff },
            awayGames = h.away?.let { it.games() }.orEmpty().filter { it.m.date < i.kickoff },
            h2h = x.headToHead, insights = insights, injuries = x.injuries,
            prices = ok?.current?.prices, priceSource = ok?.current?.bookmaker,
            feedPrices = feedPrices(i.odds), feedSource = i.odds?.home?.best?.bookmaker,
            lineupsConfirmed = x.lineups.isNotEmpty(),
        )
    }

    /** Basketball, hockey and tennis: dedicated engines (see SportEngines). */
    private fun otherMarkets(i: MatchItem, x: DetailExtras, o: OneXBetState, h: HistoryState, ranks: Map<String, Int>): com.matchday.app.domain.MarketAnalysis? {
        val ok = o as? OneXBetState.Ok
        fun rank(n: String) = ranks[n] ?: ranks.entries.firstOrNull { TeamMatcher.same(it.key, n) }?.value
        return com.matchday.app.domain.SportEngines.analyze(
            com.matchday.app.domain.SportInputs(
                sport = i.competition.sport, leagueKey = i.competition.key, home = i.homeTeam, away = i.awayTeam, kickoff = i.kickoff,
                homeGames = h.home?.games().orEmpty().filter { it.m.date < i.kickoff },
                awayGames = h.away?.games().orEmpty().filter { it.m.date < i.kickoff },
                h2h = x.headToHead, prices = ok?.current?.prices, priceSource = ok?.current?.bookmaker,
                feed = i.odds, injuries = x.injuries, lineupsConfirmed = x.lineups.isNotEmpty(),
                homeRank = if (i.competition.sport == SportType.TENNIS) rank(i.homeTeam) else null,
                awayRank = if (i.competition.sport == SportType.TENNIS) rank(i.awayTeam) else null,
                live = i.status == MatchStatus.LIVE,
            ),
        )
    }

    private fun verdictFor(i: MatchItem, x: DetailExtras, insights: List<Insight>, o: OneXBetState): Verdict {
        val p = x.prediction ?: i.prediction
        val btts = x.btts
        return if (o is OneXBetState.Ok) {
            VerdictEngine.build(i, p, btts, VerdictEngine.linesFrom1xBet(o.current.prices, "1xBet", p, btts), o.current.bookmaker, insights)
        } else {
            val lines = VerdictEngine.linesFromFeed(i.odds, p, btts)
            VerdictEngine.build(i, p, btts, lines, lines.firstOrNull()?.bookmaker, insights)
        }
    }

    private fun load(i: MatchItem) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            c.matches.loadDetail(i)
                .catch { e ->
                    // Never leave the page spinning: stop loading and say what failed.
                    extras.value = extras.value.copy(
                        loading = false,
                        notes = extras.value.notes + "Part of the analysis couldn't be loaded (${e.javaClass.simpleName}). Tap reload to retry.",
                    )
                }
                .collect { extras.value = it }
        }
        val sport = i.competition.sport
        if (sport == SportType.FOOTBALL || sport == SportType.BASKETBALL || sport == SportType.HOCKEY || sport == SportType.TENNIS) {
            if (sport == SportType.TENNIS) {
                viewModelScope.launch {
                    val tour = if (i.competition.key.endsWith("wta")) "wta" else "atp"
                    ranks.value = withContext(Dispatchers.IO) { runCatching { c.teams.tennisRanks(tour) }.getOrDefault(emptyMap()) }
                }
            } else loadHistory(i)
            viewModelScope.launch {
                oneX.value = runCatching { c.oneXBet.state(i) }.getOrDefault(OneXBetState.NotLoaded)
                val st = runCatching { c.oneXBet.apiStatus(i) }.getOrDefault(ApiStatus.NoKey)
                api.value = OneXApi(st)
                val auto = runCatching { c.settings.current().autoLoadOneXBet }.getOrDefault(false)
                if (st is ApiStatus.Ready && auto && i.status == MatchStatus.UPCOMING && runCatching { c.oneXBet.isStale(i) }.getOrDefault(false)) {
                    loadOneXBetFromApi()
                }
            }
            watchLiveOdds()
        }
        startLiveLoop()
    }

    // ------------------------------------------------------------------ live intelligence
    data class LiveUi(
        val analysis: com.matchday.app.domain.LiveAnalysis? = null,
        val stats: com.matchday.app.domain.LiveStats? = null,
        val changes: List<Pair<Long, String>> = emptyList(),   // what each score change did
        val loading: Boolean = false,
        val failed: Boolean = false,
        val updatedAt: Long? = null,
    )

    private val _live = MutableStateFlow(LiveUi())
    val live: StateFlow<LiveUi> = _live
    private var liveLoop: Job? = null
    private val snapshots = ArrayDeque<com.matchday.app.domain.LiveStats>()

    /** While the match is in play: re-read live stats every 30 s and recompute everything. */
    private fun startLiveLoop() {
        if (liveLoop != null) return
        liveLoop = viewModelScope.launch {
            while (true) {
                val m = item.value
                if (m != null && m.status == MatchStatus.LIVE) {
                    if (_live.value.analysis == null) _live.value = _live.value.copy(loading = true)
                    val st = withContext(Dispatchers.IO) { runCatching { c.matches.liveStats(m) }.getOrNull() }
                    if (st != null) { snapshots.addLast(st); while (snapshots.size > 80) snapshots.removeFirst() }
                    recomputeLive(m, st ?: _live.value.stats, failed = st == null)
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    private fun recomputeLive(m: MatchItem, st: com.matchday.app.domain.LiveStats?, failed: Boolean) {
        val cur = state.value
        val prior = when (m.competition.sport) {
            SportType.FOOTBALL -> (cur.extras.prediction ?: m.prediction).let { com.matchday.app.domain.LivePrior(it?.expectedHome, it?.expectedAway) }
            SportType.TENNIS -> cur.markets?.takeIf { it.expectedHome > 0 }.let { com.matchday.app.domain.LivePrior(null, null, it?.expectedHome, it?.expectedAway) }
            else -> cur.markets?.takeIf { it.expectedHome > 0 }.let { com.matchday.app.domain.LivePrior(it?.expectedHome, it?.expectedAway) }
        }
        // Only fresh LIVE 1xBet prices are attached; anything older is never shown as a current price.
        val snap = (oneX.value as? OneXBetState.Ok)?.current?.takeIf { it.live && System.currentTimeMillis() - it.fetchedAt < 5 * 60_000L }
        val a = runCatching {
            com.matchday.app.domain.LiveEngine.analyze(m, st, prior, snap?.prices, snap?.let { "1xBet live" }, snapshots.toList().dropLast(1))
        }.getOrNull()
        val before = _live.value.analysis
        var changes = _live.value.changes
        if (before != null && a != null && before.score != a.score) {
            fun pr(x: com.matchday.app.domain.LiveAnalysis, sel: String) = x.markets.firstOrNull { (it.market == "1X2" || it.market == "ML") && it.sel == sel }?.probability
            val hb = pr(before, "HOME"); val ha = pr(a, "HOME"); val ab = pr(before, "AWAY"); val aa = pr(a, "AWAY")
            val msg = "Score ${before.score} → ${a.score} at ${a.clockLabel}: " +
                (if (hb != null && ha != null) "${m.homeTeam} win ${Math.round(hb * 100)}% → ${Math.round(ha * 100)}%" else "") +
                (if (ab != null && aa != null) ", ${m.awayTeam} ${Math.round(ab * 100)}% → ${Math.round(aa * 100)}%" else "") +
                ". Projections, next-score and totals recalculated; 1xBet odds refresh if auto-load is on."
            changes = (listOf(System.currentTimeMillis() to msg) + changes).take(10)
        }
        _live.value = LiveUi(a, st, changes, loading = false, failed = failed && st == null, updatedAt = if (st != null) System.currentTimeMillis() else _live.value.updatedAt)
    }

    /** Re-evaluate immediately when new live odds arrive. */
    fun refreshLiveNow() {
        val m = item.value ?: return
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) { runCatching { c.matches.liveStats(m) }.getOrNull() }
            if (st != null) snapshots.addLast(st)
            recomputeLive(m, st ?: _live.value.stats, st == null)
        }
    }

    private var liveWatch: Job? = null
    private var lastScore: String? = null
    private var lastLiveFetch = 0L

    /**
     * Live 1xBet odds: re-downloaded when the score changes (goal, set, period...), at most every
     * 2 minutes (5 for basketball, whose score changes constantly) to protect the free credits.
     */
    private fun watchLiveOdds() {
        if (liveWatch != null) return
        liveWatch = viewModelScope.launch {
            item.collect { m ->
                if (m == null || m.status != MatchStatus.LIVE) return@collect
                val score = "${m.homeScore}-${m.awayScore}"
                val auto = runCatching { c.settings.current().autoLoadOneXBet }.getOrDefault(false)
                val ready = api.value.status is ApiStatus.Ready
                val minGap = if (m.competition.sport == SportType.BASKETBALL) 5 * 60_000L else 2 * 60_000L
                val changed = lastScore != null && score != lastScore
                val first = lastScore == null
                lastScore = score
                if (auto && ready && (changed || (first && (oneX.value as? OneXBetState.Ok)?.current?.live != true)) &&
                    System.currentTimeMillis() - lastLiveFetch > minGap) {
                    lastLiveFetch = System.currentTimeMillis()
                    loadOneXBetFromApi()
                }
            }
        }
    }

    private fun loadHistory(i: MatchItem) {
        histJob?.cancel()
        histJob = viewModelScope.launch {
            history.value = history.value.copy(loading = true, failed = false)
            try {
                val (h, a) = c.matches.teamIds(i.id)
                ids.value = h to a
                val key = i.competition.key
                val (hh, ah) = withContext(Dispatchers.IO) {
                    val hj = async { h?.let { runCatching { c.teams.history(key, it) }.getOrNull() } }
                    val aj = async { a?.let { runCatching { c.teams.history(key, it) }.getOrNull() } }
                    hj.await() to aj.await()
                }
                history.value = HistoryState(false, hh, ah, failed = hh == null && ah == null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                history.value = HistoryState(false, null, null, true)
            }
        }
    }

    /** Saves 1xBet prices typed in by the user (no API). */
    fun saveOneXBet(prices: List<com.matchday.app.data.repo.BookPrice>) {
        val m = item.value ?: return
        viewModelScope.launch { oneX.value = runCatching { c.oneXBet.saveManual(m, prices) }.getOrDefault(oneX.value) }
    }

    /** Downloads 1xBet prices through The Odds API. If credits are gone the app just carries on. */
    fun loadOneXBetFromApi() {
        val m = item.value ?: return
        if (api.value.loading) return
        viewModelScope.launch {
            api.value = api.value.copy(loading = true, message = null)
            val (st, msg) = try {
                withContext(Dispatchers.IO) { c.oneXBet.loadFromApi(m) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                oneX.value to "1xBet odds couldn't be downloaded."
            }
            oneX.value = st
            val status = runCatching { c.oneXBet.apiStatus(m) }.getOrDefault(ApiStatus.NoKey)
            api.value = OneXApi(status, false, msg)
            if (m.status == MatchStatus.LIVE) recomputeLive(m, _live.value.stats, false)
        }
    }

    fun clearOneXBet() {
        val m = item.value ?: return
        viewModelScope.launch { oneX.value = runCatching { c.oneXBet.clear(m) }.getOrDefault(oneX.value) }
    }

    fun reload() { item.value?.let { load(it) } }

    fun toggleTeam(name: String) = viewModelScope.launch { c.matches.toggleTeam(name) }
    fun toggleCompetition() = viewModelScope.launch { item.value?.let { c.matches.toggleCompetition(it.competition) } }
}

/** The free feed's prices (main bookmaker) in the same shape as 1xBet prices, labelled with their real source. */
fun feedPrices(o: com.matchday.app.domain.OddsSummary?): List<com.matchday.app.data.repo.BookPrice> {
    o ?: return emptyList()
    val out = mutableListOf<com.matchday.app.data.repo.BookPrice>()
    o.home?.let { h -> h.best?.let { out += com.matchday.app.data.repo.BookPrice("1X2", "HOME", h.label, null, it.price) } }
    o.draw?.best?.let { out += com.matchday.app.data.repo.BookPrice("1X2", "DRAW", "Draw", null, it.price) }
    o.away?.let { a -> a.best?.let { out += com.matchday.app.data.repo.BookPrice("1X2", "AWAY", a.label, null, it.price) } }
    val line = o.totalsLine
    if (line != null) {
        o.over?.best?.let { out += com.matchday.app.data.repo.BookPrice("OU", "OVER", "Over $line", line, it.price) }
        o.under?.best?.let { out += com.matchday.app.data.repo.BookPrice("OU", "UNDER", "Under $line", line, it.price) }
    }
    return out
}
