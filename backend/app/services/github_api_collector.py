"""GitHub execution collector via the official github-mcp-server MCP sidecar.

This is the sole source of execution signal — LifeOS does not scan local git
repos (the backend and the dev machine are different hosts, so a local scan
would only ever see whatever happened to be pulled onto the backend host).
Everything here reads from GitHub itself: commits, pull requests, and issues,
all fetched via MCP tool calls so authentication, pagination, and rate-limit
handling are delegated to the official server.
"""

from __future__ import annotations

import logging
from datetime import date, datetime, time, timedelta, timezone

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
_MAX_PAGES = 10  # safety cap: up to 1000 commits/PRs per repo per window


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
        commit_events = collect_github_commit_events(
            self.repos,
            self.author,
            start,
            end,
            client=self.client,
        )
        pr_issue_events = collect_github_pr_issue_events(
            self.repos,
            start,
            end,
            client=self.client,
        )
        rollups = rollup_commit_events(commit_events, pr_issue_events)
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


# -- commits ----------------------------------------------------------------------


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
            repo_events = _collect_repo_commit_events(client, repo, author, since, until)
            for event in repo_events:
                if event.sha not in seen:
                    seen.add(event.sha)
                    events.append(event)
        except Exception as exc:
            logger.warning("github-mcp: skipping commits for %s — %s", repo, exc)

    return events


def _collect_repo_commit_events(
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
    dt = _parse_dt(author_info.get("date"))
    if dt is None:
        return None

    additions, deletions = _fetch_stats(client, owner, repo, sha)

    return CommitEvent(
        sha=sha,
        repo=f"github:{owner}/{repo}",
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


def collect_github_pr_issue_events(
    repos: list[str],
    start: date,
    end: date,
    client: GitHubMcpClient | None = None,
    token: str = "",
) -> list[PrIssueEvent]:
    """Fetch PR-opened/merged and issue-opened/closed events for each repo via MCP."""
    if client is None:
        client = GitHubMcpClient(token=token)

    events: list[PrIssueEvent] = []
    for repo in repos:
        try:
            events.extend(_collect_repo_pr_events(client, repo, start, end))
        except Exception as exc:
            logger.warning("github-mcp: skipping PRs for %s — %s", repo, exc)
        try:
            events.extend(_collect_repo_issue_events(client, repo, start, end))
        except Exception as exc:
            logger.warning("github-mcp: skipping issues for %s — %s", repo, exc)

    return events


def _collect_repo_pr_events(
    client: GitHubMcpClient,
    repo: str,
    start: date,
    end: date,
) -> list[PrIssueEvent]:
    """``list_pull_requests`` has no ``since`` filter, so we sort by ``updated``
    (descending) and stop as soon as an entry falls before the window — every
    later entry is guaranteed to be at least as old.
    """
    owner, name = _split_repo(repo)
    events: list[PrIssueEvent] = []

    for page in range(1, _MAX_PAGES + 1):
        args = {
            "owner": owner,
            "repo": name,
            "state": "all",
            "sort": "updated",
            "direction": "desc",
            "perPage": _MAX_PER_PAGE,
            "page": page,
        }
        prs = client.call_tool("list_pull_requests", args)
        if not prs or not isinstance(prs, list):
            break

        for entry in prs:
            updated = _parse_dt(entry.get("updated_at"))
            if updated is not None and updated.date() < start:
                return events  # sorted desc: nothing after this is newer

            number = entry.get("number")
            if number is None:
                continue

            created = _parse_dt(entry.get("created_at"))
            if created is not None and start <= created.date() <= end:
                events.append(
                    PrIssueEvent(repo=f"github:{owner}/{name}", kind="pr_opened", number=number, day=created.date())
                )

            merged = _parse_dt(entry.get("merged_at"))
            if merged is not None and start <= merged.date() <= end:
                events.append(
                    PrIssueEvent(repo=f"github:{owner}/{name}", kind="pr_merged", number=number, day=merged.date())
                )

        if len(prs) < _MAX_PER_PAGE:
            break

    return events


def _collect_repo_issue_events(
    client: GitHubMcpClient,
    repo: str,
    start: date,
    end: date,
) -> list[PrIssueEvent]:
    """``list_issues`` supports a server-side ``since`` (updated-at) filter, so
    one page comfortably covers a personal repo's trailing-week activity.
    """
    owner, name = _split_repo(repo)
    since = datetime.combine(start, time.min, tzinfo=timezone.utc).isoformat()

    args = {
        "owner": owner,
        "repo": name,
        "state": "all",
        "since": since,
        "perPage": _MAX_PER_PAGE,
    }
    issues = client.call_tool("list_issues", args)
    if not issues or not isinstance(issues, list):
        return []

    events: list[PrIssueEvent] = []
    for entry in issues:
        if entry.get("pull_request"):
            continue  # PRs are also surfaced via the issues API; handled separately

        number = entry.get("number")
        if number is None:
            continue

        created = _parse_dt(entry.get("created_at"))
        if created is not None and start <= created.date() <= end:
            events.append(
                PrIssueEvent(repo=f"github:{owner}/{name}", kind="issue_opened", number=number, day=created.date())
            )

        closed = _parse_dt(entry.get("closed_at"))
        if closed is not None and start <= closed.date() <= end:
            events.append(
                PrIssueEvent(repo=f"github:{owner}/{name}", kind="issue_closed", number=number, day=closed.date())
            )

    return events


# -- shared -------------------------------------------------------------------------


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
        raise ValueError(f"GITHUB_REPOS entries must be 'owner/repo', got: {repo!r}")
    return parts[0], parts[1]
