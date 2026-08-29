# Life Insights

A personal Android app for tracking mood and energy and finding what actually drives them:
lagged sleep effects, screen use, day-of-week rhythms, and context tags.

Data stays on the device unless you turn on sync and point it at a server you run yourself. There
is no account, no analytics and no third party in either case. Patterns are descriptive only; this
is not medical or psychological advice.

Build brief: [`mood-energy-tracker-spec.md`](mood-energy-tracker-spec.md).

## Modules

| Module | What it is |
| --- | --- |
| `insights/` | The analysis engine. Plain Kotlin, **no Android dependencies**, so it can be tested against synthetic data with known structure long before months of real logging exist. |
| `sync-model/` | The wire format, the conflict-resolution rules and the server's decision logic. Plain Kotlin, compiled into **both** the phone and the server. |
| `server/` | Ktor + Postgres. Roughly 300 lines of SQL and routing on top of `sync-model`. |
| `app/` | Kotlin + Jetpack Compose + Room. Check-in, trends, insights and settings, plus screen-time collection and the sleep proxy. |

The Android modules are skipped when `LIFE_INSIGHTS_SERVER_ONLY=1` is set, so the server can be
built in a container with no Android SDK. It is an explicit switch rather than a check for whether
an SDK happens to exist, so a developer without one gets a clear error instead of a build that
quietly leaves the app out.

### Screen time and sleep

`UsageStatsManager` is read in exactly one place (`UsageStatsSource`). Everything that decides what
the numbers *mean* works on a plain `PhoneEvent` list and is unit-tested, because `UsageEvents.Event`
cannot be constructed in a test and the aggregation rules are where the mistakes would be.

Choices worth knowing about:

- **Screen time comes from foreground app sessions, not screen-on events.** `SCREEN_INTERACTIVE` is
  inconsistently reported across manufacturers; resumed/paused pairs are dependable. This
  undercounts slightly but does so consistently, which is what a correlation needs.
- **Days end at 04:00.** Scrolling at 01:00 belongs to the night that is ending, so it lands on the
  same row as the mood rating it relates to.
- **Pickups prefer unlocks, and fall back to screen-on** where the device has no lock screen, which
  emits no keyguard events at all.
- **Sleep is inferred from the absence of interaction**, and only from gaps bounded by real
  interaction on both sides. A silence that merely runs to the edge of the observation window is not
  evidence: without that rule, someone who checks their phone at 07:30 and ignores it until noon has
  that morning reported as sleep.
- **Nights the event history does not fully cover are skipped, not guessed.** Usage events expire
  after about a week and cannot be backfilled.
- **The proxy never overwrites a better source.** A wearable reading or a manual correction outranks
  it and survives re-aggregation.

Aggregation re-runs a trailing 7-day window on every app open as well as from a daily worker. Since
events expire, a worker silenced by an aggressive battery manager would otherwise mean permanent
data loss rather than a delay.

## Sync

Two-way sync against a server you deploy. Off by default; the address and token are set in Settings.

**The merge rules live in `sync-model` and both ends run the identical code.** The phone runs them
when applying a pulled row, the server when applying a pushed one. Two implementations of "which
version wins" would be free to disagree, and when they did, each end would keep its own answer,
push it, and never converge.

Three properties every rule has to hold:

- **Total.** Any two versions have a winner. Sync runs in the background with nobody to ask.
- **Commutative.** `merge(a, b) == merge(b, a)`. The two ends see the same pair in opposite orders.
- **Deterministic on ties.** "Prefer the incoming row" looks reasonable and is the trap: it is not
  commutative, so the server would keep the phone's version while the phone keeps the server's.
  Ties fall back to comparing row content, which is arbitrary but identical on both ends.

Decisions worth knowing about:

- **Check-ins carry a UUID, not the Room row id.** An autoincrement id is a per-database counter: a
  reinstall or a second phone mints the same number for a different entry. Migration 1 to 2
  backfills one per existing row, in SQL so the whole table is identified inside a single
  transaction.
- **Deletes are tombstones.** A removed row syncs as "nothing here", which the other end cannot tell
  from "you have not seen this yet", so the next pull would resurrect it.
