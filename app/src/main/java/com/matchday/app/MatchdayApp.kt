package com.matchday.app

import android.app.Application
import android.content.Context
import com.matchday.app.data.local.AppDatabase
import com.matchday.app.data.local.SettingsStore
import com.matchday.app.data.repo.JsonCache
import com.matchday.app.data.repo.MatchRepository
import com.matchday.app.data.repo.OneXBetRepository
import com.matchday.app.data.repo.TeamRepository
import com.matchday.app.data.repo.WeatherRepository
import com.matchday.app.work.Notifications
import com.matchday.app.work.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency injection: one instance of each repository for the whole app. */
class AppContainer(context: Context) {
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    val cache = JsonCache(db.cache())
    val weather = WeatherRepository(cache)
    val matches = MatchRepository(db, settings, weather, cache)
    val teams = TeamRepository(db, cache)
    val tennis = com.matchday.app.data.repo.TennisRepository(cache, teams)
    val oneXBet = OneXBetRepository(cache, settings).also { ox -> matches.tennisOddsProvider = { ox.tennisMatchOdds() } }
    val dailyPicks = com.matchday.app.data.repo.DailyPicksRepository(context, matches, oneXBet, teams, tennis).also { dp ->
        matches.onFinished = { finished -> dp.settle(finished) }
    }
}

class MatchdayApp : Application() {
    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannel(this)
        appScope.launch {
            runCatching { container.settings.addNewDefaults("v3", com.matchday.app.domain.Catalog.NEW_DEFAULTS_V3) }
            runCatching { container.settings.addNewDefaults("v4", com.matchday.app.domain.Catalog.NEW_DEFAULTS_V4) }
            runCatching { container.settings.addNewDefaults("v5", com.matchday.app.domain.Catalog.NEW_DEFAULTS_V5) }
            runCatching { container.matches.tracker.refreshCalibration() }
            RefreshScheduler.schedule(this@MatchdayApp, container.settings.current().refreshMinutes)
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as MatchdayApp).container
