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
    private val research = combine(history, oneX, ids, api) { h, o, i, a -> Research(h, o, i, a) }

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
        val markets = if (i != null && i.competition.sport == SportType.FOOTBALL) {
            runCatching { marketsFor(i, x, insights, r.oneX, r.history) }.getOrNull()
        } else null
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
        if (i.competition.sport == SportType.FOOTBALL) {
            loadHistory(i)
            viewModelScope.launch {
                oneX.value = runCatching { c.oneXBet.state(i) }.getOrDefault(OneXBetState.NotLoaded)
                val st = runCatching { c.oneXBet.apiStatus(i) }.getOrDefault(ApiStatus.NoKey)
                api.value = OneXApi(st)
                val auto = runCatching { c.settings.current().autoLoadOneXBet }.getOrDefault(false)
                if (st is ApiStatus.Ready && auto && i.status == MatchStatus.UPCOMING && runCatching { c.oneXBet.isStale(i) }.getOrDefault(false)) {
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
