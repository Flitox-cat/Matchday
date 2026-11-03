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
import com.matchday.app.domain.MatchItem
import com.matchday.app.domain.MatchStatus
import com.matchday.app.domain.SportType
import com.matchday.app.ui.Formats
import com.matchday.app.ui.zone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

object RefreshScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Hourly background job: refreshes data (respecting the interval) and sends alerts. */
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

    fun scheduleLineupCheck(context: Context, matchId: String, kickoff: Long, attempt: Int = 0) {
        val target = kickoff - 45 * 60_000L + attempt * 15 * 60_000L
        val delay = max(0L, target - System.currentTimeMillis())
        val req = OneTimeWorkRequestBuilder<LineupWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .setInputData(workDataOf("id" to matchId, "kickoff" to kickoff, "attempt" to attempt))
            .build()
        val policy = if (attempt == 0) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork("lineup_$matchId", policy, req)
    }
}

/** Sends each enabled alert at most once (tracked in Room). */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val s = c.settings.current()
        val before = c.matches.oddsSnapshot()
        c.matches.refresh(force = false)
        c.matches.loadContext()
        val after = c.matches.oddsSnapshot()

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

            // Upcoming match + pre-kick-off prediction (one combined alert)
            if ((s.notifyUpcoming || s.notifyPredictions) && untilKickoff in 0..(3 * hour)) {
                val p = m.prediction
                val title = "${m.homeTeam} v ${m.awayTeam} · ${Formats.time(m.kickoff, zone)}"
                val text = buildString {
                    append(m.competition.title)
                    if (s.notifyPredictions && p != null) {
                        append(" · Our estimate: ${p.mostLikely.label} ${Formats.pct(p.mostLikely.probability)}")
                        p.predictedScore?.let { append(" (predicted $it)") }
                        append(", ${p.confidence.name.lowercase()} confidence.")
                        p.bestValue?.let { append(" Value: ${it.label} @ ${Formats.odds(it.odds)}.") }
                    }
                }
                once("pre:${m.id}") { Notifications.show(applicationContext, "pre:${m.id}", title, text) }
            }

            // Odds movement since the last check
            if (s.notifyOdds && untilKickoff in 0..(48 * hour)) {
                val old = before[m.id]
                val new = after[m.id]
                if (old != null && new != null) {
                    val pairs = listOf(
                        Triple(m.homeTeam, old.homeClose, new.homeClose),
                        Triple("Draw", old.drawClose, new.drawClose),
                        Triple(m.awayTeam, old.awayClose, new.awayClose),
                    )
                    for ((label, o, n) in pairs) {
                        if (o == null || n == null || o <= 0) continue
                        val change = (n - o) / o
                        if (abs(change) * 100 >= s.oddsMoveThreshold) {
                            val key = "odds:${m.id}:$label:${Formats.odds(n)}"
                            val dir = if (change < 0) "shortened" else "drifted"
                            once(key) {
                                Notifications.show(
                                    applicationContext, key,
                                    "Odds move: ${m.homeTeam} v ${m.awayTeam}",
                                    "$label $dir ${Formats.odds(o)} → ${Formats.odds(n)} (${String.format("%+.0f%%", change * 100)}) at ${new.provider}.",
                                )
                            }
                        }
                    }
                }
            }

            if (!m.favourite) continue

            // Confirmed line-ups: precise one-off check ~45 min before kick-off
            if (s.notifyLineups && untilKickoff in 0..(2 * hour) &&
                (m.competition.sport == SportType.FOOTBALL || m.competition.sport == SportType.HOCKEY)
            ) {
                RefreshScheduler.scheduleLineupCheck(applicationContext, m.id, m.kickoff)
            }

            // Injury report on match day (sports where the feed has one)
            if (s.notifyInjuries && untilKickoff in 0..(24 * hour) && m.competition.sport != SportType.FOOTBALL &&
                m.competition.sport != SportType.TENNIS
            ) {
                val x = c.matches.loadDetail(m).last()
                val inj = x.injuries
                if (inj.known) {
                    val count = inj.homeOut.size + inj.homeDoubtful.size + inj.awayOut.size + inj.awayDoubtful.size
                    once("inj:${m.id}:$count") {
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
        val kickoff = inputData.getLong("kickoff", 0L)
        val attempt = inputData.getInt("attempt", 0)
        val key = "lineup:$id"
        if (c.db.notified().count(key) > 0) return Result.success()

        val item: MatchItem = c.matches.observeMatches().first().firstOrNull { it.id == id } ?: return Result.success()
        val lineups = c.matches.lineupsNow(item)
        if (lineups.isNotEmpty()) {
            val text = lineups.joinToString("\n") { l ->
                "${l.team}${l.formation?.let { " ($it)" } ?: ""}: " + l.starters.joinToString(", ") { it.name }
            }
            Notifications.show(applicationContext, key, "Line-ups: ${item.homeTeam} v ${item.awayTeam}", text)
            c.db.notified().insert(NotifiedEntity(key, System.currentTimeMillis()))
        } else if (attempt < 2) {
            RefreshScheduler.scheduleLineupCheck(applicationContext, id, kickoff, attempt + 1)
        }
        return Result.success()
    }
}
