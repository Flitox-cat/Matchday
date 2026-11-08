package com.matchday.app.ui.live

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.ui.Formats
import com.matchday.app.ui.components.Banner
import com.matchday.app.ui.components.EmptyState
import com.matchday.app.ui.components.MatchCard
import com.matchday.app.ui.components.SectionHeader
import com.matchday.app.ui.theme.Accent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LiveUiState(
    val live: List<MatchItem> = emptyList(),
    val finished: List<MatchItem> = emptyList(),
    val notes: List<String> = emptyList(),
    val lastUpdate: Long = 0,
)

class LiveViewModel(private val c: AppContainer) : ViewModel() {
    private val notes = MutableStateFlow<List<String>>(emptyList())
    private val lastUpdate = MutableStateFlow(0L)

    val state: StateFlow<LiveUiState> = combine(c.matches.observeMatches(), notes, lastUpdate) { list, n, t ->
        LiveUiState(
            live = list.filter { it.status == MatchStatus.LIVE }.sortedBy { it.kickoff },
            // Finished games stay until the end of the Qatar calendar day on which they ended.
            finished = list.filter { it.status == MatchStatus.FINISHED && endedToday(it) }.sortedByDescending { it.kickoff },
            notes = n,
            lastUpdate = t,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    suspend fun poll() {
        notes.value = try {
            c.matches.refreshLive().also { lastUpdate.value = System.currentTimeMillis() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf("Live scores unavailable right now (offline or source error). Showing saved data.")
        }
    }

    fun refreshNow() = viewModelScope.launch { poll() }
}

private const val POLL_MS = 30_000L

private val QATAR = java.time.ZoneId.of("Asia/Qatar")

/** Approximate end time (kick-off + typical duration) falls on today's date in Qatar. */
fun endedToday(m: MatchItem): Boolean {
    val dur = when (m.competition.sport) {
        com.matchday.app.domain.SportType.FOOTBALL -> 2 * 3_600_000L
        com.matchday.app.domain.SportType.TENNIS -> 2 * 3_600_000L
        else -> (5 * 3_600_000L) / 2
    }
    val end = java.time.Instant.ofEpochMilli(m.kickoff + dur).atZone(QATAR).toLocalDate()
    return end == java.time.LocalDate.now(QATAR)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(vm: LiveViewModel, onMatch: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Polls only while this screen is visible and the app is in the foreground.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.poll()
                delay(POLL_MS)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Live", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Auto-refresh every 30 s · updated ${Formats.ago(state.lastUpdate)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = { IconButton(onClick = { vm.refreshNow() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            state.notes.forEach { n -> item { Banner(n, color = Accent.Blue) } }
            if (state.live.isEmpty()) {
                item { EmptyState("No live matches right now", "Live games from your enabled competitions appear here with changing scores.") }
            } else {
                item { SectionHeader("In play", Accent.Live, state.live.size) }
                items(state.live, key = { "live_${it.id}" }) { m -> MatchCard(m, onClick = { onMatch(m.id) }) }
            }
            if (state.finished.isNotEmpty()) {
                item { SectionHeader("Finished today (Qatar time)", Color(0xFF7D879E), state.finished.size) }
                items(state.finished, key = { "fin_${it.id}" }) { m -> MatchCard(m, onClick = { onMatch(m.id) }) }
            }
        }
    }
}
