package com.matchday.app.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matchday.app.domain.LineupInfo
import com.matchday.app.domain.MarketOdds
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.OddsSummary
import com.matchday.app.domain.Prediction
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamStats
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.CompetitionBadge
import com.matchday.app.ui.components.ConfidenceChip
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.FormStrip
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.KeyValue
import com.matchday.app.ui.components.LiveDot
import com.matchday.app.ui.components.MovementArrow
import com.matchday.app.ui.components.ProbabilityBar
import com.matchday.app.ui.components.TeamBadge
import com.matchday.app.ui.components.sportColor
import com.matchday.app.ui.theme.Accent

// Match overview + AI prediction + best bet live on Overview; the rest follow the analysis order.
private val tabs = listOf("Overview", "Insights", "Teams", "Table", "Form", "H2H", "BTTS", "Squad", "Venue", "Odds")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchDetailScreen(vm: MatchDetailViewModel, onBack: () -> Unit, onSettings: () -> Unit = {}, onTeam: (String, String) -> Unit = { _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val item = state.item

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(item?.competition?.title ?: "Match", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                if (item != null) {
                    IconButton(onClick = { vm.toggleCompetition() }) {
                        Icon(
                            if (state.isFavCompetition()) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Follow competition",
                            tint = if (state.isFavCompetition()) Accent.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { vm.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload analysis") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (item == null) {
            EmptyState("Match not available", "It may have been removed from the odds feed. Go back and refresh.")
        } else {
            DetailBody(vm, state, item, tab, onSettings, onTeam) { tab = it }
        }
    }
}

@Composable
private fun DetailBody(vm: MatchDetailViewModel, state: DetailUiState, item: MatchItem, tab: Int, onSettings: () -> Unit, onTeam: (String, String) -> Unit, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.extras.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent.Purple)
        }
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 12.dp,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            tabs.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t) })
            }
        }
        val prediction = state.extras.prediction ?: item.prediction
        val odds = item.odds
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item { Header(item, state, onTeam) { vm.toggleTeam(it) } }
            when (tab) {
                0 -> item {
                    Column {
                        if (isFootball(item)) VerdictCard(item, state)
                        (state.extras.prediction ?: item.prediction)?.injuryImpact?.let { InjuryImpactCard(item, it) }
                        Overview(item, prediction, state.extras.notes)
                    }
                }
                1 -> item { if (isFootball(item)) InsightsTab(item, state) else NotForSport(item) }
                2 -> item { if (isFootball(item)) TeamComparisonTab(item, state.extras) else NotForSport(item) }
                3 -> item { if (isFootball(item)) StandingsTab(item, state.extras) else NotForSport(item) }
                4 -> item { RecentFormTab(item, state.extras) }
                5 -> item { HeadToHeadTab(item, state.extras) }
                6 -> item { if (isFootball(item)) BttsTab(item, state.extras) else NotForSport(item) }
                7 -> item { SquadTab(item, state) }
                8 -> item { ConditionsTab(item, state) }
                else -> item {
                    Column {
                        if (isFootball(item)) OneXBetSection(item, state, onSave = { vm.saveOneXBet(it) }, onClear = { vm.clearOneXBet() }, onLoadApi = { vm.loadOneXBetFromApi() }, onSettings = onSettings)
                        OddsTab(item, odds, prediction)
                    }
                }
            }
        }
    }
}

private fun isFootball(item: MatchItem) = item.competition.sport == SportType.FOOTBALL

@Composable
private fun NotForSport(item: MatchItem) {
    EmptyState("Football only", "Team profiles and league tables are shown for football. For ${item.competition.sport.label}, see Form, H2H and Odds.")
}

// ---------------------------------------------------------------------------

