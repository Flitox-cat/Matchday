package com.matchday.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matchday.app.data.repo.DetailExtras
import com.matchday.app.data.repo.LeagueTable
import com.matchday.app.data.repo.TeamProfile
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.RecentResult
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.FormStrip
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.TeamBadge
import com.matchday.app.ui.theme.Accent

private const val NA = "Data unavailable"
private val HomeColor = Accent.Blue
private val AwayColor = Accent.Purple

@Composable
private fun SourceLine(updatedAt: Long?) {
    val zone = LocalZone.current
    Text(
        "Source: ESPN" + (updatedAt?.let { " · updated ${Formats.dayTime(it, zone)}" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun LoadingOr(loading: Boolean, empty: Boolean, title: String, body: String, content: @Composable () -> Unit) {
    when {
        !empty -> content()
        loading -> EmptyState("Loading…", "Fetching data from ESPN.")
        else -> EmptyState(title, body)
    }
}

// ---------------------------------------------------------------------------
// Team comparison (side by side)
// ---------------------------------------------------------------------------

@Composable
fun TeamComparisonTab(item: MatchItem, x: DetailExtras) {
    val h = x.homeProfile
    val a = x.awayProfile
    LoadingOr(x.loading, h == null && a == null, NA, "Team information couldn't be retrieved for this match (offline, or the source has no record of these teams).") {
        InfoCard {
            Row(verticalAlignment = Alignment.Top) {
                TeamHeader(h, item.homeTeam, item.homeCrest, HomeColor, Modifier.weight(1f))
                Text("vs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 18.dp, start = 8.dp, end = 8.dp))
                TeamHeader(a, item.awayTeam, item.awayCrest, AwayColor, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            val hr = h?.row
            val ar = a?.row
            CompareRow("League position", hr?.rank, ar?.rank, lowerIsBetter = true, format = { "#$it" })
            CompareRow("Points", hr?.points, ar?.points)
            CompareRow("Matches played", hr?.played, ar?.played, neutral = true)
            CompareRow("Wins", hr?.won, ar?.won)
            CompareRow("Draws", hr?.drawn, ar?.drawn, neutral = true)
            CompareRow("Losses", hr?.lost, ar?.lost, lowerIsBetter = true)
            CompareRow("Goals scored", hr?.goalsFor, ar?.goalsFor)
            CompareRow("Goals conceded", hr?.goalsAgainst, ar?.goalsAgainst, lowerIsBetter = true)
            CompareRow("Goal difference", hr?.let { it.goalsFor - it.goalsAgainst }, ar?.let { it.goalsFor - it.goalsAgainst },
                format = { if (it > 0) "+$it" else "$it" })
            Spacer(Modifier.height(10.dp))
            Text("Recent league form (newest first)", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row {
                Column(Modifier.weight(1f)) { FormOrNa(h?.leagueForm) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { FormOrNa(a?.leagueForm) }
            }
            val homeLeague = h?.leagueKey
            val awayLeague = a?.leagueKey
            if (homeLeague != null && awayLeague != null && homeLeague != awayLeague) {
                Text(
                    "The teams play in different domestic leagues, so positions and points aren't directly comparable.",
                    style = MaterialTheme.typography.labelSmall, color = Accent.Orange, modifier = Modifier.padding(top = 8.dp),
                )
            }
            SourceLine(listOfNotNull(h?.updatedAt, a?.updatedAt).minOrNull())
        }
    }
}

@Composable
private fun FormOrNa(list: List<RecentResult>?) {
    if (list.isNullOrEmpty()) Text(NA, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else FormStrip(list, 5)
}

@Composable
private fun TeamHeader(p: TeamProfile?, fallbackName: String, fallbackLogo: String?, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamBadge(p?.name ?: fallbackName, p?.logo ?: fallbackLogo, 52.dp)
        Spacer(Modifier.height(6.dp))
        Text(p?.name ?: fallbackName, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, color = color)
        Text(p?.country ?: "Country: $NA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Text(p?.leagueName ?: "League: $NA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun CompareRow(
    label: String,
    home: Int?,
    away: Int?,
    lowerIsBetter: Boolean = false,
    neutral: Boolean = false,
    format: (Int) -> String = { it.toString() },
) {
    val homeBetter = !neutral && home != null && away != null && (if (lowerIsBetter) home < away else home > away)
    val awayBetter = !neutral && home != null && away != null && (if (lowerIsBetter) away < home else away > home)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ValuePill(home?.let(format) ?: "—", homeBetter, HomeColor, Modifier.weight(1f), Alignment.Start)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f))
        ValuePill(away?.let(format) ?: "—", awayBetter, AwayColor, Modifier.weight(1f), Alignment.End)
    }
}

@Composable
private fun ValuePill(text: String, highlight: Boolean, color: Color, modifier: Modifier, align: Alignment.Horizontal) {
    Column(modifier, horizontalAlignment = align) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) color else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (highlight) color.copy(alpha = 0.15f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// League standings with both teams highlighted
// ---------------------------------------------------------------------------

@Composable
fun StandingsTab(item: MatchItem, x: DetailExtras) {
    val tables = listOfNotNull(x.homeProfile?.table, x.awayProfile?.table).distinctBy { it.leagueKey }
    LoadingOr(x.loading, tables.isEmpty(), NA, "No league table could be retrieved for these teams.") {
        Column {
            val ids = setOfNotNull(x.homeProfile?.row?.teamId, x.awayProfile?.row?.teamId)
            tables.forEach { t ->
                val name = when (t.leagueKey) {
                    x.homeProfile?.leagueKey -> x.homeProfile?.leagueName
                    else -> x.awayProfile?.leagueName
                } ?: item.competition.title
                StandingsCard(name, t, ids, x.homeProfile?.row?.teamId)
            }
        }
    }
}

@Composable
private fun StandingsCard(title: String, t: LeagueTable, highlightIds: Set<String>, homeId: String?) {
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            HeaderCell("#", 28.dp)
            Text("Team", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            HeaderCell("P", 26.dp); HeaderCell("W", 26.dp); HeaderCell("D", 26.dp); HeaderCell("L", 26.dp)
            HeaderCell("GD", 34.dp); HeaderCell("Pts", 34.dp)
        }
        t.rows.sortedBy { it.rank }.forEach { r ->
            val highlighted = r.teamId != null && r.teamId in highlightIds
            val color = if (r.teamId == homeId) HomeColor else AwayColor
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (highlighted) color.copy(alpha = 0.18f) else Color.Transparent)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cell("${r.rank}", 28.dp, highlighted)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    TeamBadge(r.name, r.logo, 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        r.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Cell("${r.played}", 26.dp, highlighted); Cell("${r.won}", 26.dp, highlighted)
                Cell("${r.drawn}", 26.dp, highlighted); Cell("${r.lost}", 26.dp, highlighted)
                val gd = r.goalsFor - r.goalsAgainst
                Cell(if (gd > 0) "+$gd" else "$gd", 34.dp, highlighted)
                Cell("${r.points}", 34.dp, true)
            }
        }
        SourceLine(t.fetchedAt)
    }
}

@Composable
private fun HeaderCell(text: String, w: androidx.compose.ui.unit.Dp) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.width(w))
}

@Composable
private fun Cell(text: String, w: androidx.compose.ui.unit.Dp, bold: Boolean) {
    Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.width(w))
}

// ---------------------------------------------------------------------------
// Recent performance (last 5-10, all competitions)
// ---------------------------------------------------------------------------

@Composable
fun RecentFormTab(item: MatchItem, x: DetailExtras) {
    val h = x.homeProfile?.recent?.takeIf { it.isNotEmpty() } ?: x.homeRecent
    val a = x.awayProfile?.recent?.takeIf { it.isNotEmpty() } ?: x.awayRecent
    LoadingOr(x.loading, h.isEmpty() && a.isEmpty(), NA, "No recent results could be retrieved for these teams.") {
        Column {
            RecentCard(item.homeTeam, x.homeProfile?.logo ?: item.homeCrest, h, HomeColor, x.homeProfile?.updatedAt ?: x.summaryUpdatedAt)
            RecentCard(item.awayTeam, x.awayProfile?.logo ?: item.awayCrest, a, AwayColor, x.awayProfile?.updatedAt ?: x.summaryUpdatedAt)
        }
    }
}

@Composable
private fun RecentCard(team: String, logo: String?, results: List<RecentResult>, color: Color, updatedAt: Long?) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(team, logo, 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(team, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.weight(1f))
        }
        if (results.isEmpty()) {
            Text(NA, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            return@InfoCard
        }
        val n = results.size
        val wins = results.count { it.points == 3 }
        val draws = results.count { it.points == 1 }
        val gf = results.sumOf { it.goalsFor }.toDouble() / n
        val ga = results.sumOf { it.goalsAgainst }.toDouble() / n
        val clean = results.count { it.goalsAgainst == 0 }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("Win %", "${Math.round(wins * 100.0 / n)}%", Accent.Green, Modifier.weight(1f))
            Metric("Scored/gm", String.format("%.1f", gf), Accent.Blue, Modifier.weight(1f))
            Metric("Conceded/gm", String.format("%.1f", ga), Accent.Red, Modifier.weight(1f))
            Metric("Clean sheets", "$clean", Accent.Purple, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Attack: ${rate(gf, attack = true)} · Defence: ${rate(ga, attack = false)} · Record W$wins D$draws L${n - wins - draws} (last $n)",
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(8.dp))
        FormStrip(results, 10)
        Spacer(Modifier.height(6.dp))
        results.forEach { ResultLine(it) }
        SourceLine(updatedAt)
    }
}

private fun rate(perGame: Double, attack: Boolean): String = if (attack) when {
    perGame >= 2.0 -> "strong"
    perGame >= 1.2 -> "average"
    else -> "weak"
} else when {
    perGame <= 0.8 -> "strong"
    perGame <= 1.4 -> "average"
    else -> "weak"
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, fontSize = 10.sp)
    }
}

@Composable
private fun ResultLine(r: RecentResult) {
    val zone = LocalZone.current
    val c = when (r.letter) { 'W' -> Accent.Green; 'D' -> Accent.Orange; else -> Accent.Red }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(c), contentAlignment = Alignment.Center) {
            Text(r.letter.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${if (r.home) "vs" else "at"} ${r.opponent}",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(if (r.date > 0) Formats.day(r.date, zone) else null, if (r.home) "Home" else "Away", r.competition).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${r.goalsFor}-${r.goalsAgainst}", style = MaterialTheme.typography.titleSmall, color = c, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Head-to-head
// ---------------------------------------------------------------------------

@Composable
fun HeadToHeadTab(item: MatchItem, x: DetailExtras) {
    val zone = LocalZone.current
    val list = x.headToHead.take(10)
    if (list.isEmpty()) {
        if (x.loading) EmptyState("Loading…", "Fetching previous meetings.")
        else EmptyState("No historical meetings found.", "The source has no previous meetings between these teams.")
        return
    }
    val a = item.homeTeam
    val b = item.awayTeam
    val aWins = list.count { it.points == 3 }
    val draws = list.count { it.points == 1 }
    val bWins = list.size - aWins - draws
    val aGoals = list.sumOf { it.goalsFor }
    val bGoals = list.sumOf { it.goalsAgainst }
    val avg = (aGoals + bGoals).toDouble() / list.size
    val latest = list.first()
    val advantage = when {
        aWins > bWins -> "$a lead the head-to-head ($aWins–$bWins)"
        bWins > aWins -> "$b lead the head-to-head ($bWins–$aWins)"
        else -> "Level head-to-head ($aWins–$bWins)"
    }
    Column {
        InfoCard {
            Text("Head-to-Head (last ${list.size})", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TeamBadge(a, item.homeCrest, 36.dp)
                Spacer(Modifier.weight(1f))
                Text("$aWins", style = MaterialTheme.typography.headlineSmall, color = HomeColor)
                Text("  –  $draws  –  ", style = MaterialTheme.typography.titleMedium, color = Accent.Orange)
                Text("$bWins", style = MaterialTheme.typography.headlineSmall, color = AwayColor)
                Spacer(Modifier.weight(1f))
                TeamBadge(b, item.awayCrest, 36.dp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("$a wins", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Draws", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                Text("$b wins", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            // Proportional bar: home wins | draws | away wins
            Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))) {
                if (aWins > 0) Box(Modifier.weight(aWins.toFloat()).height(10.dp).background(HomeColor))
                if (draws > 0) Box(Modifier.weight(draws.toFloat()).height(10.dp).background(Accent.Orange))
                if (bWins > 0) Box(Modifier.weight(bWins.toFloat()).height(10.dp).background(AwayColor))
            }
            Spacer(Modifier.height(10.dp))
            com.matchday.app.ui.components.KeyValue("Goals", "$a $aGoals · $bGoals $b")
            com.matchday.app.ui.components.KeyValue("Average goals per match", String.format("%.1f", avg))
            com.matchday.app.ui.components.KeyValue(
                "Most recent meeting",
                "${if (latest.date > 0) Formats.day(latest.date, zone) else ""} · " +
                    (if (latest.home) "$a ${latest.goalsFor}-${latest.goalsAgainst} ${latest.opponent}"
                    else "${latest.opponent} ${latest.goalsAgainst}-${latest.goalsFor} $a"),
            )
            Text(advantage, style = MaterialTheme.typography.titleSmall, color = Accent.Green, modifier = Modifier.padding(top = 6.dp))
            if (list.size < 5) {
                Text("Only ${list.size} previous meeting(s) on record - all are shown.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        InfoCard {
            Text("Previous meetings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            list.forEach { r ->
                val homeName = if (r.home) a else r.opponent
                val awayName = if (r.home) r.opponent else a
                val homeGoals = if (r.home) r.goalsFor else r.goalsAgainst
                val awayGoals = if (r.home) r.goalsAgainst else r.goalsFor
                val winnerColor = when (r.points) { 3 -> HomeColor; 1 -> Accent.Orange; else -> AwayColor }
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(homeName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                        Text(
                            " $homeGoals - $awayGoals ",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp)).background(winnerColor).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Text(awayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text(
                        listOfNotNull(
                            if (r.date > 0) Formats.day(r.date, zone) else null,
                            r.competition,
                            "Stadium: ${r.venue ?: NA}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text("Colour shows the winner: blue = $a, purple = $b, orange = draw.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            SourceLine(x.summaryUpdatedAt)
        }
        if (x.notes.isNotEmpty() && x.headToHead.isEmpty()) x.notes.forEach { Banner(it) }
    }
}
