package com.matchday.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.PlayerModel
import com.matchday.app.domain.PlayerWatch
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.TeamBadge
import com.matchday.app.ui.theme.Accent

/** Player bets: team switch, swipeable player cards, then every player market with its chance and fair odds. */
@Composable
fun PlayersTab(item: MatchItem, state: DetailUiState, live: MatchDetailViewModel.LiveUi, vm: MatchDetailViewModel) {
    val la = live.analysis
    val logs by vm.playerLogs.collectAsState()
    val loadingLogs by vm.logsLoading.collectAsState()
    androidx.compose.runtime.LaunchedEffect(state.extras.lineups) {
        vm.ensurePlayerLogs(state.extras.lineups.flatMap { it.starters }.mapNotNull { it.id })
    }
    val (players, remaining, sofar, note) = when (item.status) {
        MatchStatus.LIVE -> Quad(la?.playerWatch.orEmpty(), la?.remainingGoals ?: 0.0, la?.goalsSoFar ?: 0,
            "Live: players on the pitch now, rest of the match. Updates with the live stats every 30 s.")
        MatchStatus.UPCOMING -> {
            val m = state.markets
            Quad(
                if (m == null) emptyList() else PlayerModel.prematch(state.extras.lineups, item.homeTeam, item.awayTeam, m.expectedHome, m.expectedAway, logs),
                (m?.expectedHome ?: 0.0) + (m?.expectedAway ?: 0.0), 0,
                "Before kick-off: confirmed starting line-ups and the match's expected goals (${m?.let { String.format("%.1f – %.1f", it.expectedHome, it.expectedAway) } ?: "N/A"}).",
            )
        }
        else -> Quad(emptyList(), 0.0, 0, "")
    }
    if (item.status == MatchStatus.FINISHED) { EmptyState("Match finished", "Player bets are shown before and during the match."); return }
    if (players.isEmpty()) {
        EmptyState(
            if (item.status == MatchStatus.LIVE) "Data unavailable" else "Line-ups not announced yet",
            if (item.status == MatchStatus.LIVE) "The live feed hasn't published the players for this match."
            else "Player bets appear when the starting line-ups are confirmed (usually about an hour before kick-off).",
        )
        return
    }
    var home by rememberSaveable(item.id) { mutableStateOf(true) }
    val team = players.filter { it.home == home }.sortedByDescending { it.lGoal }
    var sel by rememberSaveable(item.id, home) { mutableStateOf(team.firstOrNull()?.name ?: "") }
    val p = team.firstOrNull { it.name == sel } ?: team.firstOrNull()
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to item.homeTeam, false to item.awayTeam).forEach { (h, n) ->
                FilterChip(selected = home == h, onClick = { home = h }, label = { Text(n, maxLines = 1) }, modifier = Modifier.weight(1f))
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(team) { pl ->
                val on = pl.name == p?.name
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .border(if (on) 2.dp else 0.dp, if (on) Accent.Purple else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        .clickable { sel = pl.name }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TeamBadge(pl.name, pl.photo, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(PlayerModel.roleName(pl.role) + (pl.number?.let { " · #$it" } ?: ""), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        if (p == null) return@Column
        if (item.status == MatchStatus.LIVE) {
            Text("${p.minutesOn}' played · ${p.goals} goals · ${p.shots} shots (${p.onTarget} on target) · ${p.foulsSoFar} fouls · fouled ${p.fouledSoFar}" +
                if (p.booked) " · booked" else "",
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        // His last 5 games
        InfoCard {
            Text("Last 5 games", style = MaterialTheme.typography.titleMedium)
            val log = p.athleteId?.let { logs[it] }
            when {
                log != null -> {
                    Text(String.format("Average: %.1f fouls committed, %.1f fouled per game", log.avgFouls, log.avgFouled), style = MaterialTheme.typography.labelSmall, color = Accent.Orange)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("Game", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        listOf("Fouls", "Fouled", "Card", "Goals").forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(46.dp), textAlign = TextAlign.End) }
                    }
                    log.games.forEach { g ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${com.matchday.app.ui.Formats.day(g.date, com.matchday.app.ui.LocalZone.current)} ${g.atVs} ${g.opponent} · ${g.result} ${g.score}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 2)
                            listOf(g.fouls.toString(), g.fouled.toString(), if (g.red > 0) "Red" else if (g.yellow > 0) "Yellow" else "–", g.goals.toString()).forEach {
                                Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(46.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                    Text("ESPN game log (his league games this season). Used for his foul, fouled and card chances below.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                loadingLogs -> Text("Loading…", style = MaterialTheme.typography.labelSmall)
                else -> Text("Data unavailable for this player.", style = MaterialTheme.typography.labelSmall)
            }
        }
        PlayerModel.markets(p, remaining, sofar).groupBy { it.group }.forEach { (g, ms) ->
            InfoCard {
                Text(g, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                ms.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2)
                        Text("${Math.round(m.probability * 100)}%", style = MaterialTheme.typography.titleSmall,
                            color = if (m.probability >= 0.6) Accent.Green else if (m.probability >= 0.3) Accent.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
                        Text(if (m.probability >= 0.005) String.format("%.2f", 1 / m.probability) else "–", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        Text("$note Percent = estimated chance; the number on the right is the fair price (1 ÷ chance), not a bookmaker price - " +
            "a bookmaker price above it is better value. Model estimates from position, this match and the team's expected goals; " +
            "the free sources have no season player stats or player odds.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

private data class Quad(val a: List<PlayerWatch>, val b: Double, val c: Int, val d: String)
