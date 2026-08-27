from __future__ import annotations

import json

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.chat import ChatRequest, ChatResponse, SuggestionsResponse
from app.services.chat_service import ChatService
from app.services.openclaw import OpenClawClient

router = APIRouter(prefix="/chat", tags=["chat"])


@router.post("", response_model=ChatResponse)
def chat(req: ChatRequest, db: Session = Depends(get_db)) -> ChatResponse:
    """Ask LifeOS a question. The answer is grounded in your data and cites metrics."""
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
def suggestions(days: int = 30, db: Session = Depends(get_db)) -> SuggestionsResponse:
    """Data-grounded starter questions for the Ask screen (falls back to defaults)."""
    return SuggestionsResponse(questions=ChatService(db).suggest(days))


@router.get("/health")
def openclaw_health() -> dict[str, bool | str | int | None]:
    client = OpenClawClient()
    return {
        "openclaw_base_url": client.base_url,
        "reachable": client.health(),
        **client.admission_status(),
    }
