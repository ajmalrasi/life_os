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

    # Analytics
    correlation_min_samples: int = 7  # need at least a week of overlapping data
    correlation_significance: float = 0.35  # |r| threshold to surface an insight

    @property
    def is_production(self) -> bool:
        return self.environment.lower() in {"prod", "production"}


@lru_cache
def get_settings() -> Settings:
    return Settings()
