"""Automatic execution signal collection.

LifeOS should observe project output — not ask you to type commits/minutes.
The deterministic baseline is local ``git`` history on configured repo paths.
A GitHub API collector (``github_api_collector.py``) supplements this so work
done on other machines — or when the backend runs in Docker without
bind-mounts — is still observed. Both sources emit :class:`CommitEvent`s which
are deduplicated by commit SHA before being rolled up per day. OpenClaw/MCP
agents can also POST structured rollups to ``/github``.
"""

from __future__ import annotations

import subprocess
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from typing import Iterable

from sqlalchemy.orm import Session

from app.repositories.github_repo import GithubRepository
from app.schemas.github import GithubActivityIn

SESSION_GAP_SECONDS = 45 * 60
MIN_SESSION_MINUTES = 15
MAX_SESSION_MINUTES = 240


@dataclass
class CommitEvent:
    """A single commit, source-agnostic. Deduplicated across sources by ``sha``."""

    sha: str
    repo: str
    ts: int  # author/commit time, unix seconds
    additions: int = 0
    deletions: int = 0


@dataclass
class DayExecution:
    day: date
    commits: int
    coding_minutes: int
    active_repos: int
    additions: int = 0
    deletions: int = 0


@dataclass
class ExecutionSyncReport:
    days_updated: int
    repos_scanned: int
    source: str
    message: str
    today_commits: int | None = None
    today_coding_minutes: int | None = None


class ExecutionCollector:
    def __init__(
        self,
        db: Session,
        repo_paths: list[str] | None = None,
        git_author: str | None = None,
    ) -> None:
        self.db = db
        self.repo_paths = [p for p in (repo_paths or []) if p.strip()]
        self.git_author = (git_author or "").strip() or None
        self.github = GithubRepository(db)

    def sync(self, days: int = 14) -> ExecutionSyncReport:
        paths = resolve_repo_paths(self.repo_paths)
        if not paths:
            return ExecutionSyncReport(
                days_updated=0,
                repos_scanned=0,
                source="git",
                message=(
                    "No repo paths configured. Set EXECUTION_REPO_PATHS in backend/.env "
                    "(comma-separated git repo directories on the machine running the backend)."
                ),
            )

        end = date.today()
        start = end - timedelta(days=max(days - 1, 0))
        events = collect_git_commit_events(paths, self.git_author, start, end)
        rollups = rollup_commit_events(events)

        persist_rollups(self.github, rollups)

        today = rollups.get(end)
        return ExecutionSyncReport(
            days_updated=len(rollups),
            repos_scanned=len(paths),
            source="git",
            today_commits=today.commits if today else 0,
            today_coding_minutes=today.coding_minutes if today else 0,
            message=(
                f"Auto-detected execution from {len(paths)} repo(s) over {days}d "
                f"({len(rollups)} day(s) updated)."
            ),
        )


def resolve_repo_paths(raw_paths: Iterable[str]) -> list[Path]:
    """Expand, resolve, and keep only directories that are actually git repos."""
    resolved: list[Path] = []
    for raw in raw_paths:
        if not raw or not raw.strip():
            continue
        path = Path(raw).expanduser().resolve()
        if (path / ".git").exists():
            resolved.append(path)
    return resolved


def persist_rollups(github: GithubRepository, rollups: dict[date, DayExecution]) -> None:
    """Upsert per-day execution rollups into the github_activity table."""
    for rollup in rollups.values():
        github.upsert(
            GithubActivityIn(
                day=rollup.day,
                commits=rollup.commits,
                coding_minutes=rollup.coding_minutes,
                active_repos=rollup.active_repos,
                additions=rollup.additions,
                deletions=rollup.deletions,
            )
        )


