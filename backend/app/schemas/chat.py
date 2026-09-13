from __future__ import annotations

from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: str = Field(..., description="'user' or 'assistant'")
    content: str


class ChatRequest(BaseModel):
    message: str
    history: list[ChatMessage] = Field(default_factory=list)
    # Default window offered to the model, not an automatic data prefetch.
    context_days: int = Field(default=30, ge=1, le=365)


class Citation(BaseModel):
    metric: str
    value: str
    source_id: str | None = None
    label: str | None = None
    retrieved_at: str | None = None


class ChatResponse(BaseModel):
    answer: str
    citations: list[Citation] = Field(default_factory=list)
    grounded: bool = True
    error: str | None = None
    incomplete: bool = False


class SuggestionsResponse(BaseModel):
    questions: list[str] = Field(default_factory=list)
