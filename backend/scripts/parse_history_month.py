#!/usr/bin/env python3
"""Run a read-only, resumable one-month financial email parsing pilot."""
from __future__ import annotations

import argparse
from pathlib import Path

from app.services.history_month_pilot import MonthPilot, PRIMARY_ACCOUNT


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--month", required=True, help="Month to inspect, YYYY-MM")
    parser.add_argument("--account", required=True, help="Must be the primary mailbox")
    parser.add_argument("--output-dir", required=True, type=Path,
                        help="Private checkpoint directory outside the repository")
    parser.add_argument("--no-llm", action="store_true",
                        help="Run deterministic parsers only; leave remaining candidates pending")
    parser.add_argument("--workers", type=int, choices=(1, 2), default=1,
                        help="Concurrent message processing workers (default: 1; max: 2)")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.expanduser().resolve()
    if output == root or root in output.parents:
        parser.error("Private email artifacts must be outside the repository")
    if args.account.strip().lower() != PRIMARY_ACCOUNT:
        parser.error(f"This pilot only permits {PRIMARY_ACCOUNT}")

    from app.config import get_settings
    from app.mail.factory import provider_for_address

    provider = provider_for_address(get_settings(), PRIMARY_ACCOUNT)
    llm_client = None
    no_llm = args.no_llm
    if not no_llm:
        from app.services.openclaw import InferenceWorkload, OpenClawClient
        llm_client = OpenClawClient(workload=InferenceWorkload.FINANCE)
        if not llm_client.health():
            print("Local model is unavailable; leaving unmatched financial emails pending.", flush=True)
            no_llm = True
    pilot = MonthPilot(output, args.month, PRIMARY_ACCOUNT, no_llm=no_llm,
                       workers=args.workers)
    if llm_client and pilot.llm:
        pilot.llm._client = llm_client
    pilot.collect(provider, progress=lambda value: print(value, flush=True))
    report = pilot.report()
    print(f"Report: {output / 'report.md'}")
    print(f"Search complete: {report['search_complete']}; messages: {report['unique_mail_messages']}; "
          f"parsed rows: {report['parsed_transaction_rows']}; outcomes: {report['outcomes']}")
    return 0 if report["search_complete"] and not report["search_error"] and not any(
        status in report["outcomes"] for status in ("retryable_error", "pending_llm")
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
