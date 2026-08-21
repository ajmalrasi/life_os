#!/usr/bin/env python3
"""Idempotent seeder for user-confirmed payee → category labels.

Why this exists
---------------
The keyword categorizer recognises well-known merchants, but a few recurring
*person/entity* payees can only be classified by name. This script upserts the
handful the user has explicitly confirmed, so they stop polluting the "other"
bucket in the spend breakdown. Re-running is safe: each (pattern, match_type)
is unique, so an existing row is left untouched.

Only high-confidence, user-confirmed mappings live here. Ambiguous payees
(e.g. big "ACH D-" institutional debits, one-off P2P transfers, or "ETERNAL
LIMITED" which could be Zomato/Blinkit/District) are intentionally left as
"other" rather than guessed — add them via the API once the user confirms.

Usage (run where the DB is reachable, e.g. inside the api container on the
Jetson):

    docker compose exec -T api python scripts/seed_payee_labels.py
    # dry run — show what would change, touch nothing:
    docker compose exec -T api python scripts/seed_payee_labels.py --dry-run
"""
from __future__ import annotations

import argparse
import os
import sys

# Make ``app`` importable regardless of the working directory the script is
# launched from (e.g. `docker compose exec api python scripts/seed_...`).
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.database import SessionLocal  # noqa: E402
from app.repositories.payee_label_repo import PayeeLabelRepository  # noqa: E402

# (pattern, category, match_type, note). Confirmed by the user as lawyer fees —
# explicitly NOT lending. ``legal_fees`` is the compatible storage key; the app
# presents it as the broader, durable "Professional services" category.
SEED_LABELS: list[tuple[str, str, str, str]] = [
    ("SANTHOSH", "legal_fees", "substring", "lawyer fee (user-confirmed)"),
    ("SREEPRAKASH", "legal_fees", "substring", "lawyer fee (user-confirmed)"),
    ("MEHBOOBA", "legal_fees", "substring", "lawyer fee (user-confirmed)"),
    ("MEHBOOD", "legal_fees", "substring", "lawyer fee (user-confirmed)"),
    ("BADARUDHEEN", "legal_fees", "substring", "lawyer fee (user-confirmed)"),
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="Show changes, write nothing.")
    args = parser.parse_args()

    created, skipped = 0, 0
    with SessionLocal() as db:
        repo = PayeeLabelRepository(db)
        for pattern, category, match_type, note in SEED_LABELS:
            if repo.get_by_pattern(pattern, match_type):
                print(f"  skip (exists): {pattern!r} -> {category}")
                skipped += 1
                continue
            print(f"  {'would add' if args.dry_run else 'add'}: {pattern!r} -> {category}")
            if not args.dry_run:
                repo.create(pattern=pattern, category=category, match_type=match_type, note=note)
            created += 1
        if args.dry_run:
            db.rollback()
        else:
            db.commit()

    verb = "would create" if args.dry_run else "created"
    print(f"Done: {verb} {created}, skipped {skipped}.")


if __name__ == "__main__":
    main()
