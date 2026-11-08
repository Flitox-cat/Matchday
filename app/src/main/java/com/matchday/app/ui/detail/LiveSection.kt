package com.matchday.app.ui.detail

import androidx.compose.foundation.background
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
fun LiveTab(item: MatchItem, live: MatchDetailViewModel.LiveUi, state: DetailUiState) {
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
        Banner(a.oddsNote, color = if (a.markets.any { it.odds != null }) Accent.Green else Accent.Orange)
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
