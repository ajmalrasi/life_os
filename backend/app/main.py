"""LifeOS FastAPI application entrypoint."""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app import __version__
from app.api.deps import require_token
from app.api.routes import (
    analytics,
    chat,
    collectors,
    device,
    expenses,
    github_data,
    health_data,
    mail_accounts,
    mood,
    notifications,
    payee_labels,
    priority,
    statements,
    status,
    trips,
)
from app.config import get_settings
from app.database import engine, init_db, purge_seed_data
from app.mail.gmail_provider import MailProviderError
from app.migrations import apply_migrations

settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # create_all makes missing tables but never ALTERs an existing one; run the
    # numbered SQL migrations for everything create_all can't handle.
    init_db()
    apply_migrations(engine)
    # One-time cleanup: drop any synthetic seed data from the old debug button.
    purge_seed_data()
    yield


app = FastAPI(
    title=f"{settings.app_name} API",
    version=__version__,
    description="Personal intelligence system — local-first, grounded insights.",
    lifespan=lifespan,
)

# No browser client exists (Android talks to this directly over LAN/Tailscale,
# not via fetch/XHR), so CORS has nothing to protect against and nothing to
# widen. Kept locked down rather than "*".
app.add_middleware(
    CORSMiddleware,
    allow_origins=[],
    allow_credentials=False,
    allow_methods=["GET", "POST", "PUT", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)


@app.exception_handler(MailProviderError)
async def gmail_client_error_handler(_request: Request, exc: MailProviderError) -> JSONResponse:
    """Keep Gmail outages visible to Android and cron instead of returning a false 200."""
    return JSONResponse(status_code=503, content={"detail": str(exc)})

# health_data carries its own per-route auth (POST /health/batch is protected,
# POST /health/auto-export is exempt — Health Auto Export sends no headers);
# every other router requires the bearer token as a whole once one is
# configured (see app/api/deps.py — unset api_token disables auth entirely).
app.include_router(health_data.router)
app.include_router(mood.router, dependencies=[Depends(require_token)])
app.include_router(collectors.router, dependencies=[Depends(require_token)])
app.include_router(github_data.router, dependencies=[Depends(require_token)])
app.include_router(expenses.router, dependencies=[Depends(require_token)])
app.include_router(statements.router, dependencies=[Depends(require_token)])
app.include_router(status.router, dependencies=[Depends(require_token)])
app.include_router(payee_labels.router, dependencies=[Depends(require_token)])
app.include_router(priority.router, dependencies=[Depends(require_token)])
app.include_router(mail_accounts.router, dependencies=[Depends(require_token)])
app.include_router(analytics.router, dependencies=[Depends(require_token)])
app.include_router(chat.router, dependencies=[Depends(require_token)])
app.include_router(notifications.router, dependencies=[Depends(require_token)])
app.include_router(device.router, dependencies=[Depends(require_token)])
app.include_router(trips.router, dependencies=[Depends(require_token)])


@app.get("/", tags=["meta"])
def root() -> dict[str, str]:
    return {"app": settings.app_name, "version": __version__, "status": "ok"}


@app.get("/healthz", tags=["meta"])
def healthz() -> dict[str, str]:
    return {"status": "healthy"}
