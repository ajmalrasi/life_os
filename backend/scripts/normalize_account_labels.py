#!/usr/bin/env python3
"""Backfill existing transactions to canonical account labels.

Why this exists
---------------
The same physical card was historically stored under several ``account``
labels depending on which parser wrote the row — a generic statement label
("ICICI Card ..0010") vs a specific live label ("ICICI Amazon ..0010"), or a
2-digit vs 4-digit last4 ("SBI Card ..70" vs "SBI Card ..1970"). New writes are
now canonicalized at ingestion (``ExpenseRepository.upsert`` ->
``app.account_identity.normalize_account``); this script fixes the rows already
in the table so one physical card is one label everywhere, past and future.

It is safe to re-run: canonical labels normalize to themselves, so a second run
is a no-op.

Conflict audit
--------------
The registry in ``app.account_identity`` is hand-kept. This script also reports
any same-bank cluster of distinct labels whose last4s suffix-overlap but that
the registry does NOT collapse to one label — i.e. probable same-card conflicts
that still need a one-line registry entry. Review these; nothing about them is
changed automatically.

Usage (run where the DB is reachable, e.g. inside the api container on the
Jetson):

    docker compose exec -T api python scripts/normalize_account_labels.py --dry-run
    docker compose exec -T api python scripts/normalize_account_labels.py
"""
from __future__ import annotations

import argparse
import os
import sys
from collections import Counter, defaultdict

# Make ``app`` importable regardless of the working directory the script is
# launched from (e.g. `docker compose exec api python scripts/normalize_...`).
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sqlalchemy import select  # noqa: E402

from app.account_identity import _parse, _suffix_match, normalize_account  # noqa: E402
from app.database import SessionLocal  # noqa: E402
from app.models.transaction import Transaction  # noqa: E402


def _conflict_audit(labels: set[str]) -> list[list[str]]:
    """Same-bank clusters of distinct labels whose last4s suffix-overlap but
    that ``normalize_account`` does NOT already collapse to a single label —
    i.e. probable same-card conflicts missing a registry entry."""
    by_bank: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for label in labels:
        bank, digits = _parse(label)
        if digits:
            by_bank[bank].append((digits, label))

    conflicts: list[list[str]] = []
    for entries in by_bank.values():
        # Union-find-ish grouping by suffix overlap within one bank.
        clusters: list[list[tuple[str, str]]] = []
        for digits, label in entries:
            placed = False
            for cluster in clusters:
                if any(_suffix_match(digits, d) for d, _ in cluster):
                    cluster.append((digits, label))
                    placed = True
                    break
            if not placed:
                clusters.append([(digits, label)])
        for cluster in clusters:
            cluster_labels = {label for _, label in cluster}
            if len(cluster_labels) < 2:
                continue
            # Already handled if the registry maps them all to one label.
            if len({normalize_account(label) for label in cluster_labels}) == 1:
                continue
            conflicts.append(sorted(cluster_labels))
    return conflicts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Show changes, write nothing.")
    args = parser.parse_args()

    changed = 0
    mapping = Counter()  # (old -> new) -> count
    with SessionLocal() as db:
        rows = db.execute(
            select(Transaction).where(Transaction.account.is_not(None))
        ).scalars().all()

        distinct_labels = {r.account for r in rows if r.account}

        for row in rows:
            canonical = normalize_account(row.account)
            if canonical != row.account:
                mapping[(row.account, canonical)] += 1
                changed += 1
                if not args.dry_run:
                    row.account = canonical

        if args.dry_run:
            db.rollback()
        else:
            db.commit()

    verb = "would rewrite" if args.dry_run else "rewrote"
    print(f"Scanned {len(distinct_labels)} distinct label(s).")
    for (old, new), count in sorted(mapping.items()):
        print(f"  {verb}: {old!r} -> {new!r}  ({count} row(s))")
    print(f"Done: {verb} {changed} row(s).")

    conflicts = _conflict_audit(distinct_labels)
    if conflicts:
        print("\nPossible unregistered same-card conflicts (add a registry entry "
              "in app/account_identity.py if these are the same physical card):")
        for cluster in conflicts:
            print("  " + "  |  ".join(cluster))


if __name__ == "__main__":
    main()
