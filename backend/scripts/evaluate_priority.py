"""Run the model-led Priority classifier over a local email corpus.

The corpus is a JSON list of ``MailMessage`` fields (plus optional raw ``ics``
text), e.g. dumped from the Priority mailbox. Nothing is written to the
database; results print as a table and optionally save as JSON for review.

    OPENCLAW_BASE_URL=http://192.168.3.30:11434 \\
      python scripts/evaluate_priority.py corpus.json --out results.json

Inspect the actual categories, states, and times — HTTP success is not the
same as a correct classification.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.mail.models import MailAttachment, MailMessage  # noqa: E402
from app.priority.calendar import parse_ics  # noqa: E402
from app.priority.classifier import PriorityModelUnavailable, classify  # noqa: E402

_IST = ZoneInfo("Asia/Kolkata")


def _message(raw: dict) -> MailMessage:
    return MailMessage(
        message_id=raw["message_id"],
        subject=raw.get("subject", ""),
        from_addr=raw.get("from_addr", ""),
        received=raw.get("received", ""),
        text=raw.get("text", ""),
        raw_body=raw.get("raw_body", ""),
        internal_date_ms=int(raw.get("internal_date_ms") or 0),
        thread_id=raw.get("thread_id"),
        to_addrs=raw.get("to_addrs", ""),
        cc_addrs=raw.get("cc_addrs", ""),
        headers=raw.get("headers") or {},
        label_ids=tuple(raw.get("label_ids") or ()),
        snippet=raw.get("snippet", ""),
        attachments=tuple(MailAttachment(**item) for item in raw.get("attachments") or ()),
    )


def _run(raw: dict) -> dict:
    message = _message(raw)
    invite = parse_ics(raw.get("ics") or "") or parse_ics(message.raw_body)
    started = time.monotonic()
    try:
        result = classify(message, invite, account_address=raw.get("account"))
    except PriorityModelUnavailable as exc:
        return {"message_id": message.message_id, "subject": message.subject, "error": str(exc)}
    details = result.details

    def ist(value):
        return value.astimezone(_IST).strftime("%m-%d %H:%M") if value else None

    return {
        "message_id": message.message_id,
        "subject": message.subject,
        "sender": message.from_addr,
        "importance": result.importance,
        "category": result.labels[0] if result.labels else None,
        "state": result.state,
        "confidence": result.confidence,
        "summary": result.summary,
        "reason": result.why_important,
        "action": details.action_text,
        "due": ist(details.due_at),
        "event_start": ist(details.event_start),
        "event_end": ist(details.event_end),
        "company": details.company,
        "role": details.role,
        "seconds": round(time.monotonic() - started, 1),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("corpus")
    parser.add_argument("--out")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    corpus = json.loads(Path(args.corpus).read_text())[: args.limit]
    started = time.monotonic()
    with ThreadPoolExecutor(args.workers) as pool:
        results = list(pool.map(_run, corpus))
    for row in results:
        if "error" in row:
            print(f"ERROR  {row['subject'][:70]}  {row['error']}")
            continue
        print(
            f"{(row['category'] or '-'):9} {row['state']:13} {row['event_start'] or '':11} "
            f"{row['subject'][:70]}"
        )
    kept = [row for row in results if row.get("category")]
    print(
        f"\n{len(results)} emails, {len(kept)} priority, "
        f"{sum('error' in row for row in results)} errors, {time.monotonic() - started:.0f}s"
    )
    if args.out:
        Path(args.out).write_text(json.dumps(results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
