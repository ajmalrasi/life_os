# LifeOS — Improvements & Roadmap

> Last updated: June 2026

---

## Phase 1 Completion — Passive Loop

These must be done before moving to Phase 2. Goal: make LifeOS feel passive and complete.

### 1. Wellness Score Card ✅ Done

**Status (June 2026):** `scoring.wellness_score` derives a 0–100 score from the four Pulse fields (mood/energy/calm/focus weighted), persisted on `DailyScore.wellness_score`, surfaced in `/analytics/*` output, fed into correlation discovery, and shown as a Wellness card on the Android dashboard. A low-wellness nudge was added to the notification engine.

**What:** Add a Wellness score (0–100) derived from Pulse data (mood, energy, focus, stress) and display it alongside Sleep, Recovery, and Execution on the Today dashboard.

**Why:** Mood is already tracked via Pulse but invisible on the main screen. Mental health should be a first-class metric.

**Backend:**
- Add `wellness_score` to the scoring engine using the four Pulse fields
- Include it in `DailyScore` model and `POST /analytics/recompute` output

**Android:**
- Add a Wellness score card to `DashboardScreen`
- Pull `wellness_score` from the analytics response and cache in Room

---

### 2. Background Health Sync (WorkManager) ✅ Done

**Status (June 2026):** `HealthSyncWorker` (Hilt + WorkManager) runs nightly (~3 AM, network-constrained) via `WorkScheduler`, scheduled on app start. The manual button is kept as a fallback, and the last-synced time is shown in Settings.

**What:** Replace the manual "Sync & analyze" button with a nightly WorkManager job that reads Health Connect and POSTs to the backend automatically.

**Why:** Requiring a daily manual tap breaks the passive observation promise. Users will forget, and gaps in data break correlations.

**Android:**
- Add a `HealthSyncWorker` using WorkManager with a periodic constraint (nightly, requires network)
- Keep the manual button as a fallback / on-demand trigger
- Show last-synced timestamp in Settings

---

### 3. GitHub API Collector ✅ Done

**What:** Add a GitHub API-based collector as a fallback/supplement to the local git scan.

**Status (June 2026):** Implemented. `github_api_collector.py` fetches commits + churn via the GitHub REST API; `execution_sync.py` runs it alongside the local git scan during `POST /analytics/recompute` and deduplicates by commit SHA. Config: `GITHUB_TOKEN`, `GITHUB_REPOS`, `GITHUB_USER` in `.env`. Manual triggers: `POST /collectors/execution/sync` (combined) and `POST /collectors/execution/github/sync` (GitHub-only).

**Why:** The current `execution_collector.py` only works when the backend machine can directly `git log` the repos. Fails in Docker without bind-mounts and misses work on other machines.

**Backend:**
- New service: `github_api_collector.py` — fetches commits/pushes via GitHub REST API using a personal access token
- Add `GITHUB_TOKEN` and `GITHUB_REPOS` to `.env`
- Run alongside the local git scan during recompute; deduplicate by commit SHA

---

### 4. Evening Pulse Nudge ✅ Done

**Status (June 2026):** `PulseReminderWorker` fires daily at the configured hour (default 9 PM); if no Pulse is logged that day it posts a local notification that deep-links into the Pulse tab. Time and on/off toggle live in Settings. Uses the `POST_NOTIFICATIONS` permission and a dedicated channel.

**What:** Send a local notification to the phone each evening (~9 PM) prompting a Pulse check-in if none has been logged that day.

**Why:** Pulse is optional and easy to skip. A lightweight nudge improves data density without being intrusive.

**Android:**
- Schedule a daily `PulseReminderWorker` that checks if today's Pulse exists in Room cache
- Fire a local notification if missing; deep-link into the Pulse tab
- Configurable time in Settings

---

### 5. Push Notifications via FCM 🟡 Skeleton (needs Firebase credentials)

