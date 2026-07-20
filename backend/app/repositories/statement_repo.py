from __future__ import annotations

from datetime import date

from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from app.models.card_statement import CardStatement, CardStatementItem
from app.services.statement_details import classify_statement_item
from app.services.statement_parsers.base import ParsedStatement, StatementTxn
from app.database import utcnow


class StatementRepository:
    def __init__(self, db: Session) -> None:
        self.db = db

    def has_message(self, message_id: str) -> bool:
        count = self.db.execute(
            select(func.count())
            .select_from(CardStatement)
            .where(CardStatement.gmail_message_id == message_id)
        ).scalar_one()
        return count > 0

    def message_needs_processing(self, message_id: str, parser_version: int) -> bool:
        statements = list(
            self.db.execute(
                select(CardStatement).where(CardStatement.gmail_message_id == message_id)
            ).scalars()
        )
        return not statements or any(s.details_parser_version < parser_version for s in statements)

    def get_existing(self, bank: str, last4: str | None, stmt_date: date | None) -> CardStatement | None:
        return self.db.execute(
            select(CardStatement).where(
                CardStatement.bank == bank,
                CardStatement.card_last4 == last4,
                CardStatement.statement_date == stmt_date,
            )
        ).scalar_one_or_none()

    def add_statement(self, parsed: ParsedStatement, message_id: str) -> CardStatement:
        obj = CardStatement(
            bank=parsed.bank,
            card_label=parsed.card_label,
            card_last4=parsed.card_last4,
            statement_date=parsed.statement_date,
            period_start=parsed.period_start,
            period_end=parsed.period_end,
            due_date=parsed.due_date,
            total_due=parsed.total_due,
            min_due=parsed.min_due,
            total_spend=parsed.total_spend,
            line_items_total=len(parsed.line_items),
            gmail_message_id=message_id,
        )
        self.db.add(obj)
        self.db.flush()
        return obj

    def replace_details(
        self,
        statement: CardStatement,
        items: list[StatementTxn],
        parser_version: int,
    ) -> list[CardStatementItem]:
        """Atomically replace derived detail rows for one parser version."""
        self.db.execute(
            delete(CardStatementItem).where(CardStatementItem.statement_id == statement.id)
        )
        stored = [
            CardStatementItem(
                statement_id=statement.id,
                sequence=sequence,
                txn_date=item.txn_date,
                txn_time=item.txn_time,
                description=item.description[:512],
                amount=item.amount,
                direction=item.direction,
                item_type=classify_statement_item(item),
            )
            for sequence, item in enumerate(items)
        ]
        self.db.add_all(stored)
        statement.line_items_total = len(stored)
        statement.details_status = "ready" if stored else "unavailable"
        statement.details_parser_version = parser_version
        statement.details_parsed_at = utcnow()
        self.db.flush()
        return stored

    def details(self, statement_id: int) -> tuple[CardStatement | None, list[CardStatementItem]]:
        statement = self.db.get(CardStatement, statement_id)
        if statement is None:
            return None, []
        items = list(
            self.db.execute(
                select(CardStatementItem)
                .where(CardStatementItem.statement_id == statement_id)
                .order_by(CardStatementItem.sequence)
            ).scalars()
        )
        return statement, items

    def latest_per_card(self) -> list[CardStatement]:
        """Most recent statement row for each (bank, card_last4)."""
        ranked = (
            select(
                CardStatement.id,
                func.row_number()
                .over(
                    partition_by=(CardStatement.bank, CardStatement.card_last4),
                    order_by=CardStatement.statement_date.desc().nullslast(),
                )
                .label("rn"),
            )
        ).subquery()
        stmt = (
            select(CardStatement)
            .join(ranked, CardStatement.id == ranked.c.id)
            .where(ranked.c.rn == 1)
            .order_by(CardStatement.due_date)
        )
        return list(self.db.execute(stmt).scalars())

    def range(self, start: date, end: date) -> list[CardStatement]:
        stmt = (
            select(CardStatement)
            .where(CardStatement.statement_date >= start, CardStatement.statement_date <= end)
            .order_by(CardStatement.statement_date.desc())
        )
        return list(self.db.execute(stmt).scalars())

    def set_paid(self, statement_id: int, paid: bool) -> CardStatement | None:
        statement = self.db.get(CardStatement, statement_id)
        if statement is None:
            return None
        statement.paid_at = utcnow() if paid else None
        self.db.commit()
        self.db.refresh(statement)
        return statement
