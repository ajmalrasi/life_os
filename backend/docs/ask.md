# Model-led Ask

Every completed answer comes from local Qwen. One conversation retains recent
history across topics. Personal records enter only through optional tool calls.
There is no keyword routing, natural-language query planner, deterministic
answer formatter, or factual fallback response.

## Tools and evidence

Existing money, health, coding, live phone, and read-only GitHub tools remain.
`get_life_context(days)` exposes the existing overview. Money queries preserve
reconciliation exclusions and category overrides; Ask includes EMI even if the
Money screen's display filter hides it. Optional comparison dates return exact
difference/percentage fields. A zero baseline has no defined percentage.
Comparison groups cover only groups in both returned lists; omissions are unknown.

Tool responses include date windows, source metadata and coverage information.
Nested detail is bounded with explicit truncation; aggregate totals survive.
The UI labels source chips **Retrieved sources**, not verified claims.
`grounded=true` means a successful read occurred during this request; tool errors
and conversation-only responses do not set it.

## Runtime and protocol

The verified server is TensorRT EdgeLLM with Qwen3.5-4B, 6,144 input tokens,
8,192 total tokens and one active generation. Token counts use its
`/v1/messages/count_tokens` endpoint, which renders the same chat template.
Default Ask limits: 6,000 input with 128-token margin; 1,024 output capped at
2,048; two tool rounds (up to four calls each), then a final generation;
90 seconds per call and 135 seconds overall. Old complete exchanges are removed
before tool detail is compacted. Oversized required context produces an error.
Internal thinking is off by default; `ASK_ENABLE_THINKING` is optional.
Both generation and token counting use the same thinking setting.

`POST /chat` and `POST /chat/stream` share the same implementation.
Existing request fields and start/delta/done events remain. Added status/error
events carry progress and retryable failures; done includes incomplete.
Nonstream responses add nullable error and incomplete. Citations add nullable
source_id, label, and retrieved_at. Old clients can ignore the additive fields.
Failures never become canned personal summaries; partial text remains visibly
incomplete. Disconnects cancel model work, and absolute deadlines interrupt
stalled reads. Tool workers own separate read-only database sessions.

Suggestions return cached results immediately and refresh at background priority:
four model-generated questions, 15-second/256-token generation, six-hour cache
per IST date and context window. Refresh admission fails fast behind interactive
work. Cache misses and failures return an empty list, never static questions.
Android checks again once after 20 seconds. No database migration is needed.

## Verification and limitations

Run the backend suite with its isolated SQLite fixture; external model/GitHub
access is disabled there. Use `PYTHONPATH=. python scripts/evaluate_ask.py`
separately for read-only live evaluation. It prints personal answer text to the
invoking terminal and does not modify records. Check query windows, scope,
amounts, missing-data handling, general questions, follow-ups and suggestions.

Live testing exposed skipped retrieval, wrong calendar windows, invented values,
incorrect percentages, and incorrect phone setup/code details. Calendar metadata,
stronger evidence instructions, and tool-computed comparisons address observed
failure modes, but optional model tool use cannot guarantee correct answers.
An experimental thinking-enabled run was slower and did not reliably eliminate
errors, so thinking remains off by default.

The full suite has four pre-existing date-sensitive failures, reproduced on the
unchanged revision: one current-card-cycle test, one Priority list test, and two
statement reconciliation/trend-window tests. They are outside this Ask change.
Android builds and unit tests pass. Physical-device installation/visual QA
requires the Samsung device to be connected.
