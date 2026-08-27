# Build Spec: Automatic Mood & Energy Insight App (Android)

> **Purpose of this document.** This is a build brief for a coding agent. It describes *what* to build and *why*, with enough technical specificity (APIs, data model, analysis logic) to start implementing. Where a decision is left open, it is flagged as a **DECISION** for the agent to confirm or make a sensible default on. Prefer working, testable increments over completeness — see the phased roadmap at the end.

---

## 1. Problem & goal

The user experiences fluctuating mood and energy and wants to understand the drivers: whether dips are cyclical, caused by lagged sleep debt, driven by screen/social-media usage, or tied to same-day behaviors (exercise, social contact, etc.).

The goal is a **personal insight tool**, not a clinical product. It should:

1. Collect as much data as possible **automatically** from the phone and (optionally) a wearable, to minimize logging friction.
2. Capture the few things that *must* be manual (subjective mood and energy) in a **fast daily check-in**.
3. Surface **correlations over time**, with explicit support for **lagged effects** (today's state vs. sleep/behavior from 1–3 days ago), not just same-day snapshots.

**Non-goal:** diagnosis. The app describes personal patterns. Copy throughout should avoid clinical claims. Include a one-line disclaimer that patterns are informational and not a substitute for professional advice.

---

## 2. Target platform & why

**Primary target: native Android (Kotlin + Jetpack Compose).**

Rationale: the two automatic signals the user cares most about — **per-app/screen usage** and **off-phone time** — require `UsageStatsManager`, which is only meaningfully accessible on Android. Health data (sleep, steps, exercise, heart rate) is available via **Health Connect**. A cross-platform framework (Flutter/React Native) *can* reach these through plugins, but native Kotlin is the cleanest path for the special permissions and background aggregation involved. **DECISION:** default to native Kotlin unless the agent has a strong reason and the user's buy-in to use Flutter.

iOS is explicitly out of scope for v1: Apple sandboxes Screen Time data so third-party apps generally cannot export raw per-app usage into their own database. If cross-platform is wanted later, the iOS build would have to drop or heavily degrade the screen-usage feature.

---

## 3. Data sources

### 3.1 Automatic — screen & phone usage (`UsageStatsManager`)
- **Permission:** `PACKAGE_USAGE_STATS` — a *special* access permission. The app cannot request it via the normal runtime prompt; it must deep-link the user to **Settings → Special app access → Usage access** and detect when it's granted. Build a clear onboarding screen for this.
- **Capture, aggregated per day:**
  - Total screen-on time.
  - Per-app foreground time, with a user-defined **"distracting apps" group** (e.g., social media) so the app can compute a `social_media_minutes` metric. Let the user pick which packages count.
  - Unlock / device-pickup count (via `UsageEvents` — `KEYGUARD_HIDDEN` / `ACTIVITY_RESUMED` events, or `queryEvents`).
  - Late-night usage (screen-on minutes after a configurable hour, e.g., 23:00) — a strong candidate correlate.

### 3.2 Automatic — off-phone / sleep proxy
- **Primary sleep source:** if a wearable writes sleep sessions to **Health Connect**, read those (`SleepSessionRecord`). Most accurate.
- **Fallback sleep proxy (phone-only, no wearable):** infer the main sleep window as the **longest continuous overnight period with no phone interaction** (no unlocks, screen off) within a configurable night window (e.g., 21:00–10:00). Use `UsageEvents` to find the gap.
  - Store this as `sleep_proxy` and clearly label it in the UI as an estimate. It is imperfect (phone left charging away from the user, night-shift workers, etc.) — let the user correct/override the detected window.
  - **DECISION:** the agent may also expose a manual "I slept X to Y" quick-edit for nights the proxy gets wrong.

### 3.3 Automatic — health & activity (Health Connect)
- Use `androidx.health.connect:connect-client`.
- **Read permissions to request** (only what's used; request incrementally):
  - `Steps`, `TotalCaloriesBurned` or `ActiveCaloriesBurned`
  - `ExerciseSession` (type + duration → daily `exercise_minutes`)
  - `HeartRate` and, if available, resting HR / HRV
  - `SleepSession` (see 3.2)
- Works phone-only (steps/activity from phone sensors) and improves with a wearable (sleep, HR, HRV). Design so **every metric is optional** — the app degrades gracefully when a source is absent.

### 3.4 Manual — the daily check-in (keep it under ~20 seconds)
- **Mood (valence):** scale, e.g., −2…+2 or 1–5. **DECISION:** pick one scale and keep it consistent.
- **Energy (arousal):** same scale, tracked separately from mood — they dissociate (tired-but-content, wired-but-anxious) and separating them is central to this app's value.
- **Optional context tags** (single tap, multi-select): caffeine, alcohol, social contact, notable stress, ate well, outdoors/sunlight, sick. Keep the default set short; let the user edit the tag list.
- **Optional free-text note.**
- **Cadence:** default to one evening check-in with an optional midday one. Use a reminder notification (WorkManager + a notification permission on Android 13+). **DECISION:** confirm default reminder time in onboarding.

---

## 4. Data model

Store everything **on-device** (see Privacy). Use **Room (SQLite)**.

Suggested tables:

- `check_in` — `id`, `timestamp`, `mood`, `energy`, `note`
- `check_in_tag` — `check_in_id`, `tag`
- `daily_metric` — one row per calendar day, holding the automatically aggregated numbers:
  `date`, `screen_minutes`, `social_media_minutes`, `late_night_screen_minutes`, `unlock_count`, `sleep_minutes`, `sleep_source` (`wearable` | `proxy` | `manual`), `sleep_start`, `sleep_end`, `steps`, `exercise_minutes`, `resting_hr`, `hrv`
- `raw_usage_event` (optional, for recomputation/debugging) — keep lightweight or purge after aggregation.

Design notes:
- Key aggregates by **local calendar day** but keep raw timestamps so windows can be recomputed if definitions change.
- Every metric column must be **nullable** — missing data is normal and must not break analysis.
- Store a schema/version field to allow migrations as metrics are added.

---

## 5. Analysis & insights

This is the heart of the product. Same-day scatter is the easy part; the differentiator is **lagged and cyclical analysis**.

Implement, and surface in a simple insights screen:

1. **Rolling context.** 7-day rolling averages for mood, energy, sleep, screen time — smooths noise.
2. **Lagged sleep correlation.** Correlate today's mood/energy against sleep from **t−1, t−2, and t−3 days**, and against a rolling **cumulative sleep-debt** figure (e.g., 3-day rolling deficit vs. the user's own baseline). This directly answers the user's "is it poor sleep from previous days?" question. Report which lag is strongest.
3. **Same-day behavioral correlates.** Mood/energy vs. `social_media_minutes`, `late_night_screen_minutes`, `exercise_minutes`, steps, and each context tag (tag present vs. absent → mean difference).
4. **Cyclical checks.** Day-of-week effect (is there a reliable Monday/weekend pattern?). If ≥ several weeks of data exist, a simple weekly periodicity check. Leave menstrual-cycle phase analysis as an **optional module** the user can enable and log a cycle start for — do not assume it applies.
5. **Correlation hygiene:**
   - Do **not** show correlations until there are at least **~14 days** of data for the relevant metric; show a "collecting data — N days so far" state instead. Meaningful patterns usually need **2–4 weeks**.
   - Report correlation strength honestly and in plain language ("weak/moderate/strong association"), never as causation. Flag small sample sizes.
   - Handle missing days without dropping the whole analysis.

**DECISION:** keep analysis on-device with a light stats implementation (Pearson/Spearman, rolling windows). No server or ML needed for v1. An optional natural-language summary of the top 1–3 insights is a nice-to-have, not a requirement.

---

## 6. UI (minimal, four screens)

Use Jetpack Compose. Read the frontend-design conventions if styling from scratch.

1. **Today / check-in** — the mood + energy entry, tag chips, note; shows today's auto-collected numbers.
2. **Trends** — line charts of mood, energy, sleep, screen time over selectable ranges (7/30/90 days). Use a Compose charting lib (e.g., Vico).
3. **Insights** — the correlations from §5, in ranked, plain-language cards, each with the "needs more data" gating.
4. **Settings** — permission status + re-grant deep links, distracting-apps picker, reminder time, sleep-window config, data export, delete-all.

Keep the check-in the default landing screen and make it fast.

---

## 7. Permissions & background work

- **Special access:** `PACKAGE_USAGE_STATS` (Usage access) — onboarding deep-link + granted-state detection.
- **Health Connect:** request read permissions incrementally; handle the case where Health Connect isn't installed (prompt to install) and where the user denies specific records.
- **Runtime:** `POST_NOTIFICATIONS` (Android 13+) for reminders; `ACTIVITY_RECOGNITION` only if reading step/activity data that requires it.
- **Background aggregation:** a daily **WorkManager** job that pulls the previous day's usage + Health Connect data and writes `daily_metric`. Make it idempotent and recompute-safe. Respect Doze/battery constraints; don't poll aggressively.

---

## 8. Privacy (non-negotiable defaults)

- **All data stays on-device by default.** No cloud, no account, no analytics/telemetry SDKs in v1.
- Screen-usage and health data are sensitive; never transmit them. If cloud sync is added later it must be opt-in and encrypted, and disclosed clearly.
- Provide **export** (JSON or CSV, user-initiated) and **delete-all** in Settings.
- Include a short, honest privacy note and the "not medical advice" disclaimer.

---

## 9. Tech stack summary

- **Language/UI:** Kotlin, Jetpack Compose
- **Storage:** Room (SQLite), on-device
- **Health:** `androidx.health.connect:connect-client`
- **Usage:** `UsageStatsManager` / `UsageEvents`
- **Background:** WorkManager
- **Charts:** Vico (or MPAndroidChart)
- **Min SDK:** target a modern range that supports Health Connect natively (**DECISION:** confirm min/target SDK; Health Connect is built into recent Android versions and installable on older ones).

---

## 10. Phased roadmap (ship each phase working)

**Phase 1 — Manual core + trends (no special permissions).**
Daily mood/energy check-in with tags and notes, Room storage, basic trend charts, reminders, export/delete. Usable on day one; proves the logging habit.

**Phase 2 — Health Connect.**
Read sleep (if a wearable provides it), steps, exercise, heart rate. Populate `daily_metric`. Graceful degradation when sources are missing.

**Phase 3 — Screen & phone usage.**
`UsageStatsManager` integration, Usage-access onboarding, distracting-apps picker, `social_media_minutes` and `late_night_screen_minutes`, unlock counts.

**Phase 4 — Off-phone sleep proxy.**
Infer the sleep window from phone inactivity as a fallback when no wearable sleep data exists; user override.

**Phase 5 — Insights engine.**
Rolling averages, lagged sleep-debt correlation (t−1..t−3), same-day behavioral correlates, day-of-week effect, correlation gating and plain-language output.

**Later / optional:** menstrual-cycle module, natural-language insight summaries, cross-platform (accepting the iOS screen-usage limitation), optional encrypted sync.

---

## 11. Acceptance criteria (v1 = Phases 1–3)

- A user can log mood and energy in under ~20 seconds and see them trend over time.
- With permissions granted, the app automatically records daily sleep (or proxy), steps, exercise, screen time, social-media minutes, and unlock count without manual entry.
- After ~2 weeks of data, the Insights screen shows at least: lagged sleep→mood/energy correlations, a social-media/screen→mood correlation, and a day-of-week pattern — each gated on sufficient data and phrased as association, not cause.
- All data is stored locally; export and delete-all work; nothing is transmitted off-device.
