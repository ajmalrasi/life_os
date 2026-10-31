"""Guarded, explicitly invoked import of a reviewed private history snapshot.

Planning is read-only. Applying inserts only new rows, in one transaction, and
never edits existing transactions. The plan binds both the source snapshot and
the current ledger so a stale dry run cannot be applied silently.
"""
from __future__ import annotations

import hashlib
import json
import re
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.account_identity import normalize_account
from app.models.transaction import Transaction
from app.models.transaction_adjustment import TransactionAdjustment
from app.models.transaction_reconciliation import TransactionReconciliation
from app.repositories.expense_repo import _statement_party_norm
from app.services.reconcile import (
    _TRANSFER_CREDIT_PAT,
    _TRANSFER_DEBIT_PAT,
    _is_self,
    _owner_identities,
)

PRIMARY_ACCOUNT = "mail2ajmalrasi@gmail.com"
EXCLUDED_AMBIGUOUS_MESSAGE = "19e24565fd4849bd"
PLAN_VERSION = 1
_MONTH_RE = re.compile(r"^\d{4}-\d{2}$")
_CARD_PAYMENT_RECEIPT = re.compile(
    r"\breceived payment of\b[\s\S]{0,100}\bcredit card account\b", re.I
)
_ISSUER_REVERSAL_CREDIT = re.compile(
    r"\ba transaction reversal of\b[\s\S]{0,200}\bhas been initiated\b", re.I
)


def _json_hash(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":"), default=str).encode()).hexdigest()


def _month_bounds(month: str) -> tuple[date, date]:
    if not _MONTH_RE.fullmatch(month):
        raise ValueError("month must be YYYY-MM")
    try:
        first = date.fromisoformat(month + "-01")
    except ValueError as exc:
        raise ValueError("month must be a valid YYYY-MM") from exc
    following = (first.replace(day=28) + timedelta(days=4)).replace(day=1)
    return first, following


def _row_dict(row: Transaction) -> dict[str, Any]:
    txn_at = row.txn_at
    if txn_at and txn_at.tzinfo:
        txn_at = txn_at.astimezone(timezone.utc).replace(tzinfo=None)
    created_at = row.created_at
    if created_at and created_at.tzinfo:
        created_at = created_at.astimezone(timezone.utc).replace(tzinfo=None)
    return {
        "id": row.id, "txn_date": row.txn_date.isoformat(),
        "txn_at": txn_at.isoformat() if txn_at else None,
        "amount": str(row.amount), "currency": row.currency, "direction": row.direction,
        "counterparty": row.counterparty, "category": row.category,
        "spend_category_override": row.spend_category_override,
        "account": row.account, "source": row.source, "reference": row.reference,
        "gmail_message_id": row.gmail_message_id, "dedup_key": row.dedup_key,
        "raw_snippet": row.raw_snippet,
        "created_at": created_at.isoformat() if created_at else None,
    }


def _all_db_snapshot(db: Session, month: str) -> tuple[str, list[dict[str, Any]], int, str]:
    rows = db.execute(select(Transaction).order_by(Transaction.id)).scalars().all()
    snapshot = [_row_dict(row) for row in rows]
    first, following = _month_bounds(month)
    may_rows = [r for r in snapshot if first.isoformat() <= r["txn_date"] < following.isoformat()]
    outside = [r for r in snapshot if not first.isoformat() <= r["txn_date"] < following.isoformat()]
    return _json_hash(snapshot), may_rows, len(outside), _json_hash(outside)


