# Expenses API

Transactions parsed from bank / UPI / statement **emails** (read-only Gmail via
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
| `source` | string | `"upi"` (statement sources land here later) |
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
parsed, and stored. Summaries land in `card_statements`; line items land in
`transactions` with `source='cc_statement'`.

**Dedup rule:** HDFC RuPay-CC UPI purchases arrive twice — once as a live alert
email (ingested immediately, `source='upi'`) and again as a `UPI-*` line in the
next monthly statement. A statement debit matching an existing `upi` transaction
on **same date + same amount** is skipped, so each real transaction is stored once.

### `GET /statements` — stored summaries (`months` param, default 3)
### `POST /statements/sync` — fetch/parse new statement emails (idempotent)
### `GET /statements/trends` — month-by-month spend (`months` param, default 6):
per calendar month: `total_debits`, `total_credits`, `txn_count`, `by_category`,
`by_account`; plus `aggregate_debits` and `aggregate_by_category` across the window.
### `GET /statements/analysis` — month-at-a-glance:
`total_due_all_cards`, `min_due_all_cards`, `next_due_date`, latest statement per
card, `spend_by_category` (keyword categorizer in `app/api/routes/statements.py`),
and `top_merchants` over the combined feed (`days` param, default 45).

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
