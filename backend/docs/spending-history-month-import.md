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

December–October 2024 were processed next. Each month passed exact-month
database/native API count checks, and repeat dry runs proposed zero additions.
These months inserted 43/42/41 rows respectively; Money everyday spend is
₹50,986.18/₹37,137.30/₹150,356.70. The parser held 189 candidates across the
three months, including 163 HDFC alerts without a source-printed unambiguous
card/account suffix, 20 with missing account identity, and six lender mandates
whose payment-versus-spend treatment is unclear. They were left out without
DeepSeek review. This remains email-alert coverage; 18 October statement emails
are attachment-pending and no statement PDFs were parsed. Source and import
artifacts remain private on the Jetson under
`/home/ajmalrasi/lifeos-maintenance/spending-history/2026-11-01-{dec,nov,oct}-2024-import`.

September–July 2024 are also imported and verified. The three months added
44/43/59 rows, with everyday spend of ₹216,017.99/₹197,806.41/₹101,651.44;
repeat dry runs proposed zero additions. 168 candidates remain held: 119 HDFC
alerts without an unambiguous source-printed suffix, 44 with missing account
identity, four lender mandates, and one foreign-currency transaction. No
DeepSeek review or statement-PDF parsing was done. These remain email-alert
figures, not complete statement totals. Private source/import artifacts are on
the Jetson under
`/home/ajmalrasi/lifeos-maintenance/spending-history/2026-11-01-{sep,aug,jul}-2024-import`.

June–April 2024 were added and verified against the native API; repeat dry runs
proposed zero additions. They inserted 46/46/32 rows, with everyday spend of
₹47,591.16/₹142,837.87/₹145,663.00. 104 candidates remain held: 98 HDFC alerts
without an unambiguous source-printed suffix, three with missing account
identity, two lender mandates, and one foreign-currency transaction. No
DeepSeek review or statement-PDF parsing was done; these are email-alert
figures. Private source/import artifacts are on the Jetson under
`/home/ajmalrasi/lifeos-maintenance/spending-history/2026-11-01-{jun,may,apr}-2024-import`.

March–January 2024 passed database/native API verification, with repeat dry runs
proposing zero additions. They inserted 43/35/48 rows, with everyday spend of
₹53,451.06/₹49,431.13/₹55,167.98. 170 candidates remain held: 144 HDFC alerts
without an unambiguous source-printed suffix, 24 with missing account identity,
and two lender mandates. No DeepSeek review or statement-PDF parsing was done;
these are email-alert figures. Private artifacts are on the Jetson under
`/home/ajmalrasi/lifeos-maintenance/spending-history/2026-11-01-{mar,feb,jan}-2024-import`.

December–October 2023 passed database/native API verification; repeat dry runs
proposed zero additions. They inserted 60/38/56 rows, with everyday spend of
₹103,102.50/₹70,245.47/₹168,585.41. 224 candidates remain held: 199 HDFC alerts
without an unambiguous source-printed suffix, 23 with missing account identity,
and two lender mandates. No DeepSeek review or statement-PDF parsing was done;
these are email-alert figures. Private artifacts are on the Jetson under
`/home/ajmalrasi/lifeos-maintenance/spending-history/2026-11-01-{dec,nov,oct}-2023-import`.

HDFC UPI suffix correction (November 1): DeepSeek v4.1 Flash reviewed 767 held
HDFC UPI candidates from existing staged month caches in 48 requests of 16,
with eight concurrent. All returned quotes were checked against source emails.
The user clarified that an HDFC UPI alert explicitly showing account suffix
`7672` is from the HDFC bank account, not the RuPay credit card, regardless of
transaction date. Do not use a date cutoff. For import, require the source to
show `from account **7672` (or equivalent explicit account wording), INR, and
matching source amount/direction; use the date printed in the email. Eight
parser dates were corrected from their source emails. Two staged alerts lack
the suffix and remain held.

Of the 767-candidate batch, 765 showed `7672`: 46 had already been imported and
719 more were inserted as `HDFC Savings ..7672`. Separately, the 166 Jan–Mar
2025 source-reviewed candidates had 160 previously imported; five date
discrepancies were imported using the literal email dates. One remaining
message is a credit-card payment from the savings account, not a UPI purchase,
and stays excluded from spending. In total, 930 source-explicit `7672` HDFC UPI
debits or credits are present (724 inserted in this correction; 206 imported
earlier). Database/API counts matched and repeat dry runs proposed zero rows
for all affected months. The four legacy HDFC statement-summary differences
remain separate and unresolved. Only currently staged candidate months are
covered; Sep 2022–Sep 2023 still needs month parsing. Private source comparisons,
approved files and backups remain outside Git under
`/home/ajmalrasi/lifeos-maintenance/spending-history/2026-11-01-hdfc-historical-upi-correction*`.