def _validate_and_normalize(row: dict[str, Any], month: str) -> dict[str, Any]:
    first, following = _month_bounds(month)
    try:
        txn_date = date.fromisoformat(str(row["txn_date"]))
        amount = Decimal(str(row["amount"]))
    except (KeyError, ValueError, InvalidOperation) as exc:
        raise ValueError("each row needs a valid date and decimal amount") from exc
    if not first <= txn_date < following:
        raise ValueError(f"transaction date {txn_date} is outside bounded month {month}")
    if not amount.is_finite() or amount <= 0 or amount.as_tuple().exponent < -2:
        raise ValueError("amount must be positive with at most two decimal places")
    direction = str(row.get("direction", "")).lower()
    if direction not in {"debit", "credit"}:
        raise ValueError("direction must be debit or credit")
    currency = str(row.get("currency", "INR")).upper()
    if currency != "INR":
        raise ValueError("non-INR rows are outside this approved import")
    message_id = str(row.get("message_id", "")).strip()
    if not message_id or len(message_id) > 64:
        raise ValueError("each row needs a Gmail message ID")
    account = normalize_account(str(row.get("account") or "").strip())
    if not account or len(account) > 64:
        raise ValueError("each row needs a supported account label")
    source = str(row.get("source") or "").strip()
    if not source or len(source) > 32:
        raise ValueError("each row needs a valid source")
    reference = str(row.get("reference") or "").strip() or None
    if reference and len(reference) > 64:
        raise ValueError("reference exceeds transaction field capacity")
    txn_at = row.get("txn_at")
    if txn_at:
        try:
            txn_at = datetime.fromisoformat(str(txn_at))
        except ValueError as exc:
            raise ValueError("txn_at must be an explicit ISO datetime") from exc
        if txn_at.tzinfo:
            txn_at = txn_at.astimezone(timezone.utc).replace(tzinfo=None)
    # Preserve a compact, inspectable provenance pointer without storing the
    # model's full response or any API credential.
    raw_snippet = json.dumps({
        "history_import": "may-2026-deepseek-reviewed",
        "source_hash": row.get("source_hash", ""),
        "parser": row.get("parser", ""),
        "review_note": row.get("review_note", ""),
    }, ensure_ascii=False, sort_keys=True)[:512]
    # The private pilot result's exact source excerpt is attached by the CLI
    # only after both cached message and result hashes have been checked.
    if row.get("raw_snippet"):
        raw_snippet = str(row["raw_snippet"])[:512]
    return {
        "txn_date": txn_date.isoformat(), "txn_at": txn_at.isoformat() if txn_at else None,
        "amount": str(amount.quantize(Decimal("0.01"))), "currency": currency,
        "direction": direction, "counterparty": (str(row["counterparty"])[:256]
                          if row.get("counterparty") else None),
        "account": account, "source": source, "reference": reference,
        "gmail_message_id": message_id, "raw_snippet": raw_snippet,
    }


def _strong_match(candidate: dict[str, Any], existing: dict[str, Any]) -> bool:
    identity = ("txn_date", "amount", "currency", "direction", "account")
    exact = all(str(candidate[k]) == str(existing[k]) for k in identity if k != "account")
    exact = exact and _same_account(candidate["account"], existing["account"])
    same_message = (candidate["gmail_message_id"] == existing["gmail_message_id"] and
                    candidate["reference"] == existing["reference"])
    same_reference = (bool(candidate["reference"])
                      and candidate["reference"] == existing["reference"]
                      and _same_card_instrument(candidate["account"], existing["account"]))
    return exact and (same_message or same_reference)


def _same_account(left: str | None, right: str | None) -> bool:
    """Match canonical labels or same-bank labels with the same full last four.

    Labels with no suffix only match exactly; a missing suffix is never guessed.
    """
    left_n, right_n = normalize_account(left), normalize_account(right)
    if not left_n or not right_n:
        return left_n == right_n
    if left_n.casefold() == right_n.casefold():
        return True
    left_parts, right_parts = left_n.split(), right_n.split()
    suffix = re.compile(r"\.\.\s*(\d{4})$")
    left_match, right_match = suffix.search(left_n), suffix.search(right_n)
    return bool(left_match and right_match and left_parts and right_parts
                and left_parts[0].casefold() == right_parts[0].casefold()
                and left_match.group(1) == right_match.group(1))


def _same_card_instrument(left: str | None, right: str | None) -> bool:
    """Require bank and an explicitly printed full four-digit instrument suffix."""
    left_n, right_n = normalize_account(left), normalize_account(right)
    if not left_n or not right_n:
        return False
    left_parts, right_parts = left_n.split(), right_n.split()
    suffix = re.compile(r"\.\.\s*(\d{4})$")
    left_match, right_match = suffix.search(left_n), suffix.search(right_n)
    return bool(left_match and right_match and left_parts and right_parts
                and left_parts[0].casefold() == right_parts[0].casefold()
                and left_match.group(1) == right_match.group(1))


def _statement_party_overlap(left: str | None, right: str | None) -> bool:
    def compact(value: str | None) -> str:
        normalized = _statement_party_norm(value)
        normalized = re.sub(r"^(?:upi\s+)+", "", normalized, flags=re.I)
        return re.sub(r"[^a-z]+", "", normalized.casefold())

    left_party, right_party = compact(left), compact(right)
    return bool(min(len(left_party), len(right_party)) >= 4 and
                (left_party in right_party or right_party in left_party))


