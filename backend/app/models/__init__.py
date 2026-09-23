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
from app.models.mail import (
    MailAccount,
    MailAccountPurpose,
    MailAuthSession,
    MailMessageRecord,
    MailSyncState,
)
from app.models.card_statement import CardStatement, CardStatementItem
from app.models.mood import MoodCheckin
from app.models.payee_label import PayeeLabel
from app.models.priority import PriorityFeedback, PriorityItem, PriorityItemMessage, PriorityNotification
from app.models.raw_bank_email import RawBankEmail
from app.models.sync_state import CollectorState
from app.models.sync_status import SyncAttempt
from app.models.synthesized_insight import SynthesizedInsight
from app.models.transaction import Transaction
from app.models.transaction_adjustment import TransactionAdjustment
from app.models.transaction_reconciliation import TransactionReconciliation
from app.models.trip import TripAttachmentBackup, TripBackup

__all__ = [
    "CardStatement",
    "CardStatementItem",
    "HealthRecord",
    "HealthRecordType",
    "MoodCheckin",
    "GithubActivity",
    "DailyScore",
    "DailyState",
    "DeviceToken",
    "Insight",
    "MailAccount",
    "MailAccountPurpose",
    "MailAuthSession",
    "MailMessageRecord",
    "MailSyncState",
    "SynthesizedInsight",
    "Transaction",
    "TransactionAdjustment",
    "TransactionReconciliation",
    "CollectorState",
    "SyncAttempt",
    "PayeeLabel",
    "PriorityFeedback",
    "PriorityItem",
    "PriorityItemMessage",
    "PriorityNotification",
    "RawBankEmail",
    "TripBackup",
    "TripAttachmentBackup",
]
