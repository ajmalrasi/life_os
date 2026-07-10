"""GitHub execution collector via the official github-mcp-server MCP sidecar.

This is the sole source of execution signal — LifeOS does not scan local git
repos (the backend and the dev machine are different hosts, so a local scan
would only ever see whatever happened to be pulled onto the backend host).

Execution is discovered by **author search across all of GitHub**, not by
enumerating a fixed repo list: a single ``search_commits author:<user>`` query
finds every commit you pushed, in any repo — including ones created after the
backend was configured. This is correct-by-construction (a new repo counts the
moment you push to it) and far cheaper than scanning dozens of repos one by one.
PRs and issues are discovered the same way via ``search_issues``.

Caveat inherent to commit search: GitHub indexes commits on a repo's default
branch only, so work that lives solely on an unmerged feature branch won't count
until it lands.
"""

from __future__ import annotations

import logging
from datetime import date, datetime, timedelta, timezone

from sqlalchemy.orm import Session

from app.repositories.github_repo import GithubRepository
from app.services.execution_collector import (
    CommitEvent,
    ExecutionSyncReport,
    PrIssueEvent,
    persist_rollups,
    rollup_commit_events,
)
from app.services.github_mcp_client import GitHubMcpClient

logger = logging.getLogger(__name__)

_MAX_PER_PAGE = 100
_MAX_PAGES = 10  # safety cap: up to 1000 results per search query


class GitHubApiCollector:
    def __init__(
        self,
        db: Session,
        token: str | None = None,
        user: str | None = None,
        **_kwargs,
    ) -> None:
        self.db = db
        self.user = (user or "").strip() or None
        self.client = GitHubMcpClient(token=(token or "").strip())
        self.github = GithubRepository(db)

    def sync(self, days: int = 14) -> ExecutionSyncReport:
        if not self.user:
            return ExecutionSyncReport(
                days_updated=0,
                repos_scanned=0,
                source="github-search",
                message=(
                    "GitHub collector not configured. Set GITHUB_USER (your GitHub "
                    "login) and GITHUB_TOKEN in backend/.env."
                ),
            )

        end = date.today()
        start = end - timedelta(days=max(days - 1, 0))
        commit_events = collect_commit_events(self.user, start, end, client=self.client)
        pr_issue_events = collect_pr_issue_events(self.user, start, end, client=self.client)
        rollups = rollup_commit_events(commit_events, pr_issue_events)
        persist_rollups(self.github, rollups)

        today = rollups.get(end)
        repos = {ev.repo for ev in commit_events}
        return ExecutionSyncReport(
            days_updated=len(rollups),
            repos_scanned=len(repos),
            source="github-search",
            today_commits=today.commits if today else 0,
            today_coding_minutes=today.coding_minutes if today else 0,
            message=(
                f"GitHub search: {len(commit_events)} commit(s) by {self.user} across "
                f"{len(repos)} repo(s) over {days}d ({len(rollups)} day(s) updated)."
            ),
        )


# -- commits ----------------------------------------------------------------------


def collect_commit_events(
    user: str,
    start: date,
    end: date,
    client: GitHubMcpClient | None = None,
    token: str = "",
) -> list[CommitEvent]:
    """Find every commit authored by ``user`` in [start, end] across all of GitHub.

    Uses one ``search_commits`` query (paginated), so repos created after the
    backend was set up are covered automatically. Deduplicated by SHA. Network /
    MCP failures return an empty list rather than breaking the nightly recompute.
    """
    if not user:
        return []
    if client is None:
        client = GitHubMcpClient(token=token)

    query = f"author:{user} author-date:{start.isoformat()}..{end.isoformat()}"
    events: list[CommitEvent] = []
    seen: set[str] = set()
    for entry in _search_all(client, "search_commits", query):
        event = _parse_search_commit(client, entry)
        if event is not None and event.sha not in seen:
            seen.add(event.sha)
            events.append(event)
    return events


def _parse_search_commit(client: GitHubMcpClient, entry: dict) -> CommitEvent | None:
    sha = entry.get("sha")
    if not sha:
        return None

    commit = entry.get("commit") or {}
    author_info = commit.get("author") or commit.get("committer") or {}
    dt = _parse_dt(author_info.get("date"))
    if dt is None:
        return None

    repo_full = _repo_full_name(entry.get("repository") or {})
    additions, deletions = 0, 0
    if repo_full:
        owner, name = _split_repo(repo_full)
        additions, deletions = _fetch_stats(client, owner, name, sha)

    return CommitEvent(
        sha=sha,
        repo=f"github:{repo_full or 'unknown'}",
        ts=int(dt.timestamp()),
        additions=additions,
        deletions=deletions,
    )


