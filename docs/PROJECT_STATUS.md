# LifeOS — Project Status, Architecture & Plan

> Last updated: June 2026  
> Repo: [github.com/ajmalrasi/life_os](https://github.com/ajmalrasi/life_os)

LifeOS is a **local-first personal intelligence system**. It continuously correlates health, mood, project output, and (eventually) finances, location, and device activity to explain *why* you perform, procrastinate, thrive, or burn out.

This document captures **what exists today**, **what is missing**, **how the system is architected**, and **the roadmap**.

---

## Vision

LifeOS should **observe your life passively** and surface grounded insights. You should not manually report commits, spending, or detailed mood sliders unless a one-tap signal is genuinely useful.

```
Collectors (git, Health Connect, MCP agents…)  →  Postgres  →  Engines  →  Insights
                                                          ↘
                                                    OpenClaw (explain & reason)
```

**Design principles**

| Principle | Meaning |
|-----------|---------|
| Local-first | Android + Jetson/Mac backend + PostgreSQL + local OpenClaw. No cloud dependency. |
| Grounded intelligence | Scores and correlations are deterministic formulas. Chat answers cite real metrics — no invented numbers. |
| Passive observation | Signals are ingested by collectors and agents, not typed into forms. |
| Incremental phases | Ship a working MVP, then add finance, location, device activity. |

---

## Architecture

### System overview

```mermaid
flowchart LR
    subgraph phone [Android Phone]
        HC[Health Connect]
        App[LifeOS App\nCompose + Room]
        HC --> App
    end

    subgraph server [Backend Host\nMac / Jetson]
        API[FastAPI]
        PG[(PostgreSQL)]
        Git[Git Collector]
        API --> PG
        Git --> API
    end

    subgraph llm [LLM Layer]
        OC[OpenClaw\nlocal LLM]
    end

    App <-->|HTTP LAN| API
    API <-->|chat/completions| OC
```

### Layer responsibilities

| Layer | Role | Runs on |
|-------|------|---------|
| **Android app** | UI, Health Connect read, offline cache, sync trigger | Phone |
| **FastAPI backend** | Ingest, score, correlate, classify state, notifications, chat orchestration | Mac / Jetson |
| **PostgreSQL** | Source of truth for raw signals + derived snapshots | Same host as backend |
| **OpenClaw** | Conversational layer over *existing* data (not a collector today) | Jetson (LAN) |
| **Collectors** | Auto-detect execution from git (more collectors planned) | Backend host |

### Backend internal architecture

```
backend/app/
├── api/routes/           HTTP (thin controllers)
│   ├── health_data.py    POST /health/batch
│   ├── mood.py           POST/GET /mood
│   ├── github_data.py    POST/GET /github
│   ├── collectors.py     POST /collectors/execution/sync
│   ├── analytics.py      recompute, scores, state, insights
│   ├── chat.py           grounded Q&A
│   └── notifications.py  proactive nudges
├── repositories/         DB access (repository pattern)
├── services/
│   ├── scoring.py        Sleep, Recovery, Execution, Consistency, Burnout
│   ├── correlation.py    Lagged Pearson pattern discovery
│   ├── state_engine.py   Daily life-state classifier
│   ├── analytics.py      Nightly orchestration
│   ├── execution_collector.py  Auto git-based execution
│   ├── notifications.py  Rule-based nudges
│   ├── openclaw.py       OpenAI-compatible LLM client
│   └── chat_service.py   Grounded answers + citations
├── models/               SQLAlchemy ORM
└── schemas/              Pydantic DTOs
```

### Android internal architecture

```
android/app/src/main/java/com/lifeos/app/
├── domain/               Models + repository interfaces (no Android deps)
├── data/
│   ├── remote/           Retrofit + DTOs
│   ├── local/            Room cache
│   ├── health/           Health Connect manager
│   ├── demo/             Debug preview data
│   └── repository/       Implementations
├── di/                   Hilt modules
└── presentation/
    ├── dashboard/        Today screen
    ├── mood/             Pulse check-in
    ├── insights/         Correlation patterns
    ├── chat/             Ask LifeOS
    ├── settings/         Backend URL
    ├── components/       Shared UI
    └── theme/            Light Material 3 theme
```

### Data pipeline (daily loop)

```mermaid
sequenceDiagram
    participant Phone as Android
    participant API as FastAPI
    participant Git as Git Collector
    participant DB as PostgreSQL
    participant Eng as Engines
    participant OC as OpenClaw

    Phone->>API: POST /health/batch
    Phone->>API: POST /mood (optional one-tap Pulse)
    Phone->>API: POST /analytics/recompute
    API->>Git: scan EXECUTION_REPO_PATHS
    Git->>DB: upsert github_activity
    API->>Eng: score + state + correlations
    Eng->>DB: daily_scores, daily_states, insights
    Phone->>API: GET scores, state, insights, notifications
    Phone->>API: POST /chat
    API->>DB: build JSON context snapshot
    API->>OC: grounded question + context
    OC-->>Phone: cited answer
```

### Intelligence model

LifeOS uses **two layers** of intelligence:

1. **Deterministic engines** (always on, testable)
   - Scoring: transparent 0–100 formulas
   - Correlation: lagged Pearson between driver/outcome series
   - State: rule-based classifier (Peak Performance, Burnout Risk, Avoidance, etc.)

2. **OpenClaw LLM** (explanation only)
   - Receives a JSON snapshot of *your* data
   - Must cite metrics; falls back to data-only summary if unreachable

> **Important:** OpenClaw today does **not** autonomously fetch git, calendar, or bank data. Collectors and future MCP agents feed Postgres; OpenClaw explains what is already there.

---

## What is done (Phase 1 MVP)

### Backend ✅

| Feature | Status | Notes |
|---------|--------|-------|
| FastAPI + PostgreSQL + Docker | ✅ | `docker compose up` |
| Health Connect ingest | ✅ | `POST /health/batch`, idempotent by `source_id` |
| Mood / Pulse API | ✅ | mood, energy, focus, stress (1–5) per day |
| Execution storage | ✅ | `github_activity` table (commits, coding_minutes, repos) |
| **Auto execution collector** | ✅ | Scans local git repos via `EXECUTION_REPO_PATHS` |
| Scoring engine | ✅ | Sleep, Recovery, Execution, Consistency, Burnout |
| State engine | ✅ | 7 life states + confidence + rationale |
| Correlation / insights | ✅ | Lagged Pearson, human-readable summaries |
| Nightly recompute | ✅ | `POST /analytics/recompute` (+ cron-ready) |
| Grounded chat | ✅ | OpenClaw client + fallback data summary |
| Notifications | ✅ | Burnout, peak, avoidance, inactivity nudges |
| Unit + integration tests | ✅ | 27 tests (`pytest`) |

### Android ✅

| Feature | Status | Notes |
|---------|--------|-------|
| Jetpack Compose + Material 3 | ✅ | Light theme, soft palette |
| Bottom nav: Today, Pulse, Insights, Ask, Settings | ✅ | |
| Health Connect sync | ✅ | Manual trigger: **Sync & analyze** |
| One-tap Pulse check-in | ✅ | 😞 😐 🙂 🔥 → mood/energy/focus/stress |
| Score cards + state hero | ✅ | Sleep, Recovery, Execution |
| Insights list | ✅ | Correlation cards |
| Grounded chat | ✅ | Suggestion chips, citations |
| Offline-first Room cache | ✅ | Scores, states, insights, mood |
| Configurable backend URL | ✅ | Settings; LAN IP for physical device |
| Debug demo data | ✅ | `USE_DEMO_DATA=true` in debug builds |
| Settings crash fix | ✅ | `SettingsRepositoryImpl` init order |
| Emulator vs device URL hint | ✅ | Warns on `10.0.2.2` |

### Docs & ops ✅

| Item | Status |
|------|--------|
| Root README | ✅ |
| `backend/README.md` | ✅ |
| `android/README.md` | ✅ |
| `docs/SETUP.md` | ✅ |
| `.env.example` with OpenClaw + git paths | ✅ |

---

## What is NOT done

### Passive observation gaps

| Signal | Gap | Impact |
|--------|-----|--------|
| **Execution** | Only local git scan; no GitHub API, WakaTime, IDE plugins | Misses work on machines without repo access |
| **Mental health** | One-tap Pulse only; no Wellness score card on Today | Mood tracked but not prominent |
| **Mood inference** | No passive signals (usage, calendar, sleep quality → mood) | Still requires optional tap |
| **OpenClaw agents** | No MCP tool loop that collects data | OpenClaw is Q&A only |
| **Background sync** | Health sync is manual button | No WorkManager nightly job on phone |

### Product / UX gaps

| Item | Status |
|------|--------|
| Dedicated **Wellness / Mind** score on dashboard | ❌ |
| Rename **Pulse** → clearer mental-health label | ❌ |
| Evening pulse notification | ❌ |
| Push notifications to phone | ❌ (backend generates; no FCM) |
| Manual "Log execution" form | ✅ Removed (replaced by auto git) |

### Phase 2+ (never started or reverted)

| Phase | Scope | Status |
|-------|-------|--------|
| **2 — Finance** | Bank SMS/notifications, CSV import, categorization, Financial Discipline score | ❌ Reverted / not built |
| **3 — Location** | Significant places, exploration/isolation scores | ❌ |
| **4 — Device activity** | Screen time, app classification, creation vs consumption | ❌ |
| **5 — Advanced** | Richer state model, proactive push, learned patterns | ❌ |

### Infrastructure gaps

| Item | Status |
|------|--------|
| Alembic migrations | ❌ Tables created on startup (`create_all`) |
| GitHub API token collector | ❌ |
| OpenClaw MCP agent skeleton | ❌ |
| Docker volume mount docs for git repos | ⚠️ Partial (see SETUP) |
| CI/CD pipeline | ❌ |

---

## Current signal map

What LifeOS knows about you **today**:

| Domain | Source | How it enters | Visible in app |
|--------|--------|---------------|----------------|
| Sleep | Health Connect | Sync button | Sleep score |
| Recovery (body) | HRV, RHR, SpO2 from Health Connect | Sync button | Recovery score |
| Execution | Local git log on backend host | Auto on recompute | Execution score |
| Mood / stress | One-tap Pulse (optional) | Tap emoji | Pulse tab + Today card |
| Patterns | Correlation engine | After recompute | Insights tab |
| Life state | State engine | After recompute | State hero on Today |
| Q&A | OpenClaw + data snapshot | Ask tab | Chat |

**Recovery ≠ mental health.** Recovery is physical (HRV, resting heart rate). Mental wellness lives under **Pulse** (mood, energy, focus, stress) but has no dedicated score card yet.

---

## Configuration reference

### Backend `.env` (key vars)

```env
DATABASE_URL=postgresql+psycopg2://lifeos:lifeos@localhost:5432/lifeos
OPENCLAW_BASE_URL=http://<jetson-ip>:8080
OPENCLAW_MODEL=openclaw

# Auto execution — comma-separated git repo paths on the machine running the backend
EXECUTION_REPO_PATHS=/Users/you/life_os
EXECUTION_GIT_AUTHOR=          # optional git --author filter
```

### Android Settings

- **Emulator:** `http://10.0.2.2:8000/`
- **Physical phone:** `http://<mac-or-jetson-lan-ip>:8000/`

### Nightly cron (Jetson / Mac)

```cron
30 3 * * *  curl -fsS -X POST "http://localhost:8000/analytics/recompute?days=90"
```

---

## Roadmap & plan

### Near term (Phase 1 completion)

Priority: make LifeOS feel **passive and complete** before adding new domains.

| # | Task | Why |
|---|------|-----|
| 1 | **Wellness score** on Today (from mood/stress) | Mental health visible alongside Sleep/Recovery/Execution |
| 2 | **Background health sync** (WorkManager) | Remove daily manual sync button dependency |
| 3 | **GitHub API collector** | Execution works when backend can't read local git (Docker, remote repos) |
| 4 | **OpenClaw MCP agent skeleton** | Agent with tools (`git_log`, `github`, `calendar`) POSTs to `/github`, `/mood` |
| 5 | **Evening Pulse nudge** | Lightweight mental check-in reminder |
| 6 | **Docker compose**: mount repo paths for git collector | Fix execution in containerized backend |

### Phase 2 — Financial intelligence

| Task | Approach |
|------|----------|
| Transaction ingest | SMS / notification listener, CSV (SBI/Axis), or OpenClaw MCP parsing |
| Auto-categorization | Rules + LLM for merchant → category |
| Financial Discipline score | New daily score; correlations with mood/recovery |
| No manual logging | Same philosophy: observe, don't ask |

### Phase 3 — Location intelligence

| Task | Approach |
|------|----------|
| Significant places | Android location history (with permission) |
| Exploration / Isolation scores | Derived from place diversity + time at home |
| Correlations | e.g. road trips → mood lift |

### Phase 4 — Device activity

| Task | Approach |
|------|----------|
| Screen time + app usage | UsageStats / Digital Wellbeing APIs |
| Creation vs Consumption score | Classify apps (IDE, editor vs social, video) |
| Correlate with execution and mood | |

### Phase 5 — Advanced intelligence

| Task | Approach |
|------|----------|
| Proactive push notifications | FCM + backend rules |
| Richer state model | More states, mood-weighted transitions |
| Optional learned models | Only after enough labeled history |
| Multi-device collectors | Mac agent + Jetson backend + phone sensors |

---

## Target architecture (end state)

```mermaid
flowchart TB
    subgraph collectors [Collectors — passive]
        HC2[Health Connect]
        GIT[Git / GitHub]
        MCP[OpenClaw MCP Agent]
        FIN[Finance parser]
        LOC[Location]
        DEV[Device usage]
    end

    subgraph core [Intelligence Core]
        ING[Ingest API]
        SCORE[Scoring Engine]
        CORR[Correlation Engine]
        STATE[State Engine]
        DB2[(PostgreSQL)]
    end

    subgraph surfaces [Surfaces]
        AND[Android App]
        CHAT2[OpenClaw Chat]
    end

    HC2 --> ING
    GIT --> ING
    MCP --> ING
    FIN --> ING
    LOC --> ING
    DEV --> ING
    ING --> DB2
    DB2 --> SCORE --> CORR --> STATE
    STATE --> DB2
    AND <--> ING
    AND <--> CHAT2
    CHAT2 --> DB2
```

**Rule:** Collectors write facts. Engines derive scores. OpenClaw explains — it does not invent data.

---

## Known issues & workarounds

| Issue | Workaround |
|-------|------------|
| Backend unreachable from phone | Set LAN IP in Settings, not `10.0.2.2` |
| Execution score stays 0 | Set `EXECUTION_REPO_PATHS` in `.env`; ensure backend can run `git` in those dirs |
| Docker can't see Mac repos | Bind-mount repo into container or run backend natively on Mac |
| OpenClaw down | Chat returns grounded data-only summary |
| Demo data showing | Debug build with empty backend — sync real data or use release build |
| Gradle wrapper missing in repo | Open Android Studio once; it generates the wrapper |

---

## Quick links

| Doc | Purpose |
|-----|---------|
| [README.md](../README.md) | Project overview |
| [SETUP.md](./SETUP.md) | Install backend, OpenClaw, Android |
| [backend/README.md](../backend/README.md) | API & backend dev |
| [android/README.md](../android/README.md) | App build & architecture |

---

## Summary

**Done:** A working Phase 1 MVP — health ingest, optional Pulse, auto git execution, scoring, insights, grounded chat, light-themed Android UI with demo mode.

**Not done:** Passive collectors beyond git, mental wellness score prominence, finance/location/device phases, MCP agents, background sync, push notifications.

**Plan:** Finish Phase 1 passive loop (wellness score, background sync, GitHub/MCP collectors), then Phase 2 finance with the same *observe, don't ask* philosophy.
