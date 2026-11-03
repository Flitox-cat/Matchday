# Matchday – personal sports analysis app (Android)

Kotlin · Jetpack Compose · Material 3 · MVVM · Retrofit · Room · WorkManager

Daily fixtures across football, basketball, tennis and baseball, with **real bookmaker odds**, a **transparent prediction engine**, ranked **Today's Best Picks**, live scores, favourites and notifications. Dark theme by default; times in Qatar time (AST, UTC+3).

> Estimates, not guarantees. No bet is ever described as safe or risk-free. Gambling is illegal in some countries, including Qatar; check your local law.

---

## 1. Get the APK (no computer needed)

This project has **not been compiled yet**. The environment it was written in could not reach Google's Android build servers. GitHub builds it for free instead:

1. Create a free account at github.com and make a new **empty repository** (for example `matchday`).
2. Put this project in it, either way:
   - easiest: tell Claude the repository name and it can push the code for you; or
   - from a computer: unzip, then `git init && git add . && git commit -m init && git push`.
3. Open the repository's **Actions** tab. **Build APK** runs automatically and takes about 5 minutes. Use **Run workflow** to start it manually.
4. Open the finished run, download the **matchday-apk** artifact (a zip containing `app-debug.apk`), and install it on your phone. Allow "install unknown apps" when asked.

If a build fails, open the failed step, copy the red error lines, and send them to Claude to fix.

**From a computer instead:** open the folder in Android Studio (Ladybug or newer) and press **Run**.

Each GitHub build signs the APK with a new debug key. To install a newer build, uninstall the old app first. Your favourites and settings will be lost.

---

## 2. API keys (enter them in the app: Settings → Data sources)

| Service | Used for | Free plan | Paid | Required? |
|---|---|---|---|---|
| **The Odds API** – the-odds-api.com | Fixtures, bookmaker odds (1X2, totals, spreads), scores, all sports | 500 credits/month | Higher tiers on their pricing page | **Yes** |
| **football-data.org** | League tables, home/away splits, form, head-to-head | 12 competitions (CL, PL, La Liga, Bundesliga, Serie A, Ligue 1, Eredivisie, Primeira Liga, Championship, Brasileirão, World Cup, EURO), 10 requests/min | Paid tiers on their site | Recommended |
| **API-Football** – api-football.com | Injuries/suspensions, confirmed line-ups, venue, live minute, team logos | 100 requests/day | Pro $19/mo · Ultra $29/mo · Mega $39/mo | Optional |
| **Open-Meteo** | Kick-off weather | Free, no key | – | Automatic |

**Getting the keys**
- **The Odds API:** sign up with your email on the-odds-api.com. The key arrives by email.
- **football-data.org:** register at football-data.org/client/register. The key arrives by email.
- **API-Football:** register at dashboard.api-football.com. The key is on the dashboard. API-Football has limited current-season data on its free plan in the past; if injuries show as unavailable, that is the likely reason.

**How keys are stored:** keys live only in app-private storage (DataStore) on your phone and are sent only to their own provider. Developers can instead put `ODDS_API_KEY=…`, `FOOTBALL_DATA_KEY=…` and `API_FOOTBALL_KEY=…` in `local.properties`, or add them as GitHub repository secrets. Either way they are baked into the build, and a key typed in Settings always takes priority.

### Staying inside the free odds budget
One odds refresh of a competition costs one credit per market: football 2, NBA 3, MLB 2, tennis 1 per active tournament. Scores cost 2 credits, and only when games are under way. The Settings screen shows credits left and an estimate of monthly use.

The defaults are Champions League, Premier League and La Liga, refreshed every 12 hours. That is about 360 credits a month plus scores, which fits the free plan and leaves room for manual refreshes. Adding the Europa League, NBA or a shorter interval quickly needs a paid plan; the Settings estimate turns orange when you go over 500.

The app never invents prices: if the quota runs out it shows cached odds or N/A. Alerts are checked hourly from cached data, so they cost no credits.

---

## 3. How the predictions work

There is no black-box "AI score" and no random numbers. The engine (`domain/PredictionEngine.kt`) works in four steps:

