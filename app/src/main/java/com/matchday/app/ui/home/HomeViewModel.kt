package com.matchday.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.AppSettings
import com.matchday.app.domain.Competition
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.ui.Formats
import com.matchday.app.ui.zone
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

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val filters = MutableStateFlow(HomeFilters())
    val refreshing: StateFlow<Boolean> = c.matches.refreshing
    val messages: StateFlow<List<String>> = c.matches.messages

    val state: StateFlow<HomeUiState> = combine(c.matches.observeMatches(), filters, c.settings.flow) { all, f, s ->
        val zone = s.zone()
        val today = LocalDate.now(zone)
        val filtered = all.filter { m ->
            (f.sport == null || m.competition.sport == f.sport) &&
                (f.competitionKey == null || m.competition.key == f.competitionKey) &&
                (!f.popularOnly || m.popular)
        }
        fun day(m: MatchItem) = Formats.localDate(m.kickoff, zone)
        fun dayOk(m: MatchItem) = when (f.day) {
            DayFilter.ALL -> true
            DayFilter.TODAY -> day(m) == today
            DayFilter.TOMORROW -> day(m) == today.plusDays(1)
            DayFilter.WEEK -> !day(m).isAfter(today.plusDays(7))
        }
        val upcoming = filtered.filter { it.status == MatchStatus.UPCOMING && dayOk(it) }
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
            c.matches.loadContext()
        }
        viewModelScope.launch {
            val s = c.settings.current()
            if (System.currentTimeMillis() - s.lastRefresh > s.refreshMinutes * 60_000L) c.matches.refresh(force = false)
        }
    }

    fun refresh() = viewModelScope.launch { c.matches.refresh(force = true) }
    fun setSport(s: SportType?) { filters.value = filters.value.copy(sport = s, competitionKey = null) }
    fun setCompetition(k: String?) { filters.value = filters.value.copy(competitionKey = k) }
    fun setDay(d: DayFilter) { filters.value = filters.value.copy(day = d) }
    fun togglePopular() { filters.value = filters.value.copy(popularOnly = !filters.value.popularOnly) }
}
