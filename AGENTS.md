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
| LLM (Ollama) | Jetson host, port 11434, model `qwen3.5:4b` | user systemd service `lifeos-ollama` |
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

**Four data paths now exist** (all land in the one `health_records` table):
1. **Health Connect sync** (Android `HealthSyncWorker`) → `POST /health/batch`. In
   practice only activity data flows here.
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
- **Ollama dies on GPU OOM.** The Jetson has ~8GB unified RAM; under memory
  pressure Ollama returns `cudaMalloc ... out of memory` and the chat **silently
  falls back** to a deterministic summary ("OpenClaw is unreachable"). A **reboot**
  fixes it (Ollama is an enabled *user* service with linger on, and containers
  `restart: unless-stopped`, so everything comes back automatically). Check Ollama
  with `systemctl --user status lifeos-ollama`, not the system bus.
- **Don't make the post-sync recompute slow again.** `/analytics/recompute` runs
  the health/score recompute synchronously (fast) and the **GitHub execution scan
  (44 repos) in a background task** — it used to be synchronous and made syncs take
  ~81s. Recovery baselines use one grouped query (`HealthRepository.baseline_mean`),
  not a per-day loop. Keep both.
- **Sleep is attributed to the night's *start* day** (matches `analytics._sum`,
  which sums sleep by `start_time` calendar day). Day aggregation uses naive bounds
  interpreted in the DB session tz (UTC).
- **Sleep import filters fragments**: drop sessions < 30 min and > 16 h (in-bed
  artifacts) in both `import_apple_health.py` and `apple_ingest.py`.
- **Health Auto Export specifics:** REST + TCP server need **Premium**; the TCP
  server is **foreground-only** (app must be open) and the phone IP is **DHCP** (set
  a router reservation; it's `HAE_HOST` in `.env`). For the `health_metrics` TCP
  call, the `metrics` filter must go **inside `arguments`**, not beside `name`.
- **`/tmp` on the Jetson is cleared on reboot** — the copied `export.zip` vanished
  after a reboot. Re-`scp` it from the Mac when re-importing.
- **The Android sync worker swallows exceptions** (`HealthSyncWorker`) — failures
  are invisible. Consider surfacing sync status/errors in the UI.

---

## 6. Current state (2026-06-18)

- ✅ Sleep history backfilled (~1,480 clean nights, 2018→2026) and scored.
- ✅ Free-form schema deployed; column is `varchar(64)`.
- ✅ Live push endpoint `/health/auto-export` built & tested.
- ✅ Chat `live_health` tool built & verified pulling real data from the iPhone.
- ✅ Post-sync recompute optimized: **~81s → ~2s** (GitHub scan backgrounded,
  baselines batched). Android sync recompute window reduced to 30 days.
- ✅ Trend chart: single-day ranges show a dot + caption instead of a confusing
  "pick 7D" nudge (the score is per-day; only the *line* needs 2+ days).
- ⚠️ **Android changes for the last two items are committed but NOT yet built** —
  `HealthSyncWorker.kt` and `presentation/components/TrendChart.kt`. Build & verify.

## 7. Open items / next steps

- **UI pass in Android Studio** (the user has a list of visual issues). Start with
  the two unbuilt Kotlin files above, then the broader UI.
- **Finish Health Auto Export setup on the iPhone** (Premium): a REST automation →
  `http://192.168.3.30:8000/health/auto-export` (JSON, aggregated sleep, *short*
  rolling window to avoid double-counting the XML import), and *Server → Start
  Server* for the live `live_health` chat tool.
- **Set a DHCP reservation** for the iPhone so `HAE_HOST` stays valid.
- Data is only current up to the last export/sync until live push is running; the
  trend chart's recent windows stay sparse until then.

## 8. Key files

```
backend/app/api/routes/health_data.py    # /health/batch + /health/auto-export
backend/app/api/routes/analytics.py      # /analytics/recompute (bg github scan), scores, state
backend/app/services/apple_ingest.py     # Health Auto Export JSON -> records
backend/app/services/phone_health_client.py  # TCP client to the iPhone's HAE server
backend/app/services/agent_tools.py      # chat tools incl. live_health
backend/app/services/analytics.py        # recompute_day / run_nightly / baselines
backend/app/repositories/health_repo.py  # bulk_insert (dedup), baseline_mean
backend/app/models/health.py             # HealthRecord (free-form type) + HealthRecordType
backend/scripts/import_apple_health.py   # one-time export.zip importer
android/app/.../work/HealthSyncWorker.kt # nightly/manual sync -> upload -> recompute
android/app/.../components/TrendChart.kt  # dashboard trend chart + range selector
android/app/.../data/health/HealthConnectManager.kt  # Health Connect reads
```
