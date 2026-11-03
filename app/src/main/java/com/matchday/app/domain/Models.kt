package com.matchday.app.domain

/** One bookmaker's price for one outcome. Always a real price from The Odds API. */
data class PriceQuote(
    val bookmaker: String,
    val price: Double,
    val previous: Double?,   // same bookmaker's price at the previous refresh, if any
    val updatedAt: Long,     // bookmaker's own last-update time (epoch ms)
)

data class OutcomeOdds(
    val name: String,        // raw outcome name from the feed (team, "Draw", "Over", "Under")
    val label: String,       // display label
    val point: Double?,      // handicap / total line
    val quotes: List<PriceQuote>,
    val consensusProb: Double?, // de-vigged average across bookmakers
) {
    val best: PriceQuote? get() = quotes.maxByOrNull { it.price }
}

data class MarketOdds(val key: String, val title: String, val outcomes: List<OutcomeOdds>)

data class OddsSummary(
    val home: OutcomeOdds?,
    val draw: OutcomeOdds?,
    val away: OutcomeOdds?,
    val totalsLine: Double?,
    val over: OutcomeOdds?,
    val under: OutcomeOdds?,
    val markets: List<MarketOdds>,
    val bookmakerCount: Int,
    val fetchedAt: Long,
    val previousFetchedAt: Long?,
)

enum class MatchStatus { UPCOMING, LIVE, FINISHED }

data class MatchItem(
    val id: String,
    val competition: Competition,
    val homeTeam: String,
    val awayTeam: String,
    val kickoff: Long,
    val status: MatchStatus,
    val homeScore: String?,
    val awayScore: String?,
    val minute: Int?,
    val odds: OddsSummary?,
    val prediction: Prediction?,
    val homeCrest: String?,
    val awayCrest: String?,
    val venue: String?,
    val popular: Boolean,
    val favourite: Boolean,
)

// ---------------------------------------------------------------------------
// Prediction model types
// ---------------------------------------------------------------------------

data class Probs(val home: Double, val draw: Double?, val away: Double) {
    fun max(): Double = maxOf(home, away, draw ?: 0.0)
}

enum class Confidence { LOW, MEDIUM, HIGH }

data class Selection(
    val market: String,      // "1X2", "Totals"
    val label: String,       // e.g. "Arsenal", "Draw", "Over 2.5"
    val probability: Double, // our final probability
    val odds: Double?,       // best real bookmaker price, null = N/A
    val bookmaker: String?,
    val edge: Double?,       // probability * odds - 1
)

data class Prediction(
    val model: Probs?,                 // independent statistical model (football only)
    val market: Probs?,                // de-vigged bookmaker consensus
    val final: Probs,                  // blend used for everything below
    val modelWeight: Double,           // share of `final` that comes from the model
    val expectedHome: Double?,         // expected goals
    val expectedAway: Double?,
    val predictedScore: String?,
    val over15: Double?,
    val over25: Double?,
    val over35: Double?,
    val btts: Double?,
    val predictedWinner: String?,      // null when a draw is the single likeliest result
    val mostLikely: Selection,
    val bestValue: Selection?,
    val confidence: Confidence,
    val confidenceNotes: List<String>,
    val reasons: List<String>,
    val method: String,
    val completeness: Double,          // 0..1 share of the inputs that were available
    val disagreement: Double?,         // largest model-vs-market gap on any 1X2 outcome
) {
    val doubleChance1X get() = final.draw?.let { final.home + it }
    val doubleChanceX2 get() = final.draw?.let { final.away + it }
    val doubleChance12 get() = final.home + final.away
}

// ---------------------------------------------------------------------------
// Inputs for the football model
// ---------------------------------------------------------------------------

data class TeamStats(
    val name: String,
    val fdId: Int?,
    val crest: String?,
    val position: Int?,
    val played: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val points: Int,
    val homePlayed: Int,
    val homeFor: Int,
    val homeAgainst: Int,
    val awayPlayed: Int,
    val awayFor: Int,
    val awayAgainst: Int,
    val recent: List<RecentResult>, // newest first
)

data class RecentResult(
    val date: Long,
    val opponent: String,
    val home: Boolean,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val competition: String?,
) {
    val points: Int get() = when {
        goalsFor > goalsAgainst -> 3
        goalsFor == goalsAgainst -> 1
        else -> 0
    }
    val letter: Char get() = when (points) { 3 -> 'W'; 1 -> 'D'; else -> 'L' }
}

data class LeagueAverages(val homeGoals: Double, val awayGoals: Double)

data class InjuryInfo(
    val known: Boolean,
    val homeOut: List<String> = emptyList(),
    val homeDoubtful: List<String> = emptyList(),
    val awayOut: List<String> = emptyList(),
    val awayDoubtful: List<String> = emptyList(),
)

data class ModelInputs(
    val home: TeamStats?,
    val away: TeamStats?,
    val league: LeagueAverages?,
    val injuries: InjuryInfo,
)