1. **Market:** each bookmaker's prices are de-vigged (the margin is removed proportionally) and averaged into consensus probabilities.
2. **Model (football):** a Poisson goals model with Dixon-Coles low-score correction. Inputs:
   - attack and defence strengths from goals for/against, using home/away splits when a side has 4+ such games;
   - shrinkage towards the league average when samples are small;
   - a recent-form adjustment of up to ±7%;
   - reported absences: −2% expected goals per confirmed absentee, −0.8% per doubt, capped at −10%.
3. **Blend:** the model gets 15–45% of the weight, growing with how much data it has; the market keeps the rest. Markets are hard to beat, so they always keep the majority. The screen shows Model / Market / Final side by side.
4. **Value:** `probability × best real price − 1`. A selection becomes a value pick only when this is at least +3% and the probability is at least 20%.

**Confidence** combines the likeliest outcome's probability, data completeness (bookmakers, statistics, injuries) and how closely model and market agree. Sports without a model (basketball, tennis, baseball) are capped at Medium.

**Today's Best Picks** (next 48 h) shows:
- the best overall selection;
- safest-looking selections (probability ≥ 62%; flagged when the price offers little value);
- best value;
- high-risk value (odds ≥ 3.5);
- matches to avoid (low confidence, missing data, or model/market disagreement).

**Honest limitations**
- **BTTS and double chance:** probabilities are shown, but bookmaker prices are **N/A**. The Odds API's bulk feed doesn't include those markets.
- **Europa League and other competitions outside football-data's free plan:** predictions use market consensus only.
- **Absences:** the model counts them but can't tell a star from a squad player.
- **Travel distance and fatigue:** not modelled; no reliable free source.
- **Basketball, tennis and baseball:** market consensus only, plus line-shopping value (best price vs consensus).

---

## 4. Features map

| Requirement | Where |
|---|---|
| **Home:** live, today, tomorrow, upcoming popular, recently completed; filters by sport, competition, date, popularity | `ui/home` |
| **Match page tabs** | `ui/detail` |
| ↳ Overview | probabilities, predicted score, expected goals, over/under, BTTS, double chance, best bet with reasons, confidence |
| ↳ Odds | every bookmaker, best price, previous price, movement arrows, last update, our fair odds vs best price |
| ↳ Teams | table position, home/away record, goals, last 10 matches, head-to-head |
| ↳ Squad | injuries, suspensions, doubts, confirmed XI, impact note |
| ↳ Conditions | stadium, kick-off weather, home advantage |
| Today's Best Picks | `ui/predictions`, `domain/BestPicks.kt` |
| Live (auto-refresh every 2 min while open) | `ui/live` |
| Favourites (teams and competitions) | `ui/favourites` |
| **Settings:** keys, competitions, refresh interval, notifications, odds-move threshold, time zone, theme, credit usage | `ui/settings` |
| **Notifications:** pre-kick-off prediction, odds moves, confirmed line-ups, injury updates | `work/Workers.kt` |

Notifications are generated on the phone by a background job (WorkManager), so no Firebase or server is needed. Firebase Cloud Messaging only matters when a server pushes messages, and this app has none.

---

## 5. Architecture

```
data/remote   Retrofit services + DTOs (The Odds API, football-data.org, API-Football, Open-Meteo)
data/local    Room (events, odds snapshots, scores, favourites, JSON cache, sent alerts) + DataStore settings
data/repo     Repositories: MatchRepository (refresh/observe/detail), FootballData, ApiFootball, Weather, OddsMapper
domain        Catalog of competitions, models, TeamMatcher (cross-provider names), PredictionEngine, BestPicks
ui            Compose screens + ViewModels (MVVM), theme, shared components
work          WorkManager refresh + alert workers, notifications
```

**Caching**
- Odds are refreshed per competition only after the chosen interval.
- League tables and results are cached for 6 h, team histories 12 h, head-to-head 24 h, injuries 4 h, line-ups 10 min and weather 3 h.
- football-data.org calls are rate-limited to under 10 per minute.
- When a refresh fails, the app shows the last good data.

**Error handling:** missing keys, invalid keys (401), quota exhaustion (429) and unsupported markets (422, retried as 1X2 only) are shown as banners, not crashes.
