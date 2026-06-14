"""Tests for the GitHub MCP execution collector and SHA-based dedup."""

from __future__ import annotations

from datetime import date, datetime, timezone
from unittest.mock import MagicMock, patch

import pytest

from app.services.execution_collector import CommitEvent, merge_commit_events, rollup_commit_events
from app.services.github_api_collector import collect_github_commit_events


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


def test_merge_commit_events_dedupes_by_sha():
    ts = int(datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc).timestamp())
    git_event = CommitEvent(sha="shared", repo="/local/proj", ts=ts)
    gh_event = CommitEvent(sha="shared", repo="github:me/proj", ts=ts, additions=20, deletions=4)
    gh_only = CommitEvent(sha="ghonly", repo="github:me/proj", ts=ts, additions=3, deletions=0)

    merged = merge_commit_events([gh_event, gh_only], [git_event])
    by_sha = {e.sha: e for e in merged}

    assert len(merged) == 2
    assert by_sha["shared"].additions == 20
    assert by_sha["shared"].repo == "github:me/proj"

    rollups = rollup_commit_events(merged)
    day = datetime.fromtimestamp(ts).date()
    assert rollups[day].commits == 2
    assert rollups[day].additions == 23
