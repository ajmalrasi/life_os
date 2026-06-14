# LifeOS

> A personal operating system that continuously analyzes your life patterns to understand *why* you perform, procrastinate, thrive, or burn out.

LifeOS is **not** a fitness app or a budgeting app. It is a private, local-first personal intelligence system that correlates health, behavior, finances, location, and project activity to surface causal insights about your life.

```
"Productivity increases 31% when sleep exceeds 7.2 hours."
"Food delivery spending spikes during low recovery periods."
"Road trips correlate with improved mood for the following 3 days."
```

---

## Vision & Design

**Principles:**
- **Local-first:** Android + Jetson backend + PostgreSQL + local ollama. No cloud.
- **Grounded intelligence:** Scores are deterministic formulas. Chat answers cite real metrics — no invented numbers.
- **Passive observation:** Signals from Health Connect, git repos, and APIs — not manual forms.
- **Incremental phases:** Ship a working MVP, then add finance, location, device activity.

---

## Architecture

```
┌─────────────────────────┐         ┌──────────────────────────────┐
│   Android App           │         │   Jetson Orin Nano (server)  │
│   (Jetpack Compose)     │  HTTP   │                              │
│                         │ ──────▶ │   FastAPI Backend            │
│  • Health Connect       │         │   ├─ Scoring Engine          │
│  • Mood check-in        │ ◀────── │   ├─ Correlation Engine      │
│  • GitHub activity      │  JSON   │   ├─ State Engine            │
│  • Chat (grounded)      │         │   └─ ollama client ────┐     │
│  • Room offline cache   │         │                        │     │
└─────────────────────────┘         │   PostgreSQL           ▼     │
                                     │                   ollama LLM  │
                                     │              (qwen3.5:4b)    │
                                     └──────────────────────────────┘
```

- **Frontend:** Android (Jetpack Compose, Material 3, Health Connect, Retrofit, Room) — offline-first.
- **Backend:** FastAPI + PostgreSQL, Dockerized.
- **LLM Layer:** ollama (qwen3.5:4b) running locally on Jetson; preloaded at boot. The backend grounds every answer in real data and cites supporting metrics.
- **Privacy:** Everything runs on your Android device + Jetson + local PostgreSQL. No cloud dependency.

---

## Project Status (June 2026)

### ✅ DONE (Phase 1 MVP + Completion)

**Infrastructure & Deployment**
| Item | Status | Details |
|------|--------|---------|
| Backend (FastAPI + Postgres) | ✅ Live | `docker compose` on Jetson, healthy, reachable on `192.168.3.30:8000` |
| Local LLM (ollama + qwen3.5:4b) | ✅ Live | Preloaded at boot, pinned in GPU (2.97 GB VRAM), accessible on `:11434` |
| End-to-end chat pipeline | ✅ Working | Backend → ollama → grounded answers with citations |
| Android app | ✅ Built & ready | Sync button, Pulse check-in, chat, offline Room cache |

**Backend Features**
| Feature | Status | Notes |
|---------|--------|-------|
| Health Connect ingest | ✅ | `POST /health/batch` idempotent by `source_id` |
| Mood / Pulse API | ✅ | One-tap emoji: mood, energy, focus, stress (1–5 each day) |
| Wellness score | ✅ | 0–100 from Pulse fields, surfaced on dashboard + correlations |
| Auto git execution detector | ✅ | Scans local repos, stores commits + coding minutes |
| GitHub API execution collector | ✅ | Supplements git (needs `GITHUB_TOKEN` + `GITHUB_REPOS` in `.env`) |
| Scoring engine | ✅ | Sleep, Recovery, Execution, Consistency, Burnout (5 daily scores) |
| State engine | ✅ | 7 life states (Peak Performance, Burnout, Avoidance, etc.) + confidence |
| Correlation / insights | ✅ | Lagged Pearson pattern discovery, human-readable summaries |
| Grounded chat | ✅ | ollama via OpenAI-compatible API + fallback data summary |
| Notifications | ✅ | Burnout, peak, avoidance, inactivity nudges (rule-based) |
| Nightly recompute | ✅ | `POST /analytics/recompute` orchestrates scoring + state + insights |
| Tests | ✅ | 44 unit + integration tests (43 pass) |

**Android Features**
| Feature | Status | Notes |
|---------|--------|-------|
| Jetpack Compose UI | ✅ | Bottom nav: Today, Pulse, Insights, Ask, Settings |
| Health Connect sync | ✅ | Manual button + `HealthSyncWorker` (nightly via WorkManager) |
| One-tap Pulse | ✅ | Emoji selector → mood/energy/focus/stress, logged to backend |
| Score cards | ✅ | Sleep, Recovery, Execution, Wellness displayed on Today |
| Insights list | ✅ | Correlation cards (patterns discovered on backend) |
| Grounded chat | ✅ | Ask tab, suggestion chips, metric citations |
| Evening Pulse nudge | ✅ | `PulseReminderWorker` fires nightly ~9 PM (configurable) |
| Offline Room cache | ✅ | Scores, states, insights, mood persisted locally |
| Configurable backend URL | ✅ | Settings screen to set LAN IP (e.g., `http://192.168.3.30:8000/`) |

