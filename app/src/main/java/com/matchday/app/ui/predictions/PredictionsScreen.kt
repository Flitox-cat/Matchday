package com.matchday.app.ui.predictions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.domain.AvoidItem
import com.matchday.app.domain.BestPicks
import com.matchday.app.domain.BestPicksRanker
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.Pick
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.CompetitionBadge
import com.matchday.app.ui.components.ConfidenceChip
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.MatchCard
import com.matchday.app.ui.components.SectionHeader
import com.matchday.app.ui.theme.Accent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class PredictionsUiState(
    val picks: BestPicks = BestPicks(null, emptyList(), emptyList(), emptyList(), emptyList()),
    val all: List<MatchItem> = emptyList(),
    val loaded: Boolean = false,
)

class PredictionsViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<PredictionsUiState> = c.matches.observeMatches().map { list ->
        val now = System.currentTimeMillis()
        val upcoming = list.filter { it.status == MatchStatus.UPCOMING && it.prediction != null && it.kickoff <= now + 48 * 3_600_000L }
        PredictionsUiState(
            picks = BestPicksRanker.rank(list, now),
            all = upcoming.sortedWith(compareByDescending<MatchItem> { it.prediction!!.confidence.ordinal }.thenBy { it.kickoff }),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PredictionsUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionsScreen(vm: PredictionsViewModel, onMatch: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.picks
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Today's Best Picks", style = MaterialTheme.typography.headlineSmall)
                    Text("Next 48 hours · ranked by probability, value and confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Banner(
                    "Estimates, not guarantees. A likely result is not the same as a good bet: value means our probability beats the probability implied by the best real price.",
                    color = Accent.Purple,
                )
            }
            if (state.loaded && state.all.isEmpty()) {
                item { EmptyState("No predictions yet", "Refresh on the Home tab. Predictions appear for matches in the next 48 hours that have odds or team statistics.") }
            }
            p.bestOverall?.let { best ->
                item { SectionHeader("Best overall selection", Accent.Green) }
                item { PickCard(best, Accent.Green, onMatch, big = true) }
            }
            pickSection("Safest-looking selections", Accent.Blue, p.safest, onMatch)
            pickSection("Best value odds", Accent.Purple, p.bestValue, onMatch)
            pickSection("High-risk opportunities", Accent.Orange, p.highRisk, onMatch)
            if (p.btts.isNotEmpty()) {
                item { SectionHeader("Both Teams to Score", Accent.Green, p.btts.size) }
                items(p.btts, key = { "btts_${it.match.id}" }) { BttsPickCard(it, onMatch) }
            }
            if (p.avoid.isNotEmpty()) {
                item { SectionHeader("Matches to avoid", Accent.Red, p.avoid.size) }
                items(p.avoid, key = { "avoid_${it.match.id}" }) { AvoidCard(it, onMatch) }
            }
            if (state.all.isNotEmpty()) {
                item { SectionHeader("All predictions", Color(0xFF7D879E), state.all.size) }
                items(state.all, key = { "all_${it.id}" }) { m -> MatchCard(m, onClick = { onMatch(m.id) }) }
            }
        }
    }
}

private fun LazyListScope.pickSection(title: String, accent: Color, list: List<Pick>, onMatch: (String) -> Unit) {
    if (list.isEmpty()) return
    item(key = "ph_$title") { SectionHeader(title, accent, list.size) }
    items(list, key = { "${title}_${it.match.id}_${it.selection.label}" }) { PickCard(it, accent, onMatch) }
}

@Composable
private fun PickCard(pick: Pick, accent: Color, onMatch: (String) -> Unit, big: Boolean = false) {
    val zone = LocalZone.current
    val m = pick.match
    val s = pick.selection
    Card(
        onClick = { onMatch(m.id) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompetitionBadge(m.competition)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${m.homeTeam} v ${m.awayTeam}", style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(Formats.kickoffLabel(m.kickoff, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.label,
                        style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${s.market} · ${s.bookmaker ?: "no price"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(Formats.odds(s.odds), style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
                    Text("odds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Our probability ${Formats.pct(s.probability)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                s.edge?.let {
                    Text(
                        "EV ${com.matchday.app.domain.PredictionEngine.signedPct(it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (it >= 0) Accent.Green else Accent.Red,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                m.prediction?.let { ConfidenceChip(it.confidence) }
            }
            Spacer(Modifier.height(6.dp))
            Text(pick.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BttsPickCard(pick: com.matchday.app.domain.BttsPick, onMatch: (String) -> Unit) {
    val zone = LocalZone.current
    val m = pick.match
    val b = pick.analysis
    val color = if (b.recommendation == "Yes") Accent.Green else Accent.Red
    val prob = if (b.recommendation == "Yes") b.yes else b.no
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onMatch(m.id) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${m.homeTeam} v ${m.awayTeam}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Formats.kickoffLabel(m.kickoff, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BTTS ${b.recommendation}", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${Formats.pct(prob)}  ·  odds N/A", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            ConfidenceChip(b.confidence)
        }
        Text(
            "Quick estimate from expected goals; open the match for the full BTTS analysis (form, scoring rates, head-to-head).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AvoidCard(a: AvoidItem, onMatch: (String) -> Unit) {
    val zone = LocalZone.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Accent.Red.copy(alpha = 0.08f))
            .clickable { onMatch(a.match.id) }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.match.homeTeam} v ${a.match.awayTeam}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(Formats.kickoffLabel(a.match.kickoff, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(a.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
