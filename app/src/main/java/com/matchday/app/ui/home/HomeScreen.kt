package com.matchday.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matchday.app.domain.MatchItem
import com.matchday.app.ui.Formats
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.MatchCard
import com.matchday.app.ui.components.SectionHeader
import com.matchday.app.ui.components.sportIcon
import com.matchday.app.ui.theme.Accent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onMatch: (String) -> Unit, onSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val s = state.settings

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Matchday", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Updated ${Formats.ago(s.lastRefresh)}" +
                            if (s.quotaRemaining >= 0) " · ${s.quotaRemaining} odds credits left" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                item { Filters(state, vm) }

                if (s.effectiveOddsKey.isBlank()) {
                    item {
                        Banner(
                            "Add a free The Odds API key in Settings to load fixtures and bookmaker odds. Tap to open Settings.",
                            color = Accent.Blue, onClick = onSettings,
                        )
                    }
                }
                messages.forEach { m -> item { Banner(m) } }

                section("Live now", Accent.Live, state.live, onMatch)
                section("Today", Accent.Blue, state.today, onMatch)
                section("Tomorrow", Accent.Purple, state.tomorrow, onMatch)
                section("Upcoming popular", Accent.Orange, state.upcomingPopular, onMatch)
                section("Recently completed", Color(0xFF7D879E), state.completed.take(15), onMatch)

                val nothing = state.live.isEmpty() && state.today.isEmpty() && state.tomorrow.isEmpty() &&
                    state.upcomingPopular.isEmpty() && state.completed.isEmpty()
                if (nothing && state.loadedOnce && s.effectiveOddsKey.isNotBlank()) {
                    item {
                        EmptyState(
                            "No matches found",
                            "Pull to refresh, change the filters, or enable more competitions in Settings.",
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: String, accent: Color, list: List<MatchItem>, onMatch: (String) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h_$title") { SectionHeader(title, accent, list.size) }
    items(list, key = { "${title}_${it.id}" }) { m -> MatchCard(m, onClick = { onMatch(m.id) }) }
}

@Composable
private fun Filters(state: HomeUiState, vm: HomeViewModel) {
    val f = state.filters
    Column(Modifier.padding(top = 4.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FilterChip(selected = f.sport == null, onClick = { vm.setSport(null) }, label = { Text("All sports") }) }
            items(state.sports) { sp ->
                FilterChip(
                    selected = f.sport == sp,
                    onClick = { vm.setSport(if (f.sport == sp) null else sp) },
                    label = { Text(sp.label) },
                    leadingIcon = { Icon(sportIcon(sp), contentDescription = null) },
                )
            }
            item {
                FilterChip(
                    selected = f.popularOnly,
                    onClick = { vm.togglePopular() },
                    label = { Text("Popular") },
                    leadingIcon = { Icon(Icons.Filled.Whatshot, contentDescription = null) },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(DayFilter.entries) { d ->
                FilterChip(selected = f.day == d, onClick = { vm.setDay(d) }, label = { Text(d.label) })
            }
        }
        val comps = state.competitions.filter { f.sport == null || it.sport == f.sport }
        if (comps.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(comps, key = { it.key }) { c ->
                    FilterChip(
                        selected = f.competitionKey == c.key,
                        onClick = { vm.setCompetition(if (f.competitionKey == c.key) null else c.key) },
                        label = { Text(c.title) },
                    )
                }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Times shown in ${if (state.settings.useDeviceZone) "your device time zone" else "Qatar time (AST)"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