def _statement_overlap(candidate: dict[str, Any], existing_rows: list[dict[str, Any]]) -> bool:
    statement_sources = {"cc_statement", "bank_account", "statement_emi"}
    return any(
        existing.get("source") in statement_sources
        and existing.get("txn_date") == candidate["txn_date"]
        and existing.get("amount") == candidate["amount"]
        and existing.get("currency") == candidate["currency"]
        and existing.get("direction") == candidate["direction"]
        and _same_card_instrument(candidate.get("account"), existing.get("account"))
        and _statement_party_overlap(candidate.get("counterparty"), existing.get("counterparty"))
        for existing in existing_rows
    )


def _is_transfer(candidate: dict[str, Any], handles: set[str], names: set[str]) -> bool:
    blob = f"{candidate.get('counterparty') or ''} {candidate.get('raw_snippet') or ''}"
    transfer_pat = _TRANSFER_DEBIT_PAT if candidate["direction"] == "debit" else _TRANSFER_CREDIT_PAT
    return bool(transfer_pat.search(blob)) or _is_self(
        candidate.get("counterparty"), candidate.get("raw_snippet"), handles, names)


def _candidate_category(candidate: dict[str, Any], handles: set[str], names: set[str]) -> str | None:
    snippet = candidate.get("raw_snippet") or ""
    if _is_transfer(candidate, handles, names):
        return "transfer"
    if candidate["direction"] == "credit":
        if _CARD_PAYMENT_RECEIPT.search(snippet):
            return "transfer"
        if _ISSUER_REVERSAL_CREDIT.search(snippet):
            return "reversal_credit"
    return None


def plan_import(db: Session, rows: list[dict[str, Any]], owner_identities: list[str]) -> dict[str, Any]:
    """Build a JSON-safe dry-run plan; performs no database writes."""
    if not isinstance(rows, list):
        raise ValueError("rows must be a list")
    # This import is intentionally single-month, so callers cannot broaden it
    # by mixing dates in one payload.
    month = "2026-05"
    prepared, excluded, invalid = [], [], []
    for source_row in rows:
        if source_row.get("message_id") == EXCLUDED_AMBIGUOUS_MESSAGE:
            excluded.append({"message_id": EXCLUDED_AMBIGUOUS_MESSAGE,
                             "reason": "ambiguous foreign remittance excluded by reviewed scope"})
            continue
        if str(source_row.get("currency", "INR")).upper() != "INR":
            excluded.append({"message_id": source_row.get("message_id"),
                             "reason": "non-INR row excluded from approved May import"})
            continue
        prepared.append(_validate_and_normalize(source_row, month))

    db_hash, may_snapshot, outside_count, outside_hash = _all_db_snapshot(db, month)
    existing_rows = db.execute(select(Transaction).order_by(Transaction.id)).scalars().all()
    existing_dicts = [_row_dict(row) for row in existing_rows]
    handles, names = _owner_identities(owner_identities)
    by_message: dict[str, list[dict[str, Any]]] = {}
    for existing in existing_dicts:
        by_message.setdefault(existing["gmail_message_id"], []).append(existing)

    proposed: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    conflicts: list[dict[str, Any]] = []
    input_seen: dict[str, dict[str, Any]] = {}
    grouped: dict[str, dict[str, dict[str, Any]]] = {}
    for candidate in prepared:
        # Same email can contain repeated source data; only exact duplicate
        # input identity collapses. Different transactions remain distinct.
        input_key = _json_hash({k: candidate[k] for k in (
            "txn_date", "amount", "currency", "direction", "account", "reference", "gmail_message_id")})
        if input_key in input_seen:
            skipped.append({"message_id": candidate["gmail_message_id"], "reason": "exact duplicate in input"})
            continue
        input_seen[input_key] = candidate
        grouped.setdefault(candidate["gmail_message_id"], {})[input_key] = candidate

    conflicting_input_ids = {mid for mid, values in grouped.items() if len(values) > 1}
    for mid in sorted(conflicting_input_ids):
        conflicts.append({"message_id": mid,
                          "reason": "same message has conflicting transaction rows in input"})

    strong_input_seen: list[dict[str, Any]] = []
    for candidate in input_seen.values():
        if candidate["gmail_message_id"] in conflicting_input_ids:
            continue
        if candidate.get("reference") and any(
            candidate["reference"] == prior.get("reference")
            and candidate["txn_date"] == prior["txn_date"]
            and candidate["amount"] == prior["amount"]
            and candidate["currency"] == prior["currency"]
            and candidate["direction"] == prior["direction"]
            and _same_card_instrument(candidate["account"], prior["account"])
            for prior in strong_input_seen
        ):
            skipped.append({"message_id": candidate["gmail_message_id"],
                            "reason": "exact strong-reference duplicate in input"})
            continue
        if candidate.get("reference"):
            strong_input_seen.append(candidate)
        dedup = f"hmay:2026-05:{_json_hash(candidate)[:48]}"
        candidate["dedup_key"] = dedup
        same_mail = by_message.get(candidate["gmail_message_id"], [])
        if any(_strong_match(candidate, old) for old in existing_dicts):
            skipped.append({"message_id": candidate["gmail_message_id"], "reason": "strong exact match already exists"})
            continue
        if _statement_overlap(candidate, existing_dicts):
            skipped.append({"message_id": candidate["gmail_message_id"],
                            "reason": "represented by matching existing statement row"})
            continue
        if same_mail:
            conflicts.append({"message_id": candidate["gmail_message_id"],
                              "reason": "message already has a different transaction; preserved existing row"})
            continue
        if any(old["dedup_key"] == dedup for old in existing_dicts):
            conflicts.append({"message_id": candidate["gmail_message_id"], "reason": "dedup key collision"})
            continue
        candidate["category"] = _candidate_category(candidate, handles, names)
        proposed.append(candidate)

    core = {
        "version": PLAN_VERSION, "month": month, "account": PRIMARY_ACCOUNT,
        "owner_identities": list(owner_identities), "source_rows": rows,
        "source_rows_hash": _json_hash(rows), "database_hash": db_hash,
        "existing_may_rows": may_snapshot,
        "outside_month_count": outside_count, "outside_month_hash": outside_hash,
        "proposed": proposed, "skipped": skipped, "conflicts": conflicts,
        "excluded": excluded, "invalid": invalid,
    }
    core["plan_sha256"] = _json_hash(core)
    return core


