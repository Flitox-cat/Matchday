package com.matchday.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matchday.app.domain.GoalProfile
import com.matchday.app.domain.Insight
import com.matchday.app.domain.MarketAnalysis
import com.matchday.app.domain.MarketFamily
import com.matchday.app.domain.MarketPick
import com.matchday.app.domain.MatchItem
import com.matchday.app.ui.Formats
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.theme.Accent

private fun pct(x: Double?) = x?.let { "${Math.round(it * 100)}%" } ?: "N/A"
private fun probColor(p: Double) = when { p >= 0.75 -> Accent.Green; p >= 0.6 -> Accent.Orange; else -> Accent.Red }

@Composable
private fun ProbBar(p: Double, color: Color) {
    Box(Modifier.width(60.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Best, second and third selections (only as many as the evidence supports). */
@Composable
fun TopSelectionsCard(item: MatchItem, a: MarketAnalysis, insights: List<Insight>, compact: Boolean) {
    InfoCard {
        Text("Best selections (all markets)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ranked by estimated probability of success across every market, three different kinds of bet at most. Near-certainties (95%+) are skipped.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (a.top.isEmpty()) {
            Text("NO RECOMMENDATION: ${a.noPickReason ?: "no market is strong enough."}", style = MaterialTheme.typography.bodyMedium, color = Accent.Red)
            return@InfoCard
        }
        val names = listOf("Best selection", "Second-best", "Third-best")
        a.top.forEachIndexed { i, m ->
            SelectionBlock(names[i], m, insights, expandedDefault = !compact && i == 0, highlight = i == 0)
            if (i < a.top.lastIndex) Spacer(Modifier.height(8.dp))
        }
        if (a.top.size < 3) {
            Text(
                "Only ${a.top.size} selection${if (a.top.size == 1) "" else "s"} reach ${pct(com.matchday.app.domain.MarketEngine.MIN_TOP_PROB)} at a fair price, so no more are suggested.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
        val second = a.markets.filter { it.probability < com.matchday.app.domain.MarketEngine.MAX_PROB }.firstOrNull { it.key != a.top.first().key }
        if (second != null) {
            Text(
                "Why \"${a.top.first().label}\": it's estimated at ${pct(a.top.first().probability)}, ahead of ${second.label} (${pct(second.probability)}) and every result market " +
                    "(${item.homeTeam} ${pct(a.markets.firstOrNull { it.market == "1X2" && it.sel == "HOME" }?.probability)}, " +
                    "${item.awayTeam} ${pct(a.markets.firstOrNull { it.market == "1X2" && it.sel == "AWAY" }?.probability)}).",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SelectionBlock(title: String, m: MarketPick, insights: List<Insight>, expandedDefault: Boolean, highlight: Boolean) {
    var open by rememberSaveable(m.key) { mutableStateOf(expandedDefault) }
    val c = probColor(m.probability)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (highlight) c.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, c.copy(alpha = if (highlight) 0.5f else 0.2f), RoundedCornerShape(14.dp))
            .clickable { open = !open }.padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = c)
                Text(m.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(m.family.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(pct(m.probability), style = MaterialTheme.typography.titleLarge, color = c, fontWeight = FontWeight.Bold)
                Text(
                    m.odds?.let { "${Formats.odds(it)} · ${m.bookmaker ?: ""}" } ?: "1xBet odds: not loaded",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                )
                m.edge?.let { e ->
                    Text("value ${if (e >= 0) "+" else ""}${Math.round(e * 100)}%", style = MaterialTheme.typography.labelSmall,
                        color = if (e >= 0.03) Accent.Green else if (e < 0) Accent.Red else Accent.Orange)
                }
            }
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Label("Statistical evidence", Accent.Blue)
            m.evidence.forEach { Bullet(it) }
            m.injuryShift?.takeIf { kotlin.math.abs(it) >= 0.005 }?.let {
                Label("Injuries & line-ups", Accent.Orange)
                Bullet("Reported absences moved this market by ${if (it > 0) "+" else ""}${Math.round(it * 1000) / 10.0} points.")
            }
            val hist = insights.filter { it.leans.isNotEmpty() }.take(3)
            if (hist.isNotEmpty()) {
                Label("Historical factors", Accent.Purple)
                hist.forEach { Bullet("${it.title}: ${it.impact}") }
            }
            if (m.risks.isNotEmpty()) {
                Label("Risks", Accent.Red)
                m.risks.forEach { Bullet(it) }
            }
        } else {
            Text("Tap for evidence, injuries, history and risks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Label(t: String, c: Color) {
    Text(t, style = MaterialTheme.typography.labelMedium, color = c, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Bullet(t: String) {
    Text("•  $t", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
}

/** Full Markets tab: top selections, team goal profiles, injury impact, every market ranked. */
@Composable
fun MarketsTab(item: MatchItem, state: DetailUiState) {
    val a = state.markets
    if (a == null) {
        EmptyState("Data unavailable", "Market analysis needs a prediction with expected goals (league table or odds for this match).")
        return
    }
    Column {
        TopSelectionsCard(item, a, state.insights, compact = false)
        ProfilesCard(a)
        InfoCard {
            Text("Injuries, suspensions & line-ups", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            a.injuryNotes.forEach { Bullet(it) }
            Bullet(if (state.extras.lineups.isNotEmpty()) "Line-ups confirmed: see the Squad tab." else "Line-ups not confirmed yet; the analysis updates when you reopen the match closer to kick-off.")
        }
        if (state.history.loading) Banner("Team records still loading: probabilities will update when they arrive.", color = Accent.Purple)
        AllMarketsCard(a)
        InfoCard {
            Text("Not analysed", style = MaterialTheme.typography.titleSmall)
            a.unavailable.forEach { (k, v) -> Bullet("$k: $v") }
        }
    }
}

@Composable
private fun ProfilesCard(a: MarketAnalysis) {
    val rows = listOfNotNull(a.homeAtHome, a.awayAway, a.homeAll, a.awayAll)
    InfoCard {
        Text("How the teams score and concede", style = MaterialTheme.typography.titleMedium)
        Text("Expected goals: ${String.format("%.2f", a.expectedHome)} – ${String.format("%.2f", a.expectedAway)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (rows.isEmpty()) {
            Text("Data unavailable (match history not loaded).", style = MaterialTheme.typography.bodySmall); return@InfoCard
        }
        Spacer(Modifier.height(6.dp))
        val cols = listOf<Pair<String, (GoalProfile) -> String>>(
            "Games" to { p -> "${p.games}" },
            "Scored / game" to { p -> String.format("%.1f", p.avgFor) },
            "Conceded / game" to { p -> String.format("%.1f", p.avgAgainst) },
            "Over 1.5" to { p -> pct(p.over15) }, "Over 2.5" to { p -> pct(p.over25) }, "Over 3.5" to { p -> pct(p.over35) },
            "BTTS" to { p -> pct(p.btts) }, "Clean sheets" to { p -> pct(p.cleanSheets) },
            "Failed to score" to { p -> pct(p.failedToScore) }, "Scored 2+" to { p -> pct(p.scored2plus) },
        )
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1.4f))
            rows.forEach { Text(it.label, style = MaterialTheme.typography.labelSmall, color = Accent.Blue, textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.weight(1f)) }
        }
        cols.forEach { (name, fn) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1.4f))
                rows.forEach { Text(fn(it), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
            }
        }
        Text("Last 10 games each, ESPN results. First-/second-half scoring records: Data unavailable in free sources.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AllMarketsCard(a: MarketAnalysis) {
    InfoCard {
        Text("Every market, ranked by probability", style = MaterialTheme.typography.titleMedium)
        Text(
            "Probability = goals model blended with the teams' real records (up to 30%). Odds: ${a.priceSource ?: "none loaded"}; markets without a price show N/A.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MarketFamily.values().forEach { fam ->
            val list = a.markets.filter { it.family == fam }
            if (list.isEmpty()) return@forEach
            Spacer(Modifier.height(8.dp))
            Text(fam.label, style = MaterialTheme.typography.titleSmall, color = Accent.Purple)
            list.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ProbBar(m.probability, probColor(m.probability))
                    Text(pct(m.probability), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(42.dp))
                    Text(m.odds?.let { Formats.odds(it) } ?: "N/A", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.width(46.dp),
                        color = if (m.odds == null) MaterialTheme.colorScheme.onSurfaceVariant else Accent.Green)
                    Text(m.edge?.let { (if (it >= 0) "+" else "") + Math.round(it * 100) + "%" } ?: "", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                        modifier = Modifier.width(40.dp), color = when { m.edge == null -> Color.Unspecified; m.edge!! >= 0.03 -> Accent.Green; m.edge!! < 0 -> Accent.Red; else -> Accent.Orange })
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
