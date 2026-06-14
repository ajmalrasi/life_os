from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.database import get_db
from app.schemas.chat import ChatRequest, ChatResponse
from app.services.chat_service import ChatService
from app.services.openclaw import OpenClawClient

router = APIRouter(prefix="/chat", tags=["chat"])


@router.post("", response_model=ChatResponse)
def chat(req: ChatRequest, db: Session = Depends(get_db)) -> ChatResponse:
    """Ask LifeOS a question. The answer is grounded in your data and cites metrics."""
    return ChatService(db).ask(req)


@router.get("/health")
def openclaw_health() -> dict[str, bool | str]:
    client = OpenClawClient()
    return {"openclaw_base_url": client.base_url, "reachable": client.health()}
