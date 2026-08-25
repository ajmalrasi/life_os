#!/usr/bin/env python3
"""Register an already-authorized gog Gmail account for a LifeOS consumer."""

from __future__ import annotations

import argparse

from app.database import SessionLocal, init_db
from app.repositories.mail_repo import MailAccountRepository


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--account", required=True, help="Authorized Gmail address")
    parser.add_argument("--purpose", required=True, choices=("finance", "priority"))
    parser.add_argument("--label", default="", help="User-facing account label")
    args = parser.parse_args()

    init_db()
    with SessionLocal() as db:
        account = MailAccountRepository(db).register(
            args.account,
            args.purpose,
            args.label or None,
        )
        print(
            f"Registered {account.provider_account_id} as {args.purpose} "
            f"({account.display_label})."
        )


if __name__ == "__main__":
    main()
