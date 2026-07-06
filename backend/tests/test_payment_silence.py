"""Tests for the payment-source silence detector ("dead man's switch").

The point of this check is to catch a transaction going *fully un-parsed* — a
bank alert whose sender/format changed so much that nothing fetched it, so no
row exists and the only remaining signal is a normally-active source falling
unusually quiet. Tuned conservative and capped so it never becomes noise.
"""

from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal

from app.database import SessionLocal
from app.models.transaction import Transaction
from app.services.notifications import SILENCE_MAX_ALERTS, NotificationService


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


def _silence_titles(client) -> list[str]:
    with SessionLocal() as db:
        return [
            n.title
            for n in NotificationService(db).generate()
            if n.title.startswith("No activity from")
        ]


def test_active_then_silent_source_is_flagged(client):
    # ~10 distinct days at a 2-day cadence, then silent ~40 days: clearly dark.
    with SessionLocal() as db:
        _add_txns(db, "SBI Card ..1970", [40, 42, 44, 46, 48, 50, 52, 54, 56, 58])
        db.commit()

    titles = _silence_titles(client)
    assert any("SBI Card ..1970" in t and "40 days" in t for t in titles)


def test_still_active_source_is_not_flagged(client):
    with SessionLocal() as db:
        _add_txns(db, "HDFC RuPay CC ..5473", [1, 3, 5, 7, 9, 11, 13, 15, 17])
        db.commit()

    assert not any("HDFC RuPay CC ..5473" in t for t in _silence_titles(client))


def test_rarely_used_source_is_not_flagged(client):
    # Below the "active" bar (< 8 distinct days) — a long gap is expected, not alarming.
    with SessionLocal() as db:
        _add_txns(db, "Some Rare Card ..0001", [10, 40, 70, 100])
        db.commit()

    assert not any("Some Rare Card ..0001" in t for t in _silence_titles(client))


def test_gap_within_the_floor_is_not_flagged(client):
    # Heavy daily use, last seen 12 days ago. 5*median(1)=5, but the 14-day floor
    # keeps a not-yet-two-weeks gap quiet (avoids seasonal/holiday false alarms).
    with SessionLocal() as db:
        _add_txns(db, "Daily Card ..1234", list(range(12, 25)))
        db.commit()

    assert not any("Daily Card ..1234" in t for t in _silence_titles(client))


def test_label_variants_of_one_card_merge_by_last4(client):
    # Same physical card (..0010) under two parser labels; the live label is
    # recent, so the "dead" statement label must NOT be flagged as a phantom.
    with SessionLocal() as db:
        _add_txns(db, "ICICI Card ..0010", [60, 63, 66, 69, 72, 75, 78, 81])  # old label
        _add_txns(db, "ICICI Amazon ..0010", [1, 4, 7, 10])  # same card, still active
        db.commit()

    assert not any("..0010" in t for t in _silence_titles(client))


def test_last4_length_variants_of_one_card_merge(client):
    # Same physical SBI card under a 2-digit and a 4-digit last4 (the case the
    # old (bank, last4) key could NOT merge). The 4-digit label is recent, so
    # the stale 2-digit label must NOT be flagged as a phantom dead source.
    with SessionLocal() as db:
        _add_txns(db, "SBI Card ..70", [60, 63, 66, 69, 72, 75, 78, 81])  # old, short last4
        _add_txns(db, "SBI Card ..1970", [1, 4, 7, 10])  # same card, still active
        db.commit()

    assert not any("SBI Card" in t for t in _silence_titles(client))


def test_output_is_capped(client):
    # Six clearly-dark cards, but the surface is capped so it never floods.
    with SessionLocal() as db:
        for n in range(6):
            _add_txns(db, f"Card ..{n}{n}{n}{n}", [40, 43, 46, 49, 52, 55, 58, 61])
        db.commit()

    assert len(_silence_titles(client)) == SILENCE_MAX_ALERTS
