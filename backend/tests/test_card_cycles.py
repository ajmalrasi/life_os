from datetime import date
from decimal import Decimal

from app.database import SessionLocal
from app.models.card_statement import CardStatementItem
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.statement_repo import StatementRepository
from app.services.card_cycles import current_card_cycles
from app.services.expense_parsers.base import ParsedTxn
from app.services.statement_parsers.base import ParsedStatement


def _statement(
    *,
    statement_date: date,
    period_start: date,
    period_end: date,
    due_date: date,
    total_spend: str,
) -> ParsedStatement:
    return ParsedStatement(
        bank="axis",
        card_label="Axis Flipkart VISA",
        card_last4="3068",
        statement_date=statement_date,
        period_start=period_start,
        period_end=period_end,
        due_date=due_date,
        total_due=Decimal(total_spend),
        min_due=Decimal("100"),
        total_spend=Decimal(total_spend),
    )


def test_current_cycle_separates_posted_refunds_and_expected_emis(client):
    with SessionLocal() as db:
        repo = StatementRepository(db)
        repo.add_statement(
            _statement(
                statement_date=date(2026, 6, 15),
                period_start=date(2026, 5, 17),
                period_end=date(2026, 6, 15),
                due_date=date(2026, 7, 5),
                total_spend="18699.25",
            ),
            "previous",
        )
        latest = repo.add_statement(
            _statement(
                statement_date=date(2026, 7, 15),
                period_start=date(2026, 6, 17),
                period_end=date(2026, 7, 15),
                due_date=date(2026, 8, 4),
                total_spend="29136.40",
            ),
            "latest",
        )
        db.add_all(
            [
                CardStatementItem(
                    statement_id=latest.id,
                    sequence=0,
                    txn_date=date(2026, 6, 30),
                    description="EMI INTEREST - 2/3, REF# 73084373 ELECTRONICS",
                    amount=Decimal("259"),
                    direction="debit",
                    item_type="emi",
                ),
                CardStatementItem(
                    statement_id=latest.id,
                    sequence=1,
                    txn_date=date(2026, 6, 30),
                    description="EMI PRINCIPAL - 2/3, REF# 73084373 ELECTRONICS",
                    amount=Decimal("9666"),
                    direction="debit",
                    item_type="emi",
                ),
                CardStatementItem(
                    statement_id=latest.id,
                    sequence=2,
                    txn_date=date(2026, 7, 6),
                    description="EMI INTEREST - 1/3, REF# 74708359 DEPT STORES",
                    amount=Decimal("552"),
                    direction="debit",
                    item_type="emi",
                ),
                CardStatementItem(
                    statement_id=latest.id,
                    sequence=3,
                    txn_date=date(2026, 7, 6),
                    description="EMI PRINCIPAL - 1/3, REF# 74708359 DEPT STORES",
                    amount=Decimal("13607"),
                    direction="debit",
                    item_type="emi",
                ),
            ]
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
        # A previous-cycle live charge drives the honest coverage warning.
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

    assert result.posted_total == Decimal("900")
    assert result.expected_emi_total == Decimal("24229.98")
    assert result.estimated_total == Decimal("25129.98")
    assert len(result.cycles) == 1
    cycle = result.cycles[0]
    assert cycle.cycle_start == date(2026, 7, 16)
    assert cycle.cycle_end == date(2026, 8, 15)
    assert cycle.expected_statement_date == date(2026, 8, 15)
    assert cycle.expected_due_date == date(2026, 9, 4)
    assert cycle.posted_debits == Decimal("1000")
    assert cycle.posted_credits == Decimal("100")
    assert cycle.transaction_count == 2
    assert cycle.last_transaction_date == date(2026, 7, 21)
    assert cycle.historical_coverage == Decimal("0.20")
    assert cycle.confidence == "partial"
    assert [(emi.next_installment, emi.total_installments) for emi in cycle.expected_emis] == [
        (3, 3),
        (2, 3),
    ]


def test_cycle_cadence_preserves_month_end_and_skips_finished_emi(client):
    with SessionLocal() as db:
        repo = StatementRepository(db)
        repo.add_statement(
            _statement(
                statement_date=date(2026, 1, 31),
                period_start=date(2026, 1, 1),
                period_end=date(2026, 1, 31),
                due_date=date(2026, 2, 20),
                total_spend="100",
            ),
            "jan",
        )
        latest = repo.add_statement(
            _statement(
                statement_date=date(2026, 2, 28),
                period_start=date(2026, 2, 1),
                period_end=date(2026, 2, 28),
                due_date=date(2026, 3, 20),
                total_spend="100",
            ),
            "feb",
        )
        db.add(
            CardStatementItem(
                statement_id=latest.id,
                sequence=0,
                txn_date=date(2026, 2, 20),
                description="EMI PRINCIPAL - 3/3, REF# DONE123 ITEM",
                amount=Decimal("100"),
                direction="debit",
                item_type="emi",
            )
        )
        db.commit()

        result = current_card_cycles(db, as_of=date(2026, 3, 15))

    cycle = result.cycles[0]
    assert cycle.cycle_start == date(2026, 3, 1)
    assert cycle.cycle_end == date(2026, 3, 31)
    assert cycle.expected_statement_date == date(2026, 3, 31)
    assert cycle.expected_due_date == date(2026, 4, 20)
    assert cycle.expected_emis == []


def test_current_cycles_endpoint_returns_empty_shape(client):
    response = client.get("/statements/cycles/current")
    assert response.status_code == 200
    assert response.json()["cycles"] == []
    assert response.json()["estimated_total"] == "0"


def test_current_cycle_exposes_canonical_sbi_last_four(client):
    with SessionLocal() as db:
        statement = StatementRepository(db).add_statement(
            ParsedStatement(
                bank="sbi",
                card_label="SBI Card",
                card_last4="70",
                statement_date=date(2026, 7, 24),
                period_start=date(2026, 6, 25),
                period_end=date(2026, 7, 24),
                due_date=date(2026, 8, 13),
                total_due=Decimal("100"),
                min_due=Decimal("10"),
                total_spend=Decimal("100"),
            ),
            "sbi-latest",
        )
        assert statement.id is not None
        db.commit()
        result = current_card_cycles(db, as_of=date(2026, 7, 30))

    assert result.cycles[0].card_last4 == "1970"