def _lock_month(db: Session, month: str) -> None:
    if db.get_bind().dialect.name == "postgresql":
        from sqlalchemy import text
        db.execute(text(
            "SELECT pg_advisory_xact_lock(hashtext(:key))"), {"key": f"lifeos-history-import:{month}"})
        # Normal collectors do not take our advisory lock. Briefly serialize
        # their transaction writes while the snapshot is rechecked and rows
        # are inserted, closing the stale-plan race with concurrent inserts.
        db.execute(text("LOCK TABLE transactions IN SHARE ROW EXCLUSIVE MODE"))


def _manifest(runid: str, plan: dict[str, Any], manifest_rows: list[dict[str, Any]]) -> dict[str, Any]:
    result = {"runid": runid, "month": plan["month"], "account": PRIMARY_ACCOUNT,
              "plan_sha256": plan["plan_sha256"], "inserted_count": len(manifest_rows),
              "inserted_rows": manifest_rows, "skipped": plan["skipped"],
              "conflicts": plan["conflicts"], "excluded": plan["excluded"]}
    result["manifest_sha256"] = _json_hash(result)
    return result


def _run_dedup_key(candidate: dict[str, Any], runid: str) -> str:
    run_tag = _json_hash(runid)[:12]
    identity_hash = candidate["dedup_key"].rsplit(":", 1)[-1]
    return f"hmay:2026-05:{run_tag}:{identity_hash}"


def _matches_proposed(row: Transaction, candidate: dict[str, Any], runid: str) -> bool:
    actual = _row_dict(row)
    fields = ("txn_date", "txn_at", "amount", "currency", "direction", "counterparty",
              "category", "account", "source", "reference", "gmail_message_id", "raw_snippet")
    expected = dict(candidate)
    expected["dedup_key"] = _run_dedup_key(candidate, runid)
    return (all(actual.get(key) == expected.get(key) for key in fields) and
            actual["dedup_key"] == expected["dedup_key"] and
            actual.get("spend_category_override") is None)


