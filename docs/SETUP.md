# LifeOS — Full Setup Guide

End-to-end setup for the Phase 1 MVP: backend on a Jetson Orin Nano (or any
machine), a local OpenClaw LLM, and the Android client.

```
Android app  ──HTTP(LAN)──▶  FastAPI backend  ──HTTP──▶  OpenClaw (local LLM)
                                   │
                              PostgreSQL
```

Everything runs locally. No cloud, no external APIs.

---

## 1. Backend (FastAPI + PostgreSQL)

On the Jetson (or your dev machine):

```bash
cd backend
cp .env.example .env
# Edit .env: set OPENCLAW_BASE_URL to where OpenClaw listens (see step 2).
docker compose up --build -d
```

Verify:

```bash
curl http://localhost:8000/healthz          # {"status":"healthy"}
open http://localhost:8000/docs             # interactive API explorer
```

Tables are created automatically on first start.

### Run without Docker (optional)

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export DATABASE_URL=postgresql+psycopg2://lifeos:lifeos@localhost:5432/lifeos
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

---

## 2. OpenClaw (local LLM on Jetson Orin Nano)

LifeOS talks to OpenClaw via an **OpenAI-compatible** `/v1/chat/completions`
endpoint. Start your OpenClaw server so it listens on the Jetson's LAN, e.g.:

```bash
# Example — adapt to your OpenClaw build/runtime:
openclaw serve --host 0.0.0.0 --port 8080 --model openclaw
```

Then point the backend at it (in `backend/.env`):

```env
OPENCLAW_BASE_URL=http://<jetson-lan-ip>:8080
OPENCLAW_MODEL=openclaw
```

Check reachability through the backend:

```bash
curl http://localhost:8000/chat/health
# {"openclaw_base_url":"http://...:8080","reachable":true}
```

> If OpenClaw is unreachable, `/chat` still returns a **grounded, data-only**
> summary (it never fabricates numbers) — it just won't be conversational.

---

## 3. Android app

1. Install the **Health Connect** app on the phone (built in on Android 14+).
2. Open `android/` in Android Studio, let it sync, and run on a device/emulator.
3. In the app: **Settings → Backend base URL** → your Jetson, e.g.
   `http://192.168.1.50:8000/`.
4. **Today → Sync health & analyze** → grant Health Connect permissions.
5. Log a **Check-in** daily and your **execution** (commits/coding minutes).

See [`../android/README.md`](../android/README.md) for the build details
(including generating the Gradle wrapper jar).

---

## 4. The daily/nightly loop

LifeOS gets smarter with more data. Each day:

- Sync health (automatic-ish via the dashboard button; Phase 2 adds background
  sync via WorkManager).
- Submit a mood check-in.
- Log execution.

Schedule the **nightly analysis** on the Jetson so insights refresh on their own:

```cron
# crontab -e
30 3 * * *  curl -fsS -X POST "http://localhost:8000/analytics/recompute?days=90" >/dev/null
```

This recomputes scores + states for the trailing 90 days and rediscovers
correlations (e.g. *"Execution increases 31% when sleep is above 7.2h"*).

---

## 5. How insights are produced (so you can trust them)

1. **Ingest** raw signals (`/health/batch`, `/mood`, `/github`).
2. **Score** each day with transparent, bounded formulas (`services/scoring.py`):
   Sleep, Recovery, Execution, Consistency, Burnout Risk.
3. **Classify** a daily State with confidence (`services/state_engine.py`).
4. **Discover** lagged Pearson correlations between drivers (sleep, recovery)
   and outcomes (execution, mood, focus), surfacing only those with enough
   samples and meaningful strength (`services/correlation.py`).
5. **Chat** answers are constrained to a JSON snapshot of *your* data and must
   cite metrics (`services/chat_service.py`) — no hallucinations.

---

## 6. Verifying the pipeline (no phone needed)

You can exercise the whole brain with curl:

```bash
# Log a few days of mood + execution
curl -X POST localhost:8000/mood -H 'Content-Type: application/json' \
  -d '{"day":"2026-06-10","mood":4,"energy":4,"focus":4,"stress":2}'
curl -X POST localhost:8000/github -H 'Content-Type: application/json' \
  -d '{"day":"2026-06-10","commits":6,"coding_minutes":180}'

# Recompute and read insights
curl -X POST "localhost:8000/analytics/recompute?days=30"
curl localhost:8000/analytics/insights
curl -X POST localhost:8000/chat -H 'Content-Type: application/json' \
  -d '{"message":"Why was I productive?","context_days":30}'
```

The backend test suite (`cd backend && pytest`) seeds correlated data and
asserts that sleep↔execution insights are discovered end-to-end.

---

## Roadmap beyond Phase 1

| Phase | Adds |
|-------|------|
| 2 | Financial import (SBI/Axis/CSV) + auto-categorization + Financial Discipline Score |
| 3 | Location intelligence (significant places, Exploration/Isolation scores) |
| 4 | Device activity (screen time, app classification, Creation/Consumption scores) |
| 5 | Advanced pattern discovery, richer state model, push notifications |

The schema and engines are designed to absorb these without rework: the health
table is metric-typed, scores/states/insights are recomputable snapshots, and
the correlation engine is generic over any named daily series.
