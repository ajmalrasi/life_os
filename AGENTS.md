# LifeOS — Agent Handoff & Working Guide

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
  ALTERs existing columns**. Column/type changes need a manual `ALTER TABLE`
  (e.g. `health_records.type` was widened to `varchar(64)`).
- Run one-off SQL: `docker exec -i backend-db-1 psql -U lifeos -d lifeos < file.sql`
- Tests: `cd backend && pytest` (uses SQLite).

## 3. Build & test the Android app
- Standard Gradle/Android Studio project under `android/`. **Backend changes are
  verified on the Jetson; Android changes are NOT auto-built — build & eye-check
  them in Android Studio.**
- The app's backend base URL is set in-app (see `DynamicBaseUrlInterceptor` /
  settings) — point it at `http://192.168.3.30:8000`.

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
4. **Live pull** in the chat: the `live_health` tool
   (`app/services/phone_health_client.py`) opens a TCP socket to the phone's Health
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
  needs a manual `ALTER TABLE` on the Jetson DB.
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
  catches up. Priority production review remains `PRIORITY_LLM_ENABLED=false`.
  NOTE: this runtime admission layer does not solve the Jetson's boot-time CUDA
  allocation race; the 4K model must still load from clean memory before
  ancillary containers are restored (§5 watchdog recovery).

## 7. Open items / next steps

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
backend/app/services/phone_health_client.py  # TCP client to the iPhone's HAE server
backend/app/services/agent_tools.py      # chat tools incl. live_health
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
