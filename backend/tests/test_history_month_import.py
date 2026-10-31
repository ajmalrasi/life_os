from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import Session

from app.database import Base
from app.models.transaction import Transaction
from app.services.history_month_import import (
    PRIMARY_ACCOUNT,
    apply_import,
    plan_import,
    rollback_import,
)


@pytest.fixture()
def db():
    engine = create_engine("sqlite+pysqlite:///:memory:", future=True)
    Base.metadata.create_all(engine)
    with Session(engine, autoflush=False, future=True) as session:
        yield session
    engine.dispose()


def row(mid="mail-1", *, day="2026-05-04", amount="100.00", direction="debit",
        account="HDFC Savings ..7672", reference="123456789012", counterparty="SHOP",
        source="upi", currency="INR", raw_snippet="source evidence"):
    return {
        "message_id": mid, "txn_date": day, "amount": amount,
        "direction": direction, "account": account, "reference": reference,
        "counterparty": counterparty, "source": source, "currency": currency,
        "source_hash": f"hash-{mid}", "parser": "reviewed-parser",
        "raw_snippet": raw_snippet,
    }


def test_plan_and_apply_are_idempotent_and_preserve_existing_rows(db):
    existing = Transaction(
        txn_date=date(2026, 5, 1), amount=Decimal("9.00"), currency="INR",
        direction="debit", counterparty="Manually labeled", category="manual_keep",
        account="HDFC Savings ..7672", source="upi", reference="old-ref",
        gmail_message_id="existing-mail", dedup_key="existing-key", raw_snippet="old",
    )
    db.add(existing)
    db.commit()
    plan = plan_import(db, [row()], [])
    assert len(plan["proposed"]) == 1
    assert plan["existing_may_rows"][0]["category"] == "manual_keep"
    result = apply_import(db, plan, plan["plan_sha256"], "run-1")
    assert result["inserted_count"] == 1
    assert db.get(Transaction, existing.id).category == "manual_keep"
    second = plan_import(db, [row()], [])
    assert not second["proposed"]
    assert second["skipped"][0]["reason"] == "strong exact match already exists"


def test_retry_recovers_committed_run_manifest_by_run_scoped_dedup_key(db):
    plan = plan_import(db, [row()], [])
    first = apply_import(db, plan, plan["plan_sha256"], "run-recover")
    recovered = apply_import(db, plan, plan["plan_sha256"], "run-recover")
    assert recovered == first
    assert recovered["inserted_count"] == 1
    assert len(db.execute(select(Transaction)).scalars().all()) == 1


def test_stale_plan_refuses_insert_after_ledger_changes(db):
    plan = plan_import(db, [row()], [])
    db.add(Transaction(
        txn_date=date(2026, 5, 1), amount=Decimal("1.00"), currency="INR",
        direction="debit", account="Axis Savings", source="upi",
        gmail_message_id="newer", dedup_key="newer-key",
    ))
    db.commit()
    with pytest.raises(ValueError, match="stale plan"):
        apply_import(db, plan, plan["plan_sha256"], "run-1")
    assert len(db.execute(select(Transaction)).scalars().all()) == 1


def test_repeated_unreferenced_purchases_stay_distinct_and_same_mail_conflicts(db):
    purchases = [row("purchase-a", reference=None, counterparty="CAFE"),
                 row("purchase-b", reference=None, counterparty="CAFE")]
    plan = plan_import(db, purchases, [])
    assert len(plan["proposed"]) == 2
    first_plan = plan_import(db, [purchases[0]], [])
    apply_import(db, first_plan, first_plan["plan_sha256"], "run-first")
    conflict = plan_import(db, [row("purchase-a", amount="101.00")], [])
    assert not conflict["proposed"]
    assert conflict["conflicts"][0]["message_id"] == "purchase-a"


def test_exact_strong_reference_duplicate_across_emails_skips_later_message(db):
    plan = plan_import(db, [row("mail-a", reference="123456789012"),
                            row("mail-b", reference="123456789012")], [])
    assert len(plan["proposed"]) == 1
    assert plan["proposed"][0]["gmail_message_id"] == "mail-a"
    assert plan["skipped"] == [{
        "message_id": "mail-b", "reason": "exact strong-reference duplicate in input",
    }]


def test_transfer_classification_and_currency_month_guards(db):
    rows = [row("card-pay", counterparty="CRED Club", reference=None),
            row("self-debit", counterparty="AJMAL RASI K P", reference=None),
            row("self-credit", direction="credit", counterparty="AJMAL RASI K P",
                reference=None),
            row("merchant-credit", direction="credit", counterparty="OTHER PERSON",
                reference=None)]
    plan = plan_import(db, rows, ["AJMAL RASI K P"])
    assert [candidate["category"] for candidate in plan["proposed"]] == [
        "transfer", "transfer", "transfer", None]
    with pytest.raises(ValueError, match="outside bounded month"):
        plan_import(db, [row("wrong-month", day="2026-06-01")], [])
    foreign = plan_import(db, [row("foreign", currency="USD")], [])
    assert not foreign["proposed"]
    assert foreign["excluded"][0]["message_id"] == "foreign"


def test_rollback_refuses_a_modified_imported_row(db):
    plan = plan_import(db, [row()], [])
    manifest = apply_import(db, plan, plan["plan_sha256"], "run-rollback")
    inserted = db.get(Transaction, manifest["inserted_rows"][0]["id"])
    inserted.category = "manual_keep"
    db.commit()
    with pytest.raises(ValueError, match="changed after insertion"):
        rollback_import(db, manifest)
    assert db.get(Transaction, inserted.id) is not None


