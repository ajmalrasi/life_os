"""Application configuration.

All settings are environment-driven so the same image runs on a laptop,
the Jetson Orin Nano, or (later) a cloud host without code changes.
"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "LifeOS"
    environment: str = "development"

    # Database — defaults match docker-compose; override via DATABASE_URL.
    database_url: str = "postgresql+psycopg2://lifeos:lifeos@db:5432/lifeos"

    # OpenClaw local LLM. Base URL is configurable (Jetson local network first).
    openclaw_base_url: str = "http://localhost:8080"
    openclaw_model: str = "openclaw"
    openclaw_api_key: str = ""  # optional; many local servers ignore it
    openclaw_timeout_seconds: float = 120.0

    # Automatic execution collection (local git repos on the backend host).
    # Comma-separated absolute paths, e.g. /Users/you/projects/life_os,/Users/you/other
    execution_repo_paths: str = ""
    execution_git_author: str = ""  # optional git --author filter (email or name)

    # GitHub execution collection via the official github-mcp-server sidecar.
    # The sidecar runs inside docker-compose; GITHUB_TOKEN is its auth credential.
    github_token: str = ""  # personal access token (repo / contents:read)
    github_repos: str = ""  # comma-separated owner/repo, e.g. ajmalrasi/life_os
    github_user: str = ""   # optional commit author login/email filter
    github_mcp_url: str = "http://github-mcp:3000/mcp"  # internal docker-compose URL

    # Push notifications (Firebase Cloud Messaging, HTTP v1).
    # Path to a Firebase service-account JSON. Leave blank to disable push;
    # the backend still generates notifications for the client to poll.
    firebase_credentials_path: str = ""
    firebase_project_id: str = ""  # optional; otherwise read from the credentials file

    # Analytics
    correlation_min_samples: int = 7  # need at least a week of overlapping data
    correlation_significance: float = 0.35  # |r| threshold to surface an insight

    @property
    def execution_repo_path_list(self) -> list[str]:
        if not self.execution_repo_paths.strip():
            return []
        return [p.strip() for p in self.execution_repo_paths.split(",") if p.strip()]

    @property
    def github_repos_list(self) -> list[str]:
        if not self.github_repos.strip():
            return []
        return [r.strip() for r in self.github_repos.split(",") if r.strip()]

    @property
    def github_enabled(self) -> bool:
        return bool(self.github_token.strip()) and bool(self.github_repos_list)

    @property
    def fcm_enabled(self) -> bool:
        return bool(self.firebase_credentials_path.strip())

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"prod", "production"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
