"""LifeOS FastAPI application entrypoint."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.api.routes import (
    analytics,
    chat,
    collectors,
    device,
    github_data,
    health_data,
    mood,
    notifications,
)
from app.config import get_settings
from app.database import init_db, purge_seed_data

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # For the MVP we create tables on startup. Later phases swap in Alembic.
    init_db()
    # One-time cleanup: drop any synthetic seed data from the old debug button.
    purge_seed_data()
    yield


app = FastAPI(
    title=f"{settings.app_name} API",
    version=__version__,
    description="Personal intelligence system — local-first, grounded insights.",
    lifespan=lifespan,
)

# Local network first; the Android app talks to this over LAN.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health_data.router)
app.include_router(mood.router)
app.include_router(collectors.router)
app.include_router(github_data.router)
app.include_router(analytics.router)
app.include_router(chat.router)
app.include_router(notifications.router)
app.include_router(device.router)


@app.get("/", tags=["meta"])
def root() -> dict[str, str]:
    return {"app": settings.app_name, "version": __version__, "status": "ok"}


@app.get("/healthz", tags=["meta"])
def healthz() -> dict[str, str]:
    return {"status": "healthy"}
