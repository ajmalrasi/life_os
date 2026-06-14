"""Tests for automatic git-based execution collection."""

from __future__ import annotations

import subprocess
from datetime import date, datetime, timedelta
from pathlib import Path

import pytest

from app.services.execution_collector import collect_git_activity, _estimate_coding_minutes


def _init_repo(path: Path) -> None:
    subprocess.run(["git", "init"], cwd=path, check=True, capture_output=True)
    subprocess.run(
        ["git", "config", "user.email", "lifeos@test"],
        cwd=path,
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "LifeOS Test"],
        cwd=path,
        check=True,
        capture_output=True,
    )


def _commit_at(path: Path, when: datetime, message: str) -> None:
    (path / "work.txt").write_text(f"{message}\n", encoding="utf-8")
    subprocess.run(["git", "add", "work.txt"], cwd=path, check=True, capture_output=True)
    env = {
        **__import__("os").environ,
        "GIT_AUTHOR_DATE": when.isoformat(),
        "GIT_COMMITTER_DATE": when.isoformat(),
    }
    subprocess.run(
        ["git", "commit", "-m", message],
        cwd=path,
        check=True,
        capture_output=True,
        env=env,
    )


def test_collect_git_activity_counts_commits_and_repos(tmp_path: Path) -> None:
    repo = tmp_path / "project"
    repo.mkdir()
    _init_repo(repo)

    day = date.today()
    noon = datetime.combine(day, datetime.min.time()) + timedelta(hours=12)
    _commit_at(repo, noon, "first")
    _commit_at(repo, noon + timedelta(minutes=20), "second")

    rollups = collect_git_activity([repo], author=None, start=day, end=day)
    assert rollups[day].commits == 2
    assert rollups[day].active_repos == 1
    assert rollups[day].coding_minutes >= 15


def test_estimate_coding_minutes_groups_sessions() -> None:
    base = int(datetime(2026, 6, 14, 10, 0).timestamp())
    # Two commits 20m apart => one session
    one_session = _estimate_coding_minutes([base, base + 20 * 60])
    # Two sessions 2h apart => more minutes
    two_sessions = _estimate_coding_minutes([base, base + 20 * 60, base + 3 * 3600, base + 3 * 3600 + 600])
    assert two_sessions >= one_session


def test_author_filter_limits_commits(tmp_path: Path) -> None:
    repo = tmp_path / "project"
    repo.mkdir()
    _init_repo(repo)
    day = date.today()
    when = datetime.combine(day, datetime.min.time()) + timedelta(hours=9)
    _commit_at(repo, when, "mine")

    all_commits = collect_git_activity([repo], author=None, start=day, end=day)
    filtered = collect_git_activity([repo], author="lifeos@test", start=day, end=day)
    assert all_commits[day].commits == 1
    assert filtered[day].commits == 1

    missing = collect_git_activity([repo], author="nobody@example.com", start=day, end=day)
    assert day not in missing
