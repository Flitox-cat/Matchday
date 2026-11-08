package com.matchday.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.AppSettings
import com.matchday.app.domain.Catalog
import com.matchday.app.domain.Competition
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.ui.Formats
import com.matchday.app.ui.zone
import com.matchday.app.data.repo.SearchHit
import com.matchday.app.domain.TeamMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class DayFilter(val label: String) { ALL("All"), TODAY("Today"), TOMORROW("Tomorrow"), WEEK("Next 7 days") }

data class HomeFilters(
    val sport: SportType? = null,
    val competitionKey: String? = null,
    val day: DayFilter = DayFilter.ALL,
    val popularOnly: Boolean = false,
    val btts: String? = null, // "Yes" / "No": only matches with that BTTS lean
)

data class HomeUiState(
    val live: List<MatchItem> = emptyList(),
    val today: List<MatchItem> = emptyList(),
    val tomorrow: List<MatchItem> = emptyList(),
    val upcomingPopular: List<MatchItem> = emptyList(),
    val completed: List<MatchItem> = emptyList(),
    val competitions: List<Competition> = emptyList(),
    val sports: List<SportType> = emptyList(),
    val filters: HomeFilters = HomeFilters(),
    val settings: AppSettings = AppSettings(),
    val loadedOnce: Boolean = false,
)

/** One search suggestion, enriched with what the app already knows locally. */
data class SearchResult(
    val hit: SearchHit,
    val position: Int? = null,
    val next: MatchItem? = null,
    val last: MatchItem? = null,
)

data class SearchState(val query: String = "", val searching: Boolean = false, val results: List<SearchResult> = emptyList())

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search
    private var searchJob: Job? = null

    /** Debounced search: local clubs/competitions instantly, ESPN's public search after 350 ms. */
    fun setQuery(q: String) {
        _search.value = _search.value.copy(query = q)
        searchJob?.cancel()
        if (q.trim().length < 2) { _search.value = SearchState(q); return }
        searchJob = viewModelScope.launch {
            delay(200)
            _search.value = _search.value.copy(searching = true)
            val local = withContext(Dispatchers.IO) { runCatching { c.teams.searchLocal(q) }.getOrDefault(emptyList()) }
            _search.value = _search.value.copy(results = enrich(local))
            delay(150)
            val remote = withContext(Dispatchers.IO) { runCatching { c.teams.searchRemote(q) }.getOrDefault(emptyList()) }
            val merged = local + remote.filter { r -> local.none { it.id == r.id || (it.kind == r.kind && TeamMatcher.same(it.name, r.name)) } }
            _search.value = _search.value.copy(searching = false, results = enrich(merged.take(14)))
        }
    }

    fun clearSearch() { searchJob?.cancel(); _search.value = SearchState() }

    private suspend fun enrich(hits: List<SearchHit>): List<SearchResult> {
        val all = state.value.let { st -> st.live + st.today + st.tomorrow + st.upcomingPopular + st.completed }.ifEmpty { null }
            ?: runCatching { c.matches.observeMatchesOnce() }.getOrDefault(emptyList())
        return hits.map { h ->
            if (h.kind != SearchHit.Kind.TEAM) return@map SearchResult(h)
            val mine = all.filter { TeamMatcher.same(it.homeTeam, h.name) || TeamMatcher.same(it.awayTeam, h.name) }
            val next = mine.filter { it.status != MatchStatus.FINISHED }.minByOrNull { it.kickoff }
            val last = mine.filter { it.status == MatchStatus.FINISHED }.maxByOrNull { it.kickoff }
            val table = h.leagueKey?.takeIf { Catalog.find(it).hasTable }?.let { k ->
                withContext(Dispatchers.IO) { runCatching { c.matches.cachedTable(k) }.getOrNull() }
            }
            SearchResult(h, table?.row(h.id, h.name)?.rank, next, last)
        }
    }

    private val filters = MutableStateFlow(HomeFilters())
    val refreshing: StateFlow<Boolean> = c.matches.refreshing
    val messages: StateFlow<List<String>> = c.matches.messages

    val state: StateFlow<HomeUiState> = combine(c.matches.observeMatches(), filters, c.settings.flow) { all, f, s ->
        val zone = s.zone()
        val today = LocalDate.now(zone)
        val filtered = all.filter { m ->
            (f.sport == null || m.competition.sport == f.sport) &&
                (f.competitionKey == null || m.competition.key == f.competitionKey) &&
                (!f.popularOnly || m.popular) &&
                (f.btts == null || m.btts?.recommendation == f.btts)
        }
        fun day(m: MatchItem) = Formats.localDate(m.kickoff, zone)
        fun dayOk(m: MatchItem) = when (f.day) {
            DayFilter.ALL -> true
            DayFilter.TODAY -> day(m) == today
            DayFilter.TOMORROW -> day(m) == today.plusDays(1)
            DayFilter.WEEK -> !day(m).isAfter(today.plusDays(7))
        }
        val upcoming = filtered.filter { it.status == MatchStatus.UPCOMING && dayOk(it) }
        if (false) android.util.Log.i("MatchdayDiag", "home all=${all.size} filtered=${filtered.size} live=${filtered.count { it.status == MatchStatus.LIVE }} " +
            "today=${upcoming.count { day(it) == today }} zone=$zone today=$today f=$f soon=" +
            all.filter { kotlin.math.abs(it.kickoff - System.currentTimeMillis()) < 6 * 3_600_000L }.joinToString { "${it.homeTeam}@${it.kickoff}/${it.status}/${day(it)}" })
        HomeUiState(
            live = filtered.filter { it.status == MatchStatus.LIVE },
            today = upcoming.filter { day(it) == today },
            tomorrow = upcoming.filter { day(it) == today.plusDays(1) },
            upcomingPopular = upcoming.filter { day(it).isAfter(today.plusDays(1)) && it.popular },
            completed = filtered.filter { it.status == MatchStatus.FINISHED }.sortedByDescending { it.kickoff },
            competitions = all.map { it.competition }.distinctBy { it.key }.sortedByDescending { it.tier },
            sports = all.map { it.competition.sport }.distinct(),
            filters = f,
            settings = s,
            loadedOnce = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            try {
                c.matches.loadContext()
                val s = c.settings.current()
                if (System.currentTimeMillis() - s.lastRefresh > s.refreshMinutes * 60_000L) c.matches.refresh(force = false)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Startup refresh failed", e)
            }
        }
        // Keep live scores current on Home: re-check leagues with games in play every minute,
        // so a match that ends drops out of "Live" without opening the Live tab.
        viewModelScope.launch {
            runCatching { c.matches.refreshLive() }
            while (true) {
                kotlinx.coroutines.delay(60_000)
                try {
                    c.matches.refreshLive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("HomeViewModel", "Live update failed", e)
                }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        try { c.matches.refresh(force = true) } catch (e: Exception) { android.util.Log.e("HomeViewModel", "Refresh failed", e) }
    }
    fun setSport(s: SportType?) { filters.value = filters.value.copy(sport = s, competitionKey = null) }
    fun setCompetition(k: String?) { filters.value = filters.value.copy(competitionKey = k) }
    fun setDay(d: DayFilter) { filters.value = filters.value.copy(day = d) }
    fun setBtts(v: String?) { filters.value = filters.value.copy(btts = if (filters.value.btts == v) null else v) }
    fun togglePopular() { filters.value = filters.value.copy(popularOnly = !filters.value.popularOnly) }
}
