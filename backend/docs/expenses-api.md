# Expenses API

Transactions parsed from live bank / card / UPI **emails** (read-only Gmail via
the `gog` CLI baked into the API image). The collector is **incremental**: a
per-mailbox cursor is stored in Postgres, so each sync reads only emails newer
than the last one processed, and rows are idempotent — safe to call repeatedly.

- **Base URL:** `http://<jetson>:8000` (LAN; unauthenticated, same as the rest of the API)
- **Machine-readable spec:** `GET /openapi.json` · interactive docs at `/docs`
- **Source:** routes `app/api/routes/expenses.py` · collector `app/services/expense_collector.py` · parsers `app/services/expense_parsers/`

## Transaction object

| Field | Type | Notes |
|---|---|---|
| `id` | int | Autoincrement; use with `/expenses/new?after_id=` for incremental polling |
| `txn_date` | date (`YYYY-MM-DD`) | Transaction date parsed from the email |
| `amount` | string (decimal) | Positive magnitude, 2 dp, e.g. `"190.00"` |
| `currency` | string | ISO code, default `"INR"` |
| `direction` | string | `"debit"` or `"credit"` |
| `counterparty` | string \| null | e.g. UPI VPA `q186411044@ybl` |
| `category` | string \| null | Reconciliation tag (`transfer`, `reversed_debit`, …); null for ordinary spend |
| `spend_category` | string \| null | Spend-analysis category (payee labels + keyword rules). `null` when excluded from spend (transfers/reversals/returns) so the client can slice by category without double-counting |
| `account` | string \| null | e.g. `"HDFC RuPay CC ..5473"` |
| `source` | string | Live-alert source such as `"upi"` or `"card_live"` |
| `reference` | string \| null | UPI reference / txn id — the natural dedup key |
| `created_at` | datetime (ISO, tz-aware) | When the row was ingested |

## Endpoints

### `GET /expenses`
List stored transactions in a trailing window, newest first. Feeds the table UI.

Query params: `days` (int, default `30`), `source` (string, optional, e.g. `upi`).

```bash
curl -s "http://<jetson>:8000/expenses?days=30"
# -> [ { "id": 38, "txn_date": "2026-07-02", "amount": "190.00", ... }, ... ]
```

### `GET /expenses/new`
Transactions inserted **after** a given id, oldest-first. For a client/agent that
remembers the last id it saw and wants only what's new since.

Query params: `after_id` (int, default `0`), `limit` (int, default `500`).

```bash
curl -s "http://<jetson>:8000/expenses/new?after_id=35"
# -> [ {"id":36,...}, {"id":37,...}, {"id":38,...} ]   # empty [] when nothing new
```

Polling pattern: start with `after_id=0`; after each call, set `after_id` to the
max `id` returned; subsequent calls return only newly-ingested rows.

### `PATCH /expenses/{id}/category`
Set a per-transaction spend category from the app. Body: `{"category":"internet"}`.
Pass `null` to clear the override and return to payee-label/keyword categorization.
This never changes the separate reconciliation `category`, so excluded transfers,
reversals, and repayments remain excluded.

### `POST /expenses/sync`
Read only *new* emails (since the stored cursor), parse, upsert, and return the
rows added **this run**. Idempotent — a repeat call with no new mail returns
`transactions_new: 0`.

Query params: `full` (bool, default `false`) — force a backfill over
`EXPENSE_BACKFILL_DAYS`, ignoring the cursor (still idempotent).

Response (`ExpenseSyncResult`):

| Field | Type | Notes |
|---|---|---|
| `parsers_run` | int | Number of source parsers executed |
| `emails_scanned` | int | New emails fetched this run |
| `transactions_new` | int | Rows created this run |
| `transactions_seen` | int | Already-present rows skipped (dedup) |
| `source` | string | `"gmail"` |
| `message` | string | Human summary |
| `cursor_epoch` | int | Current high-water mark (epoch seconds) |
| `new_transactions` | Transaction[] | The rows created this run |

```bash
curl -s -X POST "http://<jetson>:8000/expenses/sync"
# first run seeds ~90d of history; later runs return only new transactions
```

## Statements API (monthly CC statement PDFs)

