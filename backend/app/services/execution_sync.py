"""Combined execution sync — local git scan + GitHub API, deduped by SHA.

This is the entrypoint the nightly recompute uses. It runs both collectors when
configured, merges their commit events (the GitHub source wins on conflict so
code-churn stats are preserved), and writes a single set of per-day rollups.
Because both sources key commits by SHA, a commit observed locally *and* on
GitHub is counted exactly once.
"""

from __future__ import annotations

from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.config import Settings
from app.repositories.github_repo import GithubRepository
from app.services.execution_collector import (
    ExecutionSyncReport,
    collect_git_commit_events,
    merge_commit_events,
    persist_rollups,
    resolve_repo_paths,
    rollup_commit_events,
)
from app.services.github_api_collector import collect_github_commit_events


def run_execution_sync(db: Session, settings: Settings, days: int = 14) -> ExecutionSyncReport:
    end = date.today()
    start = end - timedelta(days=max(days - 1, 0))

    sources: list[str] = []

    git_paths = resolve_repo_paths(settings.execution_repo_path_list)
    git_events = collect_git_commit_events(
        git_paths,
        settings.execution_git_author or None,
        start,
        end,
    )
    if git_paths:
        sources.append(f"git({len(git_paths)})")

    github_events = []
    if settings.github_enabled:
        github_events = collect_github_commit_events(
            settings.github_repos_list,
            settings.github_user or None,
            start,
            end,
            token=settings.github_token,
        )
        sources.append(f"github-mcp({len(settings.github_repos_list)})")

    # GitHub events first so their churn stats win when the same SHA appears in
    # both sources.
    events = merge_commit_events(github_events, git_events)
    rollups = rollup_commit_events(events)

    persist_rollups(GithubRepository(db), rollups)

    repos_scanned = len(git_paths) + len(settings.github_repos_list if settings.github_enabled else [])
    today = rollups.get(end)

    if sources:
        message = (
            f"Auto-detected execution from {', '.join(sources)} over {days}d "
            f"({len(rollups)} day(s) updated, {len(events)} unique commit(s))."
        )
    else:
        message = (
            "No execution sources configured. Set EXECUTION_REPO_PATHS and/or "
            "GITHUB_TOKEN + GITHUB_REPOS in backend/.env."
        )

    return ExecutionSyncReport(
        days_updated=len(rollups),
        repos_scanned=repos_scanned,
        source="+".join(s.split("(")[0] for s in sources) or "none",
        today_commits=today.commits if today else 0,
        today_coding_minutes=today.coding_minutes if today else 0,
        message=message,
    )