def test_rollback_deletes_only_untouched_manifest_rows(db):
    plan = plan_import(db, [row()], [])
    manifest = apply_import(db, plan, plan["plan_sha256"], "run-rollback")
    assert rollback_import(db, manifest)["deleted_count"] == 1
    assert db.execute(select(Transaction)).scalars().all() == []


def test_same_email_conflicting_input_rows_are_skipped_as_conflict(db):
    plan = plan_import(db, [row("same-mail", reference="123456789012"),
                            row("same-mail", direction="credit", reference="999999999999")], [])
    assert not plan["proposed"]
    assert plan["conflicts"] == [{
        "message_id": "same-mail",
        "reason": "same message has conflicting transaction rows in input",
    }]


def test_same_full_card_suffix_matches_bank_label_variants(db):
    existing = Transaction(
        txn_date=date(2026, 5, 4), amount=Decimal("100.00"), currency="INR",
        direction="debit", account="HDFC Diners ..5473", source="upi",
        reference="123456789012", gmail_message_id="old-mail", dedup_key="old-key",
    )
    db.add(existing)
    db.commit()
    plan = plan_import(db, [row("new-mail", account="HDFC Card ..5473")], [])
    assert not plan["proposed"]
    assert plan["skipped"][0]["reason"] == "strong exact match already exists"


@pytest.mark.parametrize("stmt_account,live_account,amount,stmt_party,live_party", [
    ("HDFC Diners ..6116", "HDFC Card ..6116", "3564.00",
     "EMI SNITCHKOZHIKODE", "SNITCHKOZHIKODE"),
    ("HDFC Diners ..5473", "HDFC Card ..5473", "2500.00",
     "EMI UPI-NEELIYAT TRADERS3", "NEELIYATTRADERS3"),
])
def test_matching_statement_emi_suppresses_live_overlap(
    db, stmt_account, live_account, amount, stmt_party, live_party,
):
    existing = Transaction(
        txn_date=date(2026, 5, 24 if amount == "3564.00" else 25),
        amount=Decimal(amount), currency="INR", direction="debit",
        counterparty=stmt_party, account=stmt_account, source="statement_emi",
        gmail_message_id="statement-mail", dedup_key=f"stmt-{amount}",
    )
    db.add(existing)
    db.commit()
    live = row("live-mail", day=existing.txn_date.isoformat(), amount=amount,
               account=live_account, counterparty=live_party, reference=None,
               source="card_live")
    plan = plan_import(db, [live], [])
    assert not plan["proposed"]
    assert plan["skipped"] == [{
        "message_id": "live-mail", "reason": "represented by matching existing statement row",
    }]
    # The pre-existing statement row remains untouched, including its EMI source/category.
    assert db.get(Transaction, existing.id).source == "statement_emi"


def test_statement_overlap_requires_merchant_containment(db):
    existing = Transaction(
        txn_date=date(2026, 5, 24), amount=Decimal("3564.00"), currency="INR",
        direction="debit", counterparty="EMI SNITCHKOZHIKODE",
        account="HDFC Diners ..6116", source="statement_emi",
        gmail_message_id="statement-mail", dedup_key="stmt-no-merchant-match",
    )
    db.add(existing)
    db.commit()
    live = row("different-merchant", day="2026-05-24", amount="3564.00",
               account="HDFC Card ..6116", counterparty="OTHER MERCHANT",
               reference=None, source="card_live")
    plan = plan_import(db, [live], [])
    assert len(plan["proposed"]) == 1
    assert not plan["skipped"]


def test_card_payment_receipts_are_transfers_and_explicit_reversal_is_not_income(db):
    original = Transaction(
        txn_date=date(2026, 5, 27), amount=Decimal("1556.00"), currency="INR",
        direction="debit", counterparty="NESTO HYPERMARKET", account="HDFC Card ..5473",
        source="card_live", reference="original-debit", gmail_message_id="original-debit-mail",
        dedup_key="original-debit-key", category=None,
    )
    db.add(original)
    db.commit()
    rows = [
        row("cc-pay-1", direction="credit", amount="17725.98", reference=None,
            counterparty=None, account="ICICI Card ..3006",
            raw_snippet="We have received payment of INR 17,725.98 on your ICICI Bank Credit Card account."),
        row("cc-pay-2", direction="credit", amount="32080.59", reference=None,
            counterparty=None, account="ICICI Card ..8004",
            raw_snippet="We have received payment of INR 32,080.59 on your ICICI Bank Credit Card account."),
        row("cc-pay-3", direction="credit", amount="159.00", reference=None,
            counterparty=None, account="ICICI Amazon Pay ..0010",
            raw_snippet="We have received payment of INR 159.00 on your ICICI Bank Credit Card account."),
        row("card-reversal", day="2026-05-27", direction="credit", amount="1556.00",
            reference=None, counterparty="NESTO HYPERMARKET", account="HDFC Card ..5473",
            raw_snippet="A transaction reversal of Rs. 1556.00 has been initiated. From Merchant: NESTO."),
        row("merchant-refund", direction="credit", amount="50.00", reference=None,
            counterparty="SHOP", raw_snippet="A refund of INR 50.00 from SHOP was credited to your card."),
    ]
    plan = plan_import(db, rows, [])
    categories = {candidate["gmail_message_id"]: candidate["category"]
                  for candidate in plan["proposed"]}
    assert categories == {
        "cc-pay-1": "transfer", "cc-pay-2": "transfer", "cc-pay-3": "transfer",
        "card-reversal": "reversal_credit", "merchant-refund": None,
    }
    assert db.get(Transaction, original.id).category is None
