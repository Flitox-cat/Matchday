package com.matchday.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.matchday.app.container
import com.matchday.app.data.local.NotifiedEntity
import com.matchday.app.data.repo.OddsMapper
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.ui.Formats
import com.matchday.app.ui.zone
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

object RefreshScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * The background job runs hourly so alerts are timely. It only spends odds credits when the
     * user's refresh interval ([minutes]) has elapsed; otherwise it works from cached data.
     */
    @Suppress("UNUSED_PARAMETER")
    fun schedule(context: Context, minutes: Int) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(60L, TimeUnit.MINUTES)
            .setConstraints(network)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork("refresh_now", ExistingWorkPolicy.REPLACE, req)
    }

    fun scheduleLineupCheck(context: Context, item: MatchItem, attempt: Int = 0) {
        val target = item.kickoff - 45 * 60_000L + attempt * 15 * 60_000L
        val delay = max(0L, target - System.currentTimeMillis())
        val req = OneTimeWorkRequestBuilder<LineupWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .setInputData(
                workDataOf(
                    "id" to item.id, "home" to item.homeTeam, "away" to item.awayTeam,
                    "kickoff" to item.kickoff, "attempt" to attempt,
                ),
            )
            .build()
        val policy = if (attempt == 0) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork("lineup_${item.id}", policy, req)
    }
}

