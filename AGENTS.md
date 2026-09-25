# LifeOS — Agent Handoff & Working Guide

## Today update (2026-09-25; supersedes older sleep-binning/state notes below)

Today is no longer a rule-based state plus an averaged "Life score". Three
aggregation bugs were fixed first (`repositories/health_repo.py`):
(1) HAE re-sends a day's aggregate as it changes and the quantity `source_id`
included the value, so revisions were stored side by side and summed or
averaged. Ids are now `hae-md5(type|date)`, `/health/auto-export` upserts in
place (bumping `created_at`), and `for_day`/`baseline_mean` keep only the
newest hae row per (type, start, end) for the legacy rows. (2) Overlapping
sleep sessions (a revised night, or XML import plus HAE) were added together;
`sleep_for_day` keeps the longest session of each overlapping group and adds
separate naps. (3) Sleep is filed under the **wake-up day** (`end_time`), not
the start day: 589 of 1,618 nights cross midnight. Full history (2,887 days)
was recomputed on 2026-09-25. `state_engine.py` is deleted (46 of 60 days
were UNKNOWN). `services/day_brief.py` computes the day's facts (with
better/worse-than-usual already stated) and Qwen picks the `LifeState` and
writes headline/summary/suggestion under strict json_schema; code rejects
uncited replies or summary numbers not in the facts, and keeps the last good
brief (no canned text). It also writes that day's `daily_states` row for Ask
and notifications; older state rows are historical rule output.
`GET /analytics/today` never waits: stale facts return `refreshing` and a
background rewrite (DAY_BRIEF_JOB lock); pushes and recomputes also refresh
it. Android shows the brief, "Health data received HH:mm", each vital's latest
reading with its day, and Wellness as a Check in link to Pulse; the Overall
trend line is gone. Qwen 4B still editorialises a little ("reset HRV"):
inspect real briefs after prompt changes.

## Priority update (2026-09-24; supersedes older Priority notes below)

