# Reviewed May2026 database import

The explicit importer inserts reviewed primary-mailbox May2026 rows into the
normal `transactions` table. It does not fetch mail, call a model, migrate the
schema, replace existing records, or advance collector cursors. Execute only in
the configured backend runtime, with private input and output paths.

```sh
PYTHONPATH=backend python backend/scripts/import_history_month.py \
  --month 2026-05 --account mail2ajmalrasi@gmail.com \
  --input /private/approved-candidates.json \
  --source-dir /private/may-pilot/data --output-dir /private/import-run
```

Default is a read-only dry run. It validates cached message/result provenance,
normalizes account labels, checks duplicates against the existing ledger, and
writes `plan.json` with a SHA256. Review proposed/skipped/conflicting/excluded
rows. Exact account identity and merchant context are required for statement/live
EMI overlap suppression; repeated unreferenced purchases remain separate.

Apply the saved plan by repeating the command with `--apply`,
`--expected-plan-sha <printed-hash>` and a unique `--runid`. Applying takes a
short PostgreSQL transaction lock, rechecks the ledger hash, and inserts all new
rows atomically. A changed ledger or input refuses the stale plan. Existing
rows, categories and user decisions remain intact. Run-scoped dedup keys and
postimages in `manifest-<runid>.json` permit recovery after an interrupted client
and make retries idempotent. Known transfers/card repayments and explicit
reversal notices are classified on new rows only; no global reconciliation runs.

Before applying, privately back up affected tables and collector cursors. Keep
all source files, plans, manifests and backups outside Git. Source-based field
repairs are recorded separately from original parser exports. The ambiguous USD
remittance is excluded; other rows withheld before the approved input must have
an explicit private held-rows record.

To inspect a rollback, pass `--rollback-manifest /private/manifest.json` with
month/account/output arguments. `--rollback-apply` explicitly executes deletion
only after every imported postimage still matches and none has adjustment or
reconciliation links. Later edits cause refusal rather than erasing decisions.

Actual May2026 import on November1:166 inserted,8 represented/skipped (six live
alerts and two statement overlaps),three held (one foreign remittance and two
unclassified lender mandates). Existing12 May rows were preserved, yielding178.
All688 prior ledger rows and collector cursors were unchanged. Exact-month live
API reads succeeded; the post-import dry run is used to check no extra rows are
proposed. Statement attachment parsing remains outside this import.
