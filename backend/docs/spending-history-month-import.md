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
An exact same-message reference can match even when the older row has no account
label. A same-message match without a reference still needs matching account
labels, and a reference across different messages requires the same explicit
instrument. Repeated unreferenced purchases remain separate. Explicit
account-to-account IMPS debits are tagged as transfers on new rows.

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

The backward run continued through July 2025 using the same importer:

| Month | Added | Final rows | Money everyday spend | Held aside |
|---|---:|---:|---:|---:|
| December 2025 | 177 | 178 | ₹123,323.08 | 5 |
| November 2025 | 145 | 145 | ₹471,593.91 | 7 |
| October 2025 | 117 | 117 | ₹217,815.22 | 3 |
| September 2025 | 102 | 102 | ₹443,399.38 | 4 |
| August 2025 | 102 | 102 | ₹67,378.89 | 5 |
| July 2025 | 186 | 186 | ₹250,711.49 | 3 |
| June 2025 | 126 | 126 | ₹123,358.42 | 4 |
| May 2025 | 97 | 97 | ₹175,339.79 | 16 |
| April 2025 | 86 | 86 | ₹148,366.27 | 35 |
| March 2025 | 60 | 60 | ₹254,897.23 | 5 |
| February 2025 | 145 | 145 | ₹181,533.66 | 5 |
| January 2025 | 120 | 120 | ₹344,763.80 | 6 |

October–December native API and Pi counts agreed; September–July native API
and database counts agreed. Repeat dry runs proposed no new rows for all six
months. Thirty-four statement emails remain attachment-pending across
October–December (13/10/11), so those figures summarize parsed email alerts
rather than full card/bank statements. November includes ₹387,000 currently tagged as investments;
excluding those tagged rows gives ₹84,593.91. December also includes ₹10,000
tagged investments. These amounts remain in the figures above under Money's
current spend rules, so the totals are not pure consumption. No DeepSeek call or
test suite run was needed.


September–July each passed exact-month database/API count verification. Repeat dry
runs proposed zero new rows. Twelve candidates remain held across those months
(four/five/three): unclear lender mandates, missing account identity or suffix,
and foreign-currency rows. These are still email-alert summaries; no statement
PDF extraction was included.


June–April 2025 each passed exact-month database/API verification; repeat dry
runs proposed zero additions. Fifty-five candidates were held aside, including
43 HDFC alerts across May and April whose source text does not identify the
account/card suffix clearly. The remainder are lender mandates, foreign-currency
rows, or a missing account identity. No statement PDFs were included.


March–January 2025 passed exact-month database/API verification and repeat
dry runs proposed zero new rows. DeepSeek v4.1 Flash reviewed the 166 held HDFC
UPI source emails: all 166 had locally verified exact quotes for savings account
suffix 7672, and amount/direction matched the parser candidate. Dates aligned on
160; five differed and one was unclear. The 160 aligned records were imported;
the six date cases remain held. Ten other candidates remain held for lender
mandates, foreign currency, or missing account identity. This remains email-alert
coverage without statement PDFs. January needed one Gmail retry after a temporary
IPv6 network failure. The seven model requests used 104,784 tokens; the provider
response did not include a charge amount.
