package com.matchday.app.ui.picks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.repo.DailyPicksRepository
import com.matchday.app.data.repo.DailySlips
import com.matchday.app.data.repo.PickRange
import com.matchday.app.data.repo.PickSport
import com.matchday.app.data.repo.Slip
import com.matchday.app.data.repo.SlipLeg
import com.matchday.app.ui.Formats
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.SectionHeader
import com.matchday.app.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PicksUi(
    val range: PickRange = PickRange.TODAY,
    val sports: Set<PickSport> = setOf(PickSport.FOOTBALL),
    val days: List<DailySlips> = emptyList(),
    val selected: String = DailyPicksRepository.today(),
    val building: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

class DailyPicksViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(PicksUi())
    val state: StateFlow<PicksUi> = _state

    init {
        viewModelScope.launch {
            val saved = runCatching { c.settings.current().pickSports }.getOrDefault(setOf("FOOTBALL"))
            _state.value = _state.value.copy(sports = saved.mapNotNull { n -> PickSport.values().firstOrNull { it.name == n } }.toSet().ifEmpty { setOf(PickSport.FOOTBALL) })
            load(rebuild = false)
        }
    }

    /** Multi-select; the choice is remembered. */
    fun toggleSport(sp: PickSport?) {
        val cur = _state.value.sports
        val next = when {
            sp == null -> PickSport.values().toSet()
            sp in cur -> (cur - sp).ifEmpty { cur }
            else -> cur + sp
        }
        if (next == cur) return
        _state.value = _state.value.copy(sports = next)
        viewModelScope.launch { runCatching { c.settings.setPickSports(next.map { it.name }.toSet()) } }
        load(rebuild = false)
    }

    fun select(d: String) { _state.value = _state.value.copy(selected = d) }

    fun setRange(r: PickRange) {
        _state.value = _state.value.copy(range = r, selected = java.time.LocalDate.now(DailyPicksRepository.QATAR).plusDays(r.startDay).toString())
        load(rebuild = false)
    }

    fun load(rebuild: Boolean) {
        val r = _state.value.range
        val sp = _state.value.sports
        viewModelScope.launch {
            _state.value = _state.value.copy(building = true)
            withContext(Dispatchers.IO) {
                runCatching { c.dailyPicks.settle(c.matches.recentFinished()) }
                runCatching { if (rebuild) c.dailyPicks.build(r, sp, force = true) else c.dailyPicks.todayOrBuild(r, sp) }
            }.exceptionOrNull()?.let { e -> android.util.Log.w("DailyPicks", "build failed", e) }
            val err = if (sp.isEmpty()) null else runCatching { withContext(Dispatchers.IO) { if (c.matches.observeMatchesOnce().isEmpty()) "No fixtures saved yet: open Home and pull to refresh, then come back." else null } }.getOrNull()
            val key = sp.map { it.name }.sorted()
            val all = withContext(Dispatchers.IO) { c.dailyPicks.all() }.filter { it.range == r.name && it.sports.sorted() == key }
            _state.value = _state.value.copy(days = all.sortedByDescending { it.date }, building = false, loaded = true, error = err)
        }
    }
}

