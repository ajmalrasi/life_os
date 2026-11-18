# LifeOS Backend

FastAPI + PostgreSQL intelligence core. Ingests raw signals, computes daily
scores, classifies your state, discovers correlations, and answers grounded
questions via a local OpenClaw LLM.

## Run with Docker (recommended)

```bash
cd backend
cp -n .env.example .env
# Set POSTGRES_PASSWORD and adjust OPENCLAW_BASE_URL in .env.
docker compose up --build
```

API: <http://localhost:8000>  ·  Interactive docs: <http://localhost:8000/docs>

PostgreSQL is reachable on the Compose network. For local database administration,
use `docker compose exec db psql -U lifeos -d lifeos` with your configured user and
database. On an existing PostgreSQL volume, set `POSTGRES_PASSWORD` to the existing
role password before recreating containers. Rotate the role password in PostgreSQL
when changing it and update `.env` to match.

Set `API_TOKEN` in `.env` to require bearer authentication on protected routes.
With `ENVIRONMENT=production`, startup requires a nonblank token. Configure the
same token on API clients before enabling it.

## Run locally (without Docker)

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
# Point DATABASE_URL at a running Postgres, then:
uvicorn app.main:app --reload
```

## Architecture (clean / repository pattern)

```
app/
├── api/routes/      HTTP layer (thin controllers)
├── schemas/         Pydantic request/response models
├── repositories/    All DB access (repository pattern)
├── services/        Business logic & analytics engines
│   ├── scoring.py        pure score functions (unit-tested)
│   ├── correlation.py    lagged Pearson pattern discovery
│   ├── state_engine.py   daily state classifier
│   ├── analytics.py      orchestration (recompute + nightly)
│   ├── notifications.py  proactive nudges
│   ├── openclaw.py       LLM client
│   └── chat_service.py   grounded, cited answers
├── models/          SQLAlchemy ORM models
├── config.py        env-driven settings
└── database.py      engine/session/base
```

## Key endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/health/batch` | Idempotent Health Connect record ingest |
| POST | `/mood` | Daily mood check-in (upsert) |
| POST | `/github` | Daily GitHub/coding activity (upsert) |
| POST | `/analytics/recompute?days=60` | Recompute scores+states, rediscover patterns |
| GET | `/analytics/scores?days=30` | Daily Sleep/Recovery/Execution/etc scores |
| GET | `/analytics/state?days=30` | Daily classified state + confidence |
| GET | `/analytics/insights` | Discovered correlations (human-readable) |
| POST | `/chat` | Ask LifeOS; grounded answer with citations |
| GET | `/notifications` | Proactive, data-backed nudges |
| GET | `/chat/health` | Check OpenClaw reachability |

## Nightly analysis

`POST /analytics/recompute` is the nightly entrypoint. Schedule it with cron on
the Jetson:

```cron
30 3 * * *  curl -fsS -X POST "http://localhost:8000/analytics/recompute?days=90"
```

## Tests

```bash
cd backend
pip install -r requirements.txt
pytest -q
```

Tests use SQLite in-memory, so no Postgres or OpenClaw is needed.
