package com.matchday.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matchday.app.domain.MarketAnalysis
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.theme.Accent

/** Live tab: live stats, Live Match Intelligence, projections and ranked live markets. */
@Composable
fun LiveTab(item: MatchItem, live: MatchDetailViewModel.LiveUi, state: DetailUiState, vm: MatchDetailViewModel? = null) {
    val zone = LocalZone.current
    if (item.status != MatchStatus.LIVE) {
        EmptyState(
            if (item.status == MatchStatus.UPCOMING) "Not started yet" else "Match finished",
            if (item.status == MatchStatus.UPCOMING) "Live statistics, intelligence and live markets appear here once the match starts."
            else "Live analysis is only shown while a match is in play.",
        )
        return
    }
    val a = live.analysis
    Column {
        // Header: score, clock, freshness
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(Accent.Live))
                Spacer(Modifier.width(8.dp))
                Text("LIVE · ${item.statusDetail ?: a?.clockLabel ?: ""}", style = MaterialTheme.typography.titleSmall, color = Accent.Live, modifier = Modifier.weight(1f))
                if (live.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent.Purple)
            }
            Text("${item.homeTeam}  ${item.homeScore ?: "-"} – ${item.awayScore ?: "-"}  ${item.awayTeam}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Stats refresh every 30 s · last update ${live.updatedAt?.let { Formats.dayTime(it, zone) + " (" + Formats.ago(it) + ")" } ?: "N/A"}" +
                    (a?.let { " · predictions calculated at score ${it.score}" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (live.failed) Text("Live statistics temporarily unavailable (feed delay or connection). Showing the last successful update.", style = MaterialTheme.typography.labelSmall, color = Accent.Orange)
        }
        if (a == null) {
            if (!live.loading) EmptyState("Data unavailable", "No live data for this match yet.")
            return@Column
        }
        if (live.changes.isNotEmpty()) {
            InfoCard {
                Text("What changed", style = MaterialTheme.typography.titleSmall, color = Accent.Orange)
                live.changes.take(4).forEach { (t, msg) -> Text("${Formats.dayTime(t, zone)} · $msg", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
            }
        }
        // Live Match Intelligence
        InfoCard {
            Text("Live Match Intelligence", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            a.intelligence.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
        }
        // Projections (estimates) kept apart from measured stats
        if (a.projections.isNotEmpty()) {
            InfoCard {
                Text("Projections (model estimates)", style = MaterialTheme.typography.titleSmall, color = Accent.Purple)
                a.projections.forEach { p -> StatLine(p.label, p.value, p.note) }
            }
        }
        InfoCard {
            Text("Live statistics (measured)", style = MaterialTheme.typography.titleSmall, color = Accent.Blue)
            if (a.actual.isEmpty()) Text("Data unavailable for this match.", style = MaterialTheme.typography.bodySmall)
            a.actual.forEach { p -> StatLine(p.label, p.value, p.note) }
            live.stats?.events?.takeIf { it.isNotEmpty() }?.let { evs ->
                Spacer(Modifier.height(6.dp))
                Text("Key events", style = MaterialTheme.typography.labelMedium, color = Accent.Orange)
                evs.takeLast(10).asReversed().forEach { e ->
                    Text("${e.clock ?: ""}  ${e.type}${e.team?.let { " · $it" } ?: ""}${e.text?.let { " — $it" } ?: ""}",
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text("Source: ESPN live feed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (a.playerWatch.isNotEmpty()) PlayerWatchCard(a.playerWatch)
        if (vm != null && a.playerWatch.isNotEmpty()) {
            val logs by vm.playerLogs.collectAsState()
            val loadingLogs by vm.logsLoading.collectAsState()
            PlayerFoulHistoryCard(a.playerWatch, logs, loadingLogs)
        }
        Banner(a.oddsNote, color = if (a.markets.any { it.odds != null }) Accent.Green else Accent.Orange)
        if (a.markets.firstOrNull()?.family == com.matchday.app.domain.MarketFamily.FIRST_HALF)
            Banner("First half in play: first-half bets (half-time result, 1st-half goals, BTTS...) are listed first, then the full-match bets.", color = Accent.Blue)
        // Ranked live markets: reuse the ranking card
        TopSelectionsCard(
            item,
            MarketAnalysis(a.markets, a.top, a.noBet, emptyList(), null, null, null, null, 0.0, 0.0, emptyList(), null),
            emptyList(), compact = false,
        )
        InfoCard {
            Text("Not available live", style = MaterialTheme.typography.titleSmall)
            a.unavailable.forEach { Text("•  $it", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, note: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1.3f))
        }
        note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Player watch: who is most likely to commit a foul, be fouled or score before the end. */
@Composable
private fun PlayerWatchCard(list: List<com.matchday.app.domain.PlayerWatch>) {
    var all by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    fun pc(x: Double) = "${Math.round(x * 100)}%"
    InfoCard {
        Text("Player watch (rest of the match)", style = MaterialTheme.typography.titleMedium)
        Text("Players on the pitch now. Each starts from the usual rate for his position and moves toward what he has done in this match; " +
            "fouls also follow how often his team is fouling today, goals follow his team's projected goals and his shots. Estimates, not bookmaker odds.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        @Composable
        fun section(title: String, color: androidx.compose.ui.graphics.Color, sel: (com.matchday.app.domain.PlayerWatch) -> Double, sofar: (com.matchday.app.domain.PlayerWatch) -> String) {
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            list.sortedByDescending(sel).take(5).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.name} (${p.role})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text("${p.team} · ${sofar(p)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Text(pc(sel(p)), style = MaterialTheme.typography.titleSmall, color = color)
                }
            }
        }
        section("Most likely to commit a foul", Accent.Orange, { it.pFoul }) { "${it.foulsSoFar} fouls so far${if (it.booked) " · booked" else ""} · ${it.minutesOn}' played" }
        section("Most likely to be fouled", Accent.Blue, { it.pFouled }) { "fouled ${it.fouledSoFar} times so far · ${it.minutesOn}' played" }
        section("Most likely to score", Accent.Green, { it.pScore }) { "${it.goals} goals · ${it.shots} shots, ${it.onTarget} on target" }
        Text(if (all) "Hide all players" else "Show all ${list.size} players", style = MaterialTheme.typography.labelMedium, color = Accent.Purple,
            modifier = Modifier.padding(top = 8.dp).clickable { all = !all })
        if (all) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text("Player", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                listOf("Foul", "Fouled", "Score").forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp), textAlign = TextAlign.End) }
            }
            list.sortedWith(compareBy({ !it.home }, { it.name })).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${p.name} · ${p.role}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 2)
                    listOf(p.pFoul, p.pFouled, p.pScore).forEach { Text(pc(it), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp), textAlign = TextAlign.End) }
                }
            }
        }
    }
}

/** Every player on the pitch: fouls committed in each of his last 5 games (tap a player for the details). */
@Composable
fun PlayerFoulHistoryCard(players: List<com.matchday.app.domain.PlayerWatch>, logs: Map<String, com.matchday.app.domain.PlayerLog>, loading: Boolean) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    InfoCard {
        Text("Players: fouls in their last 5 games", style = MaterialTheme.typography.titleMedium)
        Text("Fouls committed per game, most recent first, from the ESPN game log (all games he played this season in his league). " +
            "Tap a player for opponents, fouls suffered and cards. These records also feed the foul and card chances above.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading && logs.isEmpty()) Text("Loading players' last games…", style = MaterialTheme.typography.labelSmall, color = Accent.Purple)
        for (home in listOf(true, false)) {
            val team = players.filter { it.home == home }
            if (team.isEmpty()) continue
            Spacer(Modifier.height(8.dp))
            Text(team.first().team, style = MaterialTheme.typography.titleSmall, color = Accent.Blue)
            team.sortedByDescending { p -> p.athleteId?.let { logs[it]?.avgFouls } ?: -1.0 }.forEach { p ->
                val log = p.athleteId?.let { logs[it] }
                Row(Modifier.fillMaxWidth().clickable { open = if (open == p.name) "" else p.name }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.name} (${p.role})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, maxLines = 2)
                        Text(
                            when {
                                log != null -> "Last ${log.games.size}: " + log.games.joinToString("  ") { it.fouls.toString() } + "  ·  today ${p.foulsSoFar}"
                                loading -> "Loading…"
                                else -> "Data unavailable"
                            },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    log?.let { Text(String.format("%.1f / game", it.avgFouls), style = MaterialTheme.typography.labelMedium, color = Accent.Orange) }
                }
                if (open == p.name && log != null) {
                    log.games.forEach { g ->
                        Text("${Formats.day(g.date, com.matchday.app.ui.LocalZone.current)}  ${g.atVs} ${g.opponent}  ${g.result} ${g.score}  ·  fouls ${g.fouls}, fouled ${g.fouled}" +
                            (if (g.yellow > 0) ", yellow" else "") + (if (g.red > 0) ", red" else "") + (if (g.goals > 0) ", ${g.goals} goal${if (g.goals > 1) "s" else ""}" else ""),
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
                    }
                }
            }
        }
    }
}
