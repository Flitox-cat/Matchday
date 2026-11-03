package com.matchday.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.data.repo.DetailExtras
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.TeamMatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val item: MatchItem? = null,
    val extras: DetailExtras = DetailExtras(),
    val favourites: List<FavouriteEntity> = emptyList(),
) {
    fun isFavTeam(name: String) = favourites.any { it.type == "TEAM" && TeamMatcher.same(it.name, name) }
    fun isFavCompetition() = item != null && favourites.any { it.key == "COMP:${item.competition.key}" }
}

class MatchDetailViewModel(private val c: AppContainer, private val id: String) : ViewModel() {
    private val extras = MutableStateFlow(DetailExtras())
    private var loadJob: Job? = null
    private val item = c.matches.observeMatch(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<DetailUiState> = combine(item, extras, c.matches.observeFavourites()) { i, x, f ->
        DetailUiState(i, x, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            item.collect { i -> if (i != null && loadJob == null) load(i) }
        }
    }

    private fun load(i: MatchItem) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            c.matches.loadDetail(i).collect { extras.value = it }
        }
    }

    fun reload() { item.value?.let { load(it) } }

    fun toggleTeam(name: String) = viewModelScope.launch { c.matches.toggleTeam(name) }
    fun toggleCompetition() = viewModelScope.launch { item.value?.let { c.matches.toggleCompetition(it.competition) } }
}
