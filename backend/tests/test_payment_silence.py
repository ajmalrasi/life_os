"""Tests for the payment-source silence detector ("dead man's switch").

The point of this check is to catch a transaction going *fully un-parsed* — a
bank alert whose sender/format changed so much that nothing fetched it, so no
row exists and the only remaining signal is a normally-active source falling
unusually quiet.
"""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from app.database import SessionLocal
from app.models.transaction import Transaction
from app.services.notifications import NotificationService


def _add_txns(db, account: str, days_ago: list[int]) -> None:
    for i, d in enumerate(days_ago):
        db.add(
            Transaction(
                txn_date=date.today() - timedelta(days=d),
                amount=Decimal("100.00"),
                currency="INR",
                direction="debit",
                account=account,
                source="upi",
                gmail_message_id=f"{account}-{d}-{i}",
                dedup_key=f"{account}-{d}-{i}",
            )
        )


def _titles(client) -> list[str]:
    with SessionLocal() as db:
        return [n.title for n in NotificationService(db).generate()]


def test_active_then_silent_source_is_flagged(client):
    # Used every ~2 days for a stretch, then nothing for ~30 days.
    with SessionLocal() as db:
        _add_txns(db, "SBI Card ..1970", [30, 32, 34, 36, 38, 40, 42, 44])
        db.commit()

    titles = _titles(client)
    assert any("SBI Card ..1970" in t and "30 days" in t for t in titles)


def test_still_active_source_is_not_flagged(client):
    # Same cadence but the most recent use was just 1 day ago.
    with SessionLocal() as db:
        _add_txns(db, "HDFC RuPay CC ..5473", [1, 3, 5, 7, 9, 11, 13])
        db.commit()

    titles = _titles(client)
    assert not any("HDFC RuPay CC ..5473" in t for t in titles)


def test_rarely_used_source_is_not_flagged(client):
    # Only 3 distinct active days in the window — below the "active" bar, so a
    # long gap on it is expected, not alarming. Must not produce noise.
    with SessionLocal() as db:
        _add_txns(db, "Some Rare Card ..0001", [10, 50, 90])
        db.commit()

    titles = _titles(client)
    assert not any("Some Rare Card ..0001" in t for t in titles)


def test_daily_card_is_not_flagged_within_the_floor(client):
    # Heavy daily use, last seen 5 days ago. 4*median(1) = 4, but the floor is 7,
    # so a 5-day gap on a daily card must stay quiet (avoids weekend false alarms).
    with SessionLocal() as db:
        _add_txns(db, "Daily Card ..1234", [5, 6, 7, 8, 9, 10, 11, 12])
        db.commit()

    titles = _titles(client)
    assert not any("Daily Card ..1234" in t for t in titles)