Statement emails from HDFC / Axis / ICICI / SBI are auto-fetched, decrypted
(passwords derived from owner name + DOB in env; see `app/services/pdf_unlock.py`),
and parsed in **summary + detail + EMI mode**. Bill summaries land in `card_statements`,
preserving total due, minimum due, and due date. Ordinary statement lines do not
enter `transactions`; they land in the separate `card_statement_items` table only
to explain the bill. Live bank/card alerts remain the source for normal purchases.
Only EMI installments, interest, and processing fees that live alerts commonly
miss are stored as `source='statement_emi'`. Explicit EMI narrations use audited
rules; ambiguous merchant-EMI descriptions get one batched local-model review and
are skipped on timeout/uncertainty. Conversion credits are never imported. All
retained EMI rows expose `spend_category='emi'` before merchant-category rules are
considered, so suffixes such as `DEPT STORES` cannot misclassify them as shopping.

### `GET /statements` — stored summaries (`months` param, default 3)
### `GET /statements/{id}/details` — one summary plus its full statement-only transaction list and activity breakdown. A clearly labeled previous-balance/adjustment residual reconciles parsed activity to the bank's authoritative total due. These rows never affect spend.
### `POST /statements/sync` — fetch/parse new statement emails (idempotent)
### `PATCH /statements/{id}/paid` — set paid state with `{"paid":true}`; `false` undoes it. Paid statements remain stored but are excluded immediately from upcoming-due totals.
### `GET /statements/trends` — month-by-month spend (`months` param, default 6):
per calendar month: `total_debits`, `total_credits`, `txn_count`, `by_category`,
`by_account`; plus `aggregate_debits` and `aggregate_by_category` across the window.
### `GET /statements/analysis` — month-at-a-glance:
`total_due_all_cards`, `min_due_all_cards`, `next_due_date`, latest statement per
card, `spend_by_category` (keyword categorizer in `app/api/routes/statements.py`),
and `top_merchants` over the combined feed (`days` param, default 45).
### `GET /statements/cycles/current` — open credit-card cycles before bill generation:
per card, returns the inferred cycle start/end, likely statement and payment
dates, posted charges/refunds, explicit remaining `N/M` EMIs, estimated total,
transaction count, and last-statement alert-coverage confidence. Calendar spend
is intentionally separate. Card payments, transfers, reconciled returns, and
paired reversals do not enter the estimate; genuine card refunds reduce it.

## Payee labels API (`/payee-labels`)

User-managed rules that teach the categorizer about recurring payees, so
P2P/entity payments stop landing in `other`. A label matches a transaction's
`counterparty` and takes precedence over the built-in keyword rules; the first
active label wins, ordered by `priority` (asc) then `id`. Effect is immediate on
the next read of `/expenses`, `/statements/analysis`, and `/statements/trends` —
no sync or redeploy.

| Field | Type | Notes |
|---|---|---|
| `pattern` | string | Text matched against `counterparty` (case-insensitive) |
| `category` | string | Category to assign, e.g. `legal_fees` |
| `match_type` | string | `substring` (default), `exact`, or `regex` |
| `priority` | int | Lower evaluated first (default `100`) |
| `active` | bool | Default `true` |
| `note` | string \| null | Optional human note |

- `GET /payee-labels` — all labels in evaluation order.
- `POST /payee-labels` — create (`201`; `409` on duplicate pattern+match_type; `422` on empty/invalid regex).
- `PATCH /payee-labels/{id}` — partial update (`404`/`422` as applicable).
- `DELETE /payee-labels/{id}` — remove (`204`/`404`).
- `GET /payee-labels/preview?desc=...` — category a given string resolves to (live UI preview).
- `GET /payee-labels/match-types` — allowed `match_type` values.

Seed the user-confirmed labels (currently lawyer fees → `legal_fees`) idempotently:
`docker compose exec -T api python scripts/seed_payee_labels.py` (add `--dry-run` to preview).

### Automation
Host cron (Jetson, `crontab -l`, tagged `life_os-expense-sync`): `/expenses/sync`
hourly at :15, `/statements/sync` daily 08:30 IST. Both idempotent.

## Behavior notes for agents

- **Idempotent + incremental.** Call `POST /expenses/sync` freely; it won't
  double-count. Use `full=true` only to re-scan history (e.g. after adding a parser).
- **Read-only.** The pipeline cannot send or modify mail; it only reads.
- **Adding a source** = add a parser in `app/services/expense_parsers/` and
  register it; the API surface does not change.
- **State** lives in Postgres (`transactions`, `collector_state`) and survives restarts.
