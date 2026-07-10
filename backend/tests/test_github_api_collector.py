"""Tests for the GitHub author-search execution collector and SHA-based dedup."""

from __future__ import annotations

from datetime import date, datetime, timezone
from unittest.mock import MagicMock

from app.services.execution_collector import rollup_commit_events
from app.services.github_api_collector import (
    collect_commit_events,
    collect_pr_issue_events,
)


def _ts(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat()


def _search_result(items: list[dict]) -> dict:
    return {"total_count": len(items), "incomplete_results": False, "items": items}


def _commit_item(sha: str, when: datetime, repo: str) -> dict:
    return {
        "sha": sha,
        "commit": {"author": {"date": _ts(when)}},
        "repository": {"full_name": repo},
    }


def _mock_client(handlers: dict) -> MagicMock:
    """Build a GitHubMcpClient mock dispatching on (tool, query-substring)."""
    client = MagicMock()

    def call_tool(tool_name, arguments=None):
        args = arguments or {}
        if tool_name == "get_commit":
            key = ("get_commit", args.get("sha"))
            return handlers.get(key, {"stats": {"additions": 0, "deletions": 0}})
        query = args.get("query", "")
        for (t, needle), value in handlers.items():
            if t == tool_name and needle in query:
                return value
        return _search_result([])

    client.call_tool.side_effect = call_tool
    return client


def test_search_finds_commits_across_all_repos():
    """A single author search returns commits in repos with no static config."""
    day = date(2026, 7, 8)
    noon = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)

    client = _mock_client({
        ("search_commits", "author:me"): _search_result([
            _commit_item("aaa", noon, "me/brand-new-repo"),
            _commit_item("bbb", noon, "me/another-new-one"),
        ]),
        ("get_commit", "aaa"): {"stats": {"additions": 10, "deletions": 2}},
        ("get_commit", "bbb"): {"stats": {"additions": 5, "deletions": 1}},
    })

    events = collect_commit_events("me", day, day, client=client)

    assert {e.sha for e in events} == {"aaa", "bbb"}
    assert {e.repo for e in events} == {"github:me/brand-new-repo", "github:me/another-new-one"}

    rollups = rollup_commit_events(events)
    assert rollups[day].commits == 2
    assert rollups[day].active_repos == 2
    assert rollups[day].additions == 15
    assert rollups[day].deletions == 3


def test_search_deduplicates_by_sha():
    day = date(2026, 7, 8)
    noon = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)

    client = _mock_client({
        ("search_commits", "author:me"): _search_result([
            _commit_item("dup", noon, "me/proj"),
            _commit_item("dup", noon, "me/proj"),
        ]),
    })

    events = collect_commit_events("me", day, day, client=client)
    assert len(events) == 1


def test_search_empty_result():
    day = date(2026, 7, 8)
    client = _mock_client({("search_commits", "author:me"): _search_result([])})
    assert collect_commit_events("me", day, day, client=client) == []


def test_search_no_user_is_noop():
    day = date(2026, 7, 8)
    client = MagicMock()
    assert collect_commit_events("", day, day, client=client) == []
    assert collect_pr_issue_events("", day, day, client=client) == []
    client.call_tool.assert_not_called()


def test_search_swallows_mcp_errors():
    day = date(2026, 7, 8)
    client = MagicMock()
    client.call_tool.side_effect = Exception("MCP server down")
    assert collect_commit_events("me", day, day, client=client) == []


def test_search_pagination_stops_on_short_page():
    day = date(2026, 7, 8)
    noon = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    client = _mock_client({
        ("search_commits", "author:me"): {
            "total_count": 1,
            "items": [_commit_item("only", noon, "me/proj")],
        },
    })
    events = collect_commit_events("me", day, day, client=client)
    assert len(events) == 1
    # search_commits called once (short page ends pagination), plus one get_commit.
    search_calls = [c for c in client.call_tool.call_args_list if c.args[0] == "search_commits"]
    assert len(search_calls) == 1


def test_search_pr_and_issue_events():
    day = date(2026, 7, 8)
    noon = datetime(2026, 7, 8, 12, 0, tzinfo=timezone.utc)
    repo_url = "https://api.github.com/repos/me/proj"

    client = _mock_client({
        ("search_issues", "type:pr author:me created"): _search_result([
            {"number": 5, "created_at": _ts(noon), "repository_url": repo_url},
        ]),
        ("search_issues", "type:pr author:me merged"): _search_result([
            {
                "number": 5,
                "closed_at": _ts(noon),
                "pull_request": {"merged_at": _ts(noon)},
                "repository_url": repo_url,
            },
        ]),
        ("search_issues", "type:issue author:me created"): _search_result([
            {"number": 7, "created_at": _ts(noon), "repository_url": repo_url},
        ]),
        ("search_issues", "type:issue author:me closed"): _search_result([
            {"number": 7, "closed_at": _ts(noon), "repository_url": repo_url},
        ]),
    })

    events = collect_pr_issue_events("me", day, day, client=client)
    kinds = {(e.kind, e.number) for e in events}
    assert kinds == {
        ("pr_opened", 5),
        ("pr_merged", 5),
        ("issue_opened", 7),
        ("issue_closed", 7),
    }
    assert all(e.repo == "github:me/proj" for e in events)
    assert all(e.day == day for e in events)
