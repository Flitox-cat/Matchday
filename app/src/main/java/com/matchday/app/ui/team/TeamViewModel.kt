package com.matchday.app.ui.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.data.repo.SearchHit
import com.matchday.app.data.repo.TeamPage
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.TeamHistory
import com.matchday.app.domain.TeamMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TeamData(
    val loading: Boolean = true,
    val page: TeamPage? = null,
    val history: TeamHistory? = null,
    val historyLoading: Boolean = true,
    val failed: Boolean = false,
)

data class TeamUiState(
    val main: TeamData = TeamData(),
    val matches: List<MatchItem> = emptyList(),
    val favourites: List<FavouriteEntity> = emptyList(),
    val compareQuery: String = "",
    val compareHits: List<SearchHit> = emptyList(),
    val compare: TeamData? = null,
) {
    val isFavourite: Boolean get() = main.page?.let { p -> favourites.any { it.type == "TEAM" && TeamMatcher.same(it.name, p.name) } } ?: false
}

class TeamViewModel(private val c: AppContainer, private val leagueKey: String, private val teamId: String) : ViewModel() {
    private val main = MutableStateFlow(TeamData())
    private val compare = MutableStateFlow<TeamData?>(null)
    private val query = MutableStateFlow("")
    private val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    private var searchJob: Job? = null

    val state: StateFlow<TeamUiState> = combine(
        main, c.matches.observeMatches(), c.matches.observeFavourites(), combine(query, hits, compare) { q, h, cmp -> Triple(q, h, cmp) },
    ) { m, all, favs, (q, h, cmp) ->
        val name = m.page?.name
        val mine = if (name == null) emptyList() else all.filter { TeamMatcher.same(it.homeTeam, name) || TeamMatcher.same(it.awayTeam, name) }
        TeamUiState(m, mine, favs, q, h, cmp)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TeamUiState())

    init { reload() }

    fun reload() {
        viewModelScope.launch { load(leagueKey, teamId) { f -> main.value = f(main.value) } }
    }

    /** Loads page + history, reporting progress through [update]. */
    private suspend fun load(league: String, id: String, update: ((TeamData) -> TeamData) -> Unit) {
        update { TeamData(loading = true, historyLoading = true) }
        try {
            withContext(Dispatchers.IO) {
                val pj = async { runCatching { c.teams.teamPage(league, id) { k -> c.matches.leagueTable(k) } }.getOrNull() }
                val hj = async { runCatching { c.teams.history(league, id) }.getOrNull() }
                val page = pj.await()
                update { it.copy(loading = false, page = page, failed = page == null) }
                val h = hj.await()
                update { it.copy(history = h, historyLoading = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            update { TeamData(loading = false, historyLoading = false, failed = true) }
        }
    }

    fun toggleFavourite() = viewModelScope.launch { main.value.page?.let { c.matches.toggleTeam(it.name) } }

    fun setCompareQuery(q: String) {
        query.value = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            val local = withContext(Dispatchers.IO) { runCatching { c.teams.searchLocal(q) }.getOrDefault(emptyList()) }
                .filter { it.kind == SearchHit.Kind.TEAM && it.id != teamId }
            hits.value = local
            val remote = withContext(Dispatchers.IO) { runCatching { c.teams.searchRemote(q) }.getOrDefault(emptyList()) }
            hits.value = (local + remote.filter { r -> r.id != teamId && local.none { it.id == r.id } }).take(8)
        }
    }

    private var compareJob: Job? = null

    fun pickCompare(h: SearchHit) {
        query.value = ""
        hits.value = emptyList()
        val league = h.leagueKey ?: leagueKey
        compareJob?.cancel()
        compareJob = viewModelScope.launch { load(league, h.id) { f -> compare.value = f(compare.value ?: TeamData()) } }
    }

    fun clearCompare() { compareJob?.cancel(); compare.value = null }
}
