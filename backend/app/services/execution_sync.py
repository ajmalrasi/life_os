"""Execution sync entrypoint — GitHub only (commits + PRs + issues).

This is what the nightly recompute uses. Local git scanning was removed: the
backend runs on a different host than the dev machine, so a local `git log`
scan only ever saw whatever happened to be pulled onto the backend host —
GitHub is the actual source of truth for "did the work happen."
"""

from __future__ import annotations

from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.config import Settings
from app.repositories.github_repo import GithubRepository
from app.services.execution_collector import ExecutionSyncReport, persist_rollups, rollup_commit_events
from app.services.github_api_collector import (
    collect_github_commit_events,
    collect_github_pr_issue_events,
)


def run_execution_sync(db: Session, settings: Settings, days: int = 14) -> ExecutionSyncReport:
    end = date.today()
    start = end - timedelta(days=max(days - 1, 0))

    if not settings.github_enabled:
        return ExecutionSyncReport(
            days_updated=0,
            repos_scanned=0,
            source="none",
            message=(
                "No execution source configured. Set GITHUB_TOKEN + GITHUB_REPOS "
                "in backend/.env."
            ),
        )

    commit_events = collect_github_commit_events(
        settings.github_repos_list,
        settings.github_user or None,
        start,
        end,
        token=settings.github_token,
    )
    pr_issue_events = collect_github_pr_issue_events(
        settings.github_repos_list,
        start,
        end,
        token=settings.github_token,
    )
    rollups = rollup_commit_events(commit_events, pr_issue_events)
    persist_rollups(GithubRepository(db), rollups)

    today = rollups.get(end)
    return ExecutionSyncReport(
        days_updated=len(rollups),
        repos_scanned=len(settings.github_repos_list),
        source="github-mcp",
        today_commits=today.commits if today else 0,
        today_coding_minutes=today.coding_minutes if today else 0,
        message=(
            f"GitHub MCP: detected execution across {len(settings.github_repos_list)} repo(s) "
            f"over {days}d ({len(rollups)} day(s) updated, {len(commit_events)} commit(s), "
            f"{len(pr_issue_events)} PR/issue event(s))."
        ),
    )