- **Daily metrics merge per source group, not per row and not per field.** Each of usage, sleep and
  health carries its own timestamp. Whole-row last-writer-wins would let a phone without usage
  access erase the screen time another device recorded. Merging all twelve fields independently is
  worse and less obviously so: with one timestamp per row, a merged row claims `max(a, b)` for
  values that came from the older side, and that freshly stamped stale value then beats a genuinely
  newer one, so the answer depends on arrival order. A randomised order-independence test found it
  immediately.
- **Sleep is ranked by source before time.** Re-aggregation runs on every app open, so a proxy
  estimate is almost always the most recent write. Plain last-writer-wins would stomp a correction
  made by hand days earlier.
- **The client notices a rebuilt server.** A server restored from scratch restarts its sequence at
  zero. A phone holding the old cursor would see nothing above it, have nothing pending, and report
  successful syncs forever while transferring nothing. Comparing against the server's own counter
  catches it and re-offers everything.
- **Ordering is the server's sequence, never a timestamp.** Device clocks are not trustworthy
  enough. Timestamps decide conflicts only.
- **Writers take an advisory lock.** Postgres assigns sequence numbers at `nextval`, not at commit,
  so overlapping writers can commit out of order and a client polling in the gap would step over a
  row permanently.

### Running the server

```sh
SYNC_TOKEN=$(openssl rand -base64 32) docker compose up --build
```

That address is plain HTTP: fine on a home network, not fine over the internet. Deploy behind TLS,
or to a host that terminates it for you. The app shows a warning for an `http://` address rather
than refusing it, since a local server is a legitimate way to run this.

Configuration is all environment variables:

| Variable | Notes |
| --- | --- |
| `DATABASE_URL` | Either `jdbc:postgresql://...` or the `postgres://user:pass@host/db` form every managed host hands out. The second is converted; the driver rejects it as-is. |
| `SYNC_TOKEN` | At least 32 characters. **No default:** the server refuses to start without one rather than sitting on a public host serving someone's mood history to anyone who finds it. |
| `PORT` | Defaults to 8080. |

Deploying elsewhere is `docker build -f server/Dockerfile .` from the repository root, plus a
Postgres and those two variables. `GET /health` is unauthenticated for platform health checks.

The token lives in the app's private DataStore. Other apps cannot read it; a rooted phone or a
full-device backup could. Rotating it on the server is the remedy.

## Why the engine is a separate, dependency-free module

Correlating a few dozen daily metrics is easy to do and easy to do wrong. The engine is isolated
precisely so its claims can be checked against data whose true answer is known.

Four things it does deliberately:

1. **Correlations run on raw daily values, never on rolling averages.** Smoothing two independent
   random walks makes them look strongly related (Slutsky-Yule). Rolling means exist for charts only.
2. **Both series are residualised** on a weekday/weekend indicator and a ~month-long local trend, so
   "weekends move everything at once" and slow life drift do not masquerade as a relationship
   between two specific metrics.
3. **Confidence comes from resampling that preserves the data's structure** - a moving-block
   bootstrap for intervals, and a null built by circularly shifting one series (correlations) or
   block-permuting the outcome (group comparisons). Shuffling would destroy the day-to-day
   autocorrelation and produce a null far too narrow for self-tracking data.
4. **Every relationship examined counts towards a multiplicity correction**, including each lag
   tried. A typical run tests ~86 relationships. Reporting the best of four lags at face value is
   how noise gets promoted to insight.

Findings are gated per analysis (14 days for same-day, 21 for lagged, 42 for day-of-week), ranked,
and always phrased as association rather than cause.

### Measured calibration

Against simulated data, from `CalibrationTest`:

| Property | Result |
| --- | --- |
| Pure noise reporting anything | 3 of 30 runs (false discovery rate target is 10%) |
| Pure noise reporting a *confident* finding | 0 |
| Real lag-2 effect at beta = 0.4, 100 days | found 17 of 20 runs, always at the correct lag |
| Real lag-2 effect at beta = 0.7+ | found 20 of 20 runs |

The null statistic is not Gaussian: a 7-point ordinal scale carries heavy tie mass, and the measured
null sits about 5% further out than normal at every quantile (2.05 vs 1.96 at 95%, 2.69 vs 2.58 at
99%, 3.49 vs 3.29 at 99.9%). A Student-t reference at df = 30 reproduces that. Using the normal made
the strictest tier roughly twice as generous as it claimed.

