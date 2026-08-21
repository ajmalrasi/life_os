"""Tests for user-managed payee labels: CRUD endpoints, the label-aware
categorizer, and its precedence over the built-in keyword rules.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from app.api.routes.statements import build_categorizer
from app.database import SessionLocal
from app.models.payee_label import PayeeLabel
from app.models.transaction import Transaction


def _add_label(**kw) -> None:
    with SessionLocal() as db:
        db.add(PayeeLabel(**kw))
        db.commit()


def _add_txn(counterparty: str, amount: str = "100.00", direction: str = "debit") -> None:
    with SessionLocal() as db:
        db.add(
            Transaction(
                txn_date=date.today(),
                amount=Decimal(amount),
                direction=direction,
                counterparty=counterparty,
                source="upi",
                gmail_message_id=f"m-{counterparty}-{amount}",
                dedup_key=f"d-{counterparty}-{amount}",
            )
        )
        db.commit()


# --- categorizer unit behaviour -------------------------------------------------


def test_substring_label_matches_case_insensitively(client):
    _add_label(pattern="SHIHAB", category="friends", match_type="substring")
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("UPI-shihab k p") == "friends"
    assert categorize("someone else") == "other"


def test_exact_label_only_matches_whole_string(client):
    _add_label(pattern="ACH D- Indian Clearing", category="institutional", match_type="exact")
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("ACH D- Indian Clearing") == "institutional"
    assert categorize("ACH D- Indian Clearing House Ltd") == "other"


def test_regex_label_matches(client):
    _add_label(pattern=r"emi.*hdfc", category="loan_emi", match_type="regex")
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("Monthly EMI to HDFC") == "loan_emi"


def test_label_overrides_builtin_keyword_rule(client):
    # "zomato" would be "food" via the built-in rules; a label wins.
    _add_label(pattern="zomato", category="office_lunch", match_type="substring")
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("ZOMATO ORDER 123") == "office_lunch"


def test_emi_precedes_shopping_merchant_keywords(client):
    """Axis appends 'DEPT STORES' to EMI lines; EMI identity must win."""
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("EMI PRINCIPAL - 1/3, REF# 74708359 DEPT STORES") == "emi"
    assert categorize("EMI INTEREST - 1/3, REF# 74708359 DEPT STORES") == "emi"
    assert categorize("ORDINARY DEPT STORE PURCHASE") == "shopping"


def test_agoda_gateway_handle_is_travel_not_airtel_subscription(client):
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("agodacompanypteltdwxk.rzp@mairtel") == "travel"


def test_durable_large_expense_categories(client):
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("CAR SERVICE AND WHEEL ALIGNMENT") == "vehicle"
    assert categorize("ICICI LOMBARD INSURANCE PREMIUM") == "insurance"
    assert categorize("INCOME TAX CHALLAN PAYMENT") == "taxes"


def test_statement_gst_remains_a_fee_but_explicit_gst_payment_is_tax(client):
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("GST ON CARD INTEREST") == "emi_fees"
    assert categorize("GST PAYMENT GOVERNMENT CHALLAN") == "taxes"


def test_priority_orders_rules(client):
    _add_label(pattern="rasi", category="broad", match_type="substring", priority=200)
    _add_label(pattern="ajmal rasi", category="specific", match_type="substring", priority=10)
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("AJMAL RASI K P") == "specific"


def test_inactive_label_ignored(client):
    _add_label(pattern="dormant", category="ignored", match_type="substring", active=False)
    with SessionLocal() as db:
        categorize = build_categorizer(db)
    assert categorize("dormant payee") == "other"


# --- CRUD endpoints -------------------------------------------------------------


def test_create_and_list(client):
    resp = client.post("/payee-labels", json={"pattern": "NETFLIX", "category": "subscriptions"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["pattern"] == "NETFLIX"
    assert body["match_type"] == "substring"

    rows = client.get("/payee-labels").json()
    assert len(rows) == 1
    assert rows[0]["category"] == "subscriptions"


def test_duplicate_pattern_conflicts(client):
    client.post("/payee-labels", json={"pattern": "X", "category": "a"})
    dup = client.post("/payee-labels", json={"pattern": "X", "category": "b"})
    assert dup.status_code == 409


def test_empty_pattern_rejected(client):
    resp = client.post("/payee-labels", json={"pattern": "   ", "category": "a"})
    assert resp.status_code == 422


def test_invalid_regex_rejected_on_create(client):
    resp = client.post(
        "/payee-labels",
        json={"pattern": "([unclosed", "category": "a", "match_type": "regex"},
    )
    assert resp.status_code == 422


def test_update_label(client):
    created = client.post("/payee-labels", json={"pattern": "X", "category": "a"}).json()
    resp = client.patch(f"/payee-labels/{created['id']}", json={"category": "b", "active": False})
    assert resp.status_code == 200
    assert resp.json()["category"] == "b"
    assert resp.json()["active"] is False


def test_update_to_invalid_regex_rejected(client):
    created = client.post("/payee-labels", json={"pattern": "X", "category": "a"}).json()
    resp = client.patch(
        f"/payee-labels/{created['id']}", json={"match_type": "regex", "pattern": "([bad"}
    )
    assert resp.status_code == 422


def test_delete_label(client):
    created = client.post("/payee-labels", json={"pattern": "X", "category": "a"}).json()
    assert client.delete(f"/payee-labels/{created['id']}").status_code == 204
    assert client.get("/payee-labels").json() == []
    assert client.delete(f"/payee-labels/{created['id']}").status_code == 404


def test_preview_endpoint(client):
    client.post("/payee-labels", json={"pattern": "SHIHAB", "category": "friends"})
    resp = client.get("/payee-labels/preview", params={"desc": "UPI-SHIHAB K P"})
    assert resp.json()["category"] == "friends"


def test_labels_flow_into_spend_analysis(client):
    _add_txn("UPI-SHIHAB K P", amount="500.00")
    client.post("/payee-labels", json={"pattern": "SHIHAB", "category": "friends"})
    analysis = client.get("/statements/analysis").json()
    cats = {c["category"]: c for c in analysis["spend_by_category"]}
    assert "friends" in cats
    assert cats["friends"]["total"] == "500.00"


def test_expenses_feed_exposes_spend_category(client):
    _add_txn("UPI-SHIHAB K P", amount="500.00")
    _add_txn("ZERODHA BROKING", amount="1000.00")
    client.post("/payee-labels", json={"pattern": "SHIHAB", "category": "friends"})

    rows = {r["counterparty"]: r for r in client.get("/expenses?days=3650").json()}
    assert rows["UPI-SHIHAB K P"]["spend_category"] == "friends"  # label wins
    assert rows["ZERODHA BROKING"]["spend_category"] == "investments"  # keyword rule


def test_excluded_transactions_have_null_spend_category(client):
    with SessionLocal() as db:
        db.add(
            Transaction(
                txn_date=date.today(),
                amount=Decimal("2000.00"),
                direction="debit",
                counterparty="BPPY CC PAYMENT",
                category="transfer",  # excluded from spend
                source="upi",
                gmail_message_id="m-transfer",
                dedup_key="d-transfer",
            )
        )
        db.commit()
    rows = {r["counterparty"]: r for r in client.get("/expenses?days=3650").json()}
    assert rows["BPPY CC PAYMENT"]["spend_category"] is None
