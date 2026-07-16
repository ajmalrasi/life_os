"""Manual, reversible reconciliation of money lent and later returned."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import timedelta

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.transaction import Transaction
from app.models.transaction_reconciliation import TransactionReconciliation
from app.services.reconcile import EXCLUDED_FROM_SPEND


def _person_name(value: str | None) -> str:
    """Extract the stable human-name portion from common UPI descriptions.

    PSP handles routinely drift (``zen-4@oksbi`` -> ``zen-2@okicici``), while
    the bank-provided display name remains stable.  This is ranking only; the
    user still confirms the proposed repayment.
    """
    raw = (value or "").strip()
    raw = re.sub(r"^(upi[-/]?|mr\.?|mrs\.?)\s*", "", raw, flags=re.I)
    if "(" in raw:
        raw = raw.split("(", 1)[0]
    elif "-" in raw:
        raw = raw.split("-", 1)[0]
    return " ".join(re.sub(r"[^a-zA-Z ]+", " ", raw).lower().split())


@dataclass(frozen=True)
class RepaymentCandidate:
    transaction: Transaction
    match_score: int
    reason: str


class RepaymentError(ValueError):
    def __init__(self, message: str, status_code: int = 422) -> None:
        super().__init__(message)
        self.status_code = status_code


class RepaymentService:
    def __init__(self, db: Session) -> None:
        self.db = db

    def _transaction(self, transaction_id: int, *, lock: bool = False) -> Transaction:
        stmt = select(Transaction).where(Transaction.id == transaction_id)
        if lock:
            stmt = stmt.with_for_update()
        row = self.db.execute(stmt).scalar_one_or_none()
        if row is None:
            raise RepaymentError(f"Transaction {transaction_id} not found", 404)
        return row

    def candidates(self, debit_id: int, window_days: int = 120) -> list[RepaymentCandidate]:
        debit = self._transaction(debit_id)
        if debit.direction != "debit":
            raise RepaymentError("Repayment candidates require a debit transaction")
        if debit.category in EXCLUDED_FROM_SPEND:
            raise RepaymentError("This debit is already excluded from spending", 409)

        active_credit_ids = select(TransactionReconciliation.credit_transaction_id).where(
            TransactionReconciliation.undone_at.is_(None)
        )
        rows = list(
            self.db.execute(
                select(Transaction).where(
                    Transaction.direction == "credit",
                    Transaction.amount == debit.amount,
                    Transaction.currency == debit.currency,
                    Transaction.txn_date >= debit.txn_date,
                    Transaction.txn_date <= debit.txn_date + timedelta(days=window_days),
                    Transaction.category.is_(None),
                    Transaction.id.notin_(active_credit_ids),
                )
            ).scalars()
        )

        debit_name = _person_name(debit.counterparty)
        ranked: list[RepaymentCandidate] = []
        for credit in rows:
            credit_name = _person_name(credit.counterparty)
            days = (credit.txn_date - debit.txn_date).days
            score = 40  # amount + currency + direction/date ordering already match
            reasons = ["same amount"]
            if debit_name and credit_name and debit_name == credit_name:
                score += 50
                reasons.insert(0, "same person")
            elif debit_name and credit_name and (
                debit_name in credit_name or credit_name in debit_name
            ):
                score += 35
                reasons.insert(0, "similar name")
            if days <= 3:
                score += 10
            elif days <= 14:
                score += 5
            reasons.append("same day" if days == 0 else f"{days} day{'s' if days != 1 else ''} later")
            ranked.append(RepaymentCandidate(credit, min(score, 100), " · ".join(reasons)))

        return sorted(
            ranked,
            key=lambda candidate: (
                -candidate.match_score,
                candidate.transaction.txn_date,
                candidate.transaction.id,
            ),
        )[:20]

    def create(self, debit_id: int, credit_id: int) -> TransactionReconciliation:
        if debit_id == credit_id:
            raise RepaymentError("Debit and credit must be different transactions")

        # Lock in stable id order to avoid deadlocks if two requests race.
        rows = {
            row.id: row
            for row in self.db.execute(
                select(Transaction)
                .where(Transaction.id.in_(sorted((debit_id, credit_id))))
                .order_by(Transaction.id)
                .with_for_update()
            ).scalars()
        }
        if debit_id not in rows or credit_id not in rows:
            missing = debit_id if debit_id not in rows else credit_id
            raise RepaymentError(f"Transaction {missing} not found", 404)
        debit, credit = rows[debit_id], rows[credit_id]

        if debit.direction != "debit" or credit.direction != "credit":
            raise RepaymentError("A repayment must pair one debit with one credit")
        if debit.amount != credit.amount or debit.currency != credit.currency:
            raise RepaymentError("Debit and repayment must have the same amount and currency")
        if credit.txn_date < debit.txn_date:
            raise RepaymentError("The repayment credit cannot be earlier than the debit")
        if debit.category in EXCLUDED_FROM_SPEND or credit.category in EXCLUDED_FROM_SPEND:
            raise RepaymentError("One of these transactions is already excluded", 409)

        existing = self.db.execute(
            select(TransactionReconciliation).where(
                TransactionReconciliation.undone_at.is_(None),
                or_(
                    TransactionReconciliation.debit_transaction_id.in_((debit_id, credit_id)),
                    TransactionReconciliation.credit_transaction_id.in_((debit_id, credit_id)),
                ),
            )
        ).scalar_one_or_none()
        if existing is not None:
            raise RepaymentError("One of these transactions is already paired", 409)

        reconciliation = TransactionReconciliation(
            kind="friend_repayment",
            source="manual",
            debit_transaction_id=debit.id,
            credit_transaction_id=credit.id,
            previous_debit_category=debit.category,
            previous_credit_category=credit.category,
        )
        debit.category = "lent_returned"
        credit.category = "lend_return"
        self.db.add(reconciliation)
        self.db.commit()
        self.db.refresh(reconciliation)
        return reconciliation

    def undo(self, reconciliation_id: int) -> TransactionReconciliation:
        reconciliation = self.db.execute(
            select(TransactionReconciliation)
            .where(TransactionReconciliation.id == reconciliation_id)
            .with_for_update()
        ).scalar_one_or_none()
        if reconciliation is None:
            raise RepaymentError(f"Repayment {reconciliation_id} not found", 404)
        if reconciliation.undone_at is not None:
            raise RepaymentError("This repayment has already been undone", 409)
        if reconciliation.source != "manual":
            raise RepaymentError("Only user-confirmed repayments can be undone here", 409)

        rows = {
            row.id: row
            for row in self.db.execute(
                select(Transaction)
                .where(
                    Transaction.id.in_(
                        (reconciliation.debit_transaction_id, reconciliation.credit_transaction_id)
                    )
                )
                .with_for_update()
            ).scalars()
        }
        debit = rows.get(reconciliation.debit_transaction_id)
        credit = rows.get(reconciliation.credit_transaction_id)
        if debit is None or credit is None:
            raise RepaymentError("A linked bank transaction no longer exists", 409)

        debit.category = reconciliation.previous_debit_category
        credit.category = reconciliation.previous_credit_category
        reconciliation.undone_at = utcnow()
        self.db.commit()
        self.db.refresh(reconciliation)
        return reconciliation

    def transactions_for(
        self, reconciliation: TransactionReconciliation
    ) -> tuple[Transaction, Transaction]:
        rows = {
            row.id: row
            for row in self.db.execute(
                select(Transaction).where(
                    Transaction.id.in_(
                        (reconciliation.debit_transaction_id, reconciliation.credit_transaction_id)
                    )
                )
            ).scalars()
        }
        return (
            rows[reconciliation.debit_transaction_id],
            rows[reconciliation.credit_transaction_id],
        )
