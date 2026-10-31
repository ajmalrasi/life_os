#!/usr/bin/env python3
"""Plan or explicitly apply the reviewed May 2026 transaction snapshot."""
from __future__ import annotations

import argparse
import glob
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from app.config import get_settings  # noqa: E402
from app.database import SessionLocal  # noqa: E402
from app.services.history_month_import import (  # noqa: E402
    PRIMARY_ACCOUNT,
    _json_hash,
    apply_import,
    plan_import,
    rollback_import,
)
from app.services.history_month_pilot import _hash  # noqa: E402

DEFAULT_INPUT = Path(
    "/Users/ajmalrasi/lifeos-private/spending-history/2026-10-31-may-pilot/"
    "deepseek-six-2026-11-01/corrected-transactions.json"
)
DEFAULT_SOURCE_DIR = Path(
    "/Users/ajmalrasi/lifeos-private/spending-history/2026-10-31-may-pilot/data"
)


def _load_reviewed_rows(input_path: Path, source_dir: Path) -> list[dict]:
    rows = json.loads(input_path.read_text(encoding="utf-8"))
    if not isinstance(rows, list):
        raise ValueError("corrected transaction input must be a JSON list")
    messages_dir = source_dir / "messages"
    results_dir = source_dir / "results"
    enriched = []
    for original in rows:
        row = dict(original)
        mid = str(row.get("message_id", ""))
        stem = _hash(mid)[:32]
        message_path = messages_dir / f"{stem}.json"
        result_path = results_dir / f"{stem}.json"
        if not message_path.is_file() or not result_path.is_file():
            raise ValueError(f"missing private source/result cache for message {mid}")
        message = json.loads(message_path.read_text(encoding="utf-8"))
        result = json.loads(result_path.read_text(encoding="utf-8"))
        source_hash = _hash(message)
        if message.get("message_id") != mid or source_hash != row.get("source_hash"):
            raise ValueError(f"cached source hash mismatch for message {mid}")
        if result.get("message_id") != mid or result.get("source_hash") != source_hash:
            raise ValueError(f"cached parser result is not bound to source for message {mid}")
        candidates = result.get("transactions") or []
        # If a parsed result contains multiple movements, select only an exact
        # strong reference match; absent references require there to be one row.
        matching = [t for t in candidates if row.get("reference") and
                    t.get("reference") == row.get("reference")]
        if not matching and len(candidates) == 1:
            matching = candidates
        if len(matching) != 1:
            raise ValueError(f"cannot unambiguously bind source excerpt for message {mid}")
        row["raw_snippet"] = matching[0].get("raw_snippet") or message.get("text") or message.get("snippet") or ""
        enriched.append(row)
    return enriched


def _private_output(path: Path) -> Path:
    path = path.expanduser().resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError("private import artifacts must be outside the repository")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(path, 0o700)
    return path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--source-dir", type=Path, default=DEFAULT_SOURCE_DIR,
                        help="verified private pilot data root containing messages/ and results/ (not the messages/ folder itself)")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--month", required=True)
    parser.add_argument("--account", required=True)
    parser.add_argument("--apply", action="store_true", help="insert after plan SHA confirmation")
    parser.add_argument("--expected-plan-sha")
    parser.add_argument("--runid", default="may-2026-reviewed-import")
    parser.add_argument("--rollback-manifest", type=Path,
                        help="guardedly remove only untouched rows from this import manifest")
    parser.add_argument("--rollback-apply", action="store_true",
                        help="required to execute a rollback; otherwise print planned count")
    args = parser.parse_args()
    try:
        if args.month != "2026-05":
            raise ValueError("this helper is hard-bound to month 2026-05")
        if args.account.strip().lower() != PRIMARY_ACCOUNT:
            raise ValueError(f"this helper is restricted to {PRIMARY_ACCOUNT}")
        output = _private_output(args.output_dir)
        if args.rollback_manifest:
            manifest = json.loads(args.rollback_manifest.read_text(encoding="utf-8"))
            if not args.rollback_apply:
                print(json.dumps({"dry_run": True, "runid": manifest.get("runid"),
                                  "rows_to_check": len(manifest.get("inserted_rows", []))}, indent=2))
                return 0
            with SessionLocal() as db:
                result = rollback_import(db, manifest)
            target = output / f"rollback-{manifest.get('runid', 'import')}.json"
            target.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
            os.chmod(target, 0o600)
            print(json.dumps(result, indent=2))
            return 0

        rows = _load_reviewed_rows(args.input.expanduser().resolve(),
                                   args.source_dir.expanduser().resolve())
        settings = get_settings()
        identities = [v.strip() for v in (settings.expense_owner_identities or settings.cc_owner_name).split(",") if v.strip()]
        with SessionLocal() as db:
            if args.apply:
                if not args.expected_plan_sha:
                    raise ValueError("--expected-plan-sha is required with --apply")
                plan_path = output / "plan.json"
                if not plan_path.exists():
                    raise ValueError("no saved dry-run plan.json exists in output directory")
                plan = json.loads(plan_path.read_text(encoding="utf-8"))
                if plan.get("source_rows_hash") != _json_hash(rows):
                    raise ValueError("stale input: corrected export or bound source cache changed after dry run")
                if plan.get("owner_identities") != identities:
                    raise ValueError("configured owner identities changed after dry run")
                result = apply_import(db, plan, args.expected_plan_sha, args.runid)
                target = output / f"manifest-{args.runid}.json"
                target.write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
                os.chmod(target, 0o600)
                print(json.dumps({"inserted_count": result["inserted_count"],
                                  "plan_sha256": result["plan_sha256"],
                                  "manifest": str(target)}, indent=2))
                return 0
            plan = plan_import(db, rows, identities)
        plan_path = output / "plan.json"
        plan_path.write_text(json.dumps(plan, indent=2, sort_keys=True), encoding="utf-8")
        os.chmod(plan_path, 0o600)
        print(json.dumps({"dry_run": True, "plan_sha256": plan["plan_sha256"],
                          "proposed_count": len(plan["proposed"]),
                          "skipped_count": len(plan["skipped"]),
                          "conflict_count": len(plan["conflicts"]),
                          "excluded_count": len(plan["excluded"]),
                          "existing_may_count": len(plan["existing_may_rows"]),
                          "outside_month_count": plan["outside_month_count"],
                          "plan": str(plan_path)}, indent=2))
        return 0
    except Exception as exc:
        parser.error(f"{type(exc).__name__}: {exc}")


if __name__ == "__main__":
    raise SystemExit(main())