@Composable
private fun Header(item: MatchItem, state: DetailUiState, onTeam: (String, String) -> Unit, onFav: (String) -> Unit) {
    val zone = LocalZone.current
    val accent = sportColor(item.competition.sport)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.25f), Accent.Purple.copy(alpha = 0.18f), MaterialTheme.colorScheme.surface))),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompetitionBadge(item.competition, 20.dp)
                Spacer(Modifier.width(6.dp))
                Text(item.competition.title, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(item.homeTeam, item.homeCrest, "Home", state.isFavTeam(item.homeTeam), Modifier.weight(1f), state.homeId?.takeIf { isFootball(item) }?.let { id -> { onTeam(item.competition.key, id) } }) { onFav(item.homeTeam) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
                    when (item.status) {
                        MatchStatus.UPCOMING -> {
                            Text(Formats.time(item.kickoff, zone), style = MaterialTheme.typography.headlineSmall)
                            Text(Formats.day(item.kickoff, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            Text("${item.homeScore ?: "-"} : ${item.awayScore ?: "-"}", style = MaterialTheme.typography.headlineSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.status == MatchStatus.LIVE) { LiveDot(); Spacer(Modifier.width(4.dp)) }
                                Text(
                                    item.statusDetail ?: if (item.status == MatchStatus.LIVE) "LIVE" else "Full time",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.status == MatchStatus.LIVE) Accent.Live else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                TeamColumn(item.awayTeam, item.awayCrest, "Away", state.isFavTeam(item.awayTeam), Modifier.weight(1f), state.awayId?.takeIf { isFootball(item) }?.let { id -> { onTeam(item.competition.key, id) } }) { onFav(item.awayTeam) }
            }
            val venue = state.extras.venue?.let { v -> listOfNotNull(v, state.extras.city).joinToString(", ") } ?: item.venue
            if (venue != null) {
                Spacer(Modifier.height(10.dp))
                Text(venue, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TeamColumn(name: String, crest: String?, role: String, fav: Boolean, modifier: Modifier, onOpen: (() -> Unit)?, onFav: () -> Unit) {
    Column(if (onOpen != null) modifier.clickable { onOpen() } else modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamBadge(name, crest, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onFav() }.padding(4.dp)) {
            Icon(
                if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Follow $name",
                tint = if (fav) Accent.Orange else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

@Composable
private fun Overview(item: MatchItem, p: Prediction?, notes: List<String>) {
    if (p == null) {
        Column {
            notes.forEach { Banner(it, color = Accent.Blue) }
            InfoCard {
                Text("No prediction yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Predictions need bookmaker odds or a league table. Neither is available for this match yet (finished, live or tennis matches have no prediction).",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    Column {
        notes.forEach { Banner(it, color = Accent.Blue) }
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Our estimate", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ConfidenceChip(p.confidence)
            }
            Spacer(Modifier.height(4.dp))
            Text(p.method, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            ProbabilityBar(p.final.home, p.final.draw, p.final.away, height = 10.dp)
            Spacer(Modifier.height(12.dp))
            ProbTableHeader()
            ProbRow(item.homeTeam, p.model?.home, p.market?.home, p.final.home, Accent.Blue)
            if (item.competition.hasDraw) ProbRow("Draw", p.model?.draw, p.market?.draw, p.final.draw, Color(0xFF7D879E))
            ProbRow(item.awayTeam, p.model?.away, p.market?.away, p.final.away, Accent.Purple)
            Spacer(Modifier.height(6.dp))
            Text(
                "Model = our statistics-only estimate. Market = bookmaker consensus with margin removed. Final = blend used for picks.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard {
            Text("Prediction", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            KeyValue("Predicted winner", p.predictedWinner ?: "Draw (no clear winner)", Accent.Green)
            KeyValue("Most likely outcome", "${p.mostLikely.label} · ${Formats.pct(p.mostLikely.probability)}")
            p.predictedScore?.let { KeyValue("Predicted score", it) }
            if (p.expectedHome != null && p.expectedAway != null) {
                KeyValue("Expected goals", String.format("%.1f - %.1f (total %.1f)", p.expectedHome, p.expectedAway, p.expectedHome + p.expectedAway))
            }
            if (item.competition.sport == SportType.FOOTBALL) {
                KeyValue("Over 1.5 goals", Formats.pct(p.over15))
                KeyValue("Over 2.5 goals", Formats.pct(p.over25))
                KeyValue("Over 3.5 goals", Formats.pct(p.over35))
                KeyValue("Both teams to score", Formats.pct(p.btts))
            }
            if (item.competition.hasDraw) {
                KeyValue("Double chance 1X / X2 / 12", "${Formats.pct(p.doubleChance1X)} / ${Formats.pct(p.doubleChanceX2)} / ${Formats.pct(p.doubleChance12)}")
            }
            Text(
                "These are estimated probabilities, not bookmaker prices.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InfoCard {
            Text("Best betting opportunity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val v = p.bestValue
            if (v != null) {
                Text("${v.label} @ ${Formats.odds(v.odds)}", style = MaterialTheme.typography.titleLarge, color = Accent.Green)
                Text("Best price at ${v.bookmaker} (real bookmaker odds)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                KeyValue("Our probability", Formats.pct(v.probability))
                KeyValue("Probability implied by the price", Formats.pct(v.odds?.let { 1 / it }))
                KeyValue("Expected return per unit", com.matchday.app.domain.PredictionEngine.signedPct(v.edge ?: 0.0), Accent.Green)
            } else {
                Text("No value pick", style = MaterialTheme.typography.titleLarge)
                Text(
                    "No available price beats our estimate by at least 3%. The likeliest result is not automatically a good bet.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Why", style = MaterialTheme.typography.titleSmall)
            p.reasons.forEach { r ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text("•  ", color = Accent.Purple)
                    Text(r, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Confidence", style = MaterialTheme.typography.titleSmall)
            p.confidenceNotes.forEach { n ->
                Text("•  $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(
            "Estimates only - no bet is guaranteed or risk-free. Gambling is illegal in some countries, including Qatar; check your local law.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ProbTableHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.weight(1.6f))
        listOf("Model", "Market", "Final").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProbRow(label: String, model: Double?, market: Double?, final: Double?, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.6f))
        Text(Formats.pct(model), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(Formats.pct(market), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(Formats.pct(final), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// Odds
// ---------------------------------------------------------------------------

@Composable
private fun OddsTab(item: MatchItem, odds: OddsSummary?, p: Prediction?) {
    val zone = LocalZone.current
    if (odds == null) {
        EmptyState("Odds: N/A", "No bookmaker prices are available for this match right now.")
        return
    }
    Column {
        Banner(
            "Real bookmaker odds (decimal) from ${odds.home?.best?.bookmaker ?: odds.over?.best?.bookmaker ?: "the bookmaker"}, " +
                "via ESPN's free feed. Updated ${Formats.dayTime(odds.fetchedAt, zone)}. Arrows and \"was\" compare with the opening price.",
            color = Accent.Blue,
        )
        if (p != null) {
            InfoCard {
                Text("Our fair odds (estimate, not a bookmaker price)", style = MaterialTheme.typography.titleSmall, color = Accent.Purple)
                Spacer(Modifier.height(6.dp))
                FairRow(item.homeTeam, p.final.home, odds.home?.best?.price)
                p.final.draw?.let { FairRow("Draw", it, odds.draw?.best?.price) }
                FairRow(item.awayTeam, p.final.away, odds.away?.best?.price)
            }
        }
        odds.markets.forEach { MarketCard(it) }
        if (item.competition.sport == SportType.FOOTBALL) {
            InfoCard {
                Text("Not in the feed", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Free feed: both-teams-to-score and double-chance prices N/A. Load 1xBet odds above for those markets; our probabilities are on the Overview tab.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FairRow(label: String, prob: Double, best: Double?) {
    val fair = if (prob > 0) 1 / prob else null
    val edge = if (best != null) prob * best - 1 else null
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("fair ${Formats.odds(fair)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp), textAlign = TextAlign.End)
        Text("best ${Formats.odds(best)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(84.dp), textAlign = TextAlign.End)
        Text(
            edge?.let { com.matchday.app.domain.PredictionEngine.signedPct(it) } ?: "N/A",
            style = MaterialTheme.typography.bodyMedium,
            color = when { edge == null -> MaterialTheme.colorScheme.onSurfaceVariant; edge >= 0.03 -> Accent.Green; edge < 0 -> Accent.Red; else -> MaterialTheme.colorScheme.onSurface },
            modifier = Modifier.width(64.dp), textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MarketCard(m: MarketOdds) {
    InfoCard {
        Text(m.title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        m.outcomes.forEach { o ->
            var open by remember { mutableStateOf(false) }
            val best = o.best
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(o.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Consensus ${Formats.pct(o.consensusProb)} · best at ${best?.bookmaker ?: "N/A"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(Formats.odds(best?.price), style = MaterialTheme.typography.titleMedium, color = Accent.Green)
                MovementArrow(best?.price, best?.previous)
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                    o.quotes.forEach { q ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(q.bookmaker, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            q.previous?.let {
                                Text("was ${Formats.odds(it)}  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Formats.odds(q.price), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            MovementArrow(q.price, q.previous)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Squad (injuries, suspensions, line-ups)
// ---------------------------------------------------------------------------

@Composable
private fun SquadTab(item: MatchItem, state: DetailUiState) {
    val x = state.extras
    Column {
        if (!x.injuries.known) {
            Banner(
                if (x.loading) "Loading team news…"
                else if (item.competition.sport == SportType.FOOTBALL) "Football injury lists aren't in the free feed. Check the line-ups below once they're confirmed."
                else "No injury report published for this game.",
                color = Accent.Blue,
            )
        } else {
            InjuryCard(item.homeTeam, x.injuries.homeOut, x.injuries.homeDoubtful)
            InjuryCard(item.awayTeam, x.injuries.awayOut, x.injuries.awayDoubtful)
            Text(
                "Impact: each confirmed absence lowers that side's expected goals by 2.5% (doubts 1%, max 12%) and raises the opponent's by 1.5% (doubts 0.6%, max 8%). " +
                    "The before/after effect on the probabilities is shown on the Overview tab. The model can't tell a star from a squad player, so check who is missing.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (x.lineups.isEmpty()) {
            Banner("Line-ups: not confirmed yet. They usually appear 20-60 minutes before kick-off; reopen this match then.", color = Accent.Purple)
        } else {
            x.lineups.forEachIndexed { i, l ->
                val home = com.matchday.app.domain.TeamMatcher.same(l.team, item.homeTeam) ||
                    (i == 0 && !com.matchday.app.domain.TeamMatcher.same(l.team, item.awayTeam))
                LineupCardV2(l, home)
            }
        }
    }
}

@Composable
private fun InjuryCard(team: String, out: List<String>, doubtful: List<String>) {
    InfoCard {
        Text(team, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (out.isEmpty() && doubtful.isEmpty()) {
            Text("No reported absences.", style = MaterialTheme.typography.bodyMedium, color = Accent.Green)
        }
        if (out.isNotEmpty()) {
            Text("Out (injured / suspended)", style = MaterialTheme.typography.labelMedium, color = Accent.Red)
            out.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (doubtful.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Doubtful", style = MaterialTheme.typography.labelMedium, color = Accent.Orange)
            doubtful.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun LineupCard(l: LineupInfo) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(l.team, l.logo, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(l.team, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            l.formation?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Accent.Blue) }
        }
        Spacer(Modifier.height(6.dp))
        Text("Starting line-up", style = MaterialTheme.typography.labelMedium, color = Accent.Green)
        l.starters.forEach { p ->
            Row(Modifier.padding(top = 2.dp)) {
                Text(p.number ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(28.dp))
                Text(p.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(p.position ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (l.bench.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("Bench: " + l.bench.joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Conditions
// ---------------------------------------------------------------------------

@Composable
private fun ConditionsTab(item: MatchItem, state: DetailUiState) {
    val x = state.extras
    Column {
        InfoCard {
            Text("Venue", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            KeyValue("Stadium", x.venue ?: item.venue ?: "N/A")
            KeyValue("City", x.city ?: "N/A")
            KeyValue("Home side", item.homeTeam)
        }
        InfoCard {
            Text("Weather at kick-off", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val w = x.weather
            if (w == null) {
                Text(
                    if (x.loading) "Loading…" else "N/A - needs a known venue city and a kick-off within the 7-day forecast.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KeyValue("Conditions", w.description)
                KeyValue("Temperature", w.temperatureC?.let { String.format("%.0f °C", it) } ?: "N/A")
                KeyValue("Chance of rain", w.precipitationChance?.let { "${it.toInt()}%" } ?: "N/A")
                KeyValue("Wind", w.windKmh?.let { String.format("%.0f km/h", it) } ?: "N/A")
                Text("Forecast for ${w.place} (Open-Meteo).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        InfoCard {
            Text("Home advantage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val h = x.homeStats
            val a = x.awayStats
            if (h != null && h.homePlayed > 0) {
                KeyValue("${item.homeTeam} at home", String.format("%.2f scored · %.2f conceded per game", h.homeFor.toDouble() / h.homePlayed, h.homeAgainst.toDouble() / h.homePlayed))
            }
            if (a != null && a.awayPlayed > 0) {
                KeyValue("${item.awayTeam} away", String.format("%.2f scored · %.2f conceded per game", a.awayFor.toDouble() / a.awayPlayed, a.awayAgainst.toDouble() / a.awayPlayed))
            }
            Text(
                "The model applies a standard home-advantage factor to each side's overall scoring rates.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InfoCard {
            Text("Travel & fatigue", style = MaterialTheme.typography.titleMedium)
            Text(
                "Not modelled: no free data source provides travel distance or rest days reliably. Check the recent-matches dates on the Teams tab.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
