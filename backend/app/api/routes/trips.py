from __future__ import annotations

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, status
from fastapi.responses import StreamingResponse
from io import BytesIO
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database import get_db, utcnow
from app.models.trip import TripAttachmentBackup, TripBackup
from app.schemas.trip import TripBackupOut, TripSummaryOut, TripSyncIn

router = APIRouter(prefix="/trips", tags=["trips"])


def _out(row: TripBackup) -> TripBackupOut:
    return TripBackupOut(
        id=row.id, title=row.title, revision=row.revision,
        payload=row.payload, updated_at=row.updated_at,
    )


@router.get("", response_model=list[TripSummaryOut])
def list_trips(db: Session = Depends(get_db)) -> list[TripSummaryOut]:
    rows = db.scalars(
        select(TripBackup).where(TripBackup.deleted_at.is_(None)).order_by(TripBackup.updated_at.desc())
    ).all()
    return [TripSummaryOut(id=r.id, title=r.title, revision=r.revision, updated_at=r.updated_at) for r in rows]


@router.get("/{trip_id}", response_model=TripBackupOut)
def get_trip(trip_id: str, db: Session = Depends(get_db)) -> TripBackupOut:
    row = db.get(TripBackup, trip_id)
    if row is None or row.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Trip backup not found")
    return _out(row)


@router.put("/{trip_id}", response_model=TripBackupOut)
def sync_trip(trip_id: str, payload: TripSyncIn, db: Session = Depends(get_db)) -> TripBackupOut:
    row = db.get(TripBackup, trip_id)
    if row is not None and row.last_mutation_id == payload.mutation_id:
        return _out(row)
    if row is not None and row.deleted_at is None and payload.base_revision != row.revision:
        raise HTTPException(
            status_code=409,
            detail={"message": "Trip backup has changed", "server_revision": row.revision},
        )
    if row is None:
        if payload.base_revision is not None:
            raise HTTPException(status_code=409, detail={"message": "Trip does not exist", "server_revision": None})
        row = TripBackup(
            id=trip_id, title=payload.title, payload=payload.payload,
            revision=1, last_mutation_id=payload.mutation_id,
        )
        db.add(row)
    else:
        row.title = payload.title
        row.payload = payload.payload
        row.revision += 1
        row.last_mutation_id = payload.mutation_id
        row.deleted_at = None
    db.commit()
    db.refresh(row)
    return _out(row)


@router.delete("/{trip_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_trip(trip_id: str, db: Session = Depends(get_db)) -> Response:
    row = db.get(TripBackup, trip_id)
    if row is not None and row.deleted_at is None:
        row.deleted_at = utcnow()
        row.revision += 1
        db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.put("/{trip_id}/attachments/{attachment_id}", status_code=status.HTTP_204_NO_CONTENT)
async def upload_attachment(
    trip_id: str,
    attachment_id: str,
    request: Request,
    x_filename: str = Header(default="attachment"),
    db: Session = Depends(get_db),
) -> Response:
    trip = db.get(TripBackup, trip_id)
    if trip is None or trip.deleted_at is not None:
        raise HTTPException(status_code=404, detail="Trip backup not found")
    content = await request.body()
    if not content:
        raise HTTPException(status_code=400, detail="Attachment is empty")
    if len(content) > 15 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Attachment exceeds 15 MB")
    row = db.get(TripAttachmentBackup, attachment_id)
    if row is None:
        row = TripAttachmentBackup(
            id=attachment_id, trip_id=trip_id, display_name=x_filename[:500],
            content_type=(request.headers.get("content-type") or "application/octet-stream")[:120],
            content=content,
        )
        db.add(row)
    else:
        if row.trip_id != trip_id:
            raise HTTPException(status_code=409, detail="Attachment belongs to another trip")
        row.display_name = x_filename[:500]
        row.content_type = (request.headers.get("content-type") or "application/octet-stream")[:120]
        row.content = content
    db.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{trip_id}/attachments/{attachment_id}")
def download_attachment(trip_id: str, attachment_id: str, db: Session = Depends(get_db)) -> StreamingResponse:
    row = db.get(TripAttachmentBackup, attachment_id)
    if row is None or row.trip_id != trip_id:
        raise HTTPException(status_code=404, detail="Attachment not found")
    return StreamingResponse(
        BytesIO(row.content), media_type=row.content_type,
        headers={"Content-Disposition": f'attachment; filename="{row.display_name.replace(chr(34), "")}"'},
    )
