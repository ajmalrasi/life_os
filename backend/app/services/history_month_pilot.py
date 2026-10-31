"""Private, resumable, read-only one-month email parsing pilot.

This module deliberately parses into private JSON artifacts. It never writes
transactions to LifeOS's database or advances a normal collector cursor.
"""
from __future__ import annotations

import hashlib
import inspect
import json
import os
import tempfile
import time as clock
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Lock
from collections import Counter
from dataclasses import asdict
from datetime import date, datetime, time, timedelta
from decimal import Decimal
from email.utils import parseaddr
from pathlib import Path
from zoneinfo import ZoneInfo

from app.mail.models import MailAttachment, MailMessage
from app.mail.gmail_provider import MailRateLimitError
from app.services.expense_parsers import get_parsers
from app.services.expense_parsers.base import ParsedTxn, ParserUnavailable
from app.services.expense_parsers.llm_bank_parser import LlmBankParser

PRIMARY_ACCOUNT = "mail2ajmalrasi@gmail.com"
IST = ZoneInfo("Asia/Kolkata")
VERSION = 1
# Historical aliases plus current domains. Boundaries are exact DNS labels.
FINANCIAL_DOMAINS = {
    "HDFC": ("hdfcbank.net", "hdfcbank.com", "hdfcbank.bank.in"),
    "ICICI": ("icicibank.com", "icici.bank.in"),
    "Axis": ("axisbank.com", "axis.bank.in"),
    "SBI": ("sbicard.com", "alerts.sbi.co.in", "alerts.sbi.bank.in"),
}


def month_bounds(month: str) -> tuple[datetime, datetime]:
    try:
        first = date.fromisoformat(month + "-01")
        if first.isoformat() != month + "-01":
            raise ValueError
    except ValueError as exc:
        raise ValueError("month must be YYYY-MM") from exc
    following = (first.replace(day=28) + timedelta(days=4)).replace(day=1)
    return (datetime.combine(first, time.min, IST),
            datetime.combine(following, time.min, IST))


def sender_bank(sender: str) -> str:
    domain = parseaddr(sender)[1].lower().rsplit("@", 1)[-1]
    for bank, domains in FINANCIAL_DOMAINS.items():
        if any(domain == candidate or domain.endswith("." + candidate)
               for candidate in domains):
            return bank
    return ""


def _atomic_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path.parent, 0o700)
    fd, tmp = tempfile.mkstemp(prefix=".pilot-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(value, out, ensure_ascii=False, indent=2, sort_keys=True)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def _hash(value: object) -> str:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True,
                     separators=(",", ":"), default=str).encode()
    return hashlib.sha256(raw).hexdigest()


def _file_fingerprint(value) -> str:
    path = Path(inspect.getsourcefile(value) or __file__)
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError:
        return "unavailable"


def _txn_dict(txn: ParsedTxn) -> dict:
    result = asdict(txn)
    result["txn_date"] = txn.txn_date.isoformat()
    result["amount"] = str(txn.amount)
    result["txn_at"] = txn.txn_at.isoformat() if txn.txn_at else None
    return result


def _message_dict(msg: MailMessage) -> dict:
    return {
        "message_id": msg.message_id, "thread_id": msg.thread_id,
        "subject": msg.subject, "from_addr": msg.from_addr,
        "to_addrs": msg.to_addrs, "cc_addrs": msg.cc_addrs,
        "received": msg.received, "internal_date_ms": msg.internal_date_ms,
        "text": msg.text, "raw_body": msg.raw_body,
        "headers": msg.headers, "label_ids": list(msg.label_ids),
        "snippet": msg.snippet,
        "attachments": [asdict(a) for a in msg.attachments],
    }


