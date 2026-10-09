# Footor

A free and open-source Android app for football (soccer), in the spirit of FotMob and Sofascore, with one
priority above everything else: **the live data on screen must be right**. Goals, scores, match statistics,
lineups, tables, teams and players.

No accounts, no ads, no analytics, no Google Play Services. Licensed under GPL-3.0.

## Status

Early but usable:

- Scoreboard for any day (±3 days), grouped by competition, live matches first, "Live now" filter
- Match screen: header with live clock, timeline (goals, assists, penalties, cards, subs, VAR),
  statistics (possession, xG, shots, corners, passes…), lineups, league table
- Team pages (profile, recent/upcoming matches, squad) and player pages (season stats per competition)
- In-app announcements for goals, VAR-cancelled goals, red cards, half-time and full-time
- Demo mode with a fictional match played in real time, so the app works with no API key

## Where the data comes from

An app can only be as accurate as its feed. Footor doesn't run servers or scrape other apps (that's
fragile and violates their terms). It talks to providers through one interface, `FootballDataSource`.

The first provider is **[API-Football](https://www.api-football.com/)**. It has live scores, events, xG,
lineups, standings and player stats for 1,000+ competitions. You use your own key, set in
*Settings*. The free plan gives 100 requests a day, which is enough to follow a match or two closely.
Paid plans raise that limit.

New providers only need to implement `FootballDataSource`. Good candidates are
[football-data.org](https://www.football-data.org/) and [OpenLigaDB](https://www.openligadb.de/).

## How Footor keeps live data accurate

All of this is in `core/` and covered by unit tests.

1. **Stale snapshots are rejected** (`MatchReconciler`). CDNs and load balancers sometimes return a
   response older than the one already shown. Footor puts each snapshot on the match timeline (period,
   then minute including stoppage time). Anything that would move the match backwards is dropped, so a
   goal never "disappears" for a few seconds.
2. **Goals are shown immediately.** A stale response can only ever *lower* a score, so a higher score is
   always trusted, even if the provider's clock went backwards.
3. **Score reversals need evidence.** A lower score is accepted at once when a new VAR "goal cancelled"
   event explains it. Otherwise it's only accepted after two consecutive snapshots agree. One-off blips
   are never shown, and real corrections still appear within a poll or two.
4. **Polling matches the provider's own refresh rate** (`PollingPolicy`). That's every 15 s while live,
   30 s at half-time and 1 min near kick-off; otherwise Footor sleeps until the kick-off window. After
   full-time it refreshes a few more times to pick up corrections to scorers and stats, then stops.
   Polling more often than the provider updates can't make data fresher and only burns quota.
5. **One source of truth.** The scoreboard and the match screen share one reconciler, so they always
   show the same score.
6. **Failures are visible.** If a refresh fails, the last good data stays on screen with a banner saying
   how old it is. Retries use exponential backoff, with a longer backoff when rate-limited.
7. **Shared requests and caching.** Identical concurrent requests share one network call (`TtlCache`),
   and each endpoint has a TTL that fits how often its data changes.

## Project layout

```
core/   Pure Kotlin/JVM: models, provider interface, API-Football client, reconciler, polling, demo data
app/    Android app: Jetpack Compose + Material 3, manual DI (AppContainer), one ViewModel per screen
```

`core` doesn't depend on Android, so all the accuracy logic is tested with plain JVM tests.

## Building

Requirements: JDK 17+ and the Android SDK (API 35).

```sh
./gradlew :core:test          # unit tests (no Android SDK needed for the code under test)
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
```

CI builds the APK and runs tests and lint on every pull request.

## Roadmap

- Goal notifications for followed matches (a foreground service while a followed match is live, so
  there's no dependency on Firebase)
- Favourite teams and competitions, plus a "following" tab
- More providers, and cross-checking two providers to confirm goals
- Head-to-head, top scorers, match momentum/shot maps where the provider supports them
- Home-screen widget, translations, tablet layout
- F-Droid release (metadata already in `fastlane/`)

## Contributing

Issues and pull requests are welcome. Please keep `core` free of Android dependencies and add tests
for any change to how live data is reconciled.

## License

GPL-3.0-only. See [LICENSE](LICENSE). Team, competition and player data and images belong to their
respective owners and are served by the configured data provider.
