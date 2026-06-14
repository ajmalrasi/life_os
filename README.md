# LifeOS

> A personal operating system that continuously analyzes your life patterns to understand *why* you perform, procrastinate, thrive, or burn out.

LifeOS is **not** a fitness app or a budgeting app. It is a private, local-first personal intelligence system that correlates health, behavior, finances, location, and project activity to surface causal insights about your life.

```
"Productivity increases 31% when sleep exceeds 7.2 hours."
"Food delivery spending spikes during low recovery periods."
"Road trips correlate with improved mood for the following 3 days."
```

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
│  • Chat (grounded)      │         │   └─ OpenClaw client ───┐    │
│  • Room offline cache   │         │                         │    │
└─────────────────────────┘         │   PostgreSQL            ▼    │
                                     │                  OpenClaw LLM │
                                     └──────────────────────────────┘
```

- **Frontend:** Android (Jetpack Compose, Material 3, Health Connect, Retrofit, Room) — offline-first.
- **Backend:** FastAPI + PostgreSQL, Dockerized.
- **LLM Layer:** [OpenClaw](#openclaw) running locally; base URL is configurable. The backend grounds every answer in real data and cites supporting metrics.
- **Privacy:** Everything runs on your Android device + Jetson + local PostgreSQL. No cloud dependency.

## Build phases

This repo is built **incrementally**. Phase 1 (current) is a working MVP focused on discovering correlations between **sleep, exercise, mood, and project execution**.

| Phase | Scope |
|-------|-------|
| **1 (MVP)** | Health Connect ingest, daily mood check-in, GitHub activity, OpenClaw chat, Sleep/Recovery/Execution scores, daily insights |
| 2 | Financial tracking (SBI/Axis/CSV import + categorization) |
| 3 | Location intelligence (significant places, exploration/isolation scores) |
| 4 | Device activity (screen time, app classification, creation/consumption scores) |
| 5 | Advanced pattern discovery + proactive notifications |

## Repository layout

```
life_os/
├── backend/          FastAPI + PostgreSQL intelligence core (Dockerized)
│   ├── app/          Clean architecture: models, schemas, repositories, services, api
│   └── tests/        Unit + integration tests
├── android/          Jetpack Compose app (clean architecture, offline-first)
└── docs/             Setup & architecture documentation
```

## Quick start

- Backend: see [`backend/README.md`](backend/README.md)
- Android: see [`android/README.md`](android/README.md)
- Full setup (incl. Jetson + OpenClaw): see [`docs/SETUP.md`](docs/SETUP.md)

## License

Personal project. All data stays local.
