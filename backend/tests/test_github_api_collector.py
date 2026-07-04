"""Tests for the GitHub MCP execution collector and SHA-based dedup."""

from __future__ import annotations

from datetime import date, datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from app.services.execution_collector import rollup_commit_events
from app.services.github_api_collector import collect_github_commit_events, collect_github_pr_issue_events


def _ts(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat()


def _mock_client(tool_responses: dict) -> MagicMock:
    """Build a GitHubMcpClient mock that returns canned responses per tool+args."""
    client = MagicMock()

    def call_tool(tool_name, arguments=None):
        args = arguments or {}
        key = (tool_name, args.get("owner"), args.get("repo"), args.get("sha"))
        return tool_responses.get(key) or tool_responses.get(tool_name)

    client.call_tool.side_effect = call_tool
    return client


def test_collect_parses_commits_and_stats():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    client = _mock_client({
        ("list_commits", "me", "proj", None): [
            {"sha": "aaa", "commit": {"author": {"date": _ts(noon)}}},
            {"sha": "bbb", "commit": {"author": {"date": _ts(noon)}}},
        ],
        ("get_commit", "me", "proj", "aaa"): {"stats": {"additions": 10, "deletions": 2}},
        ("get_commit", "me", "proj", "bbb"): {"stats": {"additions": 5, "deletions": 1}},
    })

    events = collect_github_commit_events(["me/proj"], None, day, day, client=client)

    assert {e.sha for e in events} == {"aaa", "bbb"}
    assert all(e.repo == "github:me/proj" for e in events)

    rollups = rollup_commit_events(events)
    assert rollups[day].commits == 2
    assert rollups[day].active_repos == 1
    assert rollups[day].additions == 15
    assert rollups[day].deletions == 3


def test_collect_deduplicates_within_repo():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    # Same SHA returned on two pages → must appear only once.
    pages = [
        [{"sha": "dup", "commit": {"author": {"date": _ts(noon)}}}],
        [{"sha": "dup", "commit": {"author": {"date": _ts(noon)}}}],
    ]
    call_count = {"n": 0}

    client = MagicMock()
    def call_tool(tool_name, arguments=None):
        args = arguments or {}
        if tool_name == "list_commits":
            idx = call_count["n"]
            call_count["n"] += 1
            return pages[idx] if idx < len(pages) else []
        return {"stats": {"additions": 1, "deletions": 0}}
    client.call_tool.side_effect = call_tool

    events = collect_github_commit_events(["me/proj"], None, day, day, client=client)
    assert len(events) == 1


def test_collect_stops_on_empty_page():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    client = _mock_client({
        ("list_commits", "me", "proj", None): [],
    })

    events = collect_github_commit_events(["me/proj"], None, day, day, client=client)
    assert events == []


def test_collect_swallows_mcp_errors():
    day = date(2026, 6, 14)

    client = MagicMock()
    client.call_tool.side_effect = Exception("MCP server down")

    events = collect_github_commit_events(["me/proj"], None, day, day, client=client)
    assert events == []


def test_collect_mcp_none_response():
    day = date(2026, 6, 14)

    # MCP client returns None (server unreachable) rather than raising.
    client = _mock_client({"list_commits": None})

    events = collect_github_commit_events(["me/proj"], None, day, day, client=client)
    assert events == []


def test_collect_pr_events_opened_and_merged():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    client = _mock_client({
        ("list_pull_requests", "me", "proj", None): [
            {
                "number": 5,
                "updated_at": _ts(noon),
                "created_at": _ts(noon),
                "merged_at": _ts(noon),
            },
        ],
    })

    events = collect_github_pr_issue_events(["me/proj"], day, day, client=client)
    kinds = {(e.kind, e.number) for e in events if e.repo == "github:me/proj"}
    assert ("pr_opened", 5) in kinds
    assert ("pr_merged", 5) in kinds


def test_collect_pr_events_stops_pagination_before_window():
    day = date(2026, 6, 14)
    in_window = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)
    before_window = datetime(2026, 6, 10, 12, 0, tzinfo=timezone.utc)

    client = MagicMock()

    def call_tool(tool_name, arguments=None):
        if tool_name == "list_pull_requests":
            return [
                {"number": 2, "updated_at": _ts(in_window), "created_at": _ts(in_window)},
                {"number": 1, "updated_at": _ts(before_window), "created_at": _ts(before_window)},
            ]
        return None

    client.call_tool.side_effect = call_tool

    events = collect_github_pr_issue_events(["me/proj"], day, day, client=client)
    assert {e.number for e in events} == {2}


def test_collect_issue_events_opened_and_closed_excludes_prs():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    client = _mock_client({
        "list_pull_requests": [],
        "list_issues": [
            {"number": 7, "created_at": _ts(noon), "closed_at": _ts(noon)},
            {"number": 8, "created_at": _ts(noon), "pull_request": {"url": "x"}},
        ],
    })

    events = collect_github_pr_issue_events(["me/proj"], day, day, client=client)
    kinds = {(e.kind, e.number) for e in events}
    assert ("issue_opened", 7) in kinds
    assert ("issue_closed", 7) in kinds
    assert not any(number == 8 for _, number in kinds)