**Status (June 2026):** Code path is in place end-to-end; only Firebase setup remains. Backend: `FIREBASE_CREDENTIALS_PATH`/`FIREBASE_PROJECT_ID` config, `DeviceToken` model + `POST/DELETE /device/token`, `FcmService` (HTTP v1, fail-safe no-op until credentials exist, google-auth lazy-imported), and recompute pushes high-priority notifications. Android: `LifeOsMessagingService` (FCM receiver), `PushTokenRegistrar` (registers token on launch / rotation), firebase-messaging dependency added. **To activate:** add `google-auth` to backend requirements + a service-account JSON; on Android add `app/google-services.json` and uncomment the `google-services` plugin in the two build files.

**What:** Wire up Firebase Cloud Messaging so the backend can push burnout warnings, peak-state alerts, and inactivity nudges to the phone.

**Why:** The backend already generates notification objects but they're never delivered — the phone only sees them on the next manual sync.

**Backend:**
- Add `FIREBASE_CREDENTIALS_PATH` to config
- Call FCM HTTP v1 API when `notifications.py` generates a high-priority nudge

**Android:**
- Register FCM token on app start; POST token to a new `POST /device/token` endpoint
- Handle incoming FCM messages and display as system notifications

---

### 6. OpenClaw MCP Agent Skeleton ✅ Done

**Status (June 2026):** `agent_tools.py` defines OpenAI-style schemas + dispatch for `git_log`, `github_events`, and `health_summary` (read-only over the same repositories). `OpenClawClient.chat_with_tools` supports function calling, and `ChatService._run_agent` runs a bounded tool-calling loop (capped at `MAX_AGENT_STEPS`), falling back to the deterministic data summary when OpenClaw is unreachable.

**What:** Give OpenClaw tools it can call — `git_log`, `github_events`, `health_summary` — so it can autonomously collect and explain data rather than only answering questions about pre-loaded snapshots.

**Why:** Currently OpenClaw is Q&A only. An agent loop would let it surface insights proactively and fill gaps the deterministic engines miss.

**Backend:**
- Define MCP tool schemas for the above tools
- Add an agent loop in `chat_service.py` that lets OpenClaw call tools and POST results back to the ingest endpoints

---

## Phase 2 — Financial Intelligence

| Task | Approach |
|------|----------|
| Transaction ingest | SMS/notification listener on Android, CSV import (SBI/Axis) |
| Auto-categorization | Rule-based + OpenClaw LLM for merchant → category |
| Financial Discipline score | New daily score; correlations with mood and recovery |
| No manual logging | Observe, don't ask |

---

## Phase 3 — Location Intelligence

| Task | Approach |
|------|----------|
| Significant places | Android location history with permission |
| Exploration / Isolation scores | Derived from place diversity and time at home |
| Correlations | e.g. road trips → mood lift for following N days |

---

## Phase 4 — Device Activity

| Task | Approach |
|------|----------|
| Screen time + app usage | UsageStats / Digital Wellbeing APIs |
| Creation vs Consumption score | Classify apps (IDE, editor → creation; social, video → consumption) |
| Correlate with execution and mood | Feed into existing correlation engine |

---

## Phase 5 — Advanced Intelligence

| Task | Approach |
|------|----------|
| Proactive push notifications | FCM + backend rules (built in Phase 1 completion) |
| Richer state model | More life states, mood-weighted transitions |
| Learned patterns | Only after enough labeled history (months of data) |
| Multi-device collectors | Mac agent + Jetson backend + phone sensors |

---

## Known Limitations

| Limitation | Mitigation |
|---|---|
| Lagged Pearson needs weeks of history before insights are meaningful | Show "not enough data" state gracefully; seed with demo data in dev |
| Git collector breaks in Docker without bind-mounts | ✅ Resolved — GitHub API collector (Phase 1 #3) supplements the local scan |
| No auth — single-user assumption | Acceptable for local-only personal use; add if multi-user ever needed |
| Schema migrations missing (uses `create_all`) | Add Alembic before any schema change in Phase 2 |
| Backend must be on LAN | Tailscale or WireGuard as a simple remote-access bridge if needed |
| OpenClaw down → chat degrades to data summary | Acceptable fallback; keep the fallback fast |
