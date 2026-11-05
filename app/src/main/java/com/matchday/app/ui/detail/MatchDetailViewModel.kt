package com.matchday.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.data.repo.DetailExtras
import com.matchday.app.data.repo.OneXBetState
import com.matchday.app.domain.Insight
import com.matchday.app.domain.InsightsEngine
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamHistory
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
    val verdict: Verdict? = null,
    val homeId: String? = null,
    val awayId: String? = null,
) {
    fun isFavTeam(name: String) = favourites.any { it.type == "TEAM" && TeamMatcher.same(it.name, name) }
    fun isFavCompetition() = item != null && favourites.any { it.key == "COMP:${item.competition.key}" }
}

private data class Research(
    val history: HistoryState,
    val oneX: OneXBetState,
    val ids: Pair<String?, String?>,
)

class MatchDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val extras = MutableStateFlow(DetailExtras())
    private val history = MutableStateFlow(HistoryState())
    private val oneX = MutableStateFlow<OneXBetState>(OneXBetState.NotLoaded)
    private val ids = MutableStateFlow<Pair<String?, String?>>(null to null)
    private var loadJob: Job? = null
    private var histJob: Job? = null
    private val item = c.matches.observeMatch(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val research = combine(history, oneX, ids) { h, o, i -> Research(h, o, i) }

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
        val verdict = if (i != null && i.competition.sport == SportType.FOOTBALL && !x.loading) {
            runCatching { verdictFor(i, x, insights, r.oneX) }.getOrNull()
        } else null
        DetailUiState(
            item = i, extras = x, favourites = f, history = r.history, insights = insights,
            oneXBet = r.oneX, verdict = verdict,
            homeId = r.ids.first, awayId = r.ids.second,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            item.collect { i -> if (i != null && loadJob == null) load(i) }
        }
    }

    private fun verdictFor(i: MatchItem, x: DetailExtras, insights: List<Insight>, o: OneXBetState): Verdict {
        val p = x.prediction ?: i.prediction
        val btts = x.btts
        return if (o is OneXBetState.Ok) {
            VerdictEngine.build(i, p, btts, VerdictEngine.linesFrom1xBet(o.current.prices, "1xBet", p, btts), com.matchday.app.data.repo.OneXBetRepository.SOURCE, insights)
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
            viewModelScope.launch { oneX.value = runCatching { c.oneXBet.state(i) }.getOrDefault(OneXBetState.NotLoaded) }
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
        viewModelScope.launch { oneX.value = runCatching { c.oneXBet.save(m, prices) }.getOrDefault(oneX.value) }
    }

    fun clearOneXBet() {
        val m = item.value ?: return
        viewModelScope.launch { oneX.value = runCatching { c.oneXBet.clear(m) }.getOrDefault(oneX.value) }
    }

    fun reload() { item.value?.let { load(it) } }

    fun toggleTeam(name: String) = viewModelScope.launch { c.matches.toggleTeam(name) }
    fun toggleCompetition() = viewModelScope.launch { item.value?.let { c.matches.toggleCompetition(it.competition) } }
}
