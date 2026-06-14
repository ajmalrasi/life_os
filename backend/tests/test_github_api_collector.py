"""Tests for the GitHub API execution collector and SHA-based dedup."""

from __future__ import annotations

from datetime import date, datetime, timezone

import httpx
import pytest

from app.services.execution_collector import CommitEvent, merge_commit_events, rollup_commit_events
from app.services.github_api_collector import (
    collect_github_commit_events,
    _next_link,
)


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def _make_client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler), base_url="https://api.github.com")


def test_collect_github_commit_events_parses_commits_and_stats():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/repos/me/proj/commits":
            return httpx.Response(
                200,
                json=[
                    {"sha": "aaa", "commit": {"author": {"date": _iso(noon)}}},
                    {"sha": "bbb", "commit": {"author": {"date": _iso(noon)}}},
                ],
            )
        if path == "/repos/me/proj/commits/aaa":
            return httpx.Response(200, json={"stats": {"additions": 10, "deletions": 2}})
        if path == "/repos/me/proj/commits/bbb":
            return httpx.Response(200, json={"stats": {"additions": 5, "deletions": 1}})
        return httpx.Response(404, json={})

    client = _make_client(handler)
    events = collect_github_commit_events(
        ["me/proj"], "tok", None, day, day, fetch_stats=True, client=client
    )

    assert {e.sha for e in events} == {"aaa", "bbb"}
    assert all(e.repo == "github:me/proj" for e in events)

    rollups = rollup_commit_events(events)
    assert rollups[day].commits == 2
    assert rollups[day].active_repos == 1
    assert rollups[day].additions == 15
    assert rollups[day].deletions == 3


def test_collect_github_handles_pagination():
    day = date(2026, 6, 14)
    noon = datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc)

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path != "/repos/me/proj/commits":
            return httpx.Response(404, json={})
        if request.url.params.get("page") == "2":
            return httpx.Response(
                200, json=[{"sha": "p2", "commit": {"author": {"date": _iso(noon)}}}]
            )
        # first page advertises a next link
        return httpx.Response(
            200,
            json=[{"sha": "p1", "commit": {"author": {"date": _iso(noon)}}}],
            headers={
                "Link": '<https://api.github.com/repos/me/proj/commits?page=2>; rel="next"'
            },
        )

    client = _make_client(handler)
    events = collect_github_commit_events(
        ["me/proj"], "tok", None, day, day, fetch_stats=False, client=client
    )
    assert {e.sha for e in events} == {"p1", "p2"}


def test_collect_github_swallows_http_errors():
    day = date(2026, 6, 14)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"message": "Bad credentials"})

    client = _make_client(handler)
    events = collect_github_commit_events(
        ["me/proj"], "tok", None, day, day, fetch_stats=False, client=client
    )
    assert events == []


def test_merge_commit_events_dedupes_by_sha():
    ts = int(datetime(2026, 6, 14, 12, 0, tzinfo=timezone.utc).timestamp())
    # Same commit seen locally (no stats) and via GitHub (with stats).
    git_event = CommitEvent(sha="shared", repo="/local/proj", ts=ts)
    gh_event = CommitEvent(sha="shared", repo="github:me/proj", ts=ts, additions=20, deletions=4)
    gh_only = CommitEvent(sha="ghonly", repo="github:me/proj", ts=ts, additions=3, deletions=0)

    # GitHub first => its richer record wins on the shared SHA.
    merged = merge_commit_events([gh_event, gh_only], [git_event])
    by_sha = {e.sha: e for e in merged}

    assert len(merged) == 2  # shared counted once
    assert by_sha["shared"].additions == 20
    assert by_sha["shared"].repo == "github:me/proj"

    rollups = rollup_commit_events(merged)
    day = datetime.fromtimestamp(ts).date()
    assert rollups[day].commits == 2  # not 3 — dedup worked
    assert rollups[day].additions == 23


def test_next_link_parsing():
    resp = httpx.Response(
        200,
        headers={
            "Link": '<https://api.github.com/x?page=2>; rel="next", '
            '<https://api.github.com/x?page=5>; rel="last"'
        },
    )
    assert _next_link(resp) == "https://api.github.com/x?page=2"
    assert _next_link(httpx.Response(200)) is None
