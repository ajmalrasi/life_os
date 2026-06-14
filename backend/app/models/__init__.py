"""ORM models for LifeOS.

Importing this package ensures every model is registered on the shared
``Base.metadata`` so that ``create_all`` (and tests) see all tables.
"""

from app.models.daily_score import DailyScore
from app.models.daily_state import DailyState
from app.models.device import DeviceToken
from app.models.github import GithubActivity
from app.models.health import HealthRecord, HealthRecordType
from app.models.insight import Insight
from app.models.mood import MoodCheckin

__all__ = [
    "HealthRecord",
    "HealthRecordType",
    "MoodCheckin",
    "GithubActivity",
    "DailyScore",
    "DailyState",
    "DeviceToken",
    "Insight",
]
