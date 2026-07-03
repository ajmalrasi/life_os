"""CRUD for user-managed payee → category labels (Money tab).

Lets the user teach the spend categorizer about their own recurring payees so
they stop landing in the dominant "other" bucket. Labels take effect immediately
on the next read of ``/statements/analysis`` and ``/statements/trends`` — no sync
or redeploy needed.
"""

from __future__ import annotations

import re

from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy.orm import Session

from app.api.routes.statements import build_categorizer
from app.database import get_db
from app.models.payee_label import MATCH_TYPES
from app.repositories.payee_label_repo import PayeeLabelRepository
from app.schemas.payee_label import PayeeLabelIn, PayeeLabelOut, PayeeLabelUpdate

router = APIRouter(prefix="/payee-labels", tags=["payee-labels"])


@router.get("", response_model=list[PayeeLabelOut])
def list_labels(db: Session = Depends(get_db)) -> list[PayeeLabelOut]:
    """All labels in evaluation order (priority, then id)."""
    rows = PayeeLabelRepository(db).list_all()
    return [PayeeLabelOut.model_validate(r) for r in rows]


@router.get("/preview", response_model=dict)
def preview(desc: str, db: Session = Depends(get_db)) -> dict:
    """Return the category a given counterparty string would resolve to under the
    current labels + built-in rules. Lets the app show a live preview while the
    user is typing a new rule."""
    categorize = build_categorizer(db)
    return {"desc": desc, "category": categorize(desc)}


@router.post("", response_model=PayeeLabelOut, status_code=201)
def create_label(payload: PayeeLabelIn, db: Session = Depends(get_db)) -> PayeeLabelOut:
    repo = PayeeLabelRepository(db)
    if repo.get_by_pattern(payload.pattern, payload.match_type):
        raise HTTPException(
            status_code=409,
            detail=f"A {payload.match_type} label for {payload.pattern!r} already exists.",
        )
    obj = repo.create(
        pattern=payload.pattern,
        category=payload.category,
        match_type=payload.match_type,
        priority=payload.priority,
        active=payload.active,
        note=payload.note,
    )
    db.commit()
    return PayeeLabelOut.model_validate(obj)


@router.patch("/{label_id}", response_model=PayeeLabelOut)
def update_label(
    label_id: int, payload: PayeeLabelUpdate, db: Session = Depends(get_db)
) -> PayeeLabelOut:
    repo = PayeeLabelRepository(db)
    obj = repo.get(label_id)
    if obj is None:
        raise HTTPException(status_code=404, detail="Label not found.")

    fields = payload.model_dump(exclude_unset=True)
    # Validate the resulting regex against the *merged* pattern/match_type so a
    # partial update can't leave an uncompilable rule behind.
    new_match_type = fields.get("match_type", obj.match_type)
    new_pattern = fields.get("pattern", obj.pattern)
    if new_match_type == "regex":
        try:
            re.compile(new_pattern)
        except re.error as exc:
            raise HTTPException(status_code=422, detail=f"invalid regex pattern: {exc}")

    repo.update(obj, **fields)
    db.commit()
    return PayeeLabelOut.model_validate(obj)


@router.delete("/{label_id}", status_code=204, response_class=Response)
def delete_label(label_id: int, db: Session = Depends(get_db)) -> Response:
    repo = PayeeLabelRepository(db)
    obj = repo.get(label_id)
    if obj is None:
        raise HTTPException(status_code=404, detail="Label not found.")
    repo.delete(obj)
    db.commit()
    return Response(status_code=204)


@router.get("/match-types", response_model=list[str])
def match_types() -> list[str]:
    """The allowed ``match_type`` values, for the app's picker."""
    return list(MATCH_TYPES)
