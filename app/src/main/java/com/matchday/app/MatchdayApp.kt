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
    val oneXBet = OneXBetRepository(cache, settings)
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
            RefreshScheduler.schedule(this@MatchdayApp, container.settings.current().refreshMinutes)
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as MatchdayApp).container
