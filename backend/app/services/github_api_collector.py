"""GitHub REST API execution collector.

Supplements (or replaces) the local ``git`` scan in ``execution_collector.py``.
The local scan only sees repos on the machine running the backend, which fails
when the backend is containerised without bind-mounts and misses commits pushed
from other machines. This collector pulls the same signal — commits, code churn,
coding sessions — straight from GitHub, so execution is observed regardless of
where the work physically happened.

Both collectors emit :class:`CommitEvent`s keyed by SHA, so running them side by
side and merging never double-counts a commit (see ``execution_sync.py``).
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone

import httpx
from dateutil import parser as date_parser
from sqlalchemy.orm import Session

from app.repositories.github_repo import GithubRepository
from app.services.execution_collector import (
    CommitEvent,
    ExecutionSyncReport,
    persist_rollups,
    rollup_commit_events,
)

GITHUB_API_BASE = "https://api.github.com"
_PER_PAGE = 100
_MAX_PAGES = 10  # safety cap: up to 1000 commits per repo per window
_REQUEST_TIMEOUT = 30.0


class GitHubApiCollector:
    def __init__(
        self,
        db: Session,
        token: str | None,
        repos: list[str] | None = None,
        author: str | None = None,
        fetch_stats: bool = True,
    ) -> None:
        self.db = db
        self.token = (token or "").strip()
        self.repos = [r.strip() for r in (repos or []) if r.strip()]
        self.author = (author or "").strip() or None
        self.fetch_stats = fetch_stats
        self.github = GithubRepository(db)

    def sync(self, days: int = 14) -> ExecutionSyncReport:
        if not self.token or not self.repos:
            return ExecutionSyncReport(
                days_updated=0,
                repos_scanned=0,
                source="github",
                message=(
                    "GitHub API collector not configured. Set GITHUB_TOKEN and "
                    "GITHUB_REPOS (comma-separated owner/repo) in backend/.env."
                ),
            )

        end = date.today()
        start = end - timedelta(days=max(days - 1, 0))
        events = collect_github_commit_events(
            self.repos,
            self.token,
            self.author,
            start,
            end,
            fetch_stats=self.fetch_stats,
        )
        rollups = rollup_commit_events(events)
        persist_rollups(self.github, rollups)

        today = rollups.get(end)
        return ExecutionSyncReport(
            days_updated=len(rollups),
            repos_scanned=len(self.repos),
            source="github",
            today_commits=today.commits if today else 0,
            today_coding_minutes=today.coding_minutes if today else 0,
            message=(
                f"GitHub API: detected execution across {len(self.repos)} repo(s) "
                f"over {days}d ({len(rollups)} day(s) updated)."
            ),
        )


def collect_github_commit_events(
    repos: list[str],
    token: str,
    author: str | None,
    start: date,
    end: date,
    fetch_stats: bool = True,
    client: httpx.Client | None = None,
) -> list[CommitEvent]:
    """Fetch commits for each ``owner/repo`` in the window and return events.

    Network/HTTP failures for a given repo are swallowed (that repo simply
    contributes no events) so a single bad repo or transient error never breaks
    the nightly recompute.
    """
    owned_client = client is None
    if client is None:
        client = httpx.Client(timeout=_REQUEST_TIMEOUT, headers=_auth_headers(token))

    since = datetime.combine(start, time.min, tzinfo=timezone.utc).isoformat()
    until = datetime.combine(end + timedelta(days=1), time.min, tzinfo=timezone.utc).isoformat()

    events: list[CommitEvent] = []
    seen: set[str] = set()
    try:
        for repo in repos:
            for event in _collect_repo_events(
                client, repo, author, since, until, fetch_stats
            ):
                if event.sha in seen:
                    continue
                seen.add(event.sha)
                events.append(event)
    finally:
        if owned_client:
            client.close()
    return events


def _collect_repo_events(
    client: httpx.Client,
    repo: str,
    author: str | None,
    since: str,
    until: str,
    fetch_stats: bool,
) -> list[CommitEvent]:
    params: dict[str, str | int] = {"since": since, "until": until, "per_page": _PER_PAGE}
    if author:
        params["author"] = author

    events: list[CommitEvent] = []
    url: str | None = f"{GITHUB_API_BASE}/repos/{repo}/commits"
    next_params: dict[str, str | int] | None = params
    pages = 0

    while url and pages < _MAX_PAGES:
        try:
            resp = client.get(url, params=next_params)
        except httpx.HTTPError:
            break
        if resp.status_code != 200:
            break

        for entry in resp.json():
            event = _parse_commit(client, repo, entry, fetch_stats)
            if event is not None:
                events.append(event)

        pages += 1
        url = _next_link(resp)
        next_params = None  # the Link header already carries query params

    return events


def _parse_commit(
    client: httpx.Client,
    repo: str,
    entry: dict,
    fetch_stats: bool,
) -> CommitEvent | None:
    sha = entry.get("sha")
    if not sha:
        return None

    commit = entry.get("commit") or {}
    author_info = commit.get("author") or commit.get("committer") or {}
    raw_date = author_info.get("date")
    if not raw_date:
        return None
    try:
        ts = int(date_parser.isoparse(raw_date).timestamp())
    except (ValueError, TypeError):
        return None

    additions = 0
    deletions = 0
    if fetch_stats:
        additions, deletions = _fetch_commit_stats(client, repo, sha)

    return CommitEvent(
        sha=sha,
        repo=f"github:{repo}",
        ts=ts,
        additions=additions,
        deletions=deletions,
    )


def _fetch_commit_stats(client: httpx.Client, repo: str, sha: str) -> tuple[int, int]:
    try:
        resp = client.get(f"{GITHUB_API_BASE}/repos/{repo}/commits/{sha}")
    except httpx.HTTPError:
        return 0, 0
    if resp.status_code != 200:
        return 0, 0
    stats = resp.json().get("stats") or {}
    return int(stats.get("additions", 0) or 0), int(stats.get("deletions", 0) or 0)


def _next_link(resp: httpx.Response) -> str | None:
    """Extract the ``rel="next"`` URL from a GitHub Link header, if present."""
    link = resp.headers.get("Link") or resp.headers.get("link")
    if not link:
        return None
    for part in link.split(","):
        segments = part.split(";")
        if len(segments) < 2:
            continue
        url = segments[0].strip().strip("<>")
        for seg in segments[1:]:
            if seg.strip() == 'rel="next"':
                return url
    return None


def _auth_headers(token: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
