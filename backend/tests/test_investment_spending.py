from datetime import date
from decimal import Decimal
from app.models.transaction import Transaction
from app.services.reconcile import is_investment


def test_investments_and_duplicates_are_recorded_but_excluded_from_spending(client):
    from app.database import SessionLocal
    from app.api.routes.expenses import _to_out
    from app.services.money_query import query_money
    from app.repositories.expense_repo import ExpenseRepository
    with SessionLocal() as db:
        for key, amount, party, category in [('purchase', '500', 'ZOMATO', None), ('broker', '75000', None, 'investments'), ('duplicate', '500', 'ZOMATO', 'duplicate')]:
            db.add(Transaction(txn_date=date(2026,7,2), amount=Decimal(amount), currency='INR', direction='debit', counterparty=party, category=category, source='upi', gmail_message_id=key, dedup_key=key))
        db.commit()
        rows=ExpenseRepository(db).range(date(2026,7,2),date(2026,7,2))
        assert len(rows)==3
        assert sum(x.amount for x in _to_out(rows,db) if x.spend_category is not None)==500
        assert query_money(db,start_date='2026-07-02',end_date='2026-07-02')['summary']['total']==500
        assert query_money(db,mode='all',start_date='2026-07-02',end_date='2026-07-02')['summary']['count']==3
        assert ExpenseRepository(db).daily_spend(date(2026,7,2),date(2026,7,2))=={date(2026,7,2):500}


def test_investment_identification_uses_source_and_respects_user_override():
    t=Transaction(counterparty=None,raw_snippet='debited to VPA zerodhabroking@hdfcbank',spend_category_override=None)
    assert is_investment(t)
    t.spend_category_override='shopping'
    assert not is_investment(t)
