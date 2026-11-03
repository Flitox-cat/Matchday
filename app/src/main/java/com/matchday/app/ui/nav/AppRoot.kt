package com.matchday.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.matchday.app.container
import com.matchday.app.data.local.AppSettings
import com.matchday.app.ui.LocalZone
import com.matchday.app.ui.detail.MatchDetailScreen
import com.matchday.app.ui.detail.MatchDetailViewModel
import com.matchday.app.ui.favourites.FavouritesScreen
import com.matchday.app.ui.favourites.FavouritesViewModel
import com.matchday.app.ui.home.HomeScreen
import com.matchday.app.ui.home.HomeViewModel
import com.matchday.app.ui.live.LiveScreen
import com.matchday.app.ui.live.LiveViewModel
import com.matchday.app.ui.predictions.PredictionsScreen
import com.matchday.app.ui.predictions.PredictionsViewModel
import com.matchday.app.ui.settings.SettingsScreen
import com.matchday.app.ui.settings.SettingsViewModel
import com.matchday.app.ui.theme.Accent
import com.matchday.app.ui.zone

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("predictions", "Predictions", Icons.Filled.Insights),
    Tab("live", "Live", Icons.Filled.LiveTv),
    Tab("favourites", "Favourites", Icons.Filled.Star),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppRoot(settings: AppSettings) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val container = context.container
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    val openMatch: (String) -> Unit = { id -> nav.navigate("match/$id") }

    CompositionLocalProvider(LocalZone provides settings.zone()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (route?.startsWith("match/") != true) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = { goTab(t.route) },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent.Blue,
                                    selectedTextColor = Accent.Blue,
                                    indicatorColor = Accent.Blue.copy(alpha = 0.15f),
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
                        HomeScreen(vm, onMatch = openMatch, onSettings = { goTab("settings") })
                    }
                    composable("predictions") {
                        val vm: PredictionsViewModel = viewModel(factory = viewModelFactory { initializer { PredictionsViewModel(container) } })
                        PredictionsScreen(vm, onMatch = openMatch)
                    }
                    composable("live") {
                        val vm: LiveViewModel = viewModel(factory = viewModelFactory { initializer { LiveViewModel(container) } })
                        LiveScreen(vm, onMatch = openMatch)
                    }
                    composable("favourites") {
                        val vm: FavouritesViewModel = viewModel(factory = viewModelFactory { initializer { FavouritesViewModel(container) } })
                        FavouritesScreen(vm, onMatch = openMatch)
                    }
                    composable("settings") {
                        val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
                            initializer { SettingsViewModel(container, context.applicationContext) }
                        })
                        SettingsScreen(vm)
                    }
                    composable(
                        "match/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }),
                    ) { entry ->
                        val id = entry.arguments?.getString("id") ?: ""
                        val vm: MatchDetailViewModel = viewModel(
                            key = "match_$id",
                            factory = viewModelFactory { initializer { MatchDetailViewModel(container, id) } },
                        )
                        MatchDetailScreen(vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
