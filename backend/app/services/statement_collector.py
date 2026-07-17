"""Collect monthly credit-card statement PDFs from email.

Flow per bank parser: search Gmail -> for each unseen statement email, download
the PDF attachment (gog), decrypt it, extract text, and persist only the
statement summary. Ordinary parsed line items deliberately do not enter
``transactions``; only EMI costs that live alerts commonly miss are retained.
"""

from __future__ import annotations

import hashlib
import logging
import os
import tempfile
from dataclasses import dataclass, field

from sqlalchemy.orm import Session

from app.models.card_statement import CardStatement
from app.repositories.expense_repo import ExpenseRepository
from app.repositories.statement_repo import StatementRepository
from app.services.expense_parsers.base import ParsedTxn
from app.services.gmail_client import GmailClient
from app.services.openclaw import OpenClawClient
from app.services.pdf_unlock import extract_pdf_text, parse_overrides, password_candidates
from app.services.statement_emi import select_emi_items
from app.services.statement_parsers import get_statement_parsers

logger = logging.getLogger(__name__)

_MAX_EMAILS_PER_BANK = 40  # generous cap; a multi-card, multi-month backfill still fits


@dataclass
class StatementSyncReport:
    banks_run: int
    emails_seen: int
    statements_new: int
    line_items_ingested: int
    line_items_deduped: int
    message: str
    statements: list[CardStatement] = field(default_factory=list)


class StatementCollector:
    def __init__(
        self,
        db: Session,
        client: GmailClient,
        owner_name: str,
        owner_dob: str,
        password_overrides: str = "",
        window_days: int = 40,
        emi_client: OpenClawClient | None = None,
    ) -> None:
        self.db = db
        self.client = client
        self.owner_name = owner_name
        self.owner_dob = owner_dob
        self.overrides = parse_overrides(password_overrides)
        self.window_days = window_days
        self.emi_client = emi_client
        self.repo = StatementRepository(db)
        self.txn_repo = ExpenseRepository(db)

    def sync(self) -> StatementSyncReport:
        if not self.client.is_available():
            return StatementSyncReport(0, 0, 0, 0, 0, "gog CLI unavailable or gmail_account unset")

        parsers = get_statement_parsers()
        emails_seen = new_stmts = parsed_line_items = ingested = 0
        created: list[CardStatement] = []

        for parser in parsers:
            query = f"{parser.gmail_query} newer_than:{self.window_days}d"
            for meta in self.client.search(query, max_results=_MAX_EMAILS_PER_BANK):
                message_id = meta.get("id")
                if not message_id:
                    continue
                emails_seen += 1
                if self.repo.has_message(message_id):
                    continue
                for att in self.client.list_pdf_attachments(message_id):
                    stmt = self._ingest_pdf(parser, message_id, att)
                    if stmt is None:
                        continue
                    new_stmts += 1
                    parsed_line_items += stmt.line_items_total
                    ingested += stmt.line_items_ingested
                    created.append(stmt)

        self.db.commit()
        return StatementSyncReport(
            banks_run=len(parsers),
            emails_seen=emails_seen,
            statements_new=new_stmts,
            line_items_ingested=ingested,
            line_items_deduped=0,
            message=(
                f"{new_stmts} new statement(s); summaries stored, {ingested} EMI item(s) ingested, "
                f"{parsed_line_items - ingested} other line item(s) ignored"
            ),
            statements=created,
        )

    # -- internals -----------------------------------------------------------

    def _ingest_pdf(self, parser, message_id: str, att: dict) -> CardStatement | None:
        with tempfile.TemporaryDirectory() as tmp:
            pdf_path = os.path.join(tmp, "stmt.pdf")
            if not self.client.download_attachment(message_id, att["attachmentId"], pdf_path):
                logger.warning("statement download failed: %s %s", parser.bank, message_id)
                return None
            pws = password_candidates(self.owner_name, self.owner_dob, self.overrides, parser.bank)
            text = extract_pdf_text(pdf_path, pws)
        if not text:
            logger.warning("statement unreadable (bad password?): %s %s", parser.bank, message_id)
            return None

        parsed = parser.parse(text, filename=att.get("filename", ""))
        if parsed.total_due is None and not parsed.line_items:
            logger.info("attachment parsed empty, skipping: %s %s", parser.bank, att.get("filename"))
            return None
        # Same card+date already stored (e.g. resent email) -> skip.
        if self.repo.get_existing(parsed.bank, parsed.card_last4, parsed.statement_date):
            return None

        stmt = self.repo.add_statement(parsed, message_id)

        kept = 0
        for item in select_emi_items(parsed.line_items, self.emi_client):
            account = f"{parsed.card_label} ..{parsed.card_last4 or '????'}"
            if self.txn_repo.live_coverage_exists(
                account, item.txn_date, item.amount, item.direction, item.description
            ):
                continue
            txn = ParsedTxn(
                txn_date=item.txn_date,
                amount=item.amount,
                direction=item.direction,
                source="statement_emi",
                account=account,
                counterparty=item.description[:256],
                raw_snippet=item.description[:512],
            )
            basis = f"{parsed.bank}|{parsed.card_last4}|{item.txn_date}|{item.amount}|{item.description}"
            key = "stmt_emi:" + hashlib.sha256(basis.encode()).hexdigest()[:24]
            if self.txn_repo.upsert(txn, message_id=message_id, dedup_key=key) is not None:
                kept += 1

        stmt.line_items_ingested = kept
        self.db.flush()
        return stmt
