"""Manual, reversible merchant-refund adjustments.

This path is deliberately one-sided: some card refunds appear in the bank app
without generating an alert email, so LifeOS has no credit transaction to pair.
The original debit is retained and an audit row makes the exclusion undoable.
"""

from __future__ import annotations

from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from app.database import utcnow
from app.models.transaction import Transaction
from app.models.transaction_adjustment import TransactionAdjustment
from app.models.transaction_reconciliation import TransactionReconciliation
from app.services.reconcile import EXCLUDED_FROM_SPEND


class RefundError(ValueError):
    def __init__(self, message: str, status_code: int = 422) -> None:
        super().__init__(message)
        self.status_code = status_code


class RefundService:
    def __init__(self, db: Session) -> None:
        self.db = db

    def create(self, debit_id: int) -> TransactionAdjustment:
        debit = self.db.execute(
            select(Transaction).where(Transaction.id == debit_id).with_for_update()
        ).scalar_one_or_none()
        if debit is None:
            raise RefundError(f"Transaction {debit_id} not found", 404)
        if debit.direction != "debit":
            raise RefundError("Only a debit transaction can be marked as refunded")
        if debit.category in EXCLUDED_FROM_SPEND:
            raise RefundError("This transaction is already excluded from spending", 409)

        active_adjustment = self.db.execute(
            select(TransactionAdjustment).where(
                TransactionAdjustment.transaction_id == debit_id,
                TransactionAdjustment.undone_at.is_(None),
            )
        ).scalar_one_or_none()
        if active_adjustment is not None:
            raise RefundError("This transaction is already marked as refunded", 409)

        active_pair = self.db.execute(
            select(TransactionReconciliation).where(
                TransactionReconciliation.undone_at.is_(None),
                or_(
                    TransactionReconciliation.debit_transaction_id == debit_id,
                    TransactionReconciliation.credit_transaction_id == debit_id,
                ),
            )
        ).scalar_one_or_none()
        if active_pair is not None:
            raise RefundError("This transaction is already paired", 409)

        adjustment = TransactionAdjustment(
            kind="merchant_refund",
            source="manual",
            transaction_id=debit.id,
            previous_category=debit.category,
        )
        debit.category = "reversed_debit"
        self.db.add(adjustment)
        self.db.commit()
        self.db.refresh(adjustment)
        return adjustment

    def undo(self, adjustment_id: int) -> TransactionAdjustment:
        adjustment = self.db.execute(
            select(TransactionAdjustment)
            .where(TransactionAdjustment.id == adjustment_id)
            .with_for_update()
        ).scalar_one_or_none()
        if adjustment is None:
            raise RefundError(f"Refund {adjustment_id} not found", 404)
        if adjustment.undone_at is not None:
            raise RefundError("This refund has already been undone", 409)
        if adjustment.source != "manual" or adjustment.kind != "merchant_refund":
            raise RefundError("Only user-confirmed merchant refunds can be undone here", 409)

        debit = self.db.execute(
            select(Transaction)
            .where(Transaction.id == adjustment.transaction_id)
            .with_for_update()
        ).scalar_one_or_none()
        if debit is None:
            raise RefundError("The linked bank transaction no longer exists", 409)

        debit.category = adjustment.previous_category
        adjustment.undone_at = utcnow()
        self.db.commit()
        self.db.refresh(adjustment)
        return adjustment

    def transaction_for(self, adjustment: TransactionAdjustment) -> Transaction:
        return self.db.execute(
            select(Transaction).where(Transaction.id == adjustment.transaction_id)
        ).scalar_one()