---

### 🟡 PARTIAL (Skeleton code, needs Firebase setup)

| Item | Status | What's needed |
|------|--------|---------------|
| **FCM push notifications** | 🟡 Skeleton | Backend code exists (`fcm.py`, device token endpoints, notifications); Android receiver exists. Needs:<br/>• `google-auth==2.38.0` in `backend/requirements.txt` (uncomment line 13)<br/>• Firebase service-account JSON at path specified in `FIREBASE_CREDENTIALS_PATH`<br/>• `app/google-services.json` in Android<br/>• Uncomment `google-services` plugin in Android gradle files |

### ⚠️ Known Issues

| Issue | Status | Fix |
|-------|--------|-----|
| `test_wellness_clamps_out_of_range` fails | ⚠️ Test logic wrong | Test expects `0.0` but clamp-to-valid-range yields `20.0`; assertion is incorrect, not code |

---

### ❌ NOT DONE (Phase 2+)

| Phase | Scope | Status |
|-------|-------|--------|
| **Phase 2 — Finance** | Bank SMS/CSV import, categorization, Financial Discipline score | ❌ Not built |
| **Phase 3 — Location** | Significant places, exploration/isolation scores | ❌ Not built |
| **Phase 4 — Device activity** | Screen time, app classification, creation vs consumption | ❌ Not built |
| **Phase 5 — Advanced** | Richer state model, learned patterns, proactive push | ❌ Not built |
| **Alembic migrations** | Schema version control | ❌ Uses `create_all` on startup |
| **CI/CD pipeline** | GitHub Actions or similar | ❌ |

---

## Quick Start

### Prerequisites
- Android phone (with Health Connect support for health data)
- Jetson Orin Nano (or Mac) running the backend + ollama
- Same Wi-Fi network (backend on LAN)

### Backend Setup

1. **Clone the repo on your Jetson:**
   ```bash
   git clone git@github.com:ajmalrasi/life_os.git ~/life_os
   cd ~/life_os/backend
   ```

2. **Configure `.env`:**
   ```bash
   cp .env.example .env
   # Edit as needed (defaults work for LAN-local setup)
   ```

3. **Start services:**
   ```bash
   docker compose up -d
   ```

   This starts:
   - PostgreSQL on `:5432`
   - FastAPI on `:8000`
   - ollama (qwen3.5:4b) on `:11434` — preloaded at boot and pinned in GPU

4. **Verify health:**
   ```bash
   curl http://192.168.3.30:8000/healthz
   curl http://192.168.3.30:8000/chat/health
   ```

### Android Setup

1. **Clone on your dev machine**, open `android/` in Android Studio.

2. **Build a debug APK** (or run on emulator):
   ```bash
   ./gradlew build
   ```