Priority mail is now **model-led**. The regex classifier and the unreachable
`ambiguity.py` review path are gone. `app/priority/classifier.py` makes two
calls per message under the Jetson EdgeLLM build's **strict JSON-schema guided
decoding** (`OpenClawClient.chat_json`, `response_format: json_schema`):
triage (explicit yes/no questions, then one of five categories:
`interview`, `job`, `event`, `account`, `personal`, or `none`) and, for kept
mail, details (state, summary, action, quoted deadline/event times in IST).
Code applies the stated policy to the model's own answers (selling, or mass
mail / routine notice without a hiring process or required action → suppressed)
and trusts a time only if the model's quote appears verbatim in the email. A
structured ICS invite is authoritative; its parser now reads only VEVENT (the
VTIMEZONE `DTSTART:16010101` caused 1601/1970 event times) and maps Windows
zone names. The engine really decodes **batch 2**: `InferenceCoordinator`
capacity is `openclaw_max_concurrency=2`, and sync runs two messages in
parallel, newest first, within `priority_sync_budget_seconds` (100 s); a busy
model leaves mail in `retry`, never guessed. Android shows chips only for
non-empty categories and only covers the last 30 days (`priority_backfill_days`).
Neither the Gmail app nor Gmail web on Android can be opened at one message by
another app (verified on the S24: https, content://, search intents), so
details auto-load the full body via `GET /priority/{id}/message` (fetched on
demand, never stored) and "Open Gmail app" only launches the app. Meeting URLs
are never pasted: a Join button plus the platform name; invites use Add to
calendar. Evaluate
prompt changes with `backend/scripts/evaluate_priority.py` on a real corpus.
See `backend/docs/priority-mail.md`.

## Workouts and synced health update (2026-09-25)

LifeOS never contacts the iPhone. Health Auto Export's server now runs at
`http://<phone>:9000/mcp` behind a bearer token and stops whenever the app
closes, so the old `live_health` Ask tool and `phone_health_client.py` (plain
TCP, no token) were removed with the `HAE_HOST`/`HAE_PORT` settings. Ask reads
synced data through `health_records` (days or exact start/end dates): per-day sleep/stages, resting HR,
HRV, steps, active energy, exercise minutes, every workout with its type, the
last training workout, and data freshness. Two automations push to
`/health/auto-export`: Health Metrics (existing) and **Workouts** (added
2026-09-25). Workouts keep their activity in `health_records.label`
(migration `0004`); the watch's `duration` is used, not end minus start
(pauses). Each workout also stores `workout_energy` (kcal) and
`workout_avg_hr` rows with the same times and label (all 542 XML workouts
backfilled; the 28 Jun–Sep phone-pulled ones lack them). **Every workout
counts, walks included, and no code threshold decides what "real" exercise
is** (the user's rule: a daily 30-second session must not pass as training,
but don't hard-code it). `services/training.py` gives the model 13 weeks: each session with
duration/energy/HR, a week-by-week sessions/minutes series and days since the
last workout; `WORKOUT_JUDGEMENT_RULE` (Today brief, Insights mentor, weekly
recap) says to judge the routine and its trend (how often, how recently,
building or fading), not one session. A 19-min walk counts as real. The brief
returns `workout_note` + `workout_attention` (migration `0005`); Android shows
the note in Today's Needs you when flagged (opens Insights). A workouts export (even empty) touches
collector key `hae:workouts`; until then the brief says workouts are
"unknown, not zero". Workouts from 2026-06-14 to 2026-09-25 were backfilled
once via the phone's MCP `get_workouts` tool (28 sessions; last training
2026-07-29). The 542 older XML-import workouts got their types on 2026-09-25
by re-parsing the original `export.zip` (iCloud Drive Downloads) with the
importer's own `source_id` rule and updating `label` only; all 570 are typed.

## Today overview update (2026-09-25)

Today is now the home screen and the app's overview: default start
destination (`DEFAULT_DESTINATION_ROUTE = "dashboard"`, used when "Reopen last
used screen" is off or nothing was saved), first drawer entry, and where Back
goes from other area roots. Below the model-written brief it adds, all from the
local Room caches (works offline): **Needs you** (over-budget or on-pace
budgets, and a card's *newest* statement if unpaid past due; older ones are
superseded), the **trip card** (a tracked trip, else one under way, else the
soonest starting within 14 days; countdown or day N with the next planned
activity, plus card bills due during it), the **Money card** (month-to-date
spend EMI excluded, matching Money and the widget; income share incl. EMI with
a straight-line month-end estimate and savings-goal line; today / open cycles /
billed; top two budgets), and **Next 14 days** (bills grouped per bank per day,
trip start/end, expected recurring charges). Logic is pure and unit-tested in
`presentation/dashboard/TodayOverview.kt`, reusing Money's `budgetPace`,
`filterEmiTransactions` and dues rule; cards live in `TodayOverviewCards.kt`.
Income and recurring charges are fetched online; a failed fetch just omits
them. Device tests pick drawer entries by `Role.Tab`, because Today's cards
also mention area names.

The separate **Pulse page and drawer entry are gone**: the check-in is a
`PulseCheckInCard` on Today (top of Your vitals). One tap saves or changes
today's pulse; the optional note has its own Save/Cancel (no more re-tapping a
face). The evening reminder deep-links to `dashboard`; a saved last-used tab of
`mood` falls back to the default. The Wellness vitals row no longer links out.

## Trips update (2026-09-17)

Trips is now a phone-first Android feature under the navigation drawer. It uses a
separate `lifeos-trips.db` Room database (no destructive fallback), imports the
versioned JSON contract in `docs/trips/trip-v1.schema.json`, preserves conditional
activities, and stores plans, raw GPS samples, inferred visits, expenses,
reminders, and app-private attachment copies. `TripLocationService` records only
after explicit Start tracking and runs as a location foreground service. Maps
Compose uses a `MAPS_API_KEY` Gradle property; without it the itinerary still
works but the embedded map cannot authenticate.

The backend now exposes revision-guarded trip backup/restore metadata under
`/trips`, with raw attachment upload/download and soft deletion. Ask has a
read-only `get_trip_context` tool over the most recent successful phone backup.
The backend remains optional during travel: Android records and edits locally,
and backup is explicit. New backend tables are created by `create_all`.

## Ask update (2026-09-13; supersedes older Ask/MLC notes below)

Ask now uses one model-led conversation: no keyword router, canned factual
answers, money-language planner, automatic personal-data prefetch, or factual
fallback summaries. Qwen selects optional read-only tools; all successful answer
text comes from the model. Tools retain deterministic accounting and scoring.
The new `get_life_context(days)` exposes the existing overview on demand;
`query_money` also accepts `compare_start_date` / `compare_end_date` to compute
exact differences and percentages in the data layer. Never restore canned Ask
answers to compensate for model errors; optional retrieval is the user's choice.

The live runtime is **Qwen3.5-4B through TensorRT EdgeLLM**, on port 11434,
launched from `/home/ajmalrasi/TensorRT-Edge-LLM`, using
`/home/ajmalrasi/Qwen3.5-4B/engines/llm-b2-input6144-kv8192-vanilla`.
Verified limits: 6,144 input / 8,192 total; the engine decodes two sequences
together (batch 2, verified 2026-09-24).
Ask counts the matching template/tokenizer via `/v1/messages/count_tokens`,
budgets 6,000 input (with a 128-token margin), defaults to 1,024 output with a
2,048 ceiling, and retains the 90-second call / 135-second request limits.
Do not apply the historical MLC 4K watchdog/recovery instructions to this runtime.

Suggestions are generated by Qwen at background priority (15 seconds / 256
tokens), cached for six hours per IST date/context window, with no canned
fallback. Android checks cached suggestions immediately and once after 20 seconds.
Streaming adds status/error events and incomplete/retry UI; sources refer only
to successful tool reads. `grounded` means retrieved evidence, not verified prose.
The local model can still skip tools or misstate evidence; smoke evaluation
must inspect actual answers, date windows, and arithmetic, not just HTTP success.
See `backend/docs/ask.md` and `backend/scripts/evaluate_ask.py`.

Read this before working on the repo. It captures the architecture, how to deploy/
test, and the non-obvious things that will otherwise cost you hours. Keep it
updated as you learn more.

LifeOS is a personal "life operating system": an **Android app** + a **FastAPI
backend** that ingests health, mood, and coding-execution data, scores it
(sleep / recovery / fitness / execution / wellness / burnout), classifies a daily
"state", finds correlations, and answers questions via a grounded chat.

---

## 1. Architecture & where things run

| Piece | Where | Notes |
|---|---|---|
| Backend API (FastAPI) | **Jetson Orin Nano**, `backend-api-1` container, port 8000 (0.0.0.0) | Reachable on LAN at `http://192.168.3.30:8000` |
| Postgres 16 | Jetson, `backend-db-1`, port 5432 | db/user/pass all `lifeos` |
| LLM (MLC-LLM) | Jetson host, port 11434, `openclaw-mlc` container, Qwen3 4B | OpenAI-compatible API; LifeOS Ask calls it directly |
| Android app | `android/` (Kotlin, Compose, Hilt, WorkManager, Room) | reads Health Connect; talks to the backend |
| Dev machine | the Mac | **no** docker/postgres/python-deps here — backend only runs on the Jetson |

The app is on a **Samsung S24 (Android)**; the user also has an **iPhone + Apple
Watch** (`192.168.3.212`) which is where sleep/vitals actually live.

### Connecting to the Jetson
```bash
ssh -i ~/.ssh/jetson_key ajmalrasi@192.168.3.30      # plain ssh fails — key is required
# repo:        /home/ajmalrasi/life_os   (kept reconciled to origin/main)
# compose dir: /home/ajmalrasi/life_os/backend
# secrets:     backend/.env  (gitignored, NOT in repo — never overwrite it)
```

---

## 2. Deploy & test the backend

```bash
# On the Jetson:
cd /home/ajmalrasi/life_os && git pull
cd backend && docker compose up -d --build api      # rebuilds the image (code is baked in, no volume mount)
curl -s localhost:8000/healthz                       # {"status":"healthy"}
```
- **DB schema is `create_all`, no Alembic.** It creates missing tables but **never
  ALTERs existing columns**. Column/type changes ship as a numbered SQL file under
  `backend/migrations/` (e.g. `0001_statement_details.sql`, moved there from the
  old one-off `scripts/2026_07_21_statement_details.sql`); `app/migrations.py`
  applies any unapplied files automatically on API startup (after `init_db()` in
  `app/main.py`'s `lifespan`), tracked in a `schema_migrations` table so each file
  runs once. Write new files idempotently (`IF NOT EXISTS`/`IF EXISTS`) and they
  deploy themselves on the next `docker compose up -d --build api` — no manual
  `docker exec ... psql` step needed anymore for schema changes (still useful for
  one-off data queries/fixes). Migrations are skipped on SQLite (the test suite).
- Run one-off SQL: `docker exec -i backend-db-1 psql -U lifeos -d lifeos < file.sql`
- Tests: `cd backend && pytest` (uses SQLite).

## 3. Build & test the Android app

### Environment & Tooling Paths (Dev Mac)
- **Java Home (Android Studio JBR):** Plain `java` or `adb` may not be in default `PATH`. Always invoke Gradle with Android Studio's bundled JBR:
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- **ADB Path:**
  ```bash
  export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
  # Or run directly: ~/Library/Android/sdk/platform-tools/adb
  ```
- **Device:** Samsung S24 (`SM-S921B`, serial e.g. `RZCX60SNHHE`) connected via USB/Wi-Fi ADB. Verify with `~/Library/Android/sdk/platform-tools/adb devices`.

### Build Commands (Run from `android/`)
- **Unit tests:**
  ```bash
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest
  ```
- **Assemble Debug APK:**
  ```bash
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
  # Output APK: android/app/build/outputs/apk/debug/app-debug.apk
  ```
- **Connected Tests (on physical phone):**
  ```bash
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew connectedDebugAndroidTest
  # Or run specific test class:
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lifeos.app.trips.data.TripDatabaseTest
  ```

### ADB Operations: Install, Run, View Screen & Inspect DB
- **Install APK onto phone:**
  ```bash
  ~/Library/Android/sdk/platform-tools/adb install -r android/app/build/outputs/apk/debug/app-debug.apk
  ```
- **Launch app:**
  ```bash
  ~/Library/Android/sdk/platform-tools/adb shell am start -n com.lifeos.app/.MainActivity
  ```
- **Capture screenshot (to visually verify UI changes):**
  ```bash
  ~/Library/Android/sdk/platform-tools/adb exec-out screencap -p > /tmp/lifeos_screen.png
  # Use view_file on /tmp/lifeos_screen.png to inspect
  ```
- **Inspect Room SQLite DB directly on device:**
  The debug app is debuggable, allowing `run-as com.lifeos.app` access to the private `/data/data/com.lifeos.app/databases/` directory.
  - Inspect Trips DB (`lifeos-trips.db`):
    ```bash
    ~/Library/Android/sdk/platform-tools/adb shell "run-as com.lifeos.app cat databases/lifeos-trips.db" > /tmp/lifeos-trips.db
    ~/Library/Android/sdk/platform-tools/adb shell "run-as com.lifeos.app cat databases/lifeos-trips.db-wal" > /tmp/lifeos-trips.db-wal
    sqlite3 /tmp/lifeos-trips.db "SELECT id, title, startDate, endDate FROM trips;"
    sqlite3 /tmp/lifeos-trips.db "SELECT id, tripId, name FROM trip_places;"
    ```
  - Inspect Main DB (`lifeos.db`):
    ```bash
    ~/Library/Android/sdk/platform-tools/adb shell "run-as com.lifeos.app cat databases/lifeos.db" > /tmp/lifeos.db
    ~/Library/Android/sdk/platform-tools/adb shell "run-as com.lifeos.app cat databases/lifeos.db-wal" > /tmp/lifeos.db-wal
    sqlite3 /tmp/lifeos.db ".tables"
    ```

### App Structure & Key Locations
The app is 100% Jetpack Compose + Room + Hilt + Retrofit under `android/app/src/main/java/com/lifeos/app/`:
- `MainActivity.kt`: Entry point hosting Compose navigation.
- `presentation/LifeOsApp.kt`: Root scaffold, navigation drawer, and `NavHost` routes (`Today`, `Pulse`, `Money`, `Ask`, `Trips`, `Settings`).
- **Feature Packages:**
  - `presentation/dashboard/`: Today dashboard (`DashboardScreen.kt`, `DashboardViewModel.kt`).
  - `presentation/trends/`: Health and recovery score trend charts (`TrendsScreen.kt`, `TrendChart.kt`).
  - `presentation/money/`: Financial tracker, statement cycles, EMI (`MoneyScreen.kt`).
  - `presentation/chat/`: Grounded Ask chat (`ChatScreen.kt`, `ChatViewModel.kt`).
  - `trips/`:
    - `importer/TripImport.kt`: Parses and validates trip JSON against schema, builds scoped entities (`${tripId}_${childId}`) so multiple trip versions never collide. Schema in `docs/trips/trip-v1.schema.json`.
    - `data/TripDatabase.kt`, `TripEntities.kt`, `TripDao.kt`: Separate `lifeos-trips.db` Room database with foreign key cascades.
    - `data/TripRepository.kt`: Repository coordinating Room DB, attachment storage (`TripFileStore.kt`), and backend backup (`LifeOsApi.kt`).
    - `ui/TripsScreen.kt`, `TripsViewModel.kt`: Trip list, import dialog (with "Add as new trip" vs "Replace existing trip" and title editing), and trip deletion.
    - `ui/TripDetailScreen.kt`: Multi-tab trip view (Plan/Today, Map with Google Maps Compose, Details with overview and deletion, Memories/Journal).
    - `location/TripLocationService.kt`: Foreground GPS tracking service recording samples and detecting stops/visits.
  - `data/remote/LifeOsApi.kt`: Retrofit client communicating with FastAPI backend at `http://192.168.3.30:8000`.

---

## 4. The Apple Health integration (the big recent work)

**The core problem:** the app reads sleep from **Android Health Connect**, but the
user's sleep is recorded by an **Apple Watch**, which only writes to **Apple Health
on the iPhone**. That never reaches Android Health Connect (confirmed from the
device: Health Connect only contains steps/calories/workout/weight — no sleep, no
HR). So the app was correct; the pipe was empty.

**Data paths** (all land in the one `health_records` table). **As of 2026-06-21 the
sole source is Apple Health via Health Auto Export** — Health Connect is no longer
read (see §6):
1. ~~**Health Connect sync** (Android `HealthSyncWorker`) → `POST /health/batch`~~ —
   **REMOVED 2026-06-21.** It only carried activity (steps/calories), which
   *double-counted* the same activity Apple now pushes via HAE. `HealthSyncWorker`
   and `DashboardViewModel.syncAndAnalyze()` no longer read/upload Health Connect —
   they just trigger a recompute. Existing HC rows were deleted; `/health/batch`
   still exists but nothing feeds it. **The Health Connect permission request was
   also removed (2026-06-21):** the manifest `uses-permission android.permission.health.*`
   block, the rationale intent-filter + `ViewPermissionUsageActivity`, the
   `<queries>` healthdata entry, the `DashboardScreen` `PermissionController`
   launcher, and the `DashboardViewModel` permission exposure are all gone — the
   app no longer asks for or declares any Health Connect access. `HealthConnectManager`
   / `HealthRepositoryImpl` / `HealthRepository` / `HealthSample` are now fully dead
   code (inert; the `androidx.health.connect` dep is still in `libs.versions.toml`).
   Safe to delete the cluster later. Rebuilt `:app:assembleDebug` clean.
2. **One-time XML import** from an Apple Health `export.zip`:
   `backend/scripts/import_apple_health.py` (stdlib, streams the 800MB XML) →
   `POST /health/batch`. ~1.3M rows backfilled incl. ~1,480 sleep nights.
3. **Live push** from the iOS app **Health Auto Export** (Premium) →
   `POST /health/auto-export` (parser: `app/services/apple_ingest.py`). Recomputes
   touched days so the dashboard updates immediately. This is the unattended path.
4. ~~**Live pull** in the chat~~ — **REMOVED 2026-09-25** (see the Workouts
   update at the top). Historically: the `live_health` tool
   (`app/services/phone_health_client.py`) opened a TCP socket to the phone's Health
   Auto Export server (`192.168.3.212:9000`) for "right now / last night"
   questions. This is the interactive path.

`HealthRecord.type` is a **free-form string**, not a closed enum. Capture every
metric (physical effort, daylight, AFib burden, …) — analytics only consumes the
ones it understands. `HealthRecordType` is the "known types" list, not a whitelist.

---

## 5. Hard-won learnings — READ THIS

- **Health Connect ≠ Google Fit ≠ Apple Health.** They're separate stores. "I see
  it in Google Health" does not mean it's in Health Connect (which is all the app
  can read). Verify with the Health Connect app's *Data and access* screen.
- **`create_all` won't migrate columns.** Any schema change to an existing table
  needs a numbered SQL file in `backend/migrations/`, applied automatically on
  startup by `app/migrations.py` (see §2) — no more manual `ALTER TABLE` on the
  Jetson DB.
- **The Jetson git checkout is reconciled to `origin/main`** — deploy with
  `git pull`. (Historically it lagged and was deployed by rsync; don't go back to
  that.) `backend/.env` is gitignored and survives `git reset --hard` — never clobber it.
- **LifeOS Ask bypasses the OpenClaw gateway (current architecture, verified
  2026-07-16).** Despite legacy class/message names, the API calls the
  OpenAI-compatible **MLC-LLM** server directly at
  `http://host.docker.internal:11434`; the model is the `openclaw-mlc` Docker
  container (Qwen3 4B). The separately-running `openclaw-gateway` user service on
  port 18789 is not on the Ask request path. `/chat/health` only checks
  `/v1/models`, so it proves reachability, not that generation will finish. The
  model holds ~4.7GB of the Jetson's 7.4GB RAM; on the 2026-07-16 investigation it
  had zero restarts and was not OOM-killed — observed failures were generation
  timeouts, not a dead gateway.
- **MLC can lose its decoding thread while the container stays healthy-looking
  (2026-07-18).** The observed fatal is `BatchDecodeAction` / `num_rsentries > 0`:
  Uvicorn and `/v1/models` keep returning 200, but generation returns no bytes.
  `backend/scripts/watch_openclaw_mlc.sh` detects the fatal log immediately and
  also performs a conservative one-token probe after three quiet minutes. Two
  unexplained probe failures recover `openclaw-mlc.service`. The model is pinned
  to **4096 tokens only** by `10-lifeos-4k.conf` — 2K is not acceptable. Recovery
  temporarily stops the gateway plus all LifeOS/Rabbit containers so the Jetson
  can allocate the 4K model/KV cache from clean unified memory, requires at least
  6.5GB available, and proves a real generation before restoring them. It makes
  only one load attempt: repeating a CUDA OOM fragments Tegra NvMap further. The
  user timer is
  `openclaw-mlc-watchdog.timer` (repo units under `backend/ops/systemd/`). Do not
  replace this with a `/v1/models`-only health check.
- **Don't make the post-sync recompute slow again.** `/analytics/recompute` runs
  the health/score recompute synchronously (fast) and the **GitHub execution scan
  (44 repos) in a background task** — it used to be synchronous and made syncs take
  ~81s. Recovery baselines use one grouped query (`HealthRepository.baseline_mean`),
  not a per-day loop. Keep both.
- **Day aggregation is in IST (Asia/Kolkata), NOT UTC — changed 2026-06-19.**
  `analytics._sum`/`health_repo.for_day` bin sleep/metrics by naive `start_time`
  bounds interpreted in the **DB session tz**, and that tz was switched from UTC to
  Asia/Kolkata: `ALTER DATABASE lifeos SET timezone TO 'Asia/Kolkata';` (then an API
  **restart** so pooled connections rebind — not a rebuild). WHY: the user is in IST
  (+0530) and falls asleep after local midnight, so a 02:52 IST `sleepStart` is
  21:22 the *previous* UTC day — under UTC binning every night was filed one day
  early and the today-centric dashboard showed **no sleep for "today."** Sleep is
  attributed to the night's *start* day **in IST**, matching Apple Health's wake-day
  view for this user. CAVEAT: naive date literals in SQL are now interpreted in IST (intended). The
  `created_at`/`computed_at` audit-timestamp skew this *used* to cause was **fixed
  2026-06-21 (commit `fdffe72`, deployed)**: audit columns now default to the
  tz-aware `app.database.utcnow()` instead of naive `datetime.utcnow`, so they store
  the correct instant and read true IST. (Rows written *before* the fix — including
  today's full-recompute `computed_at` — stay ~5.5 h early; historical only.)
  The **full history was recomputed under IST (2026-06-21)** — all 2,791 days
  (2018-10-31→2026-06-21) in ~200 s via a `recompute_day` loop in the api container —
  so every night sits on its correct local day, not just the recent window.
- **Sleep import filters fragments**: drop sessions < 30 min and > 16 h (in-bed
  artifacts) in both `import_apple_health.py` and `apple_ingest.py`.
- **Health Auto Export specifics:** REST + TCP server need **Premium**; the TCP
  server is **foreground-only** (app must be open) and the phone IP is **DHCP** (set
  a router reservation; it's `HAE_HOST` in `.env`). For the `health_metrics` TCP
  call, the `metrics` filter must go **inside `arguments`**, not beside `name`.
  HAE automations **only run while the iPhone is unlocked** (Apple HealthKit rule),
  and the endpoint is a **LAN IP** so the phone must be on home Wi-Fi (not cellular)
  for a push to land — use a frequent cadence + "Since Last Sync" so a missed run
  is caught next time. For sleep to ingest you MUST enable **Summarize Data** (ON):
  the parser needs the *aggregated* point (`totalSleep`+`deep`/`rem`/`core`); the
  disaggregated form is raw stage segments with no `totalSleep` and is dropped.
- **Provenance lives in the `source_id` prefix, not a column.** There is **no
  `source` column** on `health_records` (queries that group by `source` fail). The
  one-time XML import writes `apple-<md5>` ids (`make_source_id`); the live HAE push
  writes `hae-<md5>` ids (`apple_ingest._sid`); Health Connect uses its own ids.
  Group by `split_part(source_id,'-',1)` to see provenance.
- **Two double-counting traps, because differing `source_id`s never dedup against
  each other:** (a) **XML import vs HAE** — the same night from both paths has a
  different prefix *and* a different date-string format, so both land and
  `analytics._sum` doubles that night's sleep. The import covers **through
  2026-06-13 (UTC)**, so live data must be **UTC date ≥ 2026-06-14**. ⚠️ Apply the
  cutoff in **UTC, not local time**: HAE logs `sleepStart` *after* local midnight
  (e.g. `2026-06-14 04:34 +0530`), which is the *previous* UTC day
  (`2026-06-13 23:04Z`), and the DB/analytics bin by UTC — so a *local-date* filter
  under-excludes by one day and re-imports the last imported night. This exact bug
  hit the 2026-06-19 TCP backfill (a 7.32 h night landed on both `apple` and `hae`
  for 06-13, halving its `sleep_score` to 19.9); fixed by
  `delete from health_records where source_id like 'hae-%'
  and start_time < '2026-06-14 00:00:00+00';` then recomputing the touched days.
  (b) **Health Connect vs HAE** — Health Connect
  already carries steps/calories/workout/weight on this device, so HAE must export
  **sleep + vitals only** (HR, HRV, resting HR, SpO2, respiratory rate, wrist temp,
  VO2 max) and **exclude** activity/weight, or those metrics double too.
- **`/tmp` on the Jetson is cleared on reboot** — the copied `export.zip` vanished
  after a reboot. Re-`scp` it from the Mac when re-importing.
- **The Android sync worker swallows exceptions** (`HealthSyncWorker`) — failures
  are invisible. Consider surfacing sync status/errors in the UI.
- **Trend headline = most-recent *non-null* reading, not necessarily today.** With
  sparse recent data the big number can be an older day's score; the card now
  mutes it + tags `latest · <date>` when stale (`isStale` in `TrendChart`). Don't
  re-introduce copy that calls the single point "today".
- **Ask latency is bounded now (2026-07-16).** Previously each model call could
  run 120s with no output-token limit, the agent could make 4 tool rounds plus a
  closing call, and merely opening Ask triggered a competing LLM call for
  suggestion chips. A timed-out `httpx` call was silently mislabeled "OpenClaw is
  unreachable." Fix: suggestions are deterministic (no model request), context
  JSON is compact, history is capped at 6 route-compatible turns, tool rounds at 2, generation at
  512 tokens (with a 220-word prompt limit), per-call timeout at 90s, and the whole Ask agent at 135s (below
  Android's 150s read timeout). `OpenClawTimeout` is logged and the UI now says
  "The local model timed out"; true connection failures say it could not be
  reached. As of 2026-07-17, normal follow-ups always receive those recent turns;
  history stops at a personal/general route boundary so an old sleep discussion
  cannot contaminate a new coding question. Keep these budgets aligned if changing
  the Android network timeout.
- **Ask money questions use a guarded semantic query, not arbitrary model SQL
  (2026-07-27).** `query_money` in `agent_tools.py` lets the model translate
  natural-language questions into an allowlisted contract implemented by
  `money_query.py`: mode, ISO date window, filters, grouping, sorting, and limit.
  SQLAlchemy constructs the only DB query; the model never supplies executable
  SQL, table names, columns, or functions. Keep this boundary. It supports
  spending/income/all transactions; date/category/merchant/account/source/amount
  filters; and day/week/month/category/merchant/account/source/direction groups.
  "Spending" excludes transfers, reversals, and repaid lending, and effective
  categories use the same user-label + override + deterministic categorizer as
  the Money page. Statement detail rows are deliberately absent because they
  explain bills but never enter Money spend. Simple today/yesterday/current-month
  totals still take the deterministic fast path; qualified questions such as
  "food last month" must reach `query_money`. **Do not rely on Qwen choosing the
  optional function call:** production smoke-testing showed it can answer from
  the shallow snapshot instead. `plan_money_query` therefore deterministically
  resolves common periods/dimensions/categories and prefetches the guarded query;
  factual results are formatted directly without an LLM call, while interpretive
  questions receive `money_query_result` in their grounded context.
- **Git from the Cowork sandbox can't delete `.git/*.lock` (EPERM).** A commit
  succeeds the first time but its lock-cleanup `unlink` is denied by the mount, so
  `.git/index.lock` + `.git/HEAD.lock` are left behind and **block every later
  commit** ("Unable to create '.git/HEAD.lock': File exists"). The sandbox cannot
  `rm` them. Fix on the **Mac**: `cd ~/life_os && rm -f .git/index.lock
  .git/HEAD.lock .git/refs/heads/main.lock`, then commit normally. (2026-06-19: the
  Ask-keyboard/state-chip changes are in the working tree, built & verified live,
  but uncommitted for this reason; a dangling commit object also exists.)
- **Build/verify tooling note:** the emulator mirrored inside Android Studio (incl.
  the detached "Running Devices" window) is part of the IDE, so automated *typing*
  into it is blocked — only taps work. Suggestion chips and the Sync button are
  tappable; reproducing keyboard-specific bugs (e.g. IME Enter) needs a real
  device or the standalone emulator. Rebuild+reinstall via the green Rerun button
  (~120-490 ms install); watch for the "Install successfully finished" toast.
- **Gmail OAuth must stay in Google Cloud “In production” mode.** While the OAuth
  consent screen was in Testing, Google's refresh token expired after seven days
  (2026-07-10 → 2026-07-17) and spending silently stopped. It was moved to In
  production and `mail2ajmalrasi@gmail.com` was reauthorized on 2026-07-18 with
  only `gmail.readonly`. `GmailClient` now turns `invalid_grant`, missing scopes,
  timeouts, and malformed responses into HTTP 503 from sync endpoints; do not
  weaken this back to an empty result/HTTP 200, because that makes an auth outage
  indistinguishable from a no-spend day.

---

## 6. Current state (2026-06-19)

- ✅ Sleep history backfilled (~1,480 clean nights, 2018→2026) and scored.
- ✅ Free-form schema deployed; column is `varchar(64)`.
- ✅ Live push endpoint `/health/auto-export` built & tested.
- ✅ Chat `live_health` tool built & verified pulling real data from the iPhone.
- ✅ Post-sync recompute optimized: **~81s → ~2s** (GitHub scan backgrounded,
  baselines batched). Android sync recompute window reduced to 30 days.
- ✅ Trend chart: single-day ranges show a dot + caption instead of a confusing
  "pick 7D" nudge (the score is per-day; only the *line* needs 2+ days).
- ✅ **`HealthSyncWorker.kt` and `TrendChart.kt` built & verified (2026-06-19)** on
  the Samsung SM-S921B emulator (API 36.1). Both compiled clean with no fixes
  needed. Verified in-app: (a) 1D range renders the dot + caption, not the old
  nudge; (b) "Sync & analyze" completes the full read→upload→recompute→exec-scan
  round-trip and reports success ("Synced N health record(s) … Auto-detected …
  commit(s)"). On the emulator Health Connect is empty, so N=0 — that's expected.
- ✅ **Trend chart sparse/stale fixes (2026-06-19, `TrendChart.kt`).** Two issues
  the headline number was *stale* on multi-day ranges (e.g. Sleep 7D showed a big
  "77" from an older night while 1D showed "no data"), and the single-point state
  looked unfinished. Fix: when the latest reading isn't the window's most-recent
  day (`isStale`), the headline is muted + tagged `latest · <date>`; the
  single-point body now shows `<value> · <date>` next to the dot with range-aware
  copy ("Only one day with X data in the last N days …" for 7D/30D, the neutral
  "Just one day in this range …" for 1D). NOTE: the 1D window's one point is the
  most-recent day in *history*, which may be yesterday — today can be empty — so
  1D copy must stay date-neutral (don't say "today").
- ✅ **Ask tab Enter fix (2026-06-19, `ChatScreen.kt`).** The input `OutlinedTextField`
  had no `singleLine`/`imeAction`/`keyboardActions`, so pressing Enter inserted a
  newline and grew the field, shoving the layout. Now `singleLine = true` +
  `KeyboardOptions(imeAction = ImeAction.Send)` + `KeyboardActions(onSend = { vm.send() })`
  (`send()` already guards empty/sending). Built clean. Could NOT inject the Enter
  keystroke to film it (see tooling note below) — fix verified by compile + code.
- ✅ **Ask tab keyboard + message placement (2026-06-19, `ChatScreen.kt`).** Two
  more complaints: after send the soft keyboard lingered (needed a back-press),
  and the new message + "Thinking…" appeared stranded at the very top near the
  header. Fixes: a shared `submit()` calls `vm.send()` then
  `focusManager.clearFocus()` (dismisses the IME) and is wired to BOTH the send
  button and `onSend`; a `rememberLazyListState` + `LaunchedEffect(itemCount)`
  auto-scrolls to the newest item; and the thread is bottom-anchored
  (`Arrangement.spacedBy(12.dp, Alignment.Bottom)`) once a conversation starts so
  messages rise from the input (the empty/suggestions state stays top-aligned).
  Message placement verified on the emulator via a suggestion tap. GOTCHA caught
  by the build: `LocalFocusManager` lives in `androidx.compose.ui.platform`, NOT
  `androidx.compose.ui.focus` — the wrong import failed `compileDebugKotlin`.
- ✅ **"Unknown · 0%" state chip fixed (2026-06-19, `DashboardScreen.kt`).** When
  the day's state classifier returns `unknown`/confidence 0, the Life Score hero
  chip showed a confusing "◆ Unknown · 0%". Now an absent state → "Awaiting data",
  an `unknown`/blank state → "No clear state yet", and the `· N%` confidence is
  only shown for a real state with non-zero confidence. Verified on the emulator.
- ✅ **Sleep-gap root-caused & live-push endpoint re-verified on the Jetson
  (2026-06-19).** Confirmed why no recent sleep reaches LifeOS: **100 % of sleep
  rows are `apple-` (the one-time XML import), latest night 2026-06-13, ZERO `hae-`
  rows — the Health Auto Export live push has never fired.** (3704 sleep rows /
  1482 nights, earliest 2019-09-22.) So the last ~6 days are blank because that's
  when the `export.zip` was taken, and the unattended path was never turned on.
  Separately re-tested `/health/auto-export` end-to-end against the live container
  with a synthetic 2017-01-01 night: ingest `inserted=5`, re-POST `inserted=0
  skipped=5` (source_id dedup), `recompute_day` wrote `sleep_score=83.8` for the
  day (+ the day-before). Test rows + their score/state rows were deleted; DB left
  pristine. **Endpoint/parser are healthy — the only missing piece is the iPhone
  setup (§7).**
- ✅ **Live push GOING (2026-06-19, later same day).** The user created the REST
  automation and it is **firing**: a push landed carrying just the most-recent day
  (06-18 UTC, last night's 4.33 h session + that day's vitals), earliest data
  06-18 18:30Z — i.e. a short window that does **not** reach the import. Automation
  is configured correctly; going forward only it runs (the TCP pull below was a
  one-off). Sleep now flows: `hae` rows span 06-14→06-18, `apple` import ends 06-13.
- ✅ **Gap (06-14→06-18) backfilled via a one-off TCP live-pull (2026-06-19).** The
  automation's short window can't fill days already missed, so pulled them from the
  phone's HAE **TCP server** (`192.168.3.212:9000`) instead of a manual export: a
  stdlib script (run on the Jetson *host*, which reaches both the phone and
  `localhost:8000`) sends one `callTool`/`health_metrics` request
  (`aggregate:true, interval:days`, metrics = sleep + vitals only), and the
  response — **the same `{"data":{"metrics":[…]}}` shape as the REST export** — is
  POSTed straight to `/health/auto-export`. Recipe lives in this session's history;
  the TCP protocol is in `phone_health_client.py`. **Two gotchas burned here:**
  (1) the **UTC-cutoff bug** in §5(a) — filtering the pull by *local* sleepStart
  date re-imported the 06-13 night; fixed. (2) A point-in-time pull can catch only
  a **fragment** of a night still in progress (the 06-19 pull saw a 4-min sliver of
  the 06-18 night, correctly dropped by the <30 min filter) — the automation's
  later push had the real 4.33 h value. Pull is best-effort; the automation is the
  source of truth. Net: nights 06-14/15/17/18 present, 06-16 genuinely empty (watch
  not worn), `sleep_score` for the affected days recomputed and correct.
- ✅ **"No sleep for today" root-caused & fixed: day-binning moved UTC → IST
  (2026-06-19).** The dashboard is today-centric but `daily_scores[today]` was
  always empty: the user's `sleepStart` is after local midnight (e.g. 02:52 IST =
  21:22 UTC *yesterday*), so UTC binning filed last night's sleep under *yesterday*.
  Fix: `ALTER DATABASE lifeos SET timezone TO 'Asia/Kolkata'` + API restart +
  `/analytics/recompute?days=120`. Verified: 06-19 went from null → `sleep_score`
  20.3 (last night's 4.33 h) and the whole recent series re-aligned to the user's
  IST day. Full history (2,791 days) later recomputed into IST. Audit-timestamp
  skew from the tz switch was fixed in commit `fdffe72` (see §5).
- ✅ **Single-source migration: Apple/HAE only, Health Connect removed (2026-06-21).**
  Per the user — read all health data through one pipe (the HAE REST push) to end
  the Samsung-vs-Apple activity double-count. Done: (a) **DB** — deleted all 715
  non-`apple`/non-`hae` rows; only `apple` (import) + `hae` (live) remain. (b)
  **Android** — `HealthSyncWorker` and `DashboardViewModel.syncAndAnalyze()` no
  longer `readRecentSamples`/`sync` Health Connect; both just `recompute`. Removed
  the now-unused `HealthRepository` injection from each. **Built & verified**:
  `:app:assembleDebug` SUCCESS (incl. KSP + Hilt codegen) via the cached Gradle
  dist (the repo's `gradle-wrapper.jar` is missing and the `gradlew` script mangles
  its JVM opts — invoke `org.gradle.launcher.GradleMain` from
  `~/.gradle/wrapper/dists/.../gradle-8.11.1/lib/gradle-launcher-*.jar` with the
  Android Studio JBR instead). APK at `android/app/build/outputs/apk/debug/`. **User
  must install this build** so the old one stops re-uploading HC (until then a
  manual "Sync & analyze" on the old build re-adds HC rows — just re-run the delete).
  The HAE automation already pushes everything (sleep+vitals+activity), so it's the
  complete source; enable **Workouts** in the automation if workout data is wanted
  (HC's old workouts were deleted). Activity is additive so multiple intra-day HAE
  points sum correctly.
- ✅ **Manual “Mark as repaid” flow (2026-07-16).** Money transaction rows now
  open a details dialog with a deliberately guarded repayment action: the backend
  ranks later same-amount credits (human display names are compared separately
  from drifting UPI handles), the user explicitly selects the return, and Android
  requires a **1.5-second press-and-hold** before saving. The original bank rows
  are never deleted: the debit is tagged `lent_returned`, the credit `lend_return`,
  and both disappear from spending/income totals. Every pairing is audited in
  `transaction_reconciliations`; the success snackbar provides Undo, which restores
  both prior categories and leaves an `undone_at` audit record. API:
  `GET /expenses/{id}/repayment-candidates`, `POST /expenses/repayments`, and
  `DELETE /expenses/repayments/{id}`. Rebuild deletion deliberately preserves any
  transaction referenced by an audit record, so a Gmail reparse cannot erase a
  user decision. Backend full suite and Android `assembleDebug` + unit tests pass.
- ✅ **Ask streams end-to-end (2026-07-17).** `POST /chat/stream` returns NDJSON
  `start`/`delta`/`done` events backed by MLC's OpenAI-compatible SSE stream. The
  Android Ask screen consumes the response incrementally, keeps the newest token
  visible, and attaches citations from the final event. The bounded tool loop and
  general-vs-personal routing are preserved; leading `<think>` output is filtered.
- ✅ **Blue visual refresh + spend widget (2026-07-17).** The muted orange app
  identity was replaced with a cool navy/ocean-blue palette. The bottom nav is a
  flat, edge-to-edge mist-blue dock with five labeled, equal-width tap targets and a
  slim selection marker (no rounded container or Material selection pill). It
  continues behind Android's native Recents/Home/Back area, whose icons are
  dark to match the active navy destination, so there is no separate system-navigation strip. A resizable home-screen widget shows
  today's and the current month's INR spend from the same filtered transaction
  cache as Money. The widget is a compact Samsung 3×1 row with a translucent
  navy/glass background (the original installed 3×2 instance was manually resized
  after its provider dimensions changed). It refreshes after app-open/manual sync and via six-hour
  best-effort WorkManager refresh; tapping it opens Money.
- ✅ **One sync surface + app-open refresh (2026-07-17).** The Today and Money
  sync controls were removed; Settings owns the single **Sync everything** action.
  `LifeOsSyncCoordinator.syncAll()` checks incremental bank alerts + statement
  summaries/EMIs, refreshes Pulse, then recomputes health/execution/insights.
  App process start calls `refreshOnAppOpen()`, which only refreshes the Android
  caches from the Jetson (no Gmail polling or LLM work), so opening Ask cannot
  contend with the local model. The nightly WorkManager recompute remains.
- ✅ **Ask general-question routing (2026-07-17).** `ChatService.ask()` now routes
  explicit personal-data questions through the grounded LifeOS context/tools and
  general knowledge/code questions through a clean general prompt with no personal
  snapshot or stale sleep history. Clear new topics intentionally drop history;
  short follow-ups retain it. A Fibonacci regression test guards the reported bug.
- ✅ **Statements use separate summary, detail, and EMI accounting paths
  (2026-07-17, detail storage/UI added 2026-07-21).** Monthly statement PDFs
  populate `card_statements` (total/minimum due, due date, cycle dates) and all
  recognized explanatory rows now live in `card_statement_items`. Detail rows
  power the per-card statement screen only and never enter spend; live alerts own
  normal purchases. The only statement rows retained in `transactions` use
  `source='statement_emi'`: deterministic
  principal/interest/processing-fee/loan patterns plus ambiguous whole-word-EMI
  merchant rows approved by one batched local-model review. Model timeout,
  malformed output, or uncertainty skips ambiguous rows; conversion credits are
  always dropped. Before inserting, `live_coverage_exists` checks bank/card,
  merchant, ±1 day, and a narrow FX tolerance. WHY: statement vs live duplicates
  survived exact date+amount dedup (e.g. OpenAI USD 23.60 settled ₹1.59 lower).
  Categorization gives whole-word EMI narrations the dedicated `emi` category
  before shopping/merchant rules (Axis appends text like `DEPT STORES`). The
  Android Money screen uses calendar periods, not statement cycles: an EMI dated
  June 30 is under June even when it appears on a June 17–July 15 statement.
  Detail parsing is versioned (`details_parser_version`) so saved Gmail PDFs can
  be reparsed idempotently. Payment state persists only as `paid_at`; API status
  (`upcoming`/`overdue`/`paid`) is derived so time-based state cannot go stale.
  Android opens a dedicated, long-scroll statement screen from each upcoming-card
  row, shows activity categories plus every parsed debit/credit, reconciles any
  prior-balance/summary-only difference as “Previous balance & adjustments”, clearly states
  that details are excluded from Money spend, and updates the cached due footer
  immediately after marking paid. Backend full suite (266 tests) and Android
  `assembleDebug` + unit tests pass. **Deployed and installed 2026-07-21:** the
  idempotent Jetson migration in `backend/scripts/2026_07_21_statement_details.sql`
  was applied before rebuilding the API, a 90-day sync stored 469 detail rows
  with zero new `statement_emi` spend rows, and all seven upcoming bills are
  `details_status='ready'`. APK installed and the Axis 0872 real-data details
  screen was visually verified on Samsung device `RZCX60SNHHE`. **UI refinement
  2026-07-21:** payment can only be changed on the statement breakdown screen;
  the redundant action was removed from the expanded due list. The breakdown uses
  Recent/Oldest and Highest/Lowest sort controls. Statement transactions now match
  the compact Money drill-down: flat rows, 10dp row spacing, 2dp vertical padding,
  single-line ellipsized descriptions, consistent trailing amounts, and no row
  dividers or individual cards. Tapping a row reveals its full description. All
  en-dash and em-dash characters were removed from Android source and user-facing
  copy. Parser v2 adds nullable
  `card_statement_items.txn_time`: HDFC's printed clock time is preserved and
  shown with the date, while other banks remain honestly date-only. The migration
  was reapplied idempotently, the 90-day v2 reparse stored the same 469 rows with
  zero new spend items, all 206 HDFC detail rows have a time, and the redesigned
  HDFC 6116 screen was installed/visually verified on `RZCX60SNHHE`.
- ✅ **Savings-account statement parsing disabled (2026-07-23).** Only credit-card
  PDF parsers are registered in `statement_parsers.get_statement_parsers()`.
  `Sync everything` must not search for, download, parse, or store savings/current
  account e-statements. The inactive parser implementations remain for historical
  test coverage, but do not register them again. Later the same day, at the user's
  request, all 15 July-created `*_acct` summaries and their 126 stored detail rows
  were deleted from production, along with 12 HDFC Savings `statement_emi` rows
  derived from those PDFs. Transaction-email rows, all 38 credit-card statements,
  and all 27 credit-card EMI rows were preserved. Production now has zero non-card
  statements and zero non-card statement-derived transactions. Android statement
  refresh now atomically replaces its Room cache instead of append-only upserting,
  so server-deleted statements disappear locally on the next refresh as well.
- ✅ **Android visual-system cleanup + working Pulse permission (2026-07-17).**
  Bottom navigation now has five primary tabs, inset rounded bounds (so edge-item
  ripples are not clipped), labels only on the selected tab, and no nav bar while
  Settings is open. Settings moved to the Today header and was rebuilt as three
  restrained groups (Connection, Automation, About); obsolete Health Connect and
  Mac/OpenClaw setup copy was removed. Pulse Reminder was already backed by a
  daily WorkManager job that checks Room and deep-links to Pulse, but Android 13+
  notification permission was never requested, so it could silently no-op. It is
  now explicit opt-in (default off), requests `POST_NOTIFICATIONS`, and reconciles
  legacy enabled-without-permission state. The dashboard hero and Pulse selection
  tints now use the shared terracotta/sage theme instead of page-specific green,
  pink, and yellow palettes. Built, unit-tested, installed, and visually checked
  on Samsung device `RZCX60SNHHE`.
- ✅ **Money edits, early card payment, Ask hardening, and system night mode
  (2026-07-18).** Money transaction details now support a per-row spend-category
  override persisted on the Jetson (`transactions.spend_category_override`), with
  Automatic restoring the payee/keyword result. This is separate from the
  reconciliation `category`. Expanded card dues can be marked paid before the due
  date; `card_statements.paid_at` retains the statement while excluding it from
  upcoming totals. Ask now treats spending-only context as real LifeOS data,
  explicitly uses authorized personal context, and filters leaked Qwen chat-template
  tokens as well as `<think>` blocks. Android colors and system bars inherit the
  system light/dark setting. **Existing Jetson databases require both columns to be
  added manually because `create_all` does not ALTER tables.**
- ✅ **Ask transaction-table querying (2026-07-27).** The existing bounded Ask
  tool loop now advertises `query_money`, a guarded NL-to-query layer over stored
  `transactions`. It answers exact arbitrary-period totals, category/merchant/
  account/source breakdowns, rankings, income, and matching-row questions without
  exposing raw SQL or write access. Historical transaction coverage is included
  in the chat context so an older-period question can still enter the tool loop
  when the current 30-day snapshot is empty. A deterministic prefetch planner
  guarantees common factual money queries hit the table even if Qwen ignores
  optional tools; this was added after the first production smoke test exposed
  that exact failure. The previous fast-path bug that treated "food last month"
  as the current-month total is regression-tested. Backend full suite passes.
- ✅ **Exact open credit-card cycle spend (2026-07-30).** Money now keeps three
  accounting views separate: calendar-period spend, already-generated card
  bills, and recorded spend inside each open card cycle. The backend endpoint
  `GET /statements/cycles/current` infers each card's live cycle from the exact
  `period_start`/`period_end` cadence in stored statements, preserves
  end-of-month schedules, and matches live transactions by bank + last-four identity (including
  suffix-normalized SBI `70`/`1970`). Card payments/transfers/reconciled rows
  are excluded; recorded debit spend and refunds are exposed separately. There
  are deliberately **no forecasts**: no expected EMIs, future fees,
  subscriptions, projected payment dates, confidence labels, or approximate
  bill totals. Android caches the server result in Room v12 and shows a separate
  expandable **Credit card cycles** panel:
  **Already billed** (confirmed statement totals, tappable for breakdown) vs
  **Current cycle spend** (only recorded transactions). Every card row gives
  its exact active cycle start and billing end date. The former bottom-only dues
  footer was folded into this panel;
  calendar spend remains unchanged. Backend requires no schema migration.
  Backend full suite and Android `assembleDebug` + unit tests pass. The exact
  production open-cycle spend at implementation time is ₹26,974.65, while
  generated August bills total ₹64,397.79. **Corrected, deployed, and installed
  2026-07-30 (commit `8a3f0fd`):** the earlier forecasting implementation was
  removed at the user's request. Jetson production output now contains only
  `current_spend_total`, `refund_total`, `net_cycle_total`, and exact per-card
  cycle fields. The Room v12 APK was installed on Samsung `RZCX60SNHHE`; the
  collapsed exact total and all expanded per-card amounts, cycle ranges, billing
  dates, long HDFC labels, and canonical SBI `····1970` were visually verified.
  **Interaction and detail pass (2026-07-30):** the two summary tiles now act as
  selectors, so Money shows either generated bills or current cycles rather than
  both lists at once. Both paths use the same compact clickable card-row design.
  Every current-cycle row opens a dedicated page backed by
  `GET /statements/cycles/current/{bank}/{card_last4}` with the exact cycle start,
  billing end date, recorded spend, refunds/net, category breakdown, sortable
  live transactions, and tap-to-expand descriptions. The generated-statement
  path continues to open its existing statement breakdown page. No forecast or
  unrecorded charge is introduced.
  **Compact Money + bank UPI filter (2026-07-30):** credit-card rows are closed
  by default; the two billed/current totals remain visible and each tile toggles
  only its own list. The monthly spend card now carries a compact source menu
  without adding another page section: All spend, all bank-account UPI, or one
  proven savings account (currently Axis `····4402` and HDFC `····7672`).
  Bank UPI identity is strict: only `source='upi'` rows whose bank + suffix is
  proven by an explicit Savings label qualify, so RuPay/credit-card UPI is
  excluded. Legacy label drift such as HDFC `Card ..7672` is recovered only
  when the same bank + suffix is proven as Savings elsewhere; unknown rows are
  not guessed. The live July check showed all bank UPI ₹14,049.90 / 24 rows,
  HDFC ₹12,269.90 / 20, and Axis ₹1,780 / 4.
- ✅ **Manual merchant refunds + everyday-spend EMI view (2026-08-04).** A debit
  can now be marked refunded even when the bank/app showed the refund but Gmail
  supplied no incoming credit alert. `POST /expenses/refunds` records a
  `merchant_refund` in the new `transaction_adjustments` audit table, preserves
  the original transaction, tags it `reversed_debit`, and excludes it from
  spend; `DELETE /expenses/refunds/{id}` restores the prior category for Undo.
  Rebuild deletion preserves adjusted source rows. Android exposes the action
  from transaction details with the same 1.5-second hold safety used for manual
  repayments and an Undo snackbar. Separately, the main Money spend view keeps
  `emi` and `emi_fees` transactions recorded but excludes them by default; the
  summary says `EMI excluded` and an `Include EMI` chip restores the full
  cash-outflow view. This filter is intentionally presentation-only: statements,
  card cycles, stored transactions, and Ask remain complete. The ₹65,632 August
  EMI is a real 2026-08-03 live HDFC Savings UPI mandate to Poonawalla Fincorp
  (`transactions.id=1410`, override `emi`), not a statement duplicate. Agoda's
  Razorpay handle ends `@mairtel`; the subscription rule now requires a true
  `airtel` word boundary so Agoda correctly resolves to `travel` instead of a
  false Airtel subscription.
- ✅ **Money-first Android redesign (2026-08-21).** Money is now the app's start
  destination and the five-item bottom dock is gone. A top-right LifeOS sheet
  keeps Today, Pulse, Insights, Ask, and Settings available without competing
  with the daily workflow. Money has persistent Overview / Transactions / Cards
  sections: Overview shows period spend, today, prior-period comparison, bills,
  recent activity, and top categories; Transactions is a flat date-grouped feed
  with one filter sheet; Cards keeps billed and current-cycle rows visible.
  Section state survives statement/current-cycle detail navigation, and widget
  opens explicitly reset Money to Overview. Large occasional expenses no longer
  need to collapse into `other`: `vehicle`, `insurance`, and `taxes` are editable
  and LLM/deterministic categories. The compatible `legal_fees` storage key is
  displayed as the broader **Professional services** category so old lawyer
  labels stay grouped with any later accountant/consultant expense. Android was
  built, installed, and visually verified on Samsung `RZCX60SNHHE` in light and
  dark modes; Overview, filters, transaction actions, Cards detail return, and
  More were exercised against real cached data, and all four Compose device
  tests pass.
- ✅ **Transaction sorting (2026-08-24).** The Money Transactions header now has
  one compact Sort control beside Filters. Its four orders are Recent, Oldest,
  Highest amount, and Lowest amount; date orders retain date-group headings,
  while amount orders use a truthful global flat ordering with the date still
  visible on every row. The selection lives in `MoneyUiState`, so it survives
  configuration changes and detail navigation. Unit ordering tests and all five
  Money Compose device tests pass on Samsung `RZCX60SNHHE`; the final APK was
  installed and Recent/Highest states were visually verified against real data.
- ✅ **Safe primary navigation drawer (2026-08-25).** The Money-first screen's
  bottom “More” sheet was replaced with a standard left-side navigation drawer,
  opened by a top-left menu button. It lists all seven LifeOS areas, highlights
  the current destination, preserves destination state, and applies status- and
  navigation-bar insets so no option can sit beneath Samsung's native controls.
  Android unit tests and all five Money Compose device tests pass; the final APK
  was reinstalled on Samsung `RZCX60SNHHE` after the connected test runner's
  cleanup uninstalled its target package.
- ✅ **Single-model admission control + mail sync lease (2026-08-28).** Every
  LifeOS generation now enters through the thread-safe coordinator in
  `services/openclaw.py`, matching MLC's one-sequence capacity. Ask is
  interactive priority and waits within its existing request deadline; Money
  parsing is finance priority; Priority semantic review and insight generation
  are background priority. Finance/background calls fail fast while the model
  is occupied, so their safe fallback/defer paths run instead of forming a
  backlog, and they release the slot between emails so a waiting Ask goes next.
  Finance calls are capped at 30 seconds and background calls at 45 seconds;
  Ask keeps its aligned 90/135-second budgets. `/chat/health` exposes the active
  workload and waiter counts. Separately, `/expenses/sync`, `/statements/sync`,
  and `/priority/sync/check` share a non-blocking mail-job lease: an in-process
  lock plus a PostgreSQL advisory lock held on a dedicated connection, so
  automatic/manual Gmail jobs cannot overlap even if API workers are added. A
  collision returns HTTP 200 with a deferred message; the next incremental run
  catches up. (Priority is now model-led; see the 2026-09-24 Priority update.)
  NOTE: this runtime admission layer does not solve the Jetson's boot-time CUDA
  allocation race; the 4K model must still load from clean memory before
  ancillary containers are restored (§5 watchdog recovery).

## 7. Open items / next steps

- **Mentor Insights + split Settings (2026-09-24).** Executed
  `.claude/plans/mentor-insights.md` (local plan file). Insights is now 3-5
  ranked, LLM-written mentor judgements across all data (money first), not
  correlation cards. **The backend does all arithmetic**:
  `app/services/life_evidence.py::build_life_evidence` computes ≤45 cited
  facts (income vs spend, MTD vs last month, per-category vs 3-month usual,
  essential-vs-discretionary comparisons that skip Other/EMI and use each
  larger category once, big one-offs, budget pace, fixed EMIs + recurring,
  card bills due in 10 days, open card-cycle spend, trips from `trip_backups`,
  and week-over-week sleep/activity/mood/work) using the Money page's own
  categorizer + exclusions, so totals reconcile (verified: evidence MTD
  ₹184,899 − EMI ₹22,091 − EMI fees ₹144 = Money's ₹1,62,664 "EMI excluded").
  `insight_synthesizer.py` asks Qwen for `{title, verdict, action, domain,
  severity, cites}`; uncited items are dropped, no canned fallback (last good
  set is kept). New: `GET/PUT /money/profile` (manual monthly income + savings
  goal, singleton `money_profile` table), `POST /analytics/synthesis/refresh`
  (`INSIGHTS_JOB` lock; returns old rows + `X-Insights-Stale: 1` when the model
  yields nothing), migration `0003_insight_mentor_fields.sql` (`action`,
  `severity`). **Two real-data bugs fixed during deploy:** (1)
  `app/migrations.py` split on a `;` inside a SQL comment, which stopped API
  startup for ~3 minutes; comment lines are now dropped before splitting
  (regression test added). Never put `;` in migration comments anyway. (2)
  Qwen returned objects without the outer `[ ]`; `_parse` now decodes a bare
  run of objects. The prompt also forbids invented debts/diagnoses and
  misdated trips (first run called the upcoming Delhi trip "recent" and
  invented "debt"). Final production output (income unset): travel ₹89,411
  = 148x usual; fuel ₹9,300 = 155% of ₹6,000 budget; vehicle ₹14,800 = 14.5x;
  shopping ₹30,430 = 3.2x; sleep score 23 vs 42. All numbers trace to cited
  facts. Known weakness: it can still editorialise ("only 7.6 hours"), so
  inspect real output after prompt changes. **Android:** Insights shows only
  mentor cards (domain chip, severity dot, "→ action") with pull-to-refresh
  ("Thinking it over..."). Settings has Money (income, budgets with the same
  offline pace line as Overview, card due reminders moved here) and Trips
  (auto backup via `TripBackupWorker`, default on, plus "Back up all trips
  now") sub-pages. Verified on `RZCX60SNHHE` in dark + light: pull-to-refresh,
  budget add/remove round-trip against `GET /budgets` (production left with
  Food + Fuel only), and trip backup (Delhi Family Trip now in
  `trip_backups`, appears as a `trip:` fact). The user has not entered income
  yet; do not invent it.

- **Staged improvement plan, Stage 5 — Cross-domain & polish (2026-09-24).**
  Final stage of `.claude/plans/split-into-stage-and-rosy-fiddle.md` (local
  plan file, not in the repo). All four sub-parts implemented and deployed.
  **5a (Android):** `TripRepository.observeSuggestedExpenses(tripId)`
  (`trips/data/TripRepository.kt`) joins Money's cached transactions (a new
  `TransactionDao.observeBetween(start, end)` query, debit-only) against the
  trip's `[startDate, endDate]`, filtered to ids not already linked via
  `TripExpenseEntity.transactionId` (that column already existed, unused,
  from the original Trips schema). Trip Details' spending card now lists
  these as "From your spending during this trip" with Add/Dismiss actions
  (`TripsViewModel.linkSuggestedExpense`/`dismissSuggestedExpense` —
  dismiss is session-only, not persisted, since it's just hiding a
  suggestion the user will still see it re-offered next visit if unlinked
  elsewhere). No cross-database join at the SQL level — `TripDatabase` and
  the main `LifeOsDatabase` are separate Room instances by design, so this
  combines two Flows in Kotlin via `flatMapLatest`. Trip total already
  equals linked + manual expenses for free, since both are the same
  `TripExpenseEntity` table. **5b (backend, new):**
  `app/services/weekly_recap.py` computes deterministic week-over-week
  numbers (sleep score avg, mood avg, spend, and any budget on pace to
  exceed — reusing `budget_pace_summary` from Stage 4b) for the 7 days
  ending yesterday vs. the 7 before that, hands them to Qwen as grounded
  EVIDENCE (same citation-bound shape as `insight_synthesizer`) to phrase
  into 2-4 sentences, and falls back to a deterministic joined-facts
  paragraph if the model is disabled/unreachable/returns junk — a recap is
  never empty when there are real numbers. Stored one row per ISO week in a
  new `weekly_recaps` table (`create_all`, no migration needed) via
  `WeeklyRecapRepository.upsert` (re-running the same week overwrites, not
  duplicates). `POST /analytics/weekly-recap` (guarded by a new
  `WEEKLY_RECAP_JOB` `try_job_lock`, same pattern as mail-sync) computes,
  stores, and best-effort pushes via the existing FCM path
  (`Notification(severity="info", title="Your week in review", ...)`);
  `GET /analytics/weekly-recap` serves the latest for polling. New
  `backend/ops/systemd/lifeos-weekly-recap.{service,timer}` (`OnCalendar=Sun
  21:00`) modeled on the existing `lifeos-priority-sync` unit — **these
  still need to be copied to the Jetson's systemd unit directory and
  enabled** (`sudo cp ... /etc/systemd/system/ && sudo systemctl daemon-reload
  && sudo systemctl enable --now lifeos-weekly-recap.timer`); the route
  itself is deployed and was verified live against real production data
  (real recap generated: sleep 42→23, mood 4.0→3.0, spend down ₹25,145 vs
  last week, "on pace to exceed this month's budget in: fuel" — every
  number traced back to an actual computed fact, re-running overwrote
  rather than duplicated). **5c (Android, mostly free):** the nightly
  correlation pipeline already computes `daily_spend`↔`sleep_score`
  (both directions, lagged) — this was existing data with zero UI consumer.
  `DashboardViewModel` now also observes `analyticsRepository.observeInsights()`
  and picks the strongest spend↔sleep pair; `DashboardScreen` renders it as
  a "SPENDING & SLEEP" card (same visual pattern as the existing "why this
  state" rationale card), narrative text preferred over the raw summary.
  Verified live: "Better sleep is tied to higher spending — daily spend
  jumps 172% when sleep score exceeds 42.1." **5d (Android):** a shared
  `OfflineBanner(lastSyncAt)` composable (`presentation/components/Components.kt`)
  shows "Offline · as of HH:mm · showing cached data" using the
  already-tracked `SettingsRepository.lastSyncAt`; wired into Today
  (replacing a plain "Backend unreachable" text) and Money's existing
  `CachedDataBanner` (which already had similar behavior — extended it with
  the same as-of-time clause rather than duplicating). The home-screen
  widget's small subtitle (`widget_spend.xml`, id `widget_updated_at`)
  shows `"UPD HH:mm"` instead of a static `"SPEND"` label, sourced from the
  same `lastSyncAt` (`SpendWidgetUpdater` now also injects
  `SettingsRepository`). **Verified:** backend `pytest` — full suite passes
  except the same 4 pre-existing failures documented throughout this file as
  unrelated (`test_card_cycles`, `test_priority`, two in `test_statements`);
  new `tests/test_weekly_recap.py` (6 tests) passes clean. Android
  `testDebugUnitTest` and `assembleDebug` both clean; installed on Samsung
  `RZCX60SNHHE` and visually verified: the Today spend↔sleep card and Money
  Overview both render real production data correctly. **Not yet visually
  verified:** the widget's `UPD HH:mm` label (no `SpendWidgetProvider`
  instance was currently pinned to a home screen page on this device during
  this session — add the widget to check) and the Trips suggestion
  list/Add/Dismiss flow (needs a trip whose date range overlaps cached
  transactions to exercise; none was active this session). All Stage 5
  backend code is deployed on the Jetson; the weekly-recap systemd timer
  itself still needs manual installation there (see above).
- **Staged improvement plan, Stage 4 — Money features (2026-09-23).** Third
  stage of `.claude/plans/split-into-stage-and-rosy-fiddle.md` (local plan
  file, not in the repo); all three sub-parts done, but **on-device visual
  verification is still pending** (phone was locked/asleep at the end of this
  session — code is built, tested, and committed, but not yet
  screenshot-verified in-app; re-verify next session before considering this
  fully closed). **4a (Android-only):** new `CardDueReminderWorker` posts a
  one-shot local notification at T-3 and T-1 days before an unpaid card
  statement's due date, rescheduled (via `WorkScheduler.scheduleCardDueReminders`,
  unique work per statement+offset) after every money refresh — both
  `refreshOnAppOpen()` and `syncAll()` in `LifeOsSyncCoordinator`. New Settings
  toggle `cardDueRemindersEnabled`, **default on** (unlike Pulse's opt-in-off:
  a missed due date has a real cost, and it's only 2 notifications/statement).
  New `card_due_reminders` notification channel. A shared permission-request
  launcher in `SettingsScreen.kt` now tracks which toggle (`pulse` vs
  `card_due`) asked for `POST_NOTIFICATIONS` via `pendingPermissionTarget`, so
  granting it enables the right one. **4b:** new `GET/PUT/DELETE /budgets`
  (`app/models/budget.py`, `app/api/routes/budgets.py`) stores one monthly
  limit per category — `create_all` handles the new table, no migration file
  needed. Pace (spent-so-far vs. projected month-end total, and whether/when
  it's on pace to exceed) is computed identically in two places from the same
  `spent / days_elapsed * days_in_month` formula: `app/services/budgets.py`
  for Ask's `get_life_context` (the `budgets` key is only present when at
  least one budget is set), and a new pure `budgetPace()` in
  `MoneyAggregation.kt` for the Android UI — the latter runs entirely
  on-device against the cached transaction feed (`spendableTransactions`,
  unfiltered by the period/source/EMI toggles a budget shouldn't care about),
  so it works offline; only the limit itself is synced from the backend
  (new `budgets_cache` Room table, `LifeOsDatabase` bumped to v14 via
  `fallbackToDestructiveMigration` — a cache table, safe to wipe/refetch like
  `card_statements_cache` before it). Money's "View all categories" sheet
  gained a per-row "set budget" (⊙) action opening a simple limit
  dialog (editable/removable); Overview shows a Budgets section with progress
  bars and pace text above Top categories. **4c:** new
  `app/services/recurring.py` groups debits by normalized counterparty
  (strips digit runs ≥4 chars — transaction reference numbers — then
  lowercases/collapses whitespace) and flags ≥3 occurrences whose gaps
  cluster within ±4 days of a weekly (3–11d median) or monthly (26–34d
  median) cadence *and* whose amounts stay within ±10% of the median —
  deliberately conservative so irregular spending never gets mislabeled.
  Reports `next_expected_date` and `price_change_pct` (last vs. median).
  Detection only, per the 2026-07-30 no-forecasting decision — never feeds
  card-cycle or budget totals. New read-only `GET /expenses/recurring`
  (`window_days`, default 180). Android: a "Recurring" summary row on
  Overview (count, soonest upcoming charge, price-increase count) opens a
  sheet listing every detected charge with a price-increase badge; fetched
  live in `MoneyViewModel.load()` (not cached — matches `analysis()`/`trends()`).
  **Verified:** backend `pytest` — 346 passed, 4 pre-existing failures
  (confirmed unrelated against unmodified `main`); Android
  `testDebugUnitTest` and `assembleDebug` clean. Backend deployed and
  confirmed live on the Jetson (`GET /budgets` → `[]` on a fresh DB). The
  debug APK was built and installed on Samsung `RZCX60SNHHE`, but the device
  was locked for the rest of the session — the UI itself (toggle, budget
  bars, dialog, recurring sheet) has **not** been visually confirmed on
  device yet.
  **Visual verification completed 2026-09-24.** Found and fixed a real
  deploy gap first: the previously-installed APK's `lastUpdateTime` predated
  the final `assembleDebug` build (20:20 vs the on-disk APK's 20:33), so the
  device was silently running pre-Stage-4c code — `adb logcat` showed zero
  `GET /expenses/recurring` calls despite the backend returning real data.
  Always compare `adb shell dumpsys package <pkg> | grep lastUpdateTime`
  against the APK's mtime, not just "I ran install earlier", before trusting
  on-device behavior. After reinstalling the current APK: the Settings
  "Card due reminders" toggle renders correctly (OFF, gated on the
  ungranted `POST_NOTIFICATIONS` permission — expected, mirrors Pulse's
  same pattern); Money Overview's Recurring summary row showed the real
  detected Spotify India subscription (`₹299 · monthly · next 2026-10-01`)
  and opened `RecurringChargesSheet` with the correct detection-only copy;
  the "View all categories" sheet's per-row budget (⊙) icon opened
  `BudgetEditDialog`, a ₹15,000 Vehicle maintenance budget was saved
  end-to-end (`PUT /budgets/vehicle` confirmed server-side), and the
  Overview Budgets section then rendered the progress bar and
  "On pace to exceed by day 25" pace text matching the API's
  `on_pace_to_exceed`/`projected_exceed_day`. The test budget was deleted
  afterward to leave production clean. Stage 4 is now fully closed.
- **Staged improvement plan, Stage 3 — sync visibility & stale-data alerts
  (2026-09-23).** Final stage of `.claude/plans/split-into-stage-and-rosy-fiddle.md`
  (local plan file, not in the repo) — all three sub-parts done. **3a
  (backend):** new `GET /status/sync` (`app/api/routes/status.py` +
  `app/services/sync_status.py`) reports five sources — `bank_mail`,
  `statements`, `priority_mail`, `sleep_vitals`, `model` — each with
  `last_success_at`, `last_attempt_at`, `last_error`, and a `stale` flag.
  Bank mail/statements freshness reads real *data* signals (`CollectorState.updated_at`
  filtered to `gmail_expenses:%`; the newest `CardStatement.statement_date`)
  rather than just "did the sync call return 200", because a sync can
  succeed every run while finding nothing new — a new `sync_attempts` table
  (migration `0002_sync_attempts.sql`) separately tracks attempted-not-just-successful
  syncs, wired into `/expenses/sync` and `/statements/sync`, so e.g. a Gmail
  `invalid_grant` failure is visible even though it never advances a cursor.
  Priority mail reuses the existing `MailSyncState`; sleep/vitals is
  `max(health_records.created_at)` filtered to `source_id LIKE 'hae-%'`;
  model reuses `OpenClawClient.health()`. Thresholds are configurable
  (`status_sleep_stale_hours=36`, `status_bank_mail_stale_hours=72`,
  `status_priority_mail_stale_hours=24`, `status_statements_stale_days=40`).
  **3c backend half:** `NotificationService._stale_sources()` surfaces any
  stale source through the existing `/notifications` + FCM path.
  **3b+3c Android:** Settings gained a "Data sources" panel (colored dot +
  relative last-seen time or error text per source, reusing the
  `formatLastSync`/`LifeOsColors.Warning` pattern already used for sync
  staleness elsewhere) via a new `AnalyticsRepository.syncStatus()`; the nav
  drawer's Settings entry shows a small warning dot when any source is stale
  (new app-scoped `LifeOsAppViewModel`, since that's above any one screen's
  ViewModel); `NightlyRecomputeWorker` also checks `/status/sync` after each
  recompute and posts a local notification (new `data_health` channel,
  deduped per source per calendar day via a small SharedPreferences file —
  deliberately not routed through the full `SettingsRepository`/DataStore
  abstraction for this narrow, self-contained case).
  **Found and fixed a real bug via hand-testing against production, not the
  test suite:** the DB session runs in Asia/Kolkata (§5), so raw column reads
  (`CollectorState.updated_at`, `HealthRecord.created_at`, `MailSyncState`
  timestamps) serialized with a `+05:30` offset instead of the UTC `Z` every
  other endpoint in this API emits; Android's `Instant.parse()` only accepts
  `Z`, so parsing silently failed for 4 of 5 sources (caught by `runCatching`,
  which *did* correctly reach the error state — the Settings panel's endless
  spinner was real but the root cause was upstream of the UI code). Fixed
  with a `field_validator` on `SourceStatusOut` normalizing both timestamp
  fields to UTC, so every endpoint keeps the same wire convention; the test
  suite didn't catch this because SQLite doesn't preserve tzinfo, masking the
  exact failure mode — added `test_timestamps_are_normalized_to_utc` as an
  explicit regression test. **Verified end-to-end on Samsung `RZCX60SNHHE`
  against real production data**: all five source rows render with correct
  local-time conversion and green (non-stale) dots; the drawer shows no
  warning dot, matching nothing being stale. Did not force an on-device stale
  visual (would require writing fake old timestamps into production data);
  covered instead by `tests/test_status.py::test_old_data_sources_are_stale`
  and `::test_stale_sources_appear_in_notifications`. Backend: `pytest` — 332
  passed, 4 pre-existing failures (confirmed unrelated against unmodified
  `main`). Android: `testDebugUnitTest` and `assembleDebug` clean.
- **Staged improvement plan, Stage 2c — Tailscale (2026-09-23).** Installed on
  the Jetson (`curl https://tailscale.com/install.sh | sudo sh` + `sudo
  tailscale up`, hostname `ajmalrasi`, tailnet IP `100.111.253.18`,
  MagicDNS `ajmalrasi.tail5c18e0.ts.net`, tailnet suffix `tail5c18e0.ts.net`).
  The phone (`ajmals-s24`, `100.97.205.23`) already had the Tailscale app
  installed but signed out; re-authenticated via the tailnet's GitHub SSO.
  **Found and fixed a real connectivity bug**, not just installed the app:
  `tailscaled`'s auto-generated `ts-forward` nftables chain is supposed to
  accept forwarded traffic from tailnet peers via a connmark-based rule, but
  that rule silently matches zero packets on this Jetson's custom NVIDIA L4T
  kernel (missing `xt_connmark`/`CONNMARK` module — the same issue
  `tailscale status` warns about: "Extension connmark revision 0 not
  supported, missing kernel module"). Net effect: peers could ping the Jetson
  but TCP into the Docker-forwarded backend (port 8000) timed out, confirmed
  via `nft` rule packet counters (the mark-setting rule showed 0 matches) and
  a phone-side `nc` test that changed from timeout to immediate success once
  fixed. Fix: `backend/scripts/fix_tailscale_forward.sh` inserts one
  unconditional `iifname "tailscale0" accept` into the top-level `FORWARD`
  chain (sits outside tailscaled's self-managed `ts-forward` chain, so
  tailscaled regenerating its rules doesn't remove it), installed via
  `backend/ops/systemd/tailscale-forward-fix.service` (oneshot,
  `WantedBy=multi-user.target`, idempotent) so it reapplies on every boot.
  **These two new files were deployed directly to the Jetson (scp + manual
  `systemctl enable --now`) but are not yet committed/pushed** — commit and
  push them so a future `git pull` on the Jetson doesn't diverge from what's
  actually installed at `/etc/systemd/system/tailscale-forward-fix.service`.
  **Verified over Wi-Fi** (still routes through the tailnet, not the LAN,
  since `100.x` addresses aren't on the home subnet): app backend address set
  to `http://100.111.253.18:8000/`, Money loaded live data, then `nc -z` to
  the Tailscale IP flipped from timeout to immediate success after the fix.
  **Could not verify over real cellular-only conditions**: with Wi-Fi
  disabled, even an unrelated public IP (`8.8.8.8`) was unreachable — the
  phone's Airtel SIM registers on the LTE data network (`mDataConnectionState=2`,
  in-service) but has no actual working data session, i.e. no active data
  pack, unrelated to anything in this stage. Re-verify once the SIM has data.
  Phone Wi-Fi and the app's backend address were restored to normal
  (`http://192.168.3.30:8000/`) afterward. **Not yet done**: renaming the
  Jetson's Tailscale hostname to something clearer than `ajmalrasi`, and the
  plan's suggested rollout order (deploy backend with token unset → install
  updated APK → set `API_TOKEN` in `.env` + restart) — no token is configured
  yet, so Tailscale access today is unauthenticated on the LAN-equivalent
  trust level of the tailnet itself.
- **Staged improvement plan, Stage 2a+2b — bearer-token auth (2026-09-23).**
  Second stage of `.claude/plans/split-into-stage-and-rosy-fiddle.md` (local
  plan file, not in the repo); 2c (Tailscale) not started — see below.
  **2a (backend):** `Settings.api_token: str | None` in `app/config.py`, unset
  by default (auth fully disabled — safe for the existing home-Wi-Fi rollout
  and the test suite). New `app/api/deps.py::require_token` compares
  `Authorization: Bearer <token>` with `hmac.compare_digest`; applied per-router
  in `app/main.py` via `include_router(..., dependencies=[Depends(require_token)])`
  on every router except `health_data` (that one applies the dependency to the
  `POST /health/batch` route only, so `POST /health/auto-export` stays exempt —
  Health Auto Export sends no headers and can't be configured to). `/` and
  `/healthz` stay exempt (no dependency attached). CORS tightened from `*` to
  an empty origin allowlist (no browser client exists — Android talks to this
  directly, not via fetch/XHR). `scripts/import_apple_health.py` gained
  `--api-token` (defaults to `$LIFEOS_API_TOKEN`). `ops/systemd/lifeos-priority-sync.service`
  now optionally loads `API_TOKEN` from `backend/.env` and adds the header only
  when set (`${API_TOKEN:+--header ...}`) — **this service file needs to be
  re-copied to the Jetson's systemd unit directory and `systemctl daemon-reload`
  run** for that to take effect there; it's a no-op today since no token is
  configured. New `tests/test_auth.py` (7 tests: disabled-by-default, both
  exempt paths, 401 missing/wrong/malformed header, 200 with the right token,
  and `/health/batch` protected while `/health/auto-export` stays exempt).
  **2b (Android):** DataStore key `api_token` in `SettingsRepositoryImpl`
  (empty by default); new `AuthInterceptor.kt` mirrors `DynamicBaseUrlInterceptor`'s
  `AtomicReference` pattern — an empty token adds no header at all, so it's a
  silent no-op until configured. Wired into `NetworkModule`'s `OkHttpClient`
  alongside the base-URL interceptor. Settings → Connection gained an "API
  token" field (masked, with a show/hide toggle) below the backend address,
  saved together by the same Save button. `LifeOsSyncCoordinator` now tracks
  whether any of `refreshOnAppOpen()`/`syncAll()`'s per-area failures was a 401
  and, if so, replaces the generic "Couldn't refresh X, Y, Z" message with
  "Unauthorized — check the API token in Settings." (one wrong token fails
  every area the same way; naming them individually would bury the one fix).
  **Verified end-to-end on Samsung `RZCX60SNHHE`** (connected via adb):
  `testDebugUnitTest` and `assembleDebug` both clean, APK installed, typed a
  token into the new field, toggled visibility, cleared it back to empty,
  saved (button showed "Saved"), and confirmed Money still loads live data
  from the (currently token-less) Jetson afterward — proving the empty-token
  path is a true no-op. Backend: `pytest` — 325 passed, 4 failed (same
  pre-existing failures as Stage 1, confirmed unrelated). **2c (Tailscale) is
  documentation/manual-setup only and has NOT been done** — it needs
  `tailscale up` on the Jetson (interactive browser auth) and installing/signing
  into the Tailscale app on the S24, both of which need the user's own
  accounts; ask before doing the Jetson half over SSH.
- **Staged improvement plan, Stage 1 — cleanup & safety net (2026-09-23).** First
  stage of `.claude/plans/split-into-stage-and-rosy-fiddle.md` (a local plan file,
  not checked into the repo). **1a (Android):** removed all dead Health Connect
  code — `HealthConnectManager.kt`, `HealthRepositoryImpl.kt`, the `HealthRepository`
  interface, `HealthSample`/`HealthMetricType`, `HealthSample.toDto()`, the
  `uploadHealth` Retrofit call, `HealthRecordDto`/`HealthBatchDto`/`IngestResultDto`,
  the `health-connect` Gradle dependency/version, and the matching Mappers test —
  none of it did anything since the 2026-06-21 single-source (HAE-only) migration.
  `HealthSyncWorker` renamed to `NightlyRecomputeWorker` (it only calls
  `recompute(30)`), **keeping `UNIQUE_NAME = "health_sync_nightly"`** so the
  existing WorkManager schedule isn't duplicated on upgrade. Backend `/health/batch`
  is untouched (the XML importer still POSTs to it). **1b (backend):** replaced the
  "manual `ALTER TABLE` on the Jetson" workflow with numbered SQL files under
  `backend/migrations/` (`0001_statement_details.sql` moved in from
  `scripts/2026_07_21_statement_details.sql`), applied automatically on startup by
  `app/migrations.py` via a `schema_migrations` tracking table; skipped on SQLite
  (tests) unless a test opts in with `force=True`. Wired into `app/main.py`'s
  `lifespan` right after `init_db()`; Dockerfile now also `COPY migrations
  ./migrations`. **1c (Android):** extracted `PeriodSlice`/`sliceFor`/
  `previousPeriodSlice`/`CategoryTotal` and the category/total rollups out of
  `MoneyScreen.kt`'s `remember{}` blocks into a pure `MoneyAggregation.kt`
  (behavior-preserving — `MoneyScreen.kt` now just calls it), with new unit tests
  covering period boundaries, EMI exclusion + bank-UPI filter composed together,
  and prior-period comparison. Fixed the widget/Money mismatch:
  `SpendWidgetUpdater.updateAll` now applies `filterEmiTransactions(...,
  includeEmi = false)` so the home-screen widget's today/month totals match
  Money's default (EMI-excluded) view instead of silently disagreeing with it.
  **Verified:** `cd backend && pytest` — 260/264 pass (the 4 failures are
  pre-existing on `main`, unrelated: `test_card_cycles`, `test_priority`, two in
  `test_statements`); the new `tests/test_migrations.py` (4 tests) passes clean.
  Android changes were reviewed by hand, not built — per [[android-studio-builds]]
  the user builds/installs via Android Studio; run `testDebugUnitTest` and
  `assembleDebug` there before installing, and check `adb shell dumpsys
  jobscheduler | grep lifeos` still shows the nightly work under the old name.
  Stages 2–5 (auth/Tailscale, sync visibility, Money features, cross-domain
  polish) remain in the plan file, not started.
- **Android navigation and spending UI pass (2026-09-11, extended 2026-09-13).** A shared, fixed
  back bar now covers every non-root route, including loading/error states and
  long statement/Priority detail pages. **Revised 2026-09-25:** drawer areas
  are siblings, not history. Choosing one from the drawer (or a notification/
  widget open) clears the stack so that area is the root and shows no Back bar.
  System Back on a non-Today area root returns to Today, and Back on Today exits
  the app (Money was home until the Today overview, below). Only pages opened from inside an area show "Back to X": details,
  Settings sub-pages, and in-page links such as Today's settings gear (which
  still returns to Today). The app can start on the last-used area, so "home" is
  never inferred from the stack. Every area root draws the shared fixed
  `AreaHeader` (`components/Components.kt`): menu button top-left, 26sp title
  matching the drawer label, one-line subtitle, optional trailing action. The
  menu comes from `LocalOpenNavigation`, provided only for area roots, so the
  button never moves or doubles up with Back. Detail pages have the Back bar
  plus a `ScreenHeader` and no menu. Today no longer has its own Settings gear,
  and Money's Transactions/Cards tabs return to Overview with system Back or the
  tabs, not by turning the menu button into an arrow. Trip deletion lives only
  in the trip's own page. Root Money Transactions and
  Cards return to Overview through either the visible arrow or Android Back;
  an open drawer or modal gets Back first. Money's header/tabs are fixed, with
  separate remembered scroll positions per section. Today uses a compact neutral
  score card, vitals before history, a metric dropdown, and calendar-day chart
  slots with explicit stale dates. Money Overview shows a five-category preview
  and a **View all** bottom sheet containing every category, including long
  labels. Its interactive spending chart follows the selected range: daily bars
  for week/month, seven-day buckets for six months, and calendar-month bars for
  year. Day and custom-range views stay uncluttered without a chart. Overview's
  chart, total, recent activity, and categories use the same period/source/EMI
  subset; transaction-category drill-down filters apply only to Transactions.
  Statement/cycle accounting is unchanged. Builds, 23 unit tests, and all 11
  Compose device tests pass. The debug APK is installed and the top-five list,
  all-category sheet, and year chart were visually verified against real data on
  Samsung `RZCX60SNHHE`. Test APKs can be installed with `adb install -r` and run
  using `am instrument`, avoiding a runner cleanup that uninstalls the user app.

- **Ask streaming follows the newest text (2026-09-13).** A streamed assistant
  response is one growing `LazyColumn` item, so scrolling only to its item index
  left the viewport near the item's beginning once it exceeded one screen. The
  Ask list now waits for the new text to be measured and scrolls to the list's
  true maximum offset. A user drag pauses automatic following so earlier text
  remains readable; a floating **Jump to latest** action resumes it. The Compose
  regression test grows a reply past the viewport and covers follow, pause, and
  resume. It passes within the full 11-test device suite. A long live Qwen essay
  was also verified on Samsung `RZCX60SNHHE`: new paragraphs remained visible
  during generation, manual scrolling exposed the jump action, and tapping it
  returned to the response tail.

- **UI pass in Android Studio** (the user has a list of visual issues). Done so
  far: the two unbuilt files (built/verified), the Sleep trend stale-headline +
  sparse-state fix, and the Ask-tab Enter fix. More UI issues likely to come from
  the user — keep going through the list.
- ✅ **Health Auto Export automation is LIVE on the iPhone** (Premium, 2026-06-19) —
  confirmed pushing recent sleep+vitals with a short, non-overlapping window. The
  settings below are the working config (keep as reference if it ever needs
  rebuilding). *Automated Exports → New Automation → REST API*:
  - **URL** `http://192.168.3.30:8000/health/auto-export` · **POST** (only option)
    · **no headers** (endpoint is unauthenticated).
  - **Data Type** = Health Metrics → **Select Health Metrics**: Sleep Analysis +
    vitals only (Heart Rate, Heart Rate Variability, Resting Heart Rate, Blood
    Oxygen, Respiratory Rate, optionally Wrist Temperature / VO2 Max). **Do NOT**
    add Steps/Active Energy/Distance/Flights/Weight — Health Connect already syncs
    those and they'd double-count (see §5).
  - **Export Format** = JSON · **Summarize Data** = ON · **Time Grouping** = Daily
    (Summarize ON is required or sleep is dropped — see §5).
  - **Date Range** = **Since Last Sync** (never reaches back into the import).
    **Sync cadence** every 1–2 h (idempotent, so frequency is free; catches the
    morning unlock when the watch's sleep lands).
  - Health check that a push landed: `select split_part(source_id,'-',1) src,
    max(start_time)::date, count(*) from health_records where type like 'sleep%'
    group by 1;` — the `hae` row should keep advancing with each new night.
  - Separately, *Server → Start Server* (foreground-only) powers the `live_health`
    chat tool **and** the one-off TCP backfill (§6); not needed for the push.
- **Set a DHCP reservation** for the iPhone (`192.168.3.212`) so `HAE_HOST` and the
  TCP server address stay valid. Still outstanding.
- **Watch the next few automation runs.** Dedup keys sleep on the `sleepStart`
  string; if Apple revises a night's `sleepStart` between syncs the new id won't
  match and the night could land twice — check `hae` sleep has one row per night.

## 8. Key files

> **API references (for other agents/clients):** the live machine-readable spec
> is always at `GET /openapi.json` (interactive at `/docs`). Curated markdown:
> **[`backend/docs/expenses-api.md`](backend/docs/expenses-api.md)** — the
> email→transaction Expenses API (`/expenses`, `/expenses/new`, `/expenses/sync`;
> incremental, read-only Gmail via `gog`).

```
backend/app/api/routes/expenses.py       # /expenses (list), /expenses/new (poll), /expenses/sync
backend/app/services/expense_collector.py # incremental cursor sync (only-new emails)
backend/app/services/expense_parsers/     # per-source parsers (registry); add a bank = add a file
backend/app/services/openclaw.py          # prioritized single-inference admission + LLM client
backend/app/services/job_guard.py         # local + PostgreSQL singleton mail-job lease
backend/app/services/repayments.py         # repayment candidates, validation, pair + Undo
backend/app/models/transaction.py         # transactions table (idempotent on dedup_key)
backend/app/models/card_statement.py      # statement summaries + statement-only detail rows
backend/app/services/statement_details.py # display classification/rollup; never spend
backend/scripts/2026_07_21_statement_details.sql # required existing-DB migration
backend/app/models/transaction_reconciliation.py # audited repayment pairs + Undo state
backend/app/api/routes/health_data.py    # /health/batch + /health/auto-export
backend/app/api/routes/analytics.py      # /analytics/recompute (bg github scan), scores, state
backend/app/services/apple_ingest.py     # Health Auto Export JSON -> records
backend/app/services/agent_tools.py      # chat tools incl. health_records
backend/app/services/money_query.py      # guarded semantic transaction queries for Ask
backend/app/services/card_cycles.py      # exact recorded spend in each open card cycle
backend/app/services/analytics.py        # recompute_day / run_nightly / baselines
backend/app/repositories/health_repo.py  # bulk_insert (dedup), baseline_mean
backend/app/models/health.py             # HealthRecord (free-form type) + HealthRecordType
backend/scripts/import_apple_health.py   # one-time export.zip importer
android/app/.../work/HealthSyncWorker.kt # nightly/manual sync -> upload -> recompute
android/app/.../components/TrendChart.kt  # dashboard trend chart + range selector
android/app/.../money/StatementDetailsScreen.kt # per-card due activity and transaction list
android/app/.../data/health/HealthConnectManager.kt  # Health Connect reads
```
