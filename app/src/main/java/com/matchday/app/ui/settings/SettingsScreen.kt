package com.matchday.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

    fun saveKeys(o: String, f: String, a: String) = viewModelScope.launch {
        c.settings.setKeys(o, f, a)
        c.matches.refresh(force = true)
    }
    fun toggleSport(k: String, on: Boolean) = viewModelScope.launch { c.settings.toggleSport(k, on) }
    fun setRefresh(m: Int) = viewModelScope.launch {
        c.settings.setRefreshMinutes(m)
        RefreshScheduler.schedule(appContext, m)
    }
    fun setDeviceZone(v: Boolean) = viewModelScope.launch { c.settings.setDeviceZone(v) }
    fun setDark(v: Boolean) = viewModelScope.launch { c.settings.setDark(v) }
    fun setNotify(kind: String, v: Boolean) = viewModelScope.launch { c.settings.setNotify(kind, v) }
    fun setThreshold(v: Int) = viewModelScope.launch { c.settings.setOddsThreshold(v) }
    fun refreshNow() = viewModelScope.launch { c.matches.refresh(force = true) }
    fun runChecksNow() = RefreshScheduler.runNow(appContext)
    fun testNotification() = Notifications.show(appContext, "test", "Matchday notifications work", "You'll get alerts like this for odds moves, line-ups and predictions.")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings", style = MaterialTheme.typography.headlineSmall) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        val current = s
        if (current != null) SettingsList(current, vm, context)
    }
}

@Composable
private fun SettingsList(settings: AppSettings, vm: SettingsViewModel, context: Context) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
            item { SectionHeader("Data sources (API keys)", Accent.Blue) }
            item { KeysCard(settings, vm, context) }

            item { SectionHeader("Usage", Accent.Green) }
            item {
                InfoCard {
                    Text(
                        if (settings.quotaRemaining >= 0) "The Odds API: ${settings.quotaRemaining} credits left this month (${settings.quotaUsed} used)"
                        else "The Odds API: credit balance shown after the first refresh",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Last refresh: ${Formats.ago(settings.lastRefresh)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val perRefresh = settings.enabledSports.sumOf { Catalog.creditCost(Catalog.find(it)) }
                    val perDay = perRefresh * (24 * 60 / settings.refreshMinutes)
                    Text(
                        "Estimated: ~$perRefresh credits per refresh, ~${perDay * 30} per month at this interval (tennis counts once per active tournament). Free plan: 500/month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (perDay * 30 > 500) Accent.Orange else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.refreshNow() }) { Text("Refresh now") }
                        OutlinedButton(onClick = { vm.runChecksNow() }) { Text("Run alerts check") }
                    }
                }
            }

            item { SectionHeader("Competitions", Accent.Purple) }
            item {
                InfoCard {
                    Text("Each enabled competition costs credits per refresh (shown in brackets).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Catalog.FIXED.forEach { comp ->
                        val on = comp.key in settings.enabledSports
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.toggleSport(comp.key, !on) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${comp.title}  (${Catalog.creditCost(comp)})", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    comp.sport.label + (if (comp.fdCode != null) " · full stats" else if (comp.sport.name == "FOOTBALL") " · odds-based only" else ""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = on, onCheckedChange = { vm.toggleSport(comp.key, it) })
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
                        items(listOf(60, 120, 180, 360, 720)) { m ->
                            FilterChip(
                                selected = settings.refreshMinutes == m,
                                onClick = { vm.setRefresh(m) },
                                label = { Text(if (m < 60) "$m min" else "${m / 60} h") },
                            )
                        }
                    }
                    Text("How often odds are re-downloaded (costs credits). Alerts are checked hourly from cached data at no cost.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionHeader("Notifications", Accent.Live) }
            item {
                InfoCard {
                    ToggleRow("Important upcoming matches", "Favourites and popular games, about 3 h before kick-off", settings.notifyUpcoming) { vm.setNotify("upcoming", it) }
                    ToggleRow("Predictions before kick-off", "Our estimate and any value pick, ~2 h before", settings.notifyPredictions) { vm.setNotify("predictions", it) }
                    ToggleRow("Odds movements", "When a price moves by ${settings.oddsMoveThreshold}% or more", settings.notifyOdds) { vm.setNotify("odds", it) }
                    if (settings.notifyOdds) {
                        Slider(
                            value = settings.oddsMoveThreshold.toFloat(),
                            onValueChange = { vm.setThreshold(it.toInt()) },
                            valueRange = 3f..25f,
                            steps = 21,
                        )
                    }
                    ToggleRow("Confirmed line-ups", "Favourite teams (needs API-Football key)", settings.notifyLineups) { vm.setNotify("lineups", it) }
                    ToggleRow("Injury updates", "Favourite teams, day of match (needs API-Football key)", settings.notifyInjuries) { vm.setNotify("injuries", it) }
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
                        "Bookmaker odds are real prices from The Odds API. Our probabilities come from a transparent Poisson goals model " +
                            "(football-data.org tables, form, absences) blended with the bookmakers' margin-free consensus. Other sports use " +
                            "the consensus only. A value pick means our probability x the best price > 1.03. Nothing here is a guarantee, " +
                            "and gambling is illegal in some countries, including Qatar.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
}

@Composable
private fun KeysCard(s: AppSettings, vm: SettingsViewModel, context: Context) {
    var odds by rememberSaveable { mutableStateOf("") }
    var fd by rememberSaveable { mutableStateOf("") }
    var af by rememberSaveable { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(s) {
        if (!loaded) { odds = s.oddsKey; fd = s.footballDataKey; af = s.apiFootballKey; loaded = true }
    }
    InfoCard {
        KeyField("The Odds API key (required)", odds, { odds = it }, "Fixtures + bookmaker odds, all sports. Free: 500 credits/month.", "https://the-odds-api.com/#get-access", context)
        KeyField("football-data.org key (recommended)", fd, { fd = it }, "Tables, form, head-to-head. Free: 10 requests/min.", "https://www.football-data.org/client/register", context)
        KeyField("API-Football key (optional)", af, { af = it }, "Injuries, line-ups, venues, live minute. Free: 100 requests/day.", "https://dashboard.api-football.com/register", context)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.saveKeys(odds, fd, af) }, modifier = Modifier.fillMaxWidth()) { Text("Save keys & refresh") }
        Text(
            "Keys are stored only on this phone (app-private storage) and sent only to their own provider.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit, help: String, url: String, context: Context) {
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 6.dp)) {
        OutlinedTextField(
            value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Show key")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Get a free key →",
            style = MaterialTheme.typography.labelMedium, color = Accent.Blue,
            modifier = Modifier.clickable {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
            }.padding(vertical = 2.dp),
        )
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
