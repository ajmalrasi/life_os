from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy.orm import Session

from app.api.routes.statements import build_categorizer
from app.database import get_db
from app.models.budget import Budget
from app.schemas.budget import BudgetIn, BudgetPaceOut
from app.services.budgets import budget_pace_summary

router = APIRouter(prefix="/budgets", tags=["budgets"])


@router.get("", response_model=list[BudgetPaceOut])
def list_budgets(db: Session = Depends(get_db)) -> list[BudgetPaceOut]:
    """Every stored budget with its month-to-date pace."""
    categorize = build_categorizer(db)
    return [BudgetPaceOut(**vars(p)) for p in budget_pace_summary(db, categorize)]


@router.put("/{category}", response_model=BudgetPaceOut)
def set_budget(category: str, payload: BudgetIn, db: Session = Depends(get_db)) -> BudgetPaceOut:
    """Create or update the monthly limit for one category (upsert)."""
    row = db.get(Budget, category)
    if row is None:
        row = Budget(category=category, monthly_limit=payload.monthly_limit)
        db.add(row)
    else:
        row.monthly_limit = payload.monthly_limit
    db.commit()

    categorize = build_categorizer(db)
    match = next((p for p in budget_pace_summary(db, categorize) if p.category == category), None)
    if match is None:  # pragma: no cover - row was just written above
        raise HTTPException(status_code=500, detail="Budget write did not persist")
    return BudgetPaceOut(**vars(match))


@router.delete("/{category}", status_code=204, response_class=Response)
def delete_budget(category: str, db: Session = Depends(get_db)) -> Response:
    row = db.get(Budget, category)
    if row is None:
        raise HTTPException(status_code=404, detail="No budget set for this category")
    db.delete(row)
    db.commit()
    return Response(status_code=204)