## Build and run

Requires JDK 17 and an Android SDK. `local.properties` is machine-specific and not committed;
create it with `sdk.dir=/path/to/Android/sdk`.

```sh
./gradlew test                  # every module: 120 tests
./gradlew :app:assembleDebug
./gradlew :app:connectedAndroidTest   # 16 more (Room migration, sync engine); needs a device
```

The server's tests boot a real Postgres (`io.zonky.test:embedded-postgres`), so they need no Docker
but do download a Postgres binary on the first run.

### Installing on a phone

Android refuses to install an unsigned APK, and the phone reports this only as a generic
"can't install app on your device". A release build therefore needs a signing key.

The key is **not** in this repo. Create one once:

```sh
keytool -genkeypair -v \
  -keystore ~/.android/keystores/life-insights-release.jks \
  -alias life-insights -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the repo root (gitignored):

```properties
storeFile=/Users/you/.android/keystores/life-insights-release.jks
storePassword=...
keyAlias=life-insights
keyPassword=...
```

```sh
./gradlew :app:assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the release build still succeeds but produces an unsigned APK that
will not install. `assembleDebug` always installs, because it is signed with the SDK's debug key.

> **Back up the keystore.** Android ties app data to the signing key. Signing a later version with a
> different key means the update cannot be installed over the existing app: you would have to
> uninstall first, which deletes every check-in on the device. For an app whose entire value is
> months of accumulated history, losing this file is the most expensive mistake available.

Debug builds have a **Seed demo data** button in Settings. It writes 120 days of synthetic history
whose true structure is known - sleep two nights earlier drives energy, weekends lift mood, alcohol
lowers next-day energy, and screen time is deliberately unrelated to anything - so the Insights
screen can be checked against the right answer.

## Status

| Phase | State |
| --- | --- |
| 1. Manual check-in, trends, reminders, export/delete | Done |
| 5. Insights engine | Done, calibrated |
| 3. Screen and phone usage (`UsageStatsManager`) | Done |
| 4. Off-phone sleep proxy | Done |
| 2. Health Connect | Next |
| Server sync | Done |

Phases 2 and 3/4 are swapped relative to the spec: with no wearable, Health Connect supplies only
phone-derived steps, so the phone-inactivity sleep proxy is the only real sleep source.

### Known gaps

- **The container has never been built.** There was no Docker on the machine this was written on,
  so `server/Dockerfile` and `docker-compose.yml` are the one part of the deploy path nothing has
  executed. The server itself does start for real in `ServerBootTest`: Netty on a socket, Postgres
  behind it, requests over HTTP, migrations against a database that has never seen the schema.
- **The phone has never talked to the server over a real network.** The two ends meet in tests over
  a mocked transport, running the same `SyncStore` and the same JSON models on both sides, so the
  wire format and the merge rules do agree. What is untested is everything a real connection adds:
  TLS, a proxy, a captive portal, a flaky mobile signal mid-push.
- **The sync settings screen has not been used on a device.** The engine below it is tested; the
  wiring from the switch and the two text fields is not.
- **Deleting all data is local only.** It clears the sync cursor and turns sync off, because
  otherwise the next pull would download everything straight back. The server keeps its copy, so
  this deletes what is on the phone rather than your history everywhere. There is no "delete from
  the server too" yet.
- **Tags are only tested same-day.** A planted "alcohol lowers *next-day* energy" effect is
  correctly not reported, because lagged tag analysis does not exist yet. That is the first thing
  to add.
- **Insights take ~15-20s** for 121 days on an emulator. Fine as a background computation, but it
  needs caching before the dataset grows much.
- **The sleep proxy has not been observed end to end on real events.** Its logic is covered by unit
  tests, including DST, window clipping and the bounded-gap rule, but an emulator has no multi-day
  usage history to produce an actual sleep window, so only the screen metrics have been watched
  through the full path from events to database.
- Screen time is the least reliable metric, being derived from events that vary across
  manufacturers.
- No UI yet for correcting a night the proxy got wrong. `UsageRepository.setManualSleep` exists and
  is respected by re-aggregation, but nothing calls it.
