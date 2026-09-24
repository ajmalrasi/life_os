from __future__ import annotations

from sqlalchemy.orm import Session
from fastapi import APIRouter, Depends

from app.database import get_db
from app.models.money_profile import MoneyProfile
from app.schemas.money_profile import MoneyProfileIn, MoneyProfileOut

router = APIRouter(prefix="/money/profile", tags=["money_profile"])


def _to_out(row: MoneyProfile | None) -> MoneyProfileOut:
    if row is None:
        return MoneyProfileOut(monthly_income=None, savings_goal_pct=None, updated_at=None)
    return MoneyProfileOut(
        monthly_income=row.monthly_income,
        savings_goal_pct=row.savings_goal_pct,
        updated_at=row.updated_at.isoformat() if row.updated_at else None,
    )


@router.get("", response_model=MoneyProfileOut)
def get_money_profile(db: Session = Depends(get_db)) -> MoneyProfileOut:
    return _to_out(db.get(MoneyProfile, 1))


@router.put("", response_model=MoneyProfileOut)
def set_money_profile(payload: MoneyProfileIn, db: Session = Depends(get_db)) -> MoneyProfileOut:
    row = db.get(MoneyProfile, 1)
    if row is None:
        row = MoneyProfile(id=1)
        db.add(row)
    row.monthly_income = payload.monthly_income
    row.savings_goal_pct = payload.savings_goal_pct
    db.commit()
    db.refresh(row)
    return _to_out(row)
