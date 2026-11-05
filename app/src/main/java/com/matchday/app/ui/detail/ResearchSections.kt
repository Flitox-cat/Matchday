package com.matchday.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matchday.app.data.repo.ApiStatus
import com.matchday.app.data.repo.BookPrice
import com.matchday.app.data.repo.BookSnapshot
import com.matchday.app.data.repo.OneXBetRepository
import com.matchday.app.data.repo.OneXBetState
import com.matchday.app.domain.Insight
import com.matchday.app.domain.InsightKind
import com.matchday.app.domain.MarketLine
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.Verdict
import com.matchday.app.domain.VerdictEngine
import com.matchday.app.domain.VerdictRating
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.KeyValue
import com.matchday.app.ui.components.MovementArrow
import com.matchday.app.ui.theme.Accent

private fun ratingColor(r: VerdictRating) = when (r) {
    VerdictRating.WORTH -> Accent.Green
    VerdictRating.RISKY -> Accent.Orange
    VerdictRating.AVOID -> Accent.Red
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------------------
// Historical Insights & Interesting Facts
// ---------------------------------------------------------------------------

@Composable
fun InsightsTab(item: MatchItem, state: DetailUiState) {
    val zone = LocalZone.current
    val h = state.history
    Column {
        Text(
            "Historical Insights & Interesting Facts",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Banner(
            "Built from real completed matches in ESPN's free feed (domestic league + UEFA competitions, current and last 3 seasons). " +
                "Facts lean towards markets but never decide the recommendation on their own.",
            color = Accent.Blue,
        )
        if (h.loading) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = Accent.Purple)
                Spacer(Modifier.width(10.dp))
                Text("Researching match history…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!h.loading && h.failed && state.extras.headToHead.isEmpty()) {
            EmptyState("Data unavailable", "Match history couldn't be loaded (offline or source error). Tap reload to try again.")
        }
        val cards = state.insights
        if (!h.loading && cards.isEmpty() && !h.failed) {
            EmptyState("No notable facts", "Not enough history to say anything reliable about this match.")
        }
        cards.forEach { InsightCard(it, zone) }
        if (h.home != null || h.away != null) {
            Text(
                "Matches analysed: ${item.homeTeam} ${h.home?.matches?.size ?: 0}, ${item.awayTeam} ${h.away?.matches?.size ?: 0}. " +
                    "Updated ${listOfNotNull(h.home?.fetchedAt, h.away?.fetchedAt).minOrNull()?.let { Formats.ago(it) } ?: "N/A"}.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun InsightCard(i: Insight, zone: java.time.ZoneId) {
    val color = when (i.kind) {
        InsightKind.WARNING -> Accent.Orange
        InsightKind.FIRE -> Accent.Red
        InsightKind.H2H -> Accent.Purple
        InsightKind.STAT -> Accent.Blue
        InsightKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.kind.emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(i.title, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
            if (i.smallSample && i.matches > 0) Pill("Small sample", Accent.Orange)
        }
        Spacer(Modifier.height(6.dp))
        Text(i.statement, style = MaterialTheme.typography.bodyMedium)
        if (i.metrics.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            i.metrics.forEach { (k, v) -> KeyValue(k, v) }
        }
        if (i.leans.isNotEmpty() || i.impact != "None.") {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.10f))
                    .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp)).padding(10.dp),
            ) {
                Text("Betting impact: ${i.impact}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        val period = if (i.from != null && i.to != null) "${Formats.day(i.from, zone)} – ${Formats.day(i.to, zone)}" else null
        Text(
            listOfNotNull(
                if (i.matches > 0) "${i.matches} match${if (i.matches == 1) "" else "es"} analysed" else null,
                period,
                i.competitions.takeIf { it.isNotEmpty() }?.joinToString(", "),
                "Source: ${i.source}",
                i.updatedAt?.let { "updated ${Formats.ago(it)}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (i.smallSample && i.matches > 0) {
            Text(
                "Fewer than 6 matches: treat as anecdotal, not a trend.",
                style = MaterialTheme.typography.labelSmall, color = Accent.Orange,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Advanced Betting Verdict
// ---------------------------------------------------------------------------

@Composable
fun VerdictCard(item: MatchItem, state: DetailUiState) {
    val v: Verdict = state.verdict ?: return
    val color = ratingColor(v.rating)
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced Betting Verdict", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(v.rating.label, color)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).padding(14.dp),
        ) {
            Column {
                Text(if (v.noBet) "NO BET" else "Best market", style = MaterialTheme.typography.labelMedium, color = color)
                Text(v.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                v.best?.let { b ->
                    Text(
                        "AI ${Formats.pct(b.probability)} · implied ${Formats.pct(b.implied)} · value ${b.edge?.let { com.matchday.app.domain.PredictionEngine.signedPct(it) } ?: "N/A"} · ${b.bookmaker}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        v.alternative?.let { a ->
            Spacer(Modifier.height(8.dp))
            KeyValue("Alternative (also value)", "${a.label} @ ${Formats.odds(a.odds)} (${a.edge?.let { com.matchday.app.domain.PredictionEngine.signedPct(it) }})", Accent.Green)
        }
        Spacer(Modifier.height(8.dp))
        Text("Reason", style = MaterialTheme.typography.titleSmall)
        v.reasons.forEach { r ->
            Row(Modifier.padding(top = 3.dp)) {
                Text("•  ", color = color)
                Text(r, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (v.insightNotes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Historical context", style = MaterialTheme.typography.titleSmall)
            v.insightNotes.forEach { n ->
                Text("•  $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        } else if (state.history.loading) {
            Text("Historical research still loading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Odds source: ${v.oddsSource ?: "none"}" + if (v.oddsSource != null && v.oddsSource?.startsWith("1xBet") != true) " (load or enter 1xBet prices on the Odds tab to use them)." else ".",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 1xBet odds
// ---------------------------------------------------------------------------

@Composable
fun OneXBetSection(item: MatchItem, state: DetailUiState, onSave: (List<BookPrice>) -> Unit, onClear: () -> Unit, onLoadApi: () -> Unit, onSettings: () -> Unit) {
    val zone = LocalZone.current
    val st = state.oneXBet
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("1xBet odds", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.api.loading) CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp, color = Accent.Purple)
        }
        Spacer(Modifier.height(4.dp))
        ApiStatusBlock(item, state, onLoadApi, onSettings)
        Spacer(Modifier.height(8.dp))
        if (editing) {
            OneXBetForm(item, (st as? OneXBetState.Ok)?.current, onCancel = { editing = false }) { prices -> onSave(prices); editing = false }
        } else {
            when (st) {
                is OneXBetState.NotLoaded -> {
                    Text("No 1xBet prices entered for this match.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { editing = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent.Green)) { Text("Enter 1xBet odds") }
                }
                is OneXBetState.Ok -> {
                    OneXBetTable(item, state, st.current, st.history, zone)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { editing = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent.Blue)) { Text("Update odds") }
                        OutlinedButton(onClick = onClear) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun OneXBetForm(item: MatchItem, current: BookSnapshot?, onCancel: () -> Unit, onSave: (List<BookPrice>) -> Unit) {
    val slots = OneXBetRepository.slots(item.homeTeam, item.awayTeam)
    val values = remember(item.id) {
        mutableStateMapOf<Int, String>().apply {
            slots.forEachIndexed { i, sl -> current?.find(sl.market, sl.sel, sl.point)?.let { put(i, Formats.odds(it.price)) } }
        }
    }
    val titles = mapOf("1X2" to "Match result (1X2)", "OU" to "Total goals", "BTTS" to "Both teams to score",
        "DC" to "Double chance", "DNB" to "Draw no bet", "1H" to "First-half result",
        "1HOU" to "First-half goals", "1HBTTS" to "First-half BTTS", "TT" to "Team goals")
    Text("Fill in only the markets you want analysed (decimal odds, e.g. 1.85).", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    slots.withIndex().groupBy { it.value.market }.forEach { (market, group) ->
        Spacer(Modifier.height(8.dp))
        Text(titles[market] ?: market, style = MaterialTheme.typography.titleSmall, color = Accent.Purple)
        group.chunked(if (market in setOf("OU", "1HOU", "TT", "1HBTTS")) 2 else 3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                row.forEach { (i, sl) ->
                    val txt = values[i] ?: ""
                    val bad = txt.isNotBlank() && OneXBetRepository.parse(txt) == null
                    OutlinedTextField(
                        value = txt, onValueChange = { v -> values[i] = v.filter { it.isDigit() || it == '.' || it == ',' }.take(7) },
                        label = { Text(sl.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true, isError = bad,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    val parsed = slots.mapIndexedNotNull { i, sl -> OneXBetRepository.parse(values[i] ?: "")?.let { BookPrice(sl.market, sl.sel, sl.label, sl.point, it) } }
    val invalid = slots.indices.any { i -> (values[i] ?: "").isNotBlank() && OneXBetRepository.parse(values[i] ?: "") == null }
    if (invalid) Text("Some values aren't valid decimal odds (must be above 1.01).", style = MaterialTheme.typography.labelSmall, color = Accent.Red)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        Button(onClick = { onSave(parsed) }, enabled = parsed.isNotEmpty() && !invalid,
            colors = ButtonDefaults.buttonColors(containerColor = Accent.Green)) { Text("Save & analyse") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun OneXBetTable(item: MatchItem, state: DetailUiState, snap: BookSnapshot, history: List<BookSnapshot>, zone: java.time.ZoneId) {
    val p = state.extras.prediction ?: item.prediction
    val lines = VerdictEngine.linesFrom1xBet(snap.prices, "1xBet", p, state.extras.btts).map { l ->
        if (l.probability != null) l
        else l.copy(probability = state.markets?.markets?.firstOrNull { it.market == l.group && it.sel == l.sel && it.point == l.point }?.probability)
    }
    val previous = history.dropLast(1).lastOrNull()
    val first = history.firstOrNull()?.takeIf { history.size > 1 }
    Text(
        if (snap.bookmaker == OneXBetRepository.SOURCE_API)
            "Genuine 1xBet prices via The Odds API. 1xBet last updated ${Formats.dayTime(snap.bookUpdatedAt, zone)}; downloaded ${Formats.ago(snap.fetchedAt)}."
        else "1xBet prices entered by you on ${Formats.dayTime(snap.fetchedAt, zone)} (${Formats.ago(snap.fetchedAt)}). Prices change: update them before deciding.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    val groups = listOf("1X2" to "Match result (1X2)", "DC" to "Double chance", "DNB" to "Draw no bet",
        "OU" to "Total goals", "BTTS" to "Both teams to score", "TT" to "Team goals", "1H" to "First-half result",
        "1HOU" to "First-half goals", "1HBTTS" to "First-half BTTS", "AH" to "Asian handicap")
    Row(Modifier.fillMaxWidth()) {
        Text("Selection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        listOf("Odds", "Implied", "AI", "Value").forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
        }
    }
    groups.forEach { (g, title) ->
        val rows = lines.filter { it.group == g }.sortedWith(compareBy({ it.point ?: 0.0 }, { it.sel }))
        if (rows.isEmpty()) return@forEach
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Accent.Purple)
        rows.forEach { l ->
            val prev = previous?.find(l.group, l.sel, l.point)?.price
            LineRow(l, prev)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
    }
    val missing = groups.filter { (g, _) -> lines.none { it.group == g } }.map { it.second }
    if (missing.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text("Not entered: ${missing.joinToString(", ")}.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (first != null) {
        Spacer(Modifier.height(10.dp))
        Text("Odds movement", style = MaterialTheme.typography.titleSmall)
        Text(
            "Since your first entry on ${Formats.dayTime(first.fetchedAt, zone)} (${history.size} saves on this phone):",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lines.filter { it.group == "1X2" || (it.group == "OU" && it.point == 2.5) || it.group == "BTTS" }.forEach { l ->
            val was = first.find(l.group, l.sel, l.point)?.price
            if (was != null && was != l.odds) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${Formats.odds(was)} → ${Formats.odds(l.odds)}", style = MaterialTheme.typography.bodySmall)
                    MovementArrow(l.odds, was)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Value = AI probability × 1xBet odds − 1 (green at +3% or more). First-half and team-goal probabilities come from the Markets tab analysis.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LineRow(l: MarketLine, previous: Double?) {
    val edge = l.edge
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        Text(l.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(Modifier.width(54.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(Formats.odds(l.odds), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Accent.Green)
            MovementArrow(l.odds, previous)
        }
        Text(Formats.pct(l.implied), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
        Text(Formats.pct(l.probability), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
        Text(
            edge?.let { com.matchday.app.domain.PredictionEngine.signedPct(it) } ?: "N/A",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End, modifier = Modifier.width(54.dp),
            fontWeight = if (l.isValue) FontWeight.Bold else FontWeight.Normal,
            color = when { edge == null -> MaterialTheme.colorScheme.onSurfaceVariant; edge >= 0.03 -> Accent.Green; edge < 0 -> Accent.Red; else -> Accent.Orange },
        )
    }
}

@Composable
private fun ApiStatusBlock(item: MatchItem, state: DetailUiState, onLoadApi: () -> Unit, onSettings: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (val a = state.api.status) {
        is ApiStatus.NoKey -> {
            Text(
                "Automatic: add your free The Odds API key in Settings to download 1xBet prices. Without it you can type them in from your 1xBet app.",
                style = MaterialTheme.typography.labelSmall, color = muted,
            )
            OutlinedButton(onClick = onSettings, modifier = Modifier.padding(top = 4.dp)) { Text("Add key") }
        }
        is ApiStatus.Paused -> Text(
            "Free Odds API credits are used up. Automatic loading restarts on ${Formats.day(a.until, LocalZone.current)}; until then enter 1xBet prices by hand - everything else works as normal.",
            style = MaterialTheme.typography.labelSmall, color = Accent.Orange,
        )
        is ApiStatus.Unsupported -> Text(a.reason, style = MaterialTheme.typography.labelSmall, color = muted)
        is ApiStatus.Ready -> {
            Text(
                "Automatic via The Odds API: about ${OneXBetRepository.CREDIT_ESTIMATE} credits per load" +
                    (if (a.creditsLeft >= 0) ", ${a.creditsLeft} left this month." else "."),
                style = MaterialTheme.typography.labelSmall, color = muted,
            )
            if (item.status == MatchStatus.UPCOMING) {
                Button(
                    onClick = onLoadApi, enabled = !state.api.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent.Green), modifier = Modifier.padding(top = 4.dp),
                ) { Text(if (state.oneXBet is OneXBetState.Ok) "Refresh from 1xBet" else "Load 1xBet odds") }
            }
        }
    }
    state.api.message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Accent.Orange, modifier = Modifier.padding(top = 4.dp)) }
}

// ---------------------------------------------------------------------------
// Injury impact (before / after)
// ---------------------------------------------------------------------------

@Composable
fun InjuryImpactCard(item: MatchItem, im: com.matchday.app.domain.InjuryImpact) {
    fun pp(x: Double?) = x?.let { "${Math.round(it * 100)}%" } ?: "N/A"
    fun fair(x: Double?) = x?.takeIf { it > 0 }?.let { String.format("%.2f", 1 / it) } ?: "N/A"
    @Composable
    fun line(label: String, before: Double?, after: Double?, withFair: Boolean = true) {
        val d = if (before != null && after != null) after - before else null
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${pp(before)} → ${pp(after)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(
                d?.let { (if (it >= 0) "+" else "") + String.format("%.1f", it * 100) + " pts" } ?: "",
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, modifier = Modifier.width(66.dp),
                color = when { d == null || kotlin.math.abs(d) < 0.005 -> MaterialTheme.colorScheme.onSurfaceVariant; d > 0 -> Accent.Green; else -> Accent.Red },
            )
            if (withFair) Text("fair ${fair(after)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
        }
    }
    InfoCard {
        Text("Injury & absence impact", style = MaterialTheme.typography.titleMedium)
        Text(
            "${item.homeTeam}: ${im.homeOut} out, ${im.homeDoubtful} doubtful · ${item.awayTeam}: ${im.awayOut} out, ${im.awayDoubtful} doubtful (API-Football).",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text("Expected goals (model): ${String.format("%.2f", im.xgBefore.first)}–${String.format("%.2f", im.xgBefore.second)} → " +
            "${String.format("%.2f", im.xgAfter.first)}–${String.format("%.2f", im.xgAfter.second)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        line(item.homeTeam, im.before.home, im.after.home)
        if (im.before.draw != null) line("Draw", im.before.draw, im.after.draw)
        line(item.awayTeam, im.before.away, im.after.away)
        line("Over 2.5", im.over25Before, im.over25After)
        line("BTTS Yes", im.bttsBefore, im.bttsAfter)
        Spacer(Modifier.height(4.dp))
        Text(
            "Before = same calculation without the absences. Only the statistical part (${Math.round(im.modelWeight * 100)}% of the blend) is adjusted: " +
                "bookmaker prices usually already include team news, so it isn't counted twice. Value and the verdict use the 'after' figures.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
