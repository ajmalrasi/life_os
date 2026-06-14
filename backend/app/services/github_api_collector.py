"""GitHub execution collector via the official github-mcp-server MCP sidecar.

Replaces the direct REST API implementation with MCP tool calls so authentication,
pagination, and rate-limit handling are delegated to the official server. The
public interface (``GitHubApiCollector``, ``CommitEvent``) is unchanged, so
``execution_sync.py`` and ``collectors.py`` need no edits.
"""

from __future__ import annotations

import logging
from datetime import date, datetime, time, timedelta, timezone

from sqlalchemy.orm import Session

from app.repositories.github_repo import GithubRepository
from app.services.execution_collector import (
    CommitEvent,
    ExecutionSyncReport,
    persist_rollups,
    rollup_commit_events,
)
from app.services.github_mcp_client import GitHubMcpClient

logger = logging.getLogger(__name__)

_MAX_PER_PAGE = 100
_MAX_PAGES = 10  # safety cap: up to 1000 commits per repo per window


class GitHubApiCollector:
    def __init__(
        self,
        db: Session,
        token: str | None = None,
        repos: list[str] | None = None,
        author: str | None = None,
        **_kwargs,
    ) -> None:
        self.db = db
        self.repos = [r.strip() for r in (repos or []) if r.strip()]
        self.author = (author or "").strip() or None
        self.client = GitHubMcpClient(token=(token or "").strip())
        self.github = GithubRepository(db)

    def sync(self, days: int = 14) -> ExecutionSyncReport:
        if not self.repos:
            return ExecutionSyncReport(
                days_updated=0,
                repos_scanned=0,
                source="github-mcp",
                message=(
                    "GitHub MCP collector not configured. Set GITHUB_REPOS "
                    "(comma-separated owner/repo) and GITHUB_TOKEN in backend/.env."
                ),
            )

        end = date.today()
        start = end - timedelta(days=max(days - 1, 0))
        events = collect_github_commit_events(
            self.repos,
            self.author,
            start,
            end,
            client=self.client,
        )
        rollups = rollup_commit_events(events)
        persist_rollups(self.github, rollups)

        today = rollups.get(end)
        return ExecutionSyncReport(
            days_updated=len(rollups),
            repos_scanned=len(self.repos),
            source="github-mcp",
            today_commits=today.commits if today else 0,
            today_coding_minutes=today.coding_minutes if today else 0,
            message=(
                f"GitHub MCP: detected execution across {len(self.repos)} repo(s) "
                f"over {days}d ({len(rollups)} day(s) updated)."
            ),
        )


def collect_github_commit_events(
    repos: list[str],
    author: str | None,
    start: date,
    end: date,
    client: GitHubMcpClient | None = None,
    token: str = "",
) -> list[CommitEvent]:
    """Fetch commits for each ``owner/repo`` via MCP and return CommitEvents.

    Network / MCP failures for a single repo are logged and skipped so a bad
    repo or transient error never breaks the nightly recompute.
    """
    if client is None:
        client = GitHubMcpClient(token=token)

    since = datetime.combine(start, time.min, tzinfo=timezone.utc).isoformat()
    until = datetime.combine(end + timedelta(days=1), time.min, tzinfo=timezone.utc).isoformat()

    events: list[CommitEvent] = []
    seen: set[str] = set()

    for repo in repos:
        try:
            repo_events = _collect_repo_events(client, repo, author, since, until)
            for event in repo_events:
                if event.sha not in seen:
                    seen.add(event.sha)
                    events.append(event)
        except Exception as exc:
            logger.warning("github-mcp: skipping repo %s — %s", repo, exc)

    return events


def _collect_repo_events(
    client: GitHubMcpClient,
    repo: str,
    author: str | None,
    since: str,
    until: str,
) -> list[CommitEvent]:
    owner, name = _split_repo(repo)
    events: list[CommitEvent] = []

    for page in range(1, _MAX_PAGES + 1):
        args: dict = {
            "owner": owner,
            "repo": name,
            "since": since,
            "until": until,
            "per_page": _MAX_PER_PAGE,
            "page": page,
        }
        if author:
            args["author"] = author

        commits = client.call_tool("list_commits", args)
        if not commits or not isinstance(commits, list):
            break

        for entry in commits:
            event = _parse_commit(client, owner, name, entry)
            if event is not None:
                events.append(event)

        if len(commits) < _MAX_PER_PAGE:
            break  # last page

    return events


def _parse_commit(
    client: GitHubMcpClient,
    owner: str,
    repo: str,
    entry: dict,
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
        ts = int(datetime.fromisoformat(raw_date.rstrip("Z")).replace(tzinfo=timezone.utc).timestamp())
    except (ValueError, TypeError):
        return None

    additions, deletions = _fetch_stats(client, owner, repo, sha)

    return CommitEvent(
        sha=sha,
        repo=f"github:{owner}/{repo}",
        ts=ts,
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


def _split_repo(repo: str) -> tuple[str, str]:
    """Split ``owner/repo`` into ``(owner, repo)``."""
    parts = repo.split("/", 1)
    if len(parts) != 2:
        raise ValueError(f"GITHUB_REPOS entries must be 'owner/repo', got: {repo!r}")
    return parts[0], parts[1]
