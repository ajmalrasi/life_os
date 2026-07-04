"""Automatic execution signal collection.

LifeOS should observe project output — not ask you to type commits/minutes.
Execution is sourced entirely from GitHub (``github_api_collector.py``):
commits, pull requests, and issues, all fetched via the official
github-mcp-server MCP sidecar. This module holds the source-agnostic event
types and the per-day rollup logic that turns those events into
:class:`DayExecution` rows.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime
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
class PrIssueEvent:
    """A pull-request or issue lifecycle event (opened/merged/closed).

    Deduplicated by ``(repo, kind, number)`` — a PR opened and merged the same
    day yields two distinct events (one per ``kind``), which is intentional.
    """

    repo: str
    kind: str  # "pr_opened" | "pr_merged" | "issue_opened" | "issue_closed"
    number: int
    day: date


@dataclass
class DayExecution:
    day: date
    commits: int
    coding_minutes: int
    active_repos: int
    additions: int = 0
    deletions: int = 0
    prs_opened: int = 0
    prs_merged: int = 0
    issues_opened: int = 0
    issues_closed: int = 0


@dataclass
class ExecutionSyncReport:
    days_updated: int
    repos_scanned: int
    source: str
    message: str
    today_commits: int | None = None
    today_coding_minutes: int | None = None


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
                prs_opened=rollup.prs_opened,
                prs_merged=rollup.prs_merged,
                issues_opened=rollup.issues_opened,
                issues_closed=rollup.issues_closed,
            )
        )


def merge_commit_events(*event_lists: Iterable[CommitEvent]) -> list[CommitEvent]:
    """Combine commit events from multiple sources, deduplicated by SHA.

    Earlier lists win on conflict, so pass the source with richer data (e.g.
    GitHub stats) first.
    """
    merged: dict[str, CommitEvent] = {}
    for events in event_lists:
        for event in events:
            if event.sha and event.sha not in merged:
                merged[event.sha] = event
    return list(merged.values())


def merge_pr_issue_events(*event_lists: Iterable[PrIssueEvent]) -> list[PrIssueEvent]:
    """Combine PR/issue events from multiple sources, deduplicated by (repo, kind, number)."""
    merged: dict[tuple[str, str, int], PrIssueEvent] = {}
    for events in event_lists:
        for event in events:
            key = (event.repo, event.kind, event.number)
            if key not in merged:
                merged[key] = event
    return list(merged.values())


def rollup_commit_events(
    events: Iterable[CommitEvent],
    pr_issue_events: Iterable[PrIssueEvent] = (),
) -> dict[date, DayExecution]:
    """Build per-day execution rollups from deduplicated commit + PR/issue events."""
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

    pr_issue_counts: dict[date, dict[str, int]] = defaultdict(
        lambda: {"prs_opened": 0, "prs_merged": 0, "issues_opened": 0, "issues_closed": 0}
    )
    field_by_kind = {
        "pr_opened": "prs_opened",
        "pr_merged": "prs_merged",
        "issue_opened": "issues_opened",
        "issue_closed": "issues_closed",
    }
    for event in pr_issue_events:
        field = field_by_kind.get(event.kind)
        if field is None:
            continue
        pr_issue_counts[event.day][field] += 1
        repos_by_day[event.day].add(event.repo)

    all_days = set(ts_by_day) | set(pr_issue_counts)
    rollups: dict[date, DayExecution] = {}
    for day in all_days:
        timestamps = ts_by_day.get(day, [])
        counts = pr_issue_counts.get(day, {})
        rollups[day] = DayExecution(
            day=day,
            commits=len(timestamps),
            coding_minutes=_estimate_coding_minutes(timestamps),
            active_repos=len(repos_by_day[day]),
            additions=additions_by_day[day],
            deletions=deletions_by_day[day],
            prs_opened=counts.get("prs_opened", 0),
            prs_merged=counts.get("prs_merged", 0),
            issues_opened=counts.get("issues_opened", 0),
            issues_closed=counts.get("issues_closed", 0),
        )

    # Explicit zero-days are not written — absence means no output that day.
    return rollups


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