def _message_from_dict(d: dict) -> MailMessage:
    return MailMessage(
        message_id=d["message_id"], subject=d.get("subject", ""),
        from_addr=d.get("from_addr", ""), received=d.get("received", ""),
        text=d.get("text", ""), raw_body=d.get("raw_body", ""),
        internal_date_ms=int(d.get("internal_date_ms") or 0),
        thread_id=d.get("thread_id"), to_addrs=d.get("to_addrs", ""),
        cc_addrs=d.get("cc_addrs", ""), headers=d.get("headers") or {},
        label_ids=tuple(d.get("label_ids") or ()), snippet=d.get("snippet", ""),
        attachments=tuple(MailAttachment(**a) for a in d.get("attachments", [])),
    )


class MonthPilot:
    def __init__(self, output_dir: Path, month: str, account: str, *,
                 no_llm: bool = False, parsers=None, workers: int = 1) -> None:
        if workers not in (1, 2):
            raise ValueError("workers must be 1 or 2")
        if account.strip().lower() != PRIMARY_ACCOUNT:
            raise ValueError(f"This pilot is restricted to {PRIMARY_ACCOUNT}")
        self.start, self.end = month_bounds(month)
        self.month, self.account = month, PRIMARY_ACCOUNT
        self.output = Path(output_dir).expanduser().resolve()
        self.no_llm = no_llm
        self.workers = workers
        self.parsers = list(parsers if parsers is not None else get_parsers())
        self.llm = next((p for p in self.parsers if isinstance(p, LlmBankParser)), None)
        self.deterministic = [p for p in self.parsers if p is not self.llm]
        self.parser_fingerprint = _hash({"version": VERSION, "no_llm": no_llm,
                                         "workers": workers,
                                         "helper": _file_fingerprint(MonthPilot),
                                         "parsers": {p.name: _file_fingerprint(p.__class__)
                                                     for p in self.parsers}})
        self.state_path = self.output / "checkpoint.json"
        self.pages_dir = self.output / "pages"
        self.messages_dir = self.output / "messages"
        self.results_dir = self.output / "results"
        self.query = (f"after:{int(self.start.timestamp()) - 1} "
                      f"before:{int(self.end.timestamp())}")
        self.state = self._load_state()
        self._read_lock = Lock()
        self._next_read = 0.0

    def _load_state(self) -> dict:
        if self.state_path.exists():
            state = json.loads(self.state_path.read_text())
            if (state.get("account"), state.get("month"), state.get("query")) != (
                    self.account, self.month, self.query):
                raise ValueError("Saved checkpoint belongs to a different account or month")
            if state.get("version") != VERSION:
                raise ValueError("Saved checkpoint version differs")
            return state
        return {"version": VERSION, "account": self.account, "month": self.month,
                "query": self.query, "next_page_token": None, "search_complete": False,
                "seen_tokens": [], "messages": {}, "search_error": None}

    def _save_state(self) -> None:
        _atomic_json(self.state_path, self.state)

    def _read(self, operation, *, interval: float):
        for attempt in range(3):
            while True:
                with self._read_lock:
                    delay = self._next_read - clock.monotonic()
                    if delay <= 0:
                        self._next_read = clock.monotonic() + interval
                        break
                clock.sleep(min(delay, 30))
            try:
                return operation()
            except MailRateLimitError:
                if attempt == 2:
                    raise
                with self._read_lock:
                    self._next_read = max(self._next_read, clock.monotonic() + 60)

    def collect(self, provider, progress=lambda _: None) -> None:
        if getattr(provider, "account", "").strip().lower() != self.account:
            raise ValueError(f"Provider account must be exactly {self.account}")
        self.output.mkdir(parents=True, exist_ok=True, mode=0o700)
        token = self.state.get("next_page_token")
        while not self.state["search_complete"]:
            try:
                rows, next_token = self._read(
                    lambda: provider.search_page(self.query, page_token=token, page_size=50), interval=3.0)
                page = {"query": self.query, "page_token": token,
                        "next_page_token": next_token, "rows": rows}
                name = "first" if token is None else _hash(token)[:20]
                _atomic_json(self.pages_dir / f"{name}.json", page)
                for row in rows:
                    mid = row["id"]
                    prior = self.state["messages"].get(mid, {})
                    prior.update({"metadata": row, "search_seen": True})
                    self.state["messages"][mid] = prior
                if next_token and (next_token == token or next_token in self.state["seen_tokens"]):
                    raise RuntimeError("Gmail repeated a page token; search remains incomplete")
                if token:
                    self.state["seen_tokens"].append(token)
                self.state["next_page_token"] = next_token
                self.state["search_complete"] = next_token is None
                self.state["search_error"] = None
                self._save_state()
                progress(f"Search page saved; {len(self.state['messages'])} unique messages")
                token = next_token
            except Exception as exc:
                self.state["search_error"] = f"{type(exc).__name__}: {exc}"
                self._save_state()
                break

        items = list(self.state["messages"].items())
        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            futures = {pool.submit(self._process_one, mid, dict(record), provider): mid
                       for mid, record in items}
            for index, future in enumerate(as_completed(futures), 1):
                mid = futures[future]
                self.state["messages"][mid] = future.result()
                self._save_state()
                if index % 25 == 0:
                    progress(f"Processed {index}/{len(items)} mailbox emails")
        self._save_state()
        self.write_report()

    def _process_one(self, mid: str, record: dict, provider) -> dict:
        """Process one ID in isolation; only the caller mutates the checkpoint."""
        meta = record.get("metadata") or {}
        sender = meta.get("from", meta.get("sender", "")) or ""
        if not sender_bank(sender):
            record["status"] = "unrelated_metadata_only"
            return record
        cached = self.messages_dir / f"{_hash(mid)[:32]}.json"
        try:
            if not cached.exists():
                msg = self._read(lambda: provider.get_message(mid), interval=0.2)
                if msg is None:
                    raise RuntimeError("provider returned no message")
                _atomic_json(cached, _message_dict(msg))
            msg_data = json.loads(cached.read_text())
            msg_hash = _hash(msg_data)
            record["sender"] = msg_data.get("from_addr", sender)
            record["subject"] = msg_data.get("subject", meta.get("subject", ""))
            bank = sender_bank(record["sender"])
            if not bank:
                record.update(status="sender_not_allowed", source_hash=msg_hash)
                return record
            ms = int(msg_data.get("internal_date_ms") or 0)
            if ms and not (int(self.start.timestamp() * 1000) <= ms < int(self.end.timestamp() * 1000)):
                record.update(status="outside_received_window", source_hash=msg_hash)
                return record
            record.update(source_hash=msg_hash, bank=bank,
                          attachment_metadata=msg_data.get("attachments", []))
            result_path = self.results_dir / f"{_hash(mid)[:32]}.json"
            existing = json.loads(result_path.read_text()) if result_path.exists() else {}
            if (existing.get("source_hash") == msg_hash and
                    existing.get("parser_fingerprint") == self.parser_fingerprint and
                    existing.get("status") not in ("retryable_error", "pending_llm")):
                record["status"] = existing["status"]
                return record
            mail = _message_from_dict(msg_data)
            if "statement" in mail.subject.casefold():
                record["status"] = "statement_body_not_parsed_attachment_pending"
                self._write_result(mid, msg_hash, record["status"], "", [])
                return record
            parsed, chosen = [], ""
            for parser in self.deterministic:
                try:
                    found = parser.parse(mail)
                except ParserUnavailable:
                    raise
                except Exception as exc:
                    record.update(status="retryable_error", error=f"{parser.name}: {exc}")
                    break
                if found:
                    parsed, chosen = found, parser.name
                    break
            else:
                if self.no_llm or self.llm is None:
                    record["status"] = "pending_llm"
                else:
                    try:
                        parsed = self.llm.parse(mail)
                        chosen = self.llm.name
                        record["status"] = "llm_no_rows_returned" if not parsed else "parsed"
                    except ParserUnavailable as exc:
                        record.update(status="retryable_error", error=str(exc))
            if parsed:
                record["status"] = "parsed"
            elif record.get("status") not in ("retryable_error", "pending_llm", "llm_no_rows_returned"):
                record["status"] = "no_regex_match"
            self._write_result(mid, msg_hash, record["status"], chosen, parsed)
        except Exception as exc:
            record.update(status="retryable_error", error=f"{type(exc).__name__}: {exc}")
        return record

    def _write_result(self, mid: str, source_hash: str, status: str,
                      parser_name: str, txns: list[ParsedTxn]) -> None:
        _atomic_json(self.results_dir / f"{_hash(mid)[:32]}.json", {
            "message_id": mid, "source_hash": source_hash,
            "parser_fingerprint": self.parser_fingerprint, "status": status,
            "parser": parser_name, "transactions": [_txn_dict(t) for t in txns],
        })

    def report(self) -> dict:
        counts = Counter(r.get("status", "unprocessed") for r in self.state["messages"].values())
        txns, parsed_emails = [], 0
        for path in self.results_dir.glob("*.json") if self.results_dir.exists() else ():
            result = json.loads(path.read_text())
            record = self.state["messages"].get(result.get("message_id"), {})
            if (record.get("source_hash") != result.get("source_hash") or
                    result.get("parser_fingerprint") != self.parser_fingerprint):
                continue
            if result.get("status") == "parsed":
                parsed_emails += 1
            txns.extend(result.get("transactions", []))
        inside = [t for t in txns if self.start.date().isoformat() <= t["txn_date"] < self.end.date().isoformat()]
        def sums(rows):
            out = Counter()
            for row in rows:
                out[(row.get("currency", ""), row.get("direction", ""))] += Decimal(row["amount"])
            return {f"{currency}:{direction}": str(amount) for (currency, direction), amount in sorted(out.items())}
        return {
            "account": self.account, "month": self.month,
            "received_window_ist": [self.start.isoformat(), self.end.isoformat()],
            "search_complete": self.state.get("search_complete", False),
            "search_error": self.state.get("search_error"),
            "unique_mail_messages": len(self.state["messages"]),
            "outcomes": dict(counts), "parsed_message_count": parsed_emails,
            "parsed_transaction_rows": len(txns),
            "transaction_rows_dated_in_month": len(inside),
            "transaction_rows_outside_month": len(txns) - len(inside),
            "gross_by_currency_and_direction_all_dates": sums(txns),
            "gross_by_currency_and_direction_in_month": sums(inside),
            "attachments_metadata_seen": sum(len(r.get("attachment_metadata", [])) for r in self.state["messages"].values()),
            "attachments_downloaded_or_parsed": 0,
            "caveat": "Parsed email alerts only; statement attachments were not downloaded or parsed. Gross debit/credit totals are not net spending and may include transfers, repayments, reversals, or duplicate economic events.",
        }

    def write_report(self) -> None:
        report = self.report()
        _atomic_json(self.output / "report.json", report)
        rows = ["# Month email parsing pilot", "", f"- Account: {report['account']}",
                f"- Received window (IST): {report['received_window_ist'][0]} through (exclusive) {report['received_window_ist'][1]}",
                f"- Gmail pagination complete: {report['search_complete']}",
                f"- Unique mailbox emails: {report['unique_mail_messages']}",
                f"- Outcomes: `{json.dumps(report['outcomes'], sort_keys=True)}`",
                f"- Parsed transaction rows: {report['parsed_transaction_rows']} ({report['transaction_rows_dated_in_month']} dated inside month; {report['transaction_rows_outside_month']} outside)",
                f"- Gross by currency/direction, all dates: `{json.dumps(report['gross_by_currency_and_direction_all_dates'], sort_keys=True)}`",
                f"- Gross by currency/direction, in month: `{json.dumps(report['gross_by_currency_and_direction_in_month'], sort_keys=True)}`",
                f"- Attachment metadata records: {report['attachments_metadata_seen']}; downloaded/parsed: 0", "",
                f"Caveat: {report['caveat']}", ""]
        (self.output / "report.md").write_text("\n".join(rows), encoding="utf-8")
