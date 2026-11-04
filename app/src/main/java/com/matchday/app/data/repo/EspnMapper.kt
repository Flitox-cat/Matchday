package com.matchday.app.data.repo

import com.matchday.app.data.local.MatchEntity
import com.matchday.app.data.local.OddsSnap
import com.matchday.app.data.remote.EComp
import com.matchday.app.data.remote.ECompetitor
import com.matchday.app.data.remote.EEvent
import com.matchday.app.data.remote.ELastFive
import com.matchday.app.data.remote.EOdds
import com.matchday.app.data.remote.ERoster
import com.matchday.app.data.remote.ESeries
import com.matchday.app.data.remote.EStandings
import com.matchday.app.data.remote.EStatus
import com.matchday.app.domain.Competition
import com.matchday.app.domain.LeagueAverages
import com.matchday.app.domain.LineupInfo
import com.matchday.app.domain.LineupPlayer
import com.matchday.app.domain.MarketOdds
import com.matchday.app.domain.OddsSummary
import com.matchday.app.domain.OutcomeOdds
import com.matchday.app.domain.PriceQuote
import com.matchday.app.domain.RecentResult
import com.matchday.app.domain.SportType
import com.matchday.app.domain.TeamMatcher
import com.matchday.app.domain.TeamStats
import java.time.Instant
import java.time.OffsetDateTime

/** One row of a league table from ESPN's standings feed. */
data class TableRow(
    val teamId: String?,
    val name: String,
    val logo: String?,
    val rank: Int,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val points: Int,
)

data class LeagueTable(val leagueKey: String, val rows: List<TableRow>, val fetchedAt: Long) {
    val averages: LeagueAverages?
        get() {
            val gp = rows.sumOf { it.played }
            if (gp < 10) return null
            val perTeamGame = rows.sumOf { it.goalsFor }.toDouble() / gp
            return LeagueAverages(perTeamGame * 1.12, perTeamGame * 0.88)
        }

    fun row(teamId: String?, name: String): TableRow? =
        rows.firstOrNull { teamId != null && it.teamId == teamId } ?: TeamMatcher.best(name, rows, { it.name })
}

object EspnMapper {

    fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try { Instant.parse(s).toEpochMilli() } catch (_: Exception) { 0L }
        }
    }

    /** American odds ("+650", "-450", "EVEN") to decimal odds. */
    fun american(s: String?): Double? {
        val t = s?.trim()?.uppercase() ?: return null
        if (t == "EVEN" || t == "EV") return 2.0
        return american(t.removePrefix("+").toDoubleOrNull())
    }

    fun american(v: Double?): Double? = when {
        v == null || v == 0.0 -> null
        v > 0 -> 1 + v / 100.0
        else -> 1 + 100.0 / -v
    }

    private fun lineNumber(line: String?): Double? =
        line?.trim()?.trimStart('o', 'u', 'O', 'U')?.toDoubleOrNull()

    fun odds(o: EOdds?, now: Long): OddsSnap? {
        if (o == null) return null
        val ml = o.moneyline
        val snap = OddsSnap(
            provider = o.provider?.displayName ?: o.provider?.name ?: "Bookmaker",
            homeOpen = american(ml?.home?.open?.odds),
            homeClose = american(ml?.home?.close?.odds) ?: american(o.homeTeamOdds?.moneyLine),
            drawOpen = american(ml?.draw?.open?.odds),
            drawClose = american(ml?.draw?.close?.odds) ?: american(o.drawOdds?.moneyLine),
            awayOpen = american(ml?.away?.open?.odds),
            awayClose = american(ml?.away?.close?.odds) ?: american(o.awayTeamOdds?.moneyLine),
            totalLine = o.overUnder ?: lineNumber(o.total?.over?.close?.line),
            overOpen = american(o.total?.over?.open?.odds),
            overClose = american(o.total?.over?.close?.odds) ?: american(o.overOdds),
            underOpen = american(o.total?.under?.open?.odds),
            underClose = american(o.total?.under?.close?.odds) ?: american(o.underOdds),
            spreadHomeLine = o.pointSpread?.home?.close?.line,
            spreadHomeOdds = american(o.pointSpread?.home?.close?.odds),
            spreadAwayLine = o.pointSpread?.away?.close?.line,
            spreadAwayOdds = american(o.pointSpread?.away?.close?.odds),
            fetchedAt = now,
        )
        val anyPrice = listOf(snap.homeClose, snap.awayClose, snap.overClose, snap.spreadHomeOdds).any { it != null }
        return if (anyPrice) snap else null
    }

    private fun stateOf(status: EStatus?): String = status?.type?.state ?: if (status?.type?.completed == true) "post" else "pre"

    private fun name(c: ECompetitor?): String? = c?.team?.displayName ?: c?.athlete?.displayName

    private fun tennisScore(c: ECompetitor): String? {
        if (c.linescores.isEmpty()) return null
        return c.linescores.count { it.winner == true }.toString()
    }

    /** Converts a scoreboard event into stored matches (tennis events hold many matches). */
    fun matches(comp: Competition, event: EEvent, leagueLogo: String?, now: Long): List<MatchEntity> {
        val list = mutableListOf<MatchEntity>()
        if (comp.sport == SportType.TENNIS) {
            event.groupings
                .filter { (it.grouping?.displayName ?: "").contains("Singles", ignoreCase = true) }
                .forEach { g -> g.competitions.forEach { c -> entity(comp, event, c, leagueLogo, now, tennis = true)?.let { list += it } } }
        } else {
            event.competitions.firstOrNull()?.let { c -> entity(comp, event, c, leagueLogo, now, tennis = false)?.let { list += it } }
        }
        return list
    }

    private fun entity(comp: Competition, event: EEvent, c: EComp, leagueLogo: String?, now: Long, tennis: Boolean): MatchEntity? {
        val home = c.competitors.firstOrNull { it.homeAway == "home" } ?: c.competitors.getOrNull(0) ?: return null
        val away = c.competitors.firstOrNull { it.homeAway == "away" } ?: c.competitors.getOrNull(1) ?: return null
        val homeName = name(home) ?: return null
        val awayName = name(away) ?: return null
        val status = c.status ?: event.status
        val state = stateOf(status)
        val rawId = (if (tennis) c.id else event.id) ?: return null
        val venue = c.venue ?: event.venue
        val venueName = listOfNotNull(venue?.fullName, venue?.court).joinToString(" · ").ifBlank { null }
        return MatchEntity(
            id = "${comp.espnSport}-$rawId",
            eventId = rawId,
            leagueKey = comp.key,
            leagueLogo = leagueLogo,
            homeId = home.team?.id,
            awayId = away.team?.id,
            homeTeam = homeName,
            awayTeam = awayName,
            homeLogo = home.team?.logo,
            awayLogo = away.team?.logo,
            kickoff = parseTime(c.date ?: event.date),
            state = state,
            statusDetail = status?.type?.shortDetail ?: status?.type?.detail,
            homeScore = if (state == "pre") null else if (tennis) tennisScore(home) else home.scoreText,
            awayScore = if (state == "pre") null else if (tennis) tennisScore(away) else away.scoreText,
            homeForm = home.form,
            awayForm = away.form,
            homeRecord = home.records.firstOrNull()?.summary,
            awayRecord = away.records.firstOrNull()?.summary,
            venue = venueName,
            city = venue?.address?.city,
            oddsJson = odds(c.odds?.firstOrNull(), now)?.let { com.matchday.app.data.remote.Network.json.encodeToString(OddsSnap.serializer(), it) },
            updatedAt = now,
        )
    }

    fun table(leagueKey: String, s: EStandings, now: Long): LeagueTable? {
        val entries = (s.standings?.entries.orEmpty()) + s.children.flatMap { it.standings?.entries.orEmpty() }
        if (entries.isEmpty()) return null
        val rows = entries.mapIndexedNotNull { i, e ->
            val n = e.team?.displayName ?: return@mapIndexedNotNull null
            fun stat(vararg names: String): Int =
                e.stats.firstOrNull { st -> names.any { it.equals(st.name, true) } }?.value?.toInt() ?: 0
            val rank = stat("rank").takeIf { it > 0 } ?: (i + 1)
            TableRow(
                teamId = e.team?.id, name = n, logo = e.team?.logo, rank = rank,
                played = stat("gamesPlayed"), won = stat("wins"), drawn = stat("ties", "draws"), lost = stat("losses"),
                goalsFor = stat("pointsFor"), goalsAgainst = stat("pointsAgainst"), points = stat("points"),
            )
        }
        return LeagueTable(leagueKey, rows, now)
    }

    /** "WDLWW" (most recent first) -> results usable by the model's form adjustment. */
    fun formResults(form: String?): List<RecentResult> =
        form.orEmpty().uppercase().filter { it in "WDL" }.map { ch ->
            when (ch) {
                'W' -> RecentResult(0, "", false, 1, 0, null)
                'D' -> RecentResult(0, "", false, 0, 0, null)
                else -> RecentResult(0, "", false, 0, 1, null)
            }
        }

    fun teamStats(table: LeagueTable?, teamId: String?, name: String, logo: String?, form: String?): TeamStats? {
        val r = table?.row(teamId, name) ?: return null
        return TeamStats(
            name = r.name, teamId = r.teamId ?: teamId, crest = r.logo ?: logo, position = r.rank,
            played = r.played, goalsFor = r.goalsFor, goalsAgainst = r.goalsAgainst, points = r.points,
            homePlayed = 0, homeFor = 0, homeAgainst = 0, awayPlayed = 0, awayFor = 0, awayAgainst = 0,
            recent = formResults(form),
        )
    }

    fun lastFive(list: List<ELastFive>, teamId: String?, name: String): List<RecentResult> {
        val block = list.firstOrNull { teamId != null && it.team?.id == teamId }
            ?: list.firstOrNull { TeamMatcher.same(it.team?.displayName ?: "", name) }
            ?: return emptyList()
        val id = block.team?.id
        return block.events.mapNotNull { e ->
            val hs = goals(e.homeTeamScore) ?: return@mapNotNull null
            val aws = goals(e.awayTeamScore) ?: return@mapNotNull null
            val isHome = id != null && e.homeTeamId == id
            RecentResult(
                date = parseTime(e.gameDate),
                opponent = e.opponent?.displayName ?: "?",
                home = isHome,
                goalsFor = if (isHome) hs else aws,
                goalsAgainst = if (isHome) aws else hs,
                competition = e.leagueName ?: e.competitionName,
            )
        }.sortedByDescending { it.date }
    }

    /** Previous meetings, from the home side's point of view. */
    fun headToHead(series: List<ESeries>, homeId: String?, homeName: String): List<RecentResult> =
        series.flatMap { it.events }.distinctBy { it.id }.mapNotNull { e ->
            val me = e.competitors.firstOrNull { (homeId != null && it.team?.id == homeId) || TeamMatcher.same(it.team?.displayName ?: "", homeName) }
                ?: return@mapNotNull null
            val opp = e.competitors.firstOrNull { it !== me } ?: return@mapNotNull null
            val gf = goals(me.score) ?: return@mapNotNull null
            val ga = goals(opp.score) ?: return@mapNotNull null
            RecentResult(parseTime(e.date), opp.team?.displayName ?: "?", me.homeAway == "home", gf, ga, e.competitionName)
        }.sortedByDescending { it.date }

    private val leadingInt = Regex("^\\s*(\\d+)")

    /** "2", "2 (4)" or "2.0" -> 2; null when the score is missing. */
    fun goals(text: String?): Int? = text?.let { leadingInt.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Completed games from a team schedule, from that team's point of view. */
    fun scheduleResults(s: com.matchday.app.data.remote.ESchedule?, teamId: String, fallbackCompetition: String?): List<RecentResult> =
        s?.events.orEmpty().mapNotNull { e ->
            val c = e.competitions.firstOrNull() ?: return@mapNotNull null
            val st = c.status ?: e.status
            val done = st?.type?.completed == true || st?.type?.state == "post"
            if (!done) return@mapNotNull null
            val me = c.competitors.firstOrNull { it.team?.id == teamId } ?: return@mapNotNull null
            val opp = c.competitors.firstOrNull { it !== me } ?: return@mapNotNull null
            val gf = goals(me.scoreText) ?: return@mapNotNull null
            val ga = goals(opp.scoreText) ?: return@mapNotNull null
            RecentResult(
                date = parseTime(c.date ?: e.date),
                opponent = opp.team?.displayName ?: "?",
                home = me.homeAway == "home",
                goalsFor = gf,
                goalsAgainst = ga,
                competition = e.season?.displayName ?: fallbackCompetition,
                venue = (c.venue ?: e.venue)?.fullName,
            )
        }.sortedByDescending { it.date }

    fun lineups(rosters: List<ERoster>): List<LineupInfo> = rosters.mapNotNull { r ->
        if (r.roster.isEmpty()) return@mapNotNull null
        fun p(e: com.matchday.app.data.remote.ERosterEntry) = LineupPlayer(
            name = e.athlete?.displayName ?: "?",
            number = e.jersey,
            position = e.position?.abbreviation ?: e.athlete?.position?.abbreviation,
        )
        LineupInfo(
            team = r.team?.displayName ?: "",
            logo = r.team?.logo,
            formation = r.formation,
            starters = r.roster.filter { it.starter == true }.map { p(it) },
            bench = r.roster.filter { it.starter != true }.map { p(it) },
        )
    }.filter { it.starters.isNotEmpty() }

    // -----------------------------------------------------------------------
    // Odds for display / the prediction engine
    // -----------------------------------------------------------------------

    fun summary(s: OddsSnap?, home: String, away: String, sport: SportType, hasDraw: Boolean): OddsSummary? {
        if (s == null) return null
        fun outcome(name: String, label: String, point: Double?, close: Double?, open: Double?, prob: Double?): OutcomeOdds? =
            close?.let { OutcomeOdds(name, label, point, listOf(PriceQuote(s.provider, it, open, s.fetchedAt)), prob) }

        val hInv = s.homeClose?.let { 1 / it }
        val aInv = s.awayClose?.let { 1 / it }
        val dInv = if (hasDraw) s.drawClose?.let { 1 / it } else null
        val sum = (hInv ?: 0.0) + (aInv ?: 0.0) + (dInv ?: 0.0)
        val complete = hInv != null && aInv != null && (!hasDraw || dInv != null)
        fun p(x: Double?) = if (complete && x != null && sum > 0) x / sum else null

        val homeO = outcome(home, home, null, s.homeClose, s.homeOpen, p(hInv))
        val drawO = if (hasDraw) outcome("Draw", "Draw", null, s.drawClose, s.drawOpen, p(dInv)) else null
        val awayO = outcome(away, away, null, s.awayClose, s.awayOpen, p(aInv))

        val oInv = s.overClose?.let { 1 / it }
        val uInv = s.underClose?.let { 1 / it }
        val tSum = (oInv ?: 0.0) + (uInv ?: 0.0)
        val lineTxt = s.totalLine?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: ""
        val overO = outcome("Over", "Over $lineTxt", s.totalLine, s.overClose, s.overOpen,
            if (oInv != null && uInv != null) oInv / tSum else null)
        val underO = outcome("Under", "Under $lineTxt", s.totalLine, s.underClose, s.underOpen,
            if (oInv != null && uInv != null) uInv / tSum else null)

        val shInv = s.spreadHomeOdds?.let { 1 / it }
        val saInv = s.spreadAwayOdds?.let { 1 / it }
        val sSum = (shInv ?: 0.0) + (saInv ?: 0.0)
        val spreadHome = outcome(home, "$home ${s.spreadHomeLine ?: ""}", null, s.spreadHomeOdds, null,
            if (shInv != null && saInv != null) shInv / sSum else null)
        val spreadAway = outcome(away, "$away ${s.spreadAwayLine ?: ""}", null, s.spreadAwayOdds, null,
            if (shInv != null && saInv != null) saInv / sSum else null)

        val football = sport == SportType.FOOTBALL
        val markets = listOfNotNull(
            listOfNotNull(homeO, drawO, awayO).takeIf { it.isNotEmpty() }
                ?.let { MarketOdds("h2h", if (football) "Match result (1X2)" else "Winner (moneyline)", it) },
            listOfNotNull(overO, underO).takeIf { it.isNotEmpty() }
                ?.let { MarketOdds("totals", if (football) "Total goals (over/under)" else "Total points (over/under)", it) },
            listOfNotNull(spreadHome, spreadAway).takeIf { it.isNotEmpty() }
                ?.let { MarketOdds("spreads", if (football) "Goal handicap" else "Point spread", it) },
        )
        return OddsSummary(
            home = homeO, draw = drawO, away = awayO,
            totalsLine = if (overO != null && underO != null) s.totalLine else null,
            over = overO, under = underO,
            markets = markets, bookmakerCount = 1,
            fetchedAt = s.fetchedAt, previousFetchedAt = null,
        )
    }
}