def _fetch_stats(
    client: GitHubMcpClient,
    owner: str,
    repo: str,
    sha: str,
) -> tuple[int, int]:
    detail = client.call_tool("get_commit", {"owner": owner, "repo": repo, "sha": sha})
    if not detail or not isinstance(detail, dict):
        return 0, 0
    stats = detail.get("stats") or {}
    return int(stats.get("additions", 0) or 0), int(stats.get("deletions", 0) or 0)


# -- pull requests + issues ---------------------------------------------------------


def collect_pr_issue_events(
    user: str,
    start: date,
    end: date,
    client: GitHubMcpClient | None = None,
    token: str = "",
) -> list[PrIssueEvent]:
    """Find PR-opened/merged and issue-opened/closed events by ``user`` across all
    repos via ``search_issues`` (one query per lifecycle event)."""
    if not user:
        return []
    if client is None:
        client = GitHubMcpClient(token=token)

    s, e = start.isoformat(), end.isoformat()
    events: list[PrIssueEvent] = []
    events += _search_issue_events(client, f"type:pr author:{user} created:{s}..{e}", "pr_opened", "created_at")
    events += _search_issue_events(client, f"type:pr author:{user} merged:{s}..{e}", "pr_merged", "merged_at")
    events += _search_issue_events(client, f"type:issue author:{user} created:{s}..{e}", "issue_opened", "created_at")
    events += _search_issue_events(client, f"type:issue author:{user} closed:{s}..{e}", "issue_closed", "closed_at")
    return events


def _search_issue_events(
    client: GitHubMcpClient,
    query: str,
    kind: str,
    when_field: str,
) -> list[PrIssueEvent]:
    events: list[PrIssueEvent] = []
    for item in _search_all(client, "search_issues", query):
        number = item.get("number")
        if number is None:
            continue
        raw = item.get(when_field)
        if not raw and when_field == "merged_at":
            # search_issues nests merge time under pull_request; a merged PR is
            # also closed at merge time, so closed_at is a safe fallback.
            raw = (item.get("pull_request") or {}).get("merged_at") or item.get("closed_at")
        dt = _parse_dt(raw)
        if dt is None:
            continue
        repo_full = _repo_from_url(item.get("repository_url") or "")
        events.append(
            PrIssueEvent(repo=f"github:{repo_full or 'unknown'}", kind=kind, number=number, day=dt.date())
        )
    return events


# -- shared -------------------------------------------------------------------------


def _search_all(client: GitHubMcpClient, tool: str, query: str) -> list[dict]:
    """Page through a github-mcp search tool, returning all ``items``.

    A single failed/None page ends collection (the collectors treat a partial or
    empty result as "no activity" rather than crashing the recompute).
    """
    collected: list[dict] = []
    for page in range(1, _MAX_PAGES + 1):
        try:
            result = client.call_tool(tool, {"query": query, "perPage": _MAX_PER_PAGE, "page": page})
        except Exception as exc:
            logger.warning("github-mcp: %s failed for %r — %s", tool, query, exc)
            break
        items = result.get("items") if isinstance(result, dict) else None
        if not items or not isinstance(items, list):
            break
        collected.extend(items)
        total = result.get("total_count") if isinstance(result, dict) else None
        if len(items) < _MAX_PER_PAGE or (isinstance(total, int) and len(collected) >= total):
            break
    return collected


def _repo_full_name(repository: dict) -> str:
    """Extract ``owner/name`` from a search-commits ``repository`` object."""
    if not isinstance(repository, dict):
        return ""
    full = repository.get("full_name")
    if full:
        return full
    owner = (repository.get("owner") or {}).get("login")
    name = repository.get("name")
    return f"{owner}/{name}" if owner and name else ""


def _repo_from_url(repository_url: str) -> str:
    """Extract ``owner/name`` from a search-issues ``repository_url``.

    e.g. ``https://api.github.com/repos/ajmalrasi/openclaw`` -> ``ajmalrasi/openclaw``.
    """
    marker = "/repos/"
    idx = repository_url.find(marker)
    return repository_url[idx + len(marker) :] if idx != -1 else ""


def _parse_dt(raw: str | None) -> datetime | None:
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.rstrip("Z")).replace(tzinfo=timezone.utc)
    except (ValueError, TypeError):
        return None


def _split_repo(repo: str) -> tuple[str, str]:
    """Split ``owner/repo`` into ``(owner, repo)``."""
    parts = repo.split("/", 1)
    if len(parts) != 2:
        raise ValueError(f"repo must be 'owner/repo', got: {repo!r}")
    return parts[0], parts[1]
