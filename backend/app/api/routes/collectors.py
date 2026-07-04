from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.config import get_settings
from app.database import get_db
from app.schemas.collectors import ExecutionSyncResult
from app.services.execution_sync import run_execution_sync
from app.services.github_api_collector import GitHubApiCollector

router = APIRouter(prefix="/collectors", tags=["collectors"])


@router.post("/execution/sync", response_model=ExecutionSyncResult)
def sync_execution(days: int = 14, db: Session = Depends(get_db)) -> ExecutionSyncResult:
    """Auto-detect coding execution from GitHub (commits, PRs, issues).

    Idempotent — commits are deduplicated by SHA and PR/issue events by
    (repo, kind, number), so this is safe to trigger on demand (e.g. a "sync
    now" button) without double counting.
    """
    settings = get_settings()
    report = run_execution_sync(db, settings, days=days)
    return ExecutionSyncResult(
        days_updated=report.days_updated,
        repos_scanned=report.repos_scanned,
        source=report.source,
        today_commits=report.today_commits,
        today_coding_minutes=report.today_coding_minutes,
        message=report.message,
    )


@router.post("/execution/github/sync", response_model=ExecutionSyncResult)
def sync_github_execution(days: int = 14, db: Session = Depends(get_db)) -> ExecutionSyncResult:
    """GitHub-only execution sync (useful for testing the GITHUB_TOKEN setup)."""
    settings = get_settings()
    report = GitHubApiCollector(
        db,
        token=settings.github_token,
        repos=settings.github_repos_list,
        author=settings.github_user or None,
    ).sync(days=days)
    return ExecutionSyncResult(
        days_updated=report.days_updated,
        repos_scanned=report.repos_scanned,
        source=report.source,
        today_commits=report.today_commits,
        today_coding_minutes=report.today_coding_minutes,
        message=report.message,
    )