/**
 * Periodic background job: refreshes fixtures/odds (respecting the interval and credit budget),
 * then sends the alerts the user enabled. Every alert is sent at most once (tracked in Room).
 */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        if (s.effectiveOddsKey.isBlank()) return Result.success()

        c.matches.refresh(force = false)
        c.matches.loadContext()

        val now = System.currentTimeMillis()
        val zone = s.zone()
        val hour = 60 * 60_000L
        val items = c.matches.observeMatches().first()
            .filter { it.status == MatchStatus.UPCOMING && (it.favourite || it.competition.tier >= 9) }

        suspend fun once(key: String, send: () -> Unit) {
            if (c.db.notified().count(key) > 0) return
            send()
            c.db.notified().insert(NotifiedEntity(key, now))
        }

        for (m in items) {
            val untilKickoff = m.kickoff - now

            // 1 + 2: upcoming match and pre-kick-off prediction (one combined alert)
            if ((s.notifyUpcoming || s.notifyPredictions) && untilKickoff in 0..(3 * hour)) {
                val p = m.prediction
                val title = "${m.homeTeam} v ${m.awayTeam} · ${Formats.time(m.kickoff, zone)}"
                val text = buildString {
                    append(m.competition.title)
                    if (s.notifyPredictions && p != null) {
                        append(" · Our estimate: ${p.mostLikely.label} ${Formats.pct(p.mostLikely.probability)}")
                        p.predictedScore?.let { append(" (predicted $it)") }
                        append(", ${p.confidence.name.lowercase()} confidence.")
                        p.bestValue?.let { append(" Value: ${it.label} @ ${Formats.odds(it.odds)} (${it.bookmaker}).") }
                    }
                }
                once("pre:${m.id}") { Notifications.show(applicationContext, "pre:${m.id}", title, text) }
            }

            // 3: odds movement (average price across bookmakers vs previous refresh)
            if (s.notifyOdds && untilKickoff in 0..(48 * 3_600_000L)) {
                val odds = OddsMapper.summarize(c.db.odds().forEvent(m.id), m.homeTeam, m.awayTeam, m.competition.sport)
                if (odds != null && odds.previousFetchedAt != null) {
                    listOfNotNull(odds.home, odds.draw, odds.away).forEach { o ->
                        val nowAvg = o.quotes.map { it.price }.average()
                        val prevPrices = o.quotes.mapNotNull { it.previous }
                        if (prevPrices.size >= 2) {
                            val prevAvg = prevPrices.average()
                            val change = (nowAvg - prevAvg) / prevAvg
                            if (abs(change) * 100 >= s.oddsMoveThreshold) {
                                val key = "odds:${m.id}:${o.name}:${odds.fetchedAt}"
                                val dir = if (change < 0) "shortened" else "drifted"
                                once(key) {
                                    Notifications.show(
                                        applicationContext, key,
                                        "Odds move: ${m.homeTeam} v ${m.awayTeam}",
                                        "${o.label} $dir ${Formats.odds(prevAvg)} → ${Formats.odds(nowAvg)} " +
                                            "(${String.format("%+.0f%%", change * 100)}). Best now ${Formats.odds(o.best?.price)} at ${o.best?.bookmaker}.",
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (m.competition.sport != SportType.FOOTBALL || !m.favourite) continue

            // 4: confirmed line-ups -> precise one-off check ~45 min before kick-off
            if (s.notifyLineups && untilKickoff in 0..(2 * hour) && c.apiFootball.hasKey()) {
                RefreshScheduler.scheduleLineupCheck(applicationContext, m)
            }

            // 5: injury news on match day (first summary, then whenever the count changes)
            if (s.notifyInjuries && untilKickoff in 0..(24 * 3_600_000L) && c.apiFootball.hasKey()) {
                val date = Instant.ofEpochMilli(m.kickoff).atZone(ZoneOffset.UTC).toLocalDate().toString()
                val fx = c.apiFootball.find(c.apiFootball.fixtures(date), m.homeTeam, m.awayTeam, m.kickoff) ?: continue
                val inj = c.apiFootball.injuries(fx.fixture.id, fx.teams.home.id, fx.teams.away.id)
                if (inj.known) {
                    val count = inj.homeOut.size + inj.homeDoubtful.size + inj.awayOut.size + inj.awayDoubtful.size
                    val key = "inj:${m.id}:$count"
                    once(key) {
                        Notifications.show(
                            applicationContext, "inj:${m.id}",
                            "Team news: ${m.homeTeam} v ${m.awayTeam}",
                            "${m.homeTeam}: ${inj.homeOut.size} out, ${inj.homeDoubtful.size} doubtful. " +
                                "${m.awayTeam}: ${inj.awayOut.size} out, ${inj.awayDoubtful.size} doubtful.",
                        )
                    }
                }
            }
        }
        return Result.success()
    }
}

/** One-off check shortly before kick-off; retries twice, 15 minutes apart, if line-ups aren't out. */
class LineupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val id = inputData.getString("id") ?: return Result.success()
        val home = inputData.getString("home") ?: return Result.success()
        val away = inputData.getString("away") ?: return Result.success()
        val kickoff = inputData.getLong("kickoff", 0L)
        val attempt = inputData.getInt("attempt", 0)
        val key = "lineup:$id"
        if (c.db.notified().count(key) > 0) return Result.success()

        val date = Instant.ofEpochMilli(kickoff).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val fx = c.apiFootball.find(c.apiFootball.fixtures(date), home, away, kickoff) ?: return Result.success()
        val lineups = c.apiFootball.lineups(fx.fixture.id)
        if (lineups.isNotEmpty()) {
            val text = lineups.joinToString("\n") { l ->
                "${l.team.name} (${l.formation ?: "?"}): " + l.startXI.mapNotNull { it.player.name }.joinToString(", ")
            }
            Notifications.show(applicationContext, key, "Line-ups confirmed: $home v $away", text)
            c.db.notified().insert(NotifiedEntity(key, System.currentTimeMillis()))
        } else if (attempt < 2) {
            val item = MatchItem(
                id = id, competition = com.matchday.app.domain.Catalog.find("soccer_other"), homeTeam = home, awayTeam = away,
                kickoff = kickoff, status = MatchStatus.UPCOMING, homeScore = null, awayScore = null, minute = null,
                odds = null, prediction = null, homeCrest = null, awayCrest = null, venue = null, popular = false, favourite = true,
            )
            RefreshScheduler.scheduleLineupCheck(applicationContext, item, attempt + 1)
        }
        return Result.success()
    }
}