@Composable
fun DailyPicksScreen(vm: DailyPicksViewModel, onMatch: (String) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val zone = LocalZone.current
    val day = st.days.firstOrNull { it.date == st.selected }
    val startToday = java.time.LocalDate.now(DailyPicksRepository.QATAR).plusDays(st.range.startDay).toString()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Daily Picks", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Three accumulators a day (5, 10 and 20 matches) from the sports you choose. Each match is analysed first; risky ones are left out.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = st.sports.size == PickSport.values().size, onClick = { vm.toggleSport(null) }, enabled = !st.building,
                        label = { Text("Select all") })
                }
                items(PickSport.values().toList()) { sp ->
                    FilterChip(selected = sp in st.sports, onClick = { vm.toggleSport(sp) }, enabled = !st.building,
                        label = { Text("${sp.emoji} ${sp.label}") })
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PickRange.values().toList()) { r ->
                    FilterChip(selected = st.range == r, onClick = { vm.setRange(r) }, enabled = !st.building, label = { Text(r.label) })
                }
            }
        }
        if (st.building) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = Accent.Purple) }
        if (st.days.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(st.days.map { it.date }) { d ->
                        val w = st.days.first { it.date == d }.slips.map { it.won }
                        val mark = when { w.any { it == true } -> " ✅"; w.all { it == false } && w.isNotEmpty() -> " ❌"; else -> "" }
                        FilterChip(selected = d == st.selected, onClick = { vm.select(d) },
                            label = { Text((if (d == startToday) "Current" else d) + mark) })
                    }
                }
            }
        }
        if (day == null) {
            item {
                if (st.loaded && !st.building) when {
                    st.sports.isEmpty() -> EmptyState("Choose a sport", "Pick at least one sport above to build the slips.")
                    st.error != null -> EmptyState("No picks yet", st.error!!)
                    st.selected != startToday -> EmptyState("No picks for this day", "No slips were saved for this date.")
                    else -> Column(Modifier.padding(16.dp)) {
                        EmptyState("Slips couldn't be built", "The free sports feed or the saved data wasn't readable just now. Tap Rebuild to try again.")
                        OutlinedButton(onClick = { vm.load(rebuild = true) }) { Text("Rebuild") }
                    }
                }
            }
        } else {
            item {
                InfoCard {
                    Text("${st.range.label} · ${st.sports.joinToString(" ") { it.emoji }}: ${day.analysed} matches analysed · ${day.qualified} passed the risk filter", style = MaterialTheme.typography.titleSmall)
                    if (PickSport.TENNIS in st.sports) Text("Tennis: ranking and real odds only - surface records, head-to-head, recent form and fatigue aren't in the free feed, so they aren't used.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.range == PickRange.TOMORROW) Text("Tomorrow also includes today's matches that haven't started yet (Qatar time).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.range.startDay > 0 || st.range.days > 1) Text("Games further ahead often have no bookmaker odds published yet. Those legs show the model prediction marked \"Odds unavailable\" (never an invented price); rebuild later for real prices.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Built ${Formats.dayTime(day.createdAt, zone)}. Odds are the real bookmaker prices at that time (1xBet when you've loaded them, otherwise the free feed's bookmaker, as labelled).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (day.excluded.isNotEmpty()) {
                        var open by rememberSaveable(day.date) { mutableStateOf(false) }
                        Text(if (open) "Hide left-out matches" else "Why ${day.excluded.values.sum()} matches were left out ›",
                            style = MaterialTheme.typography.labelMedium, color = Accent.Blue, modifier = Modifier.padding(top = 6.dp).clickable { open = !open })
                        if (open) day.excluded.entries.sortedByDescending { it.value }.forEach { (k, v) ->
                            Text("•  $k: $v", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    val started = day.slips.flatMap { it.legs }.any { it.kickoff <= System.currentTimeMillis() }
                    if (day.date == startToday && !started) {
                        OutlinedButton(onClick = { vm.load(rebuild = true) }, enabled = !st.building, modifier = Modifier.padding(top = 6.dp)) {
                            Text("Rebuild with the latest data")
                        }
                    }
                }
            }
            day.slips.forEach { slip ->
                item { SectionHeader("${slip.size}-match slip", slipColor(slip), slip.legs.size) }
                item { SlipCard(slip, onMatch) }
            }
            item {
                Text(
                    "Combined probability multiplies each leg's estimate and assumes the matches are independent. Even strong accumulators lose more often than single bets: treat them as entertainment, not income.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun slipColor(s: Slip) = when (s.won) { true -> Accent.Green; false -> Accent.Red; null -> Accent.Purple }

@Composable
private fun SlipCard(slip: Slip, onMatch: (String) -> Unit) {
    val zone = LocalZone.current
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Total odds ${if (slip.legs.none { it.odds != null }) "Odds unavailable" else String.format("%.2f", slip.totalOdds) + if (slip.unpriced > 0) " (${slip.legs.size - slip.unpriced} priced legs)" else ""}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Combined estimate ${if (slip.legs.isEmpty()) "N/A" else "${String.format("%.1f", slip.combinedProbability * 100)}%"} · each leg ${Math.round(slip.minProbability * 100)}%+",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val (t, col) = when {
                slip.legs.isEmpty() -> "No slip" to MaterialTheme.colorScheme.onSurfaceVariant
                slip.won == true -> "✅ Won" to Accent.Green
                slip.won == false -> "❌ Lost" to Accent.Red
                else -> "⏳ Pending" to Accent.Orange
            }
            Text(t, style = MaterialTheme.typography.labelLarge, color = col, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        slip.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Accent.Orange, modifier = Modifier.padding(top = 4.dp)) }
        slip.legs.forEachIndexed { i, leg ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 6.dp)) else Spacer(Modifier.height(8.dp))
            LegRow(leg, zone, onMatch)
        }
    }
}

@Composable
private fun LegRow(leg: SlipLeg, zone: java.time.ZoneId, onMatch: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onMatch(leg.matchId) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${PickSport.emojiOf(leg.sport)} ${leg.home} vs ${leg.away}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${leg.competition} · ${Formats.kickoffLabel(leg.kickoff, zone)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val (icon, col) = when {
                leg.void -> "↺" to MaterialTheme.colorScheme.onSurfaceVariant
                leg.result == true -> "✅" to Accent.Green
                leg.result == false -> "❌" to Accent.Red
                else -> "⏳" to Accent.Orange
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(icon, style = MaterialTheme.typography.titleMedium, color = col)
                leg.score?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text(leg.label, style = MaterialTheme.typography.titleSmall, color = Accent.Blue, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val o = leg.odds
            if (o != null) Text("@ ${String.format("%.2f", o)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Accent.Green)
            else Text("Odds unavailable", style = MaterialTheme.typography.labelSmall, color = Accent.Orange)
            Spacer(Modifier.width(6.dp))
            Text("${Math.round(leg.probability * 100)}%", style = MaterialTheme.typography.labelMedium)
        }
        Text("${leg.bookmaker} · ${leg.reason}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (leg.void) Text("Void: refunded or not settleable from the free feed.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