def merge_commit_events(*event_lists: Iterable[CommitEvent]) -> list[CommitEvent]:
    """Combine commit events from multiple sources, deduplicated by SHA.

    Earlier lists win on conflict, so pass the source with richer data (e.g.
    GitHub stats) first. The same commit observed by both the local git scan and
    the GitHub API shares one SHA and is therefore counted exactly once.
    """
    merged: dict[str, CommitEvent] = {}
    for events in event_lists:
        for event in events:
            if event.sha and event.sha not in merged:
                merged[event.sha] = event
    return list(merged.values())


def rollup_commit_events(events: Iterable[CommitEvent]) -> dict[date, DayExecution]:
    """Build per-day execution rollups from deduplicated commit events."""
    ts_by_day: dict[date, list[int]] = defaultdict(list)
    repos_by_day: dict[date, set[str]] = defaultdict(set)
    additions_by_day: dict[date, int] = defaultdict(int)
    deletions_by_day: dict[date, int] = defaultdict(int)

    for event in events:
        day = datetime.fromtimestamp(event.ts).date()
        ts_by_day[day].append(event.ts)
        repos_by_day[day].add(event.repo)
        additions_by_day[day] += event.additions
        deletions_by_day[day] += event.deletions

    rollups: dict[date, DayExecution] = {}
    for day, timestamps in ts_by_day.items():
        rollups[day] = DayExecution(
            day=day,
            commits=len(timestamps),
            coding_minutes=_estimate_coding_minutes(timestamps),
            active_repos=len(repos_by_day[day]),
            additions=additions_by_day[day],
            deletions=deletions_by_day[day],
        )

    # Explicit zero-days are not written — absence means no output that day.
    return rollups


def collect_git_activity(
    repo_paths: list[Path],
    author: str | None,
    start: date,
    end: date,
) -> dict[date, DayExecution]:
    """Scan git logs and build per-day execution rollups (deterministic baseline)."""
    events = collect_git_commit_events(repo_paths, author, start, end)
    return rollup_commit_events(events)


def collect_git_commit_events(
    repo_paths: list[Path],
    author: str | None,
    start: date,
    end: date,
) -> list[CommitEvent]:
    """Read git history across the window and return per-commit events."""
    events: list[CommitEvent] = []
    seen: set[str] = set()
    for repo in repo_paths:
        for sha, ts in _git_commits(repo, start, end, author):
            if sha in seen:
                continue
            seen.add(sha)
            events.append(CommitEvent(sha=sha, repo=str(repo), ts=ts))
    return events


def _git_commits(
    repo: Path,
    start: date,
    end: date,
    author: str | None,
) -> list[tuple[str, int]]:
    since = datetime.combine(start, time.min).isoformat(sep=" ")
    until = datetime.combine(end + timedelta(days=1), time.min).isoformat(sep=" ")
    cmd = [
        "git",
        "-C",
        str(repo),
        "log",
        f"--since={since}",
        f"--until={until}",
        "--no-merges",
        "--format=%H %ct",
    ]
    if author:
        cmd.append(f"--author={author}")

    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return []

    if proc.returncode != 0:
        return []

    out: list[tuple[str, int]] = []
    for line in proc.stdout.splitlines():
        parts = line.strip().split()
        if len(parts) == 2 and parts[1].isdigit():
            out.append((parts[0], int(parts[1])))
    return out


def _estimate_coding_minutes(timestamps: list[int]) -> int:
    if not timestamps:
        return 0
    if len(timestamps) == 1:
        return MIN_SESSION_MINUTES

    ordered = sorted(timestamps)
    sessions: list[tuple[int, int]] = []
    start = ordered[0]
    prev = ordered[0]
    for ts in ordered[1:]:
        if ts - prev > SESSION_GAP_SECONDS:
            sessions.append((start, prev))
            start = ts
        prev = ts
    sessions.append((start, prev))

    total = 0
    for first, last in sessions:
        span_min = max(0, (last - first) // 60)
        minutes = max(MIN_SESSION_MINUTES, span_min + MIN_SESSION_MINUTES)
        total += min(MAX_SESSION_MINUTES, minutes)
    return total
