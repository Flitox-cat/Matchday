package com.matchday.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
fun HomeScreen(vm: HomeViewModel, onMatch: (String) -> Unit, onSettings: () -> Unit, onTeam: (String, String) -> Unit = { _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val s = state.settings

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Matchday", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Updated ${Formats.ago(s.lastRefresh)} · free public data, no account needed",
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
                item { SearchBox(search, vm) }
                if (search.query.trim().length >= 2) {
                    searchResults(search, vm, onMatch, onTeam)
                    return@LazyColumn
                }
                item { Filters(state, vm) }

                messages.forEach { m -> item { Banner(m) } }

                // Home = upcoming matches only; live and finished games live in the Live tab.
                section("Today", Accent.Blue, state.today, onMatch)
                section("Tomorrow", Accent.Purple, state.tomorrow, onMatch)
                section("Upcoming popular", Accent.Orange, state.upcomingPopular, onMatch)

                val nothing = state.today.isEmpty() && state.tomorrow.isEmpty() && state.upcomingPopular.isEmpty()
                if (nothing && state.loadedOnce) {
                    item {
                        EmptyState(
                            "No upcoming matches",
                            "Matches in play and today's results are in the Live tab. Pull to refresh, change the filters, or enable more competitions in Settings.",
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
            item { FilterChip(selected = f.btts == "Yes", onClick = { vm.setBtts("Yes") }, label = { Text("BTTS Yes") }) }
            item { FilterChip(selected = f.btts == "No", onClick = { vm.setBtts("No") }, label = { Text("BTTS No") }) }
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

@Composable
private fun SearchBox(search: SearchState, vm: HomeViewModel) {
    androidx.compose.material3.OutlinedTextField(
        value = search.query,
        onValueChange = { vm.setQuery(it) },
        singleLine = true,
        placeholder = { Text("Search teams or competitions") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (search.query.isNotEmpty()) IconButton(onClick = { vm.clearSearch() }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun LazyListScope.searchResults(search: SearchState, vm: HomeViewModel, onMatch: (String) -> Unit, onTeam: (String, String) -> Unit) {
    if (search.searching) item { androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Accent.Purple) }
    if (!search.searching && search.results.isEmpty()) {
        item { EmptyState("No results", "No team or competition matches \"${search.query}\". Try another spelling (offline, only teams in your match list are searched).") }
    }
    items(search.results, key = { "s_${it.hit.kind}_${it.hit.id}" }) { r ->
        SearchResultCard(r, onClick = {
            if (r.hit.kind == com.matchday.app.data.repo.SearchHit.Kind.COMPETITION) {
                vm.clearSearch(); vm.setCompetition(r.hit.id)
            } else {
                val league = r.hit.leagueKey ?: "soccer/all"
                onTeam(league, r.hit.id)
            }
        }, onMatch = onMatch)
    }
}

@Composable
private fun SearchResultCard(r: SearchResult, onClick: () -> Unit, onMatch: (String) -> Unit) {
    val zone = com.matchday.app.ui.LocalZone.current
    com.matchday.app.ui.components.InfoCard(Modifier.clickable { onClick() }) {
        com.matchday.app.ui.team.HitRow(r.hit, onClick)
        if (r.hit.kind == com.matchday.app.data.repo.SearchHit.Kind.TEAM) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 38.dp, top = 2.dp)) {
                Text(r.position?.let { "#$it in league" } ?: "Position: N/A", style = MaterialTheme.typography.labelSmall, color = Accent.Green)
                Spacer(Modifier.width(10.dp))
                val form = r.hit.form?.filter { it in "WDL" }?.takeLast(5)
                if (form != null && form.isNotEmpty()) {
                    com.matchday.app.ui.components.FormStrip(com.matchday.app.data.repo.EspnMapper.formResults(form), 5)
                } else Text("Form: N/A", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.last?.let { m ->
                Text("Last: ${m.homeTeam} ${m.homeScore ?: "-"}-${m.awayScore ?: "-"} ${m.awayTeam}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 38.dp, top = 2.dp))
            }
            r.next?.let { m ->
                val p = m.prediction
                Text(
                    "Next: ${m.homeTeam} vs ${m.awayTeam} · ${Formats.kickoffLabel(m.kickoff, zone)}\n" +
                        (m.odds?.let { o -> "Odds ${Formats.odds(o.home?.best?.price)} / ${Formats.odds(o.draw?.best?.price)} / ${Formats.odds(o.away?.best?.price)}" } ?: "Odds N/A") +
                        (p?.let { " · AI ${Formats.pct(it.final.home)} / ${Formats.pct(it.final.draw)} / ${Formats.pct(it.final.away)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = Accent.Blue,
                    modifier = Modifier.padding(start = 38.dp, top = 4.dp).clickable { onMatch(m.id) },
                )
            }
            if (r.next == null && r.last == null) {
                Text("Open the team page for fixtures, results and history.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 38.dp, top = 2.dp))
            }
        }
    }
}
