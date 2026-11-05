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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import com.matchday.app.data.repo.AccuracyReport
import com.matchday.app.domain.MarketEngine
import com.matchday.app.domain.MarketPick
import com.matchday.app.domain.SportType
import kotlinx.coroutines.flow.stateIn

/** One entry of "Today's Best Betting Opportunities": the strongest market of one match. */
data class Opportunity(val match: MatchItem, val pick: MarketPick)

data class PredictionsUiState(
    val picks: BestPicks = BestPicks(null, emptyList(), emptyList(), emptyList(), emptyList()),
    val all: List<MatchItem> = emptyList(),
    val loaded: Boolean = false,
    val opportunities: List<Opportunity> = emptyList(),
    val accuracy: AccuracyReport? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PredictionsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<PredictionsUiState> = c.matches.observeMatches().mapLatest { list ->
        val now = System.currentTimeMillis()
        val upcoming = list.filter { it.status == MatchStatus.UPCOMING && it.prediction != null && it.kickoff <= now + 48 * 3_600_000L }
        val opps = runCatching { opportunities(list, now) }.getOrDefault(emptyList())
        // Track today's ranking for the accuracy report (top 10).
        opps.take(10).forEach { o -> runCatching { c.matches.tracker.record(o.match, listOf(o.pick), "daily") } }
        PredictionsUiState(
            picks = BestPicksRanker.rank(list, now),
            all = upcoming.sortedWith(compareByDescending<MatchItem> { it.prediction!!.confidence.ordinal }.thenBy { it.kickoff }),
            loaded = true,
            opportunities = opps,
            accuracy = runCatching { c.matches.tracker.report() }.getOrNull(),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PredictionsUiState())

    /** Best market of each football match kicking off in the next 24 hours, ranked by probability. */
    private suspend fun opportunities(list: List<MatchItem>, now: Long): List<Opportunity> =
        list.filter { it.status == MatchStatus.UPCOMING && it.competition.sport == SportType.FOOTBALL && it.kickoff in now..(now + 24 * 3_600_000L) }
            .mapNotNull { m ->
                val p = m.prediction ?: return@mapNotNull null
                if (p.completeness < 0.5) return@mapNotNull null
                val lh = p.expectedHome ?: return@mapNotNull null
                val la = p.expectedAway ?: return@mapNotNull null
                val saved = runCatching { c.oneXBet.state(m) }.getOrNull() as? com.matchday.app.data.repo.OneXBetState.Ok
                val markets = MarketEngine.analyze(
                    m.homeTeam, m.awayTeam, lh, la, p, null, emptyList(), emptyList(), emptyList(), emptyList(), null,
                    saved?.current?.prices, saved?.current?.bookmaker,
                    com.matchday.app.ui.detail.feedPrices(m.odds), m.odds?.home?.best?.bookmaker, false,
                ).top
                markets.firstOrNull()?.let { Opportunity(m, it) }
            }
            .sortedByDescending { it.pick.probability }
            .take(15)
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
            if (state.opportunities.isNotEmpty()) {
                item { SectionHeader("Today's Best Betting Opportunities", Accent.Green, state.opportunities.size) }
                item {
                    Text(
                        "Every market of every match in the next 24 h compared; the strongest of each match, ranked by probability. Quick view from the goals model and saved prices: open a match for the full analysis.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
                items(state.opportunities, key = { "opp_${it.match.id}" }) { OpportunityRow(it, onMatch) }
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
            state.accuracy?.let { a ->
                item { SectionHeader("Prediction accuracy", Accent.Blue) }
                item { AccuracyCard(a) }
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

@Composable
private fun OpportunityRow(o: Opportunity, onMatch: (String) -> Unit) {
    val zone = LocalZone.current
    val pk = o.pick
    val c = when { pk.probability >= 0.75 -> Accent.Green; pk.probability >= 0.6 -> Accent.Orange; else -> Accent.Red }
    Card(
        onClick = { onMatch(o.match.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${o.match.homeTeam} vs ${o.match.awayTeam}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${o.match.competition.title} · ${Formats.kickoffLabel(o.match.kickoff, zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${Math.round(pk.probability * 100)}%", style = MaterialTheme.typography.titleLarge, color = c, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pk.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pk.odds?.let { "${Formats.odds(it)} · ${pk.bookmaker ?: ""}" } ?: "1xBet odds: not loaded", style = MaterialTheme.typography.labelSmall,
                    color = if (pk.odds == null) MaterialTheme.colorScheme.onSurfaceVariant else Accent.Green)
            }
            Text(pk.family.label + " · " + (pk.evidence.firstOrNull() ?: ""), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AccuracyCard(a: AccuracyReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (a.rows.isEmpty()) {
                Text("No settled recommendations yet. Every pick the app makes is saved on this phone and checked against the final score after the match.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth()) {
                    listOf("Market" to 1.6f, "Settled" to 0.8f, "Won" to 0.7f, "Hit rate" to 0.9f, "Expected" to 0.9f).forEach { (t, w) ->
                        Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w))
                    }
                }
                a.rows.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(r.category, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.6f), maxLines = 2)
                        Text("${r.settled}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
                        Text("${r.won}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.7f))
                        Text("${Math.round(r.hitRate * 100)}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.9f),
                            color = if (r.hitRate >= r.avgPredicted - 0.05) Accent.Green else Accent.Red)
                        Text("${Math.round(r.avgPredicted * 100)}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.9f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Calibration (does 70% happen about 70% of the time?)", style = MaterialTheme.typography.titleSmall)
                a.calibration.forEach { c ->
                    Text("Predicted ${c.range}: ${c.n} picks, expected ${Math.round(c.predicted * 100)}%, actual ${Math.round(c.actual * 100)}%",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
                        color = if (kotlin.math.abs(c.actual - c.predicted) <= 0.08) MaterialTheme.colorScheme.onSurface else Accent.Orange)
                }
                a.brier?.let { Text("Brier score: ${String.format("%.3f", it)} (lower is better; 0.25 = coin-flip guessing)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                if (a.rows.sumOf { it.settled } < 30) Text("Fewer than 30 settled picks: too early to judge calibration.", style = MaterialTheme.typography.labelSmall, color = Accent.Orange)
            }
            Text("Pending: ${a.pending} · Void/unsettleable: ${a.voided} (draw-no-bet refunds, first-goal markets, missing half-time scores)",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (a.recent.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Latest results", style = MaterialTheme.typography.titleSmall)
                a.recent.take(10).forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                        Text(if (t.void) "–" else if (t.result == true) "✓" else "✗", color = if (t.void) Color.Gray else if (t.result == true) Accent.Green else Accent.Red,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                        Text("${t.home} ${t.score ?: ""} ${t.away}: ${t.label} (${Math.round(t.probability * 100)}%)",
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
