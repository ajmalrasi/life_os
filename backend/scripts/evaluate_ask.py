"""Read-only live Ask smoke evaluation. Run against an isolated code checkout.

Prints personal answer text to the invoking terminal; does not write to the DB.
"""
import json
from time import monotonic

from app.database import SessionLocal
from app.schemas.chat import ChatMessage, ChatRequest
from app.services.chat_service import ChatService
from app.services.chat_suggestions import _refresh, _cache, IST, datetime


def main():
    questions = [
        "How much did I spend today?",
        "How much did I spend on food last month?",
        "Compare my spending this month with last month.",
        "How was my sleep last night?",
        "Write a short Python function for Fibonacci numbers.",
        "Explain the time complexity of that function.",
    ]
    history = []
    with SessionLocal() as db:
        service = ChatService(db)
        for question in questions:
            started = monotonic()
            response = service.ask(ChatRequest(message=question, history=history))
            print(json.dumps({"question": question, "seconds": round(monotonic()-started, 1),
                              **response.model_dump()}, ensure_ascii=False), flush=True)
            if not response.error:
                history.extend([ChatMessage(role="user", content=question),
                                ChatMessage(role="assistant", content=response.answer)])
        key = (30, datetime.now(IST).date().isoformat())
        _refresh(db.get_bind(), key)
        print(json.dumps({"suggestions": _cache.get(key, (0, []))[1]}), flush=True)


if __name__ == "__main__":
    main()
