package com.matchday.app.ui.settings

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.matchday.app.AppContainer
import com.matchday.app.data.local.AppSettings
import com.matchday.app.domain.Catalog
import com.matchday.app.ui.Formats
import com.matchday.app.ui.components.InfoCard
import com.matchday.app.ui.components.SectionHeader
import com.matchday.app.ui.theme.Accent
import com.matchday.app.work.Notifications
import com.matchday.app.work.RefreshScheduler
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer, private val appContext: Context) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleLeague(k: String, on: Boolean) = viewModelScope.launch {
        c.settings.toggleLeague(k, on)
        if (on) c.matches.refresh(force = false)
    }
    fun setRefresh(m: Int) = viewModelScope.launch { c.settings.setRefreshMinutes(m) }
    fun setDeviceZone(v: Boolean) = viewModelScope.launch { c.settings.setDeviceZone(v) }
    fun setDark(v: Boolean) = viewModelScope.launch { c.settings.setDark(v) }
    fun setNotify(kind: String, v: Boolean) = viewModelScope.launch { c.settings.setNotify(kind, v) }
    fun setThreshold(v: Int) = viewModelScope.launch { c.settings.setOddsThreshold(v) }
    fun setOneXKey(v: String) = viewModelScope.launch { c.settings.setOneXBetKey(v) }
    fun setOneXAuto(v: Boolean) = viewModelScope.launch { c.settings.setAutoLoadOneXBet(v) }
    fun refreshNow() = viewModelScope.launch { c.matches.refresh(force = true) }
    fun runChecksNow() = RefreshScheduler.runNow(appContext)
    fun testNotification() = Notifications.show(appContext, "test", "Matchday notifications work", "You'll get alerts like this for odds moves, line-ups and predictions.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        val current = s
        if (current != null) SettingsList(current, vm)
    }
}

@Composable
private fun SettingsList(settings: AppSettings, vm: SettingsViewModel) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item { SectionHeader("Data", Accent.Green) }
        item {
            InfoCard {
                Text("No account or API key needed (1xBet odds optional, below).", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Fixtures, scores, tables, line-ups and odds come from ESPN's free public feed; weather from Open-Meteo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text("Last refresh: ${Formats.ago(settings.lastRefresh)}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.refreshNow() }) { Text("Refresh now") }
                    OutlinedButton(onClick = { vm.runChecksNow() }) { Text("Run alerts check") }
                }
            }
        }

        item { SectionHeader("1xBet odds", Accent.Orange) }
        item { OneXBetSettings(settings, vm) }
        item { SectionHeader("Competitions", Accent.Purple) }
        item {
            InfoCard {
                Catalog.ALL.forEach { comp ->
                    val on = comp.key in settings.enabledLeagues
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.toggleLeague(comp.key, !on) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(comp.title, style = MaterialTheme.typography.bodyMedium)
                            Text(comp.sport.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = on, onCheckedChange = { vm.toggleLeague(comp.key, it) })
                    }
                }
            }
        }

        item { SectionHeader("Refresh", Accent.Orange) }
        item {
            InfoCard {
                Text("Automatic refresh interval", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(30, 60, 120, 180, 360)) { m ->
                        FilterChip(
                            selected = settings.refreshMinutes == m,
                            onClick = { vm.setRefresh(m) },
                            label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                        )
                    }
                }
                Text(
                    "Background checks run hourly for alerts. Opening the app or pulling down always refreshes; the Live tab updates every 2 minutes.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionHeader("Notifications", Accent.Live) }
        item {
            InfoCard {
                ToggleRow("Important upcoming matches", "Favourites and big games, about 3 h before kick-off", settings.notifyUpcoming) { vm.setNotify("upcoming", it) }
                ToggleRow("Predictions before kick-off", "Our estimate and any value pick", settings.notifyPredictions) { vm.setNotify("predictions", it) }
                ToggleRow("Odds movements", "When a price moves by ${settings.oddsMoveThreshold}% or more", settings.notifyOdds) { vm.setNotify("odds", it) }
                if (settings.notifyOdds) {
                    Slider(
                        value = settings.oddsMoveThreshold.toFloat(),
                        onValueChange = { vm.setThreshold(it.toInt()) },
                        valueRange = 3f..25f,
                        steps = 21,
                    )
                }
                ToggleRow("Confirmed line-ups", "Favourite teams, shortly before kick-off", settings.notifyLineups) { vm.setNotify("lineups", it) }
                ToggleRow("Injury updates", "Favourite teams where an injury report exists (NBA, NFL, NHL, MLB)", settings.notifyInjuries) { vm.setNotify("injuries", it) }
                OutlinedButton(onClick = { vm.testNotification() }) { Text("Send test notification") }
            }
        }

        item { SectionHeader("Display", Accent.Blue) }
        item {
            InfoCard {
                ToggleRow("Dark theme", "Default", settings.darkTheme) { vm.setDark(it) }
                ToggleRow("Use device time zone", "Off = always show Qatar time (AST, UTC+3)", settings.useDeviceZone) { vm.setDeviceZone(it) }
            }
        }

        item { SectionHeader("About the predictions", Accent.Purple) }
        item {
            InfoCard {
                Text(
                    "Odds are real prices from one bookmaker (DraftKings), published in ESPN's feed. For football, our probabilities blend a " +
                        "transparent Poisson goals model (league table and recent form) with the bookmaker's margin-free odds. Other sports use " +
                        "the margin-free odds only; tennis has fixtures and scores but no odds. A value pick means our probability x the price > 1.03. " +
                        "Nothing here is a guarantee, and gambling is illegal in some countries, including Qatar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun OneXBetSettings(settings: AppSettings, vm: SettingsViewModel) {
    var key by rememberSaveable { mutableStateOf(settings.oneXBetKey) }
    var show by remember { mutableStateOf(false) }
    InfoCard {
        Text(if (settings.oneXBetKey.isBlank()) "Not connected" else "Connected", style = MaterialTheme.typography.titleSmall,
            color = if (settings.oneXBetKey.isBlank()) Accent.Orange else Accent.Green)
        Text(
            "1xBet doesn't offer a public odds API, and reading its website would mean getting around its protections, which Matchday doesn't do. " +
                "Genuine 1xBet prices come instead from The Odds API (the-odds-api.com), a licensed aggregator that lists 1xBet as bookmaker \"onexbet\". " +
                "Its free plan gives 500 credits a month, no card needed; loading one match uses about 7. Other bookmakers' prices are never shown as 1xBet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it }, singleLine = true,
            label = { Text("The Odds API key") },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { vm.setOneXKey(key) }) { Text("Save key") }
            OutlinedButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
            if (settings.oneXBetKey.isNotBlank()) OutlinedButton(onClick = { key = ""; vm.setOneXKey("") }) { Text("Remove") }
        }
        Spacer(Modifier.height(6.dp))
        ToggleRow(
            "Load 1xBet odds automatically",
            "When you open a football match and saved prices are older than 3 hours (uses credits).",
            settings.autoLoadOneXBet,
        ) { vm.setOneXAuto(it) }
        Text(
            "Credits left this month: ${if (settings.oddsApiRemaining >= 0) settings.oddsApiRemaining else "N/A (updates after the first load)"}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
