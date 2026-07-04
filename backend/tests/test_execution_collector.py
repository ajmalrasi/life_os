"""Tests for source-agnostic execution rollup logic (commits + PR/issue events)."""

from __future__ import annotations

from datetime import date, datetime, timezone

from app.services.execution_collector import (
    CommitEvent,
    PrIssueEvent,
    _estimate_coding_minutes,
    merge_commit_events,
    merge_pr_issue_events,
    rollup_commit_events,
)


def test_estimate_coding_minutes_groups_sessions() -> None:
    base = int(datetime(2026, 6, 14, 10, 0).timestamp())
    # Two commits 20m apart => one session
    one_session = _estimate_coding_minutes([base, base + 20 * 60])
    # Two sessions 2h apart => more minutes
    two_sessions = _estimate_coding_minutes([base, base + 20 * 60, base + 3 * 3600, base + 3 * 3600 + 600])
    assert two_sessions >= one_session


def test_merge_commit_events_dedupes_by_sha() -> None:
    ts = int(datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc).timestamp())
    a = CommitEvent(sha="shared", repo="github:me/proj", ts=ts, additions=5)
    b = CommitEvent(sha="shared", repo="github:me/proj", ts=ts, additions=99)
    c = CommitEvent(sha="other", repo="github:me/proj", ts=ts)

    merged = merge_commit_events([a], [b, c])
    assert {e.sha for e in merged} == {"shared", "other"}
    assert next(e for e in merged if e.sha == "shared").additions == 5  # first list wins


def test_merge_pr_issue_events_dedupes_by_repo_kind_number() -> None:
    day = date(2026, 6, 14)
    a = PrIssueEvent(repo="github:me/proj", kind="pr_merged", number=1, day=day)
    b = PrIssueEvent(repo="github:me/proj", kind="pr_merged", number=1, day=day)
    c = PrIssueEvent(repo="github:me/proj", kind="pr_opened", number=1, day=day)

    merged = merge_pr_issue_events([a], [b, c])
    assert len(merged) == 2


def test_rollup_combines_commits_and_pr_issue_events() -> None:
    day = date(2026, 6, 14)
    ts = int(datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc).timestamp())

    commit_events = [CommitEvent(sha="a", repo="github:me/proj", ts=ts)]
    pr_issue_events = [
        PrIssueEvent(repo="github:me/proj", kind="pr_opened", number=1, day=day),
        PrIssueEvent(repo="github:me/proj", kind="pr_merged", number=1, day=day),
        PrIssueEvent(repo="github:me/proj", kind="issue_closed", number=2, day=day),
    ]

    rollups = rollup_commit_events(commit_events, pr_issue_events)

    assert rollups[day].commits == 1
    assert rollups[day].prs_opened == 1
    assert rollups[day].prs_merged == 1
    assert rollups[day].issues_closed == 1
    assert rollups[day].issues_opened == 0


def test_rollup_includes_pr_issue_only_days() -> None:
    """A day with PR/issue activity but no commits still produces a rollup row."""
    day = date(2026, 6, 15)
    pr_issue_events = [PrIssueEvent(repo="github:me/proj", kind="issue_opened", number=3, day=day)]

    rollups = rollup_commit_events([], pr_issue_events)

    assert rollups[day].commits == 0
    assert rollups[day].issues_opened == 1
    assert rollups[day].active_repos == 1