def apply_import(db: Session, plan: dict[str, Any], expected_plan_sha: str, runid: str) -> dict[str, Any]:
    """Validate the dry-run against the current ledger, then atomically insert."""
    if plan.get("version") != PLAN_VERSION or plan.get("account") != PRIMARY_ACCOUNT:
        raise ValueError("plan version/account is outside approved scope")
    if plan.get("month") != "2026-05":
        raise ValueError("only the approved 2026-05 scope can be applied")
    if not runid or len(runid) > 80:
        raise ValueError("runid must be a non-empty manifest identifier")
    claimed_hash = plan.get("plan_sha256")
    unsigned = {k: v for k, v in plan.items() if k != "plan_sha256"}
    if claimed_hash != _json_hash(unsigned) or expected_plan_sha != claimed_hash:
        raise ValueError("expected plan SHA does not match the reviewed plan")
    _lock_month(db, plan["month"])
    recovery_keys = [_run_dedup_key(candidate, runid) for candidate in plan["proposed"]]
    recovered = (db.execute(select(Transaction).where(Transaction.dedup_key.in_(recovery_keys)))
                 .scalars().all() if recovery_keys else [])
    if recovered:
        recovered_by_key = {row.dedup_key: row for row in recovered}
        if set(recovered_by_key) != set(recovery_keys):
            raise ValueError("runid collision: only part of the atomic import is present")
        by_key_candidate = {_run_dedup_key(candidate, runid): candidate for candidate in plan["proposed"]}
        if any(not _matches_proposed(row, by_key_candidate[row.dedup_key], runid) for row in recovered):
            raise ValueError("run recovery refused: an imported row was modified")
        return _manifest(runid, plan, [_row_dict(recovered_by_key[key]) for key in recovery_keys])
    fresh = plan_import(db, plan["source_rows"], plan["owner_identities"])
    if fresh["plan_sha256"] != claimed_hash:
        raise ValueError("stale plan: source or ledger changed after dry run")
    manifest_rows = []
    try:
        for candidate in fresh["proposed"]:
            run_dedup_key = _run_dedup_key(candidate, runid)
            obj = Transaction(
                txn_date=date.fromisoformat(candidate["txn_date"]),
                txn_at=datetime.fromisoformat(candidate["txn_at"]) if candidate["txn_at"] else None,
                amount=Decimal(candidate["amount"]), currency=candidate["currency"],
                direction=candidate["direction"], counterparty=candidate["counterparty"],
                category=candidate["category"], account=candidate["account"],
                source=candidate["source"], reference=candidate["reference"],
                gmail_message_id=candidate["gmail_message_id"], dedup_key=run_dedup_key,
                raw_snippet=candidate["raw_snippet"],
            )
            db.add(obj)
            db.flush()
            manifest_rows.append(_row_dict(obj))
        db.commit()
    except Exception:
        db.rollback()
        raise
    return _manifest(runid, fresh, manifest_rows)


def rollback_import(db: Session, manifest: dict[str, Any]) -> dict[str, Any]:
    """Delete only this manifest's untouched rows; refuse any linked/edited row."""
    rows = manifest.get("inserted_rows")
    if not isinstance(rows, list):
        raise ValueError("manifest has no inserted_rows list")
    unsigned_manifest = {k: v for k, v in manifest.items() if k != "manifest_sha256"}
    if manifest.get("manifest_sha256") != _json_hash(unsigned_manifest):
        raise ValueError("rollback refused: manifest checksum is invalid")
    ids = [int(row["id"]) for row in rows]
    current = db.execute(select(Transaction).where(Transaction.id.in_(ids))).scalars().all() if ids else []
    by_id = {row.id: row for row in current}
    if set(by_id) != set(ids):
        raise ValueError("rollback refused: one or more imported rows are missing")
    linked = set(db.execute(select(TransactionReconciliation.debit_transaction_id).where(
        TransactionReconciliation.debit_transaction_id.in_(ids))).scalars())
    linked.update(db.execute(select(TransactionReconciliation.credit_transaction_id).where(
        TransactionReconciliation.credit_transaction_id.in_(ids))).scalars())
    linked.update(db.execute(select(TransactionAdjustment.transaction_id).where(
        TransactionAdjustment.transaction_id.in_(ids))).scalars())
    if linked:
        raise ValueError("rollback refused: imported rows have linked reconciliations or adjustments")
    for expected in rows:
        current_row = _row_dict(by_id[int(expected["id"])])
        if not current_row["dedup_key"].startswith("hmay:2026-05:"):
            raise ValueError("rollback refused: row is outside the May import namespace")
        if current_row != expected:
            raise ValueError("rollback refused: an imported row was changed after insertion")
    try:
        for row in current:
            db.delete(row)
        db.commit()
    except Exception:
        db.rollback()
        raise
    return {"deleted_count": len(ids), "runid": manifest.get("runid"),
            "plan_sha256": manifest.get("plan_sha256")}
