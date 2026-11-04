# Matchday – personal sports analysis app (Android)

Kotlin · Jetpack Compose · Material 3 · MVVM · Retrofit · Room · WorkManager

Daily fixtures, live scores, real bookmaker odds, transparent predictions and **Today's Best Picks**. It covers football (Champions League, top European leagues, more), NBA, NFL, MLB, NHL and tennis. Dark theme by default; 

**No account, sign-up or API key is needed.** Install it and it works.


## Install

1. Open the repository's **Actions** tab, then the latest green **Build APK** run.
2. Download the **matchday-apk** artifact (a zip).
3. Open the zip and tap `app-debug.apk`. Allow "install unknown apps" when asked.

Every push to `main` builds a new APK automatically.

## Data sources (all free, no keys)

| Source | Provides |
|---|---|
| ESPN public site feed (`site.api.espn.com`) | Fixtures, live scores and status, team logos, venues, league tables, team form, last 5 matches, head-to-head, line-ups, injury reports (NBA/NFL/NHL/MLB), bookmaker odds (DraftKings: moneyline/1X2, totals, spread, with opening and current price) |
| Open-Meteo | Kick-off weather |

**Limits:**
- ESPN's feed is public but undocumented. The app tolerates missing fields, but ESPN could change it without notice.
- Odds come from **one bookmaker** per match, so there is no multi-bookmaker price comparison. Opening vs current price is shown instead.
- **Football:** no injury lists (line-ups appear about an hour before kick-off). **Tennis:** fixtures and scores only, with no odds or predictions.
- **Not available:** both-teams-to-score and double-chance prices (our probabilities for them are shown); travel and fatigue modelling.

## How predictions work (`domain/PredictionEngine.kt`)

1. **Market:** the bookmaker's prices with the margin removed proportionally.
2. **Model (football):** a Poisson goals model with Dixon-Coles low-score correction.
   - Attack and defence strengths come from league-table goals for/against, shrunk towards the league average when samples are small.
   - A standard home-advantage factor is applied.
   - Recent form adjusts expected goals by up to ±7%.
3. **Blend:** the model gets 15–45% of the weight depending on data quality; the market keeps the majority. The match page shows Model / Market / Final.
4. **Value:** probability × price − 1 ≥ +3%. With a single bookmaker, value only appears where the football model disagrees with the market.

**Confidence** combines the top outcome's probability, data completeness and model/market agreement. Sports without a model are capped at Medium.

## Architecture

```
data/remote   Retrofit services + DTOs (ESPN, Open-Meteo)
data/local    Room (matches incl. odds snapshot, favourites, JSON cache, sent alerts) + DataStore settings
data/repo     MatchRepository (refresh/observe/detail), EspnMapper (feed -> models, odds conversion), Weather, JsonCache
domain        Competition catalog, models, TeamMatcher, PredictionEngine, BestPicks
ui            Compose screens + ViewModels (MVVM): Home, Predictions, Live, Favourites, Settings, Match detail
work          WorkManager hourly refresh + alerts (odds moves, line-ups, injuries, pre-kick-off predictions)
```

**Caching:** scoreboards refresh at the chosen interval (default 60 min), on pull-to-refresh, and every 2 min on the Live tab for games in play. League tables are cached for 6 h; match details for 5 min near kick-off and 3 h otherwise.
