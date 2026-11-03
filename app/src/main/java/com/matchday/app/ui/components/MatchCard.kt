package com.matchday.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.theme.Accent

@Composable
fun MatchCard(item: MatchItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = item.prediction
    val accent = sportColor(item.competition.sport)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        // Thin sport-coloured accent strip
        Row(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0f)))),
        ) {}
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
            // Header: competition + time/status
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompetitionBadge(item.competition)
                Spacer(Modifier.width(6.dp))
                Text(
                    item.competition.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.favourite) {
                    Icon(Icons.Filled.Star, contentDescription = "Favourite", tint = Accent.Orange, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                StatusLabel(item)
            }
            Spacer(Modifier.height(10.dp))

            // Teams + score
            TeamRow(item.homeTeam, item.homeCrest, item.homeScore.takeIf { item.status != MatchStatus.UPCOMING },
                bold = p?.predictedWinner == item.homeTeam)
            Spacer(Modifier.height(6.dp))
            TeamRow(item.awayTeam, item.awayCrest, item.awayScore.takeIf { item.status != MatchStatus.UPCOMING },
                bold = p?.predictedWinner == item.awayTeam)

            item.venue?.let {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(3.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Odds (real bookmaker prices: best available)
            val o = item.odds
            if (item.status != MatchStatus.FINISHED) {
                Spacer(Modifier.height(10.dp))
                val fav = p?.mostLikely?.label
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OddsBox("1", o?.home?.best?.price, Modifier.weight(1f), highlight = fav == item.homeTeam)
                    if (item.competition.hasDraw) OddsBox("X", o?.draw?.best?.price, Modifier.weight(1f), highlight = fav == "Draw")
                    OddsBox("2", o?.away?.best?.price, Modifier.weight(1f), highlight = fav == item.awayTeam)
                }
            }

            // Prediction strip (our estimate, visually distinct from bookmaker odds)
            if (p != null && item.status == MatchStatus.UPCOMING) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent.Purple.copy(alpha = 0.10f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Accent.Purple, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        val headline = buildString {
                            append(p.mostLikely.label)
                            append(" ")
                            append(Formats.pct(p.mostLikely.probability))
                            p.predictedScore?.let { append(" · $it") }
                        }
                        Text(headline, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = p.bestValue?.let { "Value: ${it.label} @ ${Formats.odds(it.odds)}" } ?: "Estimate · no value pick"
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    ConfidenceChip(p.confidence)
                }
                Spacer(Modifier.height(6.dp))
                ProbabilityBar(p.final.home, p.final.draw, p.final.away, height = 5.dp)
            }
        }
    }
}

@Composable
private fun StatusLabel(item: MatchItem) {
    val zone = LocalZone.current
    when (item.status) {
        MatchStatus.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot()
            Spacer(Modifier.width(4.dp))
            Text(
                item.statusDetail?.let { "LIVE · $it" } ?: "LIVE",
                style = MaterialTheme.typography.labelMedium,
                color = Accent.Live, fontWeight = FontWeight.Bold,
            )
        }
        MatchStatus.FINISHED -> Text(item.statusDetail ?: "FT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MatchStatus.UPCOMING -> Text(
            Formats.kickoffLabel(item.kickoff, zone),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TeamRow(name: String, crest: String?, score: String?, bold: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(name, crest, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (score != null) Text(score, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
