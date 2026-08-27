# Life Insights

A personal Android app for tracking mood and energy and finding what actually drives them:
lagged sleep effects, screen use, day-of-week rhythms, and context tags.

All data stays on the device. No account, no server, no analytics, nothing transmitted.
Patterns are descriptive only; this is not medical or psychological advice.

Build brief: [`mood-energy-tracker-spec.md`](mood-energy-tracker-spec.md).

## Modules

| Module | What it is |
| --- | --- |
| `insights/` | The analysis engine. Plain Kotlin, **no Android dependencies**, so it can be tested against synthetic data with known structure long before months of real logging exist. |
| `app/` | Kotlin + Jetpack Compose + Room. Check-in, trends, insights and settings. |

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
./gradlew :insights:test        # the analysis test suite
./gradlew :app:assembleDebug
```

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
| 3. Screen and phone usage (`UsageStatsManager`) | Next |
| 4. Off-phone sleep proxy | Next, shares the usage-events plumbing with Phase 3 |
| 2. Health Connect | After 3 and 4 |

Phases 2 and 3/4 are swapped relative to the spec: with no wearable, Health Connect supplies only
phone-derived steps, so the phone-inactivity sleep proxy is the only real sleep source.

### Known gaps

- **Tags are only tested same-day.** A planted "alcohol lowers *next-day* energy" effect is
  correctly not reported, because lagged tag analysis does not exist yet. That is the first thing
  to add.
- **Insights take ~15-20s** for 121 days on an emulator. Fine as a background computation, but it
  needs caching before the dataset grows much.
- Total screen-on time will be the least reliable metric once Phase 3 lands; it is derived from
  events that vary across manufacturers.
- Usage events expire after roughly a week and cannot be backfilled, so aggregation must re-run a
  trailing window on app open rather than relying on the daily worker alone.
