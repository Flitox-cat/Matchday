package com.matchday.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.matchday.app.data.repo.DetailExtras
import com.matchday.app.domain.BttsAnalysis
import com.matchday.app.domain.MatchItem
import com.matchday.app.ui.Formats
import com.matchday.app.ui.components.ConfidenceChip
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.KeyValue
import com.matchday.app.ui.theme.Accent

@Composable
fun BttsTab(item: MatchItem, x: DetailExtras) {
    val b: BttsAnalysis? = x.btts ?: item.btts
    if (b == null) {
        if (x.loading) EmptyState("Loading…", "Analysing both teams' scoring and conceding records.")
        else EmptyState("Data unavailable", "Not enough data to estimate whether both teams will score (no odds-based goals model and no recent results).")
        return
    }
    Column {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Both Teams to Score", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ConfidenceChip(b.confidence)
            }
            Spacer(Modifier.height(12.dp))
            BttsBar("YES", b.yes, b.oddsYes, Accent.Green, highlight = b.recommendation == "Yes")
            Spacer(Modifier.height(8.dp))
            BttsBar("NO", b.no, b.oddsNo, Accent.Red, highlight = b.recommendation == "No")
            Spacer(Modifier.height(12.dp))
            Text(
                "Recommended: BTTS ${b.recommendation}",
                style = MaterialTheme.typography.titleLarge,
                color = if (b.recommendation == "Yes") Accent.Green else Accent.Red,
            )
            Text(
                "Odds: not available - the free data source publishes no BTTS prices, so this is a probability estimate, not a value bet.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!b.full) {
                Text(
                    "Showing the quick estimate while the full analysis loads.",
                    style = MaterialTheme.typography.labelSmall, color = Accent.Orange, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        InfoCard {
            Text("Why", style = MaterialTheme.typography.titleSmall)
            b.reasons.forEach { r ->
                Row(Modifier.padding(top = 4.dp)) {
                    Text("•  ", color = Accent.Purple)
                    Text(r, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (b.inputs.isNotEmpty()) {
            InfoCard {
                Text("Factors used", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                b.inputs.forEach { (k, v) ->
                    KeyValue(k, v, if (v == "Data unavailable") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "Method: goals model 45%, scoring/conceding rates 25%, recent BTTS rate 20%, head-to-head 10% (when 3+ meetings). " +
                        "Missing factors are left out and small samples are pulled towards 50%. Source: ESPN.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun BttsBar(label: String, p: Double, odds: Double?, color: Color, highlight: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
            Text(
                "${Formats.pct(p)} probability",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Text("Odds: ${odds?.let { Formats.odds(it) } ?: "N/A"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(50)).background(color))
        }
    }
}