3. **In the app → Settings**, set backend URL:
   - **Emulator:** `http://10.0.2.2:8000/`
   - **Physical phone:** `http://192.168.3.30:8000/` (your Jetson's LAN IP)

4. **Tap "Sync & analyze"** to pull health data and kick off the pipeline.

### First Data Flow

1. **Phone syncs health** → `POST /health/batch` uploads sleep, HRV, RHR, etc.
2. **Phone submits Pulse** → optional emoji check-in (mood/energy/focus/stress)
3. **Backend recomputes** → scoring + state + correlations (if 2+ weeks of data)
4. **Phone displays scores** → Sleep, Recovery, Execution, Wellness cards
5. **Ask LifeOS** → chat grounded in your actual metrics

---

## Optional: Enable Execution Tracking (GitHub)

The backend uses the official [github-mcp-server](https://github.com/github/github-mcp-server) as a Docker sidecar. This replaces any manual REST calls with the maintained MCP tool layer, and also gives the LLM (qwen3.5:4b) direct access to GitHub tools during chat (`list_commits`, `get_commit`, `search_commits`, `get_file_contents`).

1. **Create a GitHub Personal Access Token** (fine-grained, read access to your repos)

2. **On Jetson, edit `~/life_os/backend/.env`:**
   ```env
   GITHUB_TOKEN=ghp_xxxxx...
   GITHUB_REPOS=ajmalrasi/life_os,ajmalrasi/other_repo
   GITHUB_USER=ajmalrasi
   ```

3. **Restart the stack** (`github-mcp` sidecar starts automatically):
   ```bash
   docker compose up -d
   ```

4. **Next sync fetches commits** via the MCP server and computes your Execution score. The LLM can also search GitHub live during chat.

---

## Repository Layout

```
life_os/
├── backend/                FastAPI + PostgreSQL core
│   ├── app/
│   │   ├── models/        SQLAlchemy ORM (daily_score, mood, github_activity, etc.)
│   │   ├── schemas/       Pydantic DTOs (request/response bodies)
│   │   ├── services/      Scoring, correlation, state, analytics, chat, notifications
│   │   ├── repositories/  Database access (repository pattern)
│   │   ├── api/routes/    HTTP endpoints (health, mood, chat, analytics, etc.)
│   │   └── main.py        FastAPI app setup
│   ├── tests/             44 unit + integration tests
│   ├── Dockerfile         Multi-arch (arm64 + x86)
│   ├── docker-compose.yml Postgres + API
│   ├── requirements.txt    Python deps
│   └── README.md          Backend development guide
│
├── android/               Jetpack Compose app
│   ├── app/src/main/java/com/lifeos/app/
│   │   ├── domain/         Models + repository interfaces (no Android deps)
│   │   ├── data/
│   │   │   ├── remote/     Retrofit HTTP client + DTOs
│   │   │   ├── local/      Room database + DAOs
│   │   │   ├── health/     Health Connect manager
│   │   │   └── repository/ Repository implementations
│   │   ├── di/             Hilt dependency injection
│   │   ├── presentation/   UI (dashboard, mood, insights, chat, settings)
│   │   └── work/           WorkManager jobs (health sync, pulse reminder)
│   ├── app/build.gradle.kts
│   └── README.md           Android development guide
│
└── README.md              (this file)
```

---

## Configuration Reference

### Backend `.env` (key variables)

```env
# Postgres
DATABASE_URL=postgresql+psycopg2://lifeos:lifeos@localhost:5432/lifeos

# ollama (local LLM)
OPENCLAW_BASE_URL=http://host.docker.internal:11434
OPENCLAW_MODEL=qwen3.5:4b
OPENCLAW_API_KEY=

# Auto execution (local git repos)
EXECUTION_REPO_PATHS=/Users/you/life_os
EXECUTION_GIT_AUTHOR=

# GitHub execution via github-mcp-server sidecar (optional)
GITHUB_TOKEN=ghp_xxxxx
GITHUB_REPOS=ajmalrasi/life_os,ajmalrasi/other_repo
GITHUB_USER=ajmalrasi

# Firebase Cloud Messaging (optional, for push notifications)
FIREBASE_CREDENTIALS_PATH=
FIREBASE_PROJECT_ID=
```

### Android Settings

- **Backend URL:** `http://192.168.3.30:8000/` (set to your Jetson's LAN IP)
- **Last sync:** Shown in Settings, also logged locally
- **Pulse reminder time:** Configurable in Settings (default 9 PM)

---

## Development

### Backend

- **Run locally (no Docker):**
  ```bash
  cd backend
  ./.venv/bin/pip install -r requirements.txt
  ./.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
  ```

- **Run tests:**
  ```bash
  ./.venv/bin/pytest
  ```

- **Add a new scoring function:** Edit `app/services/scoring.py`, add a test in `tests/test_scoring.py`, run `pytest`.

### Android

- **Open in Android Studio** and run on emulator or device.
- **Debug builds** can use `USE_DEMO_DATA=true` for offline testing.
- **Gradle wrapper** is included; if missing, open Android Studio once to generate it.

---

## Known Limitations & Workarounds

| Limitation | Mitigation |
|---|---|
| Lagged Pearson needs 2+ weeks before insights are meaningful | Show "not enough data" gracefully; seed with demo data in dev |
| Docker backend can't access local git repos without bind-mounts | Use GitHub API collector (Phase 1 #3) as a supplement |
| No user authentication — assumes single-user local setup | Acceptable for personal use; add if multi-user needed |
| Schema migrations via `create_all` (not Alembic) | Plan to add Alembic before Phase 2 schema changes |
| Backend must be on LAN | Tailscale or WireGuard for remote access if needed |
| ollama on Orin Nano: 4B models slower than cloud | Full grounded answer takes ~50s; acceptable for personal insights |

---

## Next Steps

**Immediate (optional):**
- Enable GitHub API collector (set `GITHUB_TOKEN` + `GITHUB_REPOS` in `.env`)
- Fix the failing test (`test_wellness_clamps_out_of_range`)

**Near term (Phase 1 completion):**
- Alembic migrations (before Phase 2)
- More extensive tests for correlation engine

**Phase 2+ (not started):**
- Financial tracking (SBI/Axis bank API, CSV import, auto-categorization)
- Location intelligence (significant places, exploration/isolation scores)
- Device activity (screen time, app classification)
- Advanced pattern discovery + learned models

---

## License

Personal project. All data stays local — Jetson + Android + PostgreSQL.
