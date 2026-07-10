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
from app.models.card_statement import CardStatement
from app.models.mood import MoodCheckin
from app.models.payee_label import PayeeLabel
from app.models.raw_bank_email import RawBankEmail
from app.models.sync_state import CollectorState
from app.models.synthesized_insight import SynthesizedInsight
from app.models.transaction import Transaction

__all__ = [
    "CardStatement",
    "HealthRecord",
    "HealthRecordType",
    "MoodCheckin",
    "GithubActivity",
    "DailyScore",
    "DailyState",
    "DeviceToken",
    "Insight",
    "SynthesizedInsight",
    "Transaction",
    "CollectorState",
    "PayeeLabel",
    "RawBankEmail",
]
