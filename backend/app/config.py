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

    # Bearer-token auth. Unset (the default) disables auth entirely — safe for
    # the existing home-Wi-Fi-only rollout and for tests. Set it before exposing
    # the API beyond the LAN (e.g. over Tailscale); see AGENTS.md §2.
    api_token: str | None = None

    # Database — defaults match docker-compose; override via DATABASE_URL.
    database_url: str = "postgresql+psycopg2://lifeos:lifeos@db:5432/lifeos"

    # OpenClaw local LLM. Base URL is configurable (Jetson local network first).
    openclaw_base_url: str = "http://localhost:8080"
    openclaw_model: str = "openclaw"
    openclaw_api_key: str = ""  # optional; many local servers ignore it
    # One generation must fail before the Android client's overall 150s read
    # timeout. ChatService also enforces a whole-request deadline across tool
    # rounds, so several individually-valid calls cannot run forever.
    openclaw_timeout_seconds: float = 90.0
    openclaw_chat_deadline_seconds: float = 135.0
    openclaw_max_tokens: int = 1024
    ask_max_tokens: int = 1024
    ask_input_tokens: int = 6000
    ask_enable_thinking: bool = False

    # GitHub execution collection via the official github-mcp-server sidecar.
    # The sidecar runs inside docker-compose; GITHUB_TOKEN is its auth credential.
    # Execution is discovered by author search across ALL of GitHub, so the only
    # required knobs are the token and GITHUB_USER (your login) — no repo list to
    # maintain. github_repos is retained only for the `github_search` chat tool /
    # backward compat and is not used by the execution sync.
    github_token: str = ""  # personal access token (repo / contents:read)
    github_user: str = ""   # your GitHub login — scopes the author search
    github_repos: str = ""  # deprecated for sync; comma-separated owner/repo

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

    # Priority mail classification. Connected accounts live in mail_accounts;
    # unlike the legacy Money bootstrap above, no mailbox address is hard-coded
    # into environment settings. Ambiguous local-model review is opt-in so a
    # background mail poll cannot contend with Ask by default.
    priority_backfill_days: int = 120
    priority_llm_enabled: bool = False

    # CC statement PDFs — unlock secrets. Passwords derive from name+DOB per
    # bank scheme (see services/pdf_unlock.py); overrides cover banks with
    # nonstandard passwords, format "bank:password,bank2:password2".
    cc_owner_name: str = ""            # as printed on cards, e.g. AJMAL RASI
    cc_owner_dob: str = ""             # DDMMYYYY
    cc_pdf_password_overrides: str = ""  # e.g. sbi:01071993xxxx

    # Self-transfers move money between your OWN accounts, so the counterparty
    # is your own name / UPI handle rather than a merchant. List your name
    # variant(s) and VPA handle(s) here (comma-separated) so reconciliation
    # tags them "transfer" (excluded from spend) instead of counting them as
    # spend or income — in either direction, from any bank. Matching is
    # case-insensitive and truncation-tolerant (banks clip long names, e.g.
    # "AJMAL RASI K P" -> "AJMAL RAS"). Falls back to cc_owner_name when unset.
    expense_owner_identities: str = ""  # e.g. "AJMAL RASI K P, ajmalrasi@upi"

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
    # Let the LLM synthesize higher-level, cross-domain insights by connecting
    # several grounded facts at once (best-effort; each insight must cite the
    # computed evidence it rests on, so it can't invent relationships).
    insight_synthesis_enabled: bool = True
    # Let the LLM suggest payee_labels for payees the keyword rules miss
    # (best-effort; skipped entirely when OpenClaw is unreachable).
    openclaw_payee_classification_enabled: bool = True
    # Registers the LLM catch-all bank-email parser (llm_bank_parser.py)
    # alongside the regex parsers.
    llm_bank_parser_enabled: bool = True
    # Ask the LLM to extract each transaction's clock time from the alert email
    # (best-effort; one call per transaction-bearing email). When disabled, or
    # when the model finds no time / is unreachable, the collector falls back to
    # the email's arrival timestamp.
    expense_txn_time_llm_enabled: bool = True

    @property
    def github_repos_list(self) -> list[str]:
        if not self.github_repos.strip():
            return []
        return [r.strip() for r in self.github_repos.split(",") if r.strip()]

    @property
    def github_enabled(self) -> bool:
        return bool(self.github_token.strip()) and bool(self.github_user.strip())

    @property
    def gmail_expenses_enabled(self) -> bool:
        return bool(self.gmail_account.strip())

    @property
    def expense_owner_identities_list(self) -> list[str]:
        """Owner name/VPA variants used to detect self-transfers. Falls back to
        cc_owner_name so self-transfer tagging works with existing config."""
        raw = self.expense_owner_identities.strip() or self.cc_owner_name.strip()
        return [s.strip() for s in raw.split(",") if s.strip()]

    @property
    def fcm_enabled(self) -> bool:
        return bool(self.firebase_credentials_path.strip())

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"prod", "production"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
