package com.matchday.app.ui.favourites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.FavouriteEntity
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.MatchCard
import com.matchday.app.ui.components.SectionHeader
import com.matchday.app.ui.components.TeamBadge
import com.matchday.app.ui.theme.Accent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FavouritesUiState(
    val favourites: List<FavouriteEntity> = emptyList(),
    val matches: List<MatchItem> = emptyList(),
)

class FavouritesViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<FavouritesUiState> = combine(c.matches.observeFavourites(), c.matches.observeMatches()) { f, m ->
        FavouritesUiState(f, m.filter { it.favourite && it.status != MatchStatus.FINISHED })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FavouritesUiState())

    fun addTeam(name: String) = viewModelScope.launch { if (name.isNotBlank()) c.matches.toggleTeam(name.trim()) }
    fun remove(key: String) = viewModelScope.launch { c.matches.removeFavourite(key) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(vm: FavouritesViewModel, onMatch: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Favourites", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item {
                InfoCard {
                    Text("Follow a team", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it },
                            placeholder = { Text("e.g. Arsenal, Real Madrid, Lakers") },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.addTeam(input); input = "" }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                    }
                    Text(
                        "You can also tap the star next to a team or competition on any match page.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.favourites.isEmpty()) {
                item { EmptyState("Nothing followed yet", "Followed teams and competitions get their own feed here and priority notifications.") }
            } else {
                item { SectionHeader("Following", Accent.Orange, state.favourites.size) }
                items(state.favourites, key = { it.key }) { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (f.type == "TEAM") TeamBadge(f.name, null, 32.dp)
                        else Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Accent.Orange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (f.type == "TEAM") "Team" else "Competition", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.remove(f.key) }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                    }
                }
            }
            if (state.matches.isNotEmpty()) {
                item { SectionHeader("Upcoming & live", Accent.Blue, state.matches.size) }
                items(state.matches, key = { "fav_${it.id}" }) { m -> MatchCard(m, onClick = { onMatch(m.id) }) }
            }
        }
    }
}
