from datetime import date
from decimal import Decimal

from app.database import SessionLocal
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.statement_repo import StatementRepository
from app.services.card_cycles import current_card_cycles
from app.services.expense_parsers.base import ParsedTxn
from app.services.statement_parsers.base import ParsedStatement


def _statement(
    *,
    bank: str = "axis",
    label: str = "Axis Flipkart VISA",
    last4: str = "3068",
    statement_date: date,
    period_start: date,
    period_end: date,
    due_date: date,
) -> ParsedStatement:
    return ParsedStatement(
        bank=bank,
        card_label=label,
        card_last4=last4,
        statement_date=statement_date,
        period_start=period_start,
        period_end=period_end,
        due_date=due_date,
        total_due=Decimal("100"),
        min_due=Decimal("10"),
        total_spend=Decimal("100"),
    )


def test_current_cycle_sums_only_recorded_spend_and_refunds(client):
    with SessionLocal() as db:
        repo = StatementRepository(db)
        repo.add_statement(
            _statement(
                statement_date=date(2026, 6, 15),
                period_start=date(2026, 5, 17),
                period_end=date(2026, 6, 15),
                due_date=date(2026, 7, 5),
            ),
            "previous",
        )
        repo.add_statement(
            _statement(
                statement_date=date(2026, 7, 15),
                period_start=date(2026, 6, 17),
                period_end=date(2026, 7, 15),
                due_date=date(2026, 8, 4),
            ),
            "latest",
        )
        expense_repo = ExpenseRepository(db)
        expense_repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 7, 20),
                amount=Decimal("1000"),
                direction="debit",
                source="card_live",
                account="Axis Card ..3068",
                counterparty="STORE",
            ),
            "current-debit",
            "current-debit",
        )
        expense_repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 7, 21),
                amount=Decimal("100"),
                direction="credit",
                source="card_live",
                account="Axis Card ..3068",
                counterparty="STORE REFUND",
            ),
            "current-refund",
            "current-refund",
        )
        expense_repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 7, 22),
                amount=Decimal("5000"),
                direction="credit",
                source="card_live",
                account="Axis Card ..3068",
                counterparty="PAYMENT RECEIVED",
                category="transfer",
            ),
            "card-payment",
            "card-payment",
        )
        # This happened before the active cycle and must not be carried forward.
        expense_repo.upsert(
            ParsedTxn(
                txn_date=date(2026, 7, 13),
                amount=Decimal("5864.99"),
                direction="debit",
                source="card_live",
                account="Axis Card ..3068",
                counterparty="OLDER PURCHASE",
            ),
            "previous-charge",
            "previous-charge",
        )
        db.commit()

        result = current_card_cycles(db, as_of=date(2026, 7, 30))

    assert result.current_spend_total == Decimal("1000")
    assert result.refund_total == Decimal("100")
    assert result.net_cycle_total == Decimal("900")
    assert len(result.cycles) == 1
    cycle = result.cycles[0]
    assert cycle.cycle_start == date(2026, 7, 16)
    assert cycle.cycle_end == date(2026, 8, 15)
    assert cycle.current_spend == Decimal("1000")
    assert cycle.refunds == Decimal("100")
    assert cycle.net_cycle_amount == Decimal("900")
    assert cycle.transaction_count == 2
    assert cycle.last_transaction_date == date(2026, 7, 21)

    details = client.get("/statements/cycles/current/axis/3068")
    assert details.status_code == 200
    body = details.json()
    assert body["cycle"]["cycle_end"] == "2026-08-15"
    assert [row["amount"] for row in body["transactions"]] == ["100.00", "1000.00"]
    assert all(row["spend_category"] for row in body["transactions"])


def test_cycle_cadence_preserves_exact_month_end(client):
    with SessionLocal() as db:
        repo = StatementRepository(db)
        repo.add_statement(
            _statement(
                statement_date=date(2026, 1, 31),
                period_start=date(2026, 1, 1),
                period_end=date(2026, 1, 31),
                due_date=date(2026, 2, 20),
            ),
            "jan",
        )
        repo.add_statement(
            _statement(
                statement_date=date(2026, 2, 28),
                period_start=date(2026, 2, 1),
                period_end=date(2026, 2, 28),
                due_date=date(2026, 3, 20),
            ),
            "feb",
        )
        db.commit()

        result = current_card_cycles(db, as_of=date(2026, 3, 15))

    cycle = result.cycles[0]
    assert cycle.cycle_start == date(2026, 3, 1)
    assert cycle.cycle_end == date(2026, 3, 31)
    assert cycle.current_spend == Decimal("0")


def test_current_cycles_endpoint_returns_empty_shape(client):
    response = client.get("/statements/cycles/current")
    assert response.status_code == 200
    assert response.json()["cycles"] == []
    assert response.json()["current_spend_total"] == "0"
    assert client.get("/statements/cycles/current/axis/3068").status_code == 404


def test_current_cycle_exposes_canonical_sbi_last_four(client):
    with SessionLocal() as db:
        StatementRepository(db).add_statement(
            _statement(
                bank="sbi",
                label="SBI Card",
                last4="70",
                statement_date=date(2026, 7, 24),
                period_start=date(2026, 6, 25),
                period_end=date(2026, 7, 24),
                due_date=date(2026, 8, 13),
            ),
            "sbi-latest",
        )
        db.commit()
        result = current_card_cycles(db, as_of=date(2026, 7, 30))

    assert result.cycles[0].card_last4 == "1970"
