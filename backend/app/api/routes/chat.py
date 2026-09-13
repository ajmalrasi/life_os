from __future__ import annotations

import json

from fastapi import APIRouter, Depends, Query
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.chat import ChatRequest, ChatResponse, SuggestionsResponse
from app.services.chat_service import ChatService
from app.services.openclaw import OpenClawClient

router = APIRouter(prefix="/chat", tags=["chat"])


@router.post("", response_model=ChatResponse)
def chat(req: ChatRequest, db: Session = Depends(get_db)) -> ChatResponse:
    """Ask Qwen, optionally retrieving personal evidence through tools."""
    return ChatService(db).ask(req)


@router.post("/stream")
def stream_chat(req: ChatRequest, db: Session = Depends(get_db)) -> StreamingResponse:
    """Stream answer deltas as NDJSON; the final event carries citations."""
    service = ChatService(db)

    def events():
        for event in service.stream(req):
            yield json.dumps(event, separators=(",", ":")) + "\n"

    return StreamingResponse(
        events(),
        media_type="application/x-ndjson",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.get("/suggestions", response_model=SuggestionsResponse)
def suggestions(days: int = Query(30, ge=1, le=365), db: Session = Depends(get_db)) -> SuggestionsResponse:
    """Return cached model-generated starters; refresh in the background."""
    from app.services.chat_suggestions import suggestions as cached_suggestions
    return SuggestionsResponse(questions=cached_suggestions(db, days))


@router.get("/health")
def openclaw_health() -> dict[str, bool | str | int | None]:
    client = OpenClawClient()
    return {
        "openclaw_base_url": client.base_url,
        "reachable": client.health(),
        **client.admission_status(),
    }
