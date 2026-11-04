package com.matchday.app.ui.team

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matchday.app.data.repo.SearchHit
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.TeamRecord
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.TeamGame
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.games
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.FormStrip
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.KeyValue
import com.matchday.app.ui.components.TeamBadge
import com.matchday.app.ui.theme.Accent

fun TeamGame.asRecent() = RecentResult(m.date, opponent, home, gf, ga, m.competition, m.venue)

fun flagOf(country: String?): String {
    val code = when (country?.lowercase()) {
        "england", "wales", "scotland", "northern ireland", "united kingdom" -> return "🏴"
        "spain" -> "ES"; "germany" -> "DE"; "italy" -> "IT"; "france" -> "FR"; "netherlands" -> "NL"
        "portugal" -> "PT"; "belgium" -> "BE"; "turkey", "türkiye" -> "TR"; "austria" -> "AT"; "switzerland" -> "CH"
        "greece" -> "GR"; "denmark" -> "DK"; "norway" -> "NO"; "sweden" -> "SE"; "czech republic", "czechia" -> "CZ"
        "usa", "united states" -> "US"; "saudi arabia" -> "SA"; "croatia" -> "HR"; "serbia" -> "RS"; "ukraine" -> "UA"
        "poland" -> "PL"; "cyprus" -> "CY"; "qatar" -> "QA"; "russia" -> "RU"; "scotland " -> "GB"
        else -> return "🌍"
    }
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(vm: TeamViewModel, onBack: () -> Unit, onMatch: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val page = state.main.page
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(page?.name ?: "Team", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                if (page != null) {
                    IconButton(onClick = { vm.toggleFavourite() }) {
                        Icon(
                            if (state.isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Follow team",
                            tint = if (state.isFavourite) Accent.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { vm.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload team") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        if (state.main.loading || state.main.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent.Purple)
        if (page == null) {
            if (!state.main.loading) EmptyState("Data unavailable", "This team couldn't be loaded (offline or source error). Tap reload to retry.")
        } else {
            TeamBody(state, vm, page, onMatch)
        }
    }
}

@Composable
private fun TeamBody(state: TeamUiState, vm: TeamViewModel, page: com.matchday.app.data.repo.TeamPage, onMatch: (String) -> Unit) {
        val zone = LocalZone.current
        val games = state.main.history?.games().orEmpty()
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp))
                        .background(Brush.linearGradient(listOf(Accent.Blue.copy(alpha = 0.25f), Accent.Purple.copy(alpha = 0.18f), MaterialTheme.colorScheme.surface))),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TeamBadge(page.name, page.logo, 64.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(page.name, style = MaterialTheme.typography.titleLarge)
                            Text("${flagOf(page.country)} ${page.country ?: "Country: Data unavailable"}", style = MaterialTheme.typography.bodyMedium)
                            Text(page.leagueName ?: "League: Data unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            page.stadium?.let { Text("🏟 $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item {
                InfoCard {
                    Text("League position", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    val r = page.row
                    if (r != null) {
                        KeyValue("Position", "#${r.rank}", Accent.Green)
                        KeyValue("Points", "${r.points} from ${r.played} games")
                        KeyValue("W-D-L", "${r.won}-${r.drawn}-${r.lost}")
                        KeyValue("Goals", "${r.goalsFor}-${r.goalsAgainst}")
                    } else {
                        Text(page.standingSummary ?: "Data unavailable", style = MaterialTheme.typography.bodyMedium)
                    }
                    page.updatedAt?.let { Text("Source: ESPN · updated ${Formats.ago(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            // Upcoming with odds and AI predictions (from the app's own match list when available)
            item {
                InfoCard {
                    Text("Upcoming matches", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    val known = state.matches.filter { it.status != MatchStatus.FINISHED }
                    if (page.upcoming.isEmpty() && known.isEmpty()) Text("Data unavailable", style = MaterialTheme.typography.bodyMedium)
                    page.upcoming.forEach { f ->
                        val m = known.firstOrNull { (TeamMatcher.same(it.homeTeam, f.opponent) || TeamMatcher.same(it.awayTeam, f.opponent)) && kotlin.math.abs(it.kickoff - f.date) < 6 * 3_600_000L }
                        UpcomingRow(Formats.kickoffLabel(f.date, zone), f.opponent, f.opponentLogo, f.home, f.competition, m, onMatch)
                    }
                    known.filter { k -> page.upcoming.none { kotlin.math.abs(it.date - k.kickoff) < 6 * 3_600_000L } }.forEach { m ->
                        val home = TeamMatcher.same(m.homeTeam, page.name)
                        UpcomingRow(Formats.kickoffLabel(m.kickoff, zone), if (home) m.awayTeam else m.homeTeam, if (home) m.awayCrest else m.homeCrest, home, m.competition.title, m, onMatch)
                    }
                }
            }
            item {
                InfoCard {
                    Text("Recent results", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    if (games.isEmpty()) {
                        Text(if (state.main.historyLoading) "Loading…" else "Data unavailable", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        FormStrip(games.take(6).map { it.asRecent() })
                        Spacer(Modifier.height(6.dp))
                        games.take(8).forEach { g ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(Formats.day(g.m.date, zone), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(78.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${if (g.home) "vs" else "at"} ${g.opponent}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${g.gf}-${g.ga}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                                    color = if (g.win) Accent.Green else if (g.draw) Accent.Orange else Accent.Red)
                            }
                        }
                    }
                }
            }
            if (games.isNotEmpty()) {
                item { StadiumCard(page.stadium, games, state.main.history?.seasonsFrom, state.main.history?.seasonsTo) }
                item { CountryCard(games) }
                item { CompetitionCard(games) }
                item { OpponentSearch(games) }
            }
            item { CompareSection(state, vm, page.name) }
            item {
                Text(
                    "History: ESPN's free public feed, domestic league and UEFA competitions${state.main.history?.let { " ${it.seasonsFrom}–${it.seasonsTo}" } ?: ""}. Cups and friendlies may be missing.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
}

@Composable
private fun UpcomingRow(time: String, opponent: String, logo: String?, home: Boolean, competition: String?, m: MatchItem?, onMatch: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (m != null) Modifier.clickable { onMatch(m.id) } else Modifier).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(opponent, logo, 24.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${if (home) "vs" else "at"} $opponent", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(time, competition).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (m != null) {
            val p = m.prediction
            val o = m.odds
            Text(
                listOfNotNull(
                    o?.let { "Odds ${Formats.odds(it.home?.best?.price)} / ${Formats.odds(it.draw?.best?.price)} / ${Formats.odds(it.away?.best?.price)}" } ?: "Odds N/A",
                    p?.let { "AI ${Formats.pct(it.final.home)} / ${Formats.pct(it.final.draw)} / ${Formats.pct(it.final.away)}" },
                ).joinToString(" · ") + "  ›",
                style = MaterialTheme.typography.labelSmall, color = Accent.Blue, modifier = Modifier.padding(start = 32.dp, top = 2.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun RecordLine(label: String, r: TeamRecord) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${r.n} · W${r.w} D${r.d} L${r.l} · ${r.gf}-${r.ga}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        val c = when { r.winRate >= 0.55 -> Accent.Green; r.winRate >= 0.35 -> Accent.Orange; else -> Accent.Red }
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
    }
}

@Composable
private fun StadiumCard(stadium: String?, games: List<TeamGame>, from: Int?, to: Int?) {
    InfoCard {
        Text("Stadium history${stadium?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        val home = games.filter { it.home }
        RecordLine("All home games", TeamRecord.of(home))
        RecordLine("Away games", TeamRecord.of(games.filter { !it.home }))
        val bestVisitor = home.groupBy { it.opponent }.filter { it.value.size >= 2 }.maxByOrNull { e -> e.value.count { it.loss } }
        bestVisitor?.takeIf { e -> e.value.count { it.loss } > 0 }?.let { e ->
            Text("Most successful visitor: ${e.key} (won ${e.value.count { it.loss }} of ${e.value.size} here)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (from != null && to != null) Text("Seasons $from–$to · Source: ESPN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CountryCard(games: List<TeamGame>) {
    val byCountry = games.filter { !it.home && it.m.european && it.m.country != null }.groupBy { it.m.country!! }
    InfoCard {
        Text("European away record by country", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (byCountry.isEmpty()) {
            Text("Data unavailable (no European away games with a known country in the data).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        byCountry.entries.sortedByDescending { it.value.size }.forEach { (c, g) -> RecordLine("${flagOf(c)} $c", TeamRecord.of(g)) }
        if (byCountry.values.any { it.size < 6 }) Text("Fewer than 6 games in a country: anecdotal only.", style = MaterialTheme.typography.labelSmall, color = Accent.Orange)
    }
}

@Composable
private fun CompetitionCard(games: List<TeamGame>) {
    InfoCard {
        Text("Domestic vs international", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        games.groupBy { it.m.competition }.entries.sortedByDescending { it.value.size }.forEach { (c, g) -> RecordLine(c, TeamRecord.of(g)) }
        val eu = games.filter { it.m.european }
        if (eu.isNotEmpty()) {
            RecordLine("European home", TeamRecord.of(eu.filter { it.home }))
            RecordLine("European away", TeamRecord.of(eu.filter { !it.home }))
        }
    }
}

@Composable
private fun OpponentSearch(games: List<TeamGame>) {
    var q by rememberSaveable { mutableStateOf("") }
    val zone = LocalZone.current
    InfoCard {
        Text("TeamRecord vs a specific opponent", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Opponent name") }, modifier = Modifier.fillMaxWidth())
        val norm = TeamMatcher.normalize(q)
        if (norm.length >= 3) {
            val g = games.filter { TeamMatcher.normalize(it.opponent).contains(norm) || TeamMatcher.same(it.opponent, q) }
            Spacer(Modifier.height(6.dp))
            if (g.isEmpty()) {
                Text("No meetings in the data analysed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                RecordLine("All meetings", TeamRecord.of(g))
                g.take(10).forEach { m ->
                    Text("${Formats.day(m.m.date, zone)} · ${if (m.home) "H" else "A"} vs ${m.opponent} ${m.gf}-${m.ga} · ${m.m.competition}",
                        style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun CompareSection(state: TeamUiState, vm: TeamViewModel, name: String) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compare with another team", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (state.compare != null) IconButton(onClick = { vm.clearCompare() }) { Icon(Icons.Filled.Close, contentDescription = "Clear comparison") }
        }
        if (state.compare == null) {
            OutlinedTextField(state.compareQuery, { vm.setCompareQuery(it) }, singleLine = true, placeholder = { Text("Search a team") }, modifier = Modifier.fillMaxWidth())
            state.compareHits.forEach { h -> HitRow(h) { vm.pickCompare(h) } }
            return@InfoCard
        }
        val other = state.compare ?: return@InfoCard
        if (other.loading) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent.Purple); return@InfoCard }
        val a = state.main
        val b = other
        val pa = a.page; val pb = b.page
        if (pb == null) { Text("Data unavailable", style = MaterialTheme.typography.bodyMedium); return@InfoCard }
        val ga = a.history?.games().orEmpty(); val gb = b.history?.games().orEmpty()
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { TeamBadge(name, pa?.logo, 36.dp); Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            Text("vs", style = MaterialTheme.typography.labelMedium)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { TeamBadge(pb.name, pb.logo, 36.dp); Text(pb.name, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
        fun rec(g: List<TeamGame>) = TeamRecord.of(g)
        val last10a = rec(ga.take(10)); val last10b = rec(gb.take(10))
        CmpRow("League position", pa?.row?.rank?.let { "#$it" }, pb.row?.rank?.let { "#$it" }, lowerBetter = true, a = pa?.row?.rank?.toDouble(), b = pb.row?.rank?.toDouble())
        CmpRow("League points", pa?.row?.points?.toString(), pb.row?.points?.toString(), a = pa?.row?.points?.toDouble(), b = pb.row?.points?.toDouble())
        CmpRow("Last 10: wins", last10a.w.takeIf { ga.isNotEmpty() }?.toString(), last10b.w.takeIf { gb.isNotEmpty() }?.toString(), a = last10a.w.toDouble(), b = last10b.w.toDouble())
        CmpRow("Last 10: scored / game", fmt(last10a.gfAvg, ga), fmt(last10b.gfAvg, gb), a = last10a.gfAvg, b = last10b.gfAvg)
        CmpRow("Last 10: conceded / game", fmt(last10a.gaAvg, ga), fmt(last10b.gaAvg, gb), lowerBetter = true, a = last10a.gaAvg, b = last10b.gaAvg)
        val ha = rec(ga.filter { it.home }.take(10)); val hb = rec(gb.filter { it.home }.take(10))
        CmpRow("Home win rate (last 10)", pct(ha), pct(hb), a = ha.winRate, b = hb.winRate)
        val aa = rec(ga.filter { !it.home }.take(10)); val ab = rec(gb.filter { !it.home }.take(10))
        CmpRow("Away win rate (last 10)", pct(aa), pct(ab), a = aa.winRate, b = ab.winRate)
        val ea = rec(ga.filter { it.m.european }); val eb = rec(gb.filter { it.m.european })
        CmpRow("European record", if (ea.n > 0) "W${ea.w} D${ea.d} L${ea.l}" else "N/A", if (eb.n > 0) "W${eb.w} D${eb.d} L${eb.l}" else "N/A", a = ea.winRate, b = eb.winRate)
        val meetings = ga.filter { it.opponentId == pb.id }
        if (meetings.isNotEmpty()) {
            val r = rec(meetings)
            Text("Head-to-head in the data: $name W${r.w} D${r.d} L${r.l} (${r.n} games, goals ${r.gf}-${r.ga})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FormStrip(ga.take(5).map { it.asRecent() }); Spacer(Modifier.weight(1f)); FormStrip(gb.take(5).map { it.asRecent() })
        }
    }
}

private fun fmt(d: Double, g: List<TeamGame>) = if (g.isEmpty()) null else String.format("%.1f", d)
private fun pct(r: TeamRecord) = if (r.n == 0) null else "${Math.round(r.winRate * 100)}%"

@Composable
private fun CmpRow(label: String, va: String?, vb: String?, lowerBetter: Boolean = false, a: Double?, b: Double?) {
    val better = if (va == null || vb == null || a == null || b == null || a == b) 0 else if ((a > b) != lowerBetter) -1 else 1
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(va ?: "N/A", style = MaterialTheme.typography.bodySmall, fontWeight = if (better == -1) FontWeight.Bold else FontWeight.Normal,
            color = if (better == -1) Accent.Green else MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(72.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        Text(vb ?: "N/A", style = MaterialTheme.typography.bodySmall, fontWeight = if (better == 1) FontWeight.Bold else FontWeight.Normal,
            color = if (better == 1) Accent.Green else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
    }
}

@Composable
fun HitRow(h: SearchHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (h.kind == SearchHit.Kind.COMPETITION) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Accent.Purple.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Text("🏆")
            }
        } else TeamBadge(h.name, h.logo, 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(h.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            h.subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        Text(if (h.kind == SearchHit.Kind.COMPETITION) "Competition" else "Team", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7D879E))
    }
}
