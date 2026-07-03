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

    # Expense tracking — reads bank/UPI/statement emails via the `gog` CLI
    # (Google Workspace), baked into the API image. Auth is a shared, read-only
    # gogcli config (OAuth client + refresh token); the only secret to inject
    # here is the file-keyring password. The mailbox is authorized once with
    # `gog auth add` and nothing here can mutate it.
    gmail_account: str = ""            # e.g. mail2ajmalrasi@gmail.com; empty disables
    gog_bin: str = "gog"               # path to the gog binary
    gog_home: str = ""                 # optional GOG_HOME (shared gogcli config dir)
    gog_keyring_password: str = ""     # file-keyring password (inject as a secret)
    expense_backfill_days: int = 90    # window seeded on the first sync (cursor unset)

    # CC statement PDFs — unlock secrets. Passwords derive from name+DOB per
    # bank scheme (see services/pdf_unlock.py); overrides cover banks with
    # nonstandard passwords, format "bank:password,bank2:password2".
    cc_owner_name: str = ""            # as printed on cards, e.g. AJMAL RASI
    cc_owner_dob: str = ""             # DDMMYYYY
    cc_pdf_password_overrides: str = ""  # e.g. sbi:01071993xxxx

    # Health Auto Export TCP server on the iPhone (live, on-demand health pull).
    # The app exposes a JSON-RPC TCP server on the LAN (Automations -> Server).
    # Used by the chat's `live_health` tool. Host is the phone's IP (DHCP — set a
    # reservation, or update when it changes). Empty host disables the tool.
    hae_host: str = ""          # e.g. 192.168.3.212
    hae_port: int = 9000
    hae_timeout_seconds: float = 8.0  # short: the phone server is foreground-only

    # Push notifications (Firebase Cloud Messaging, HTTP v1).
    # Path to a Firebase service-account JSON. Leave blank to disable push;
    # the backend still generates notifications for the client to poll.
    firebase_credentials_path: str = ""
    firebase_project_id: str = ""  # optional; otherwise read from the credentials file

    # Analytics
    correlation_min_samples: int = 7  # need at least a week of overlapping data
    correlation_significance: float = 0.35  # |r| threshold to surface an insight
    # Let the LLM retell discovered patterns in fresher language (best-effort;
    # falls back to the deterministic summaries when OpenClaw is unreachable).
    insight_narration_enabled: bool = True
    # Let the LLM suggest payee_labels for payees the keyword rules miss
    # (best-effort; skipped entirely when OpenClaw is unreachable).
    openclaw_payee_classification_enabled: bool = True

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
    def gmail_expenses_enabled(self) -> bool:
        return bool(self.gmail_account.strip())

    @property
    def fcm_enabled(self) -> bool:
        return bool(self.firebase_credentials_path.strip())

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"prod", "production"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
