# Guarded monthly history import

The explicit importer can plan or apply one `YYYY-MM` period at a time from a
private, source-bound candidate file. It never fetches mail, calls a model,
changes existing transactions, or advances normal collector cursors. The Gmail
pilot remains restricted to `mail2ajmalrasi@gmail.com`.

```sh
PYTHONPATH=backend python backend/scripts/import_history_month.py \
  --month 2026-04 --account mail2ajmalrasi@gmail.com \
  --input /private/april/approved-candidates.json \
  --source-dir /private/april/data --output-dir /private/april/import-run
```

The default is a read-only dry run. The source directory must contain the
verified pilot `messages/` and `results/` caches; the importer checks their
source hashes and binds each candidate back to its parser result. The plan
counts proposed rows, strong duplicates, statement overlaps, conflicts, and
excluded non-INR rows. Account labels are normalized, and statement/live EMI
overlap suppression requires matching card identity and merchant context.
Repeated unreferenced purchases remain separate.

Review the plan, then apply with its exact SHA and a unique run ID:

```sh
PYTHONPATH=backend python backend/scripts/import_history_month.py \
  --month 2026-04 --account mail2ajmalrasi@gmail.com \
  --input /private/april/approved-candidates.json \
  --source-dir /private/april/data --output-dir /private/april/import-run \
  --apply --expected-plan-sha <printed-hash> --runid april-2026-primary
```

Applying takes a short PostgreSQL transaction lock, rechecks the complete
ledger snapshot, then inserts new rows atomically. Existing rows, categories and
user decisions remain intact. A stale plan is refused. Run-scoped dedup keys
and exact postimages in the manifest permit recovery after an interrupted
client and make a repeated month run idempotent. A rollback preview accepts the
saved manifest; `--rollback-apply` removes only unchanged rows from that run,
and refuses if a row was edited or linked to an adjustment/reconciliation.
Original May pilot manifests and rollback keys remain supported.

Before each apply, privately back up the affected tables and collector cursors.
Keep source files, plans, manifests and backups outside Git. Record source-based
corrections and held ambiguous rows separately. Never invent statement rows or
silently reconcile existing transactions. A month may be imported with a few
unclear alerts held; statement attachments are not part of this email-body
workflow.

April 2026 run on November 1: 111 rows inserted, five represented by existing
alerts/statements, and four source cases held before planning. Ten April rows
already existed, so the resulting month contains 121 records. The exact-month
native API and Pi web view both returned all 121. Recorded everyday spend was
₹174,769.03 under Money's existing EMI/transfer exclusions; source coverage is
email alerts, not a complete statement audit.
