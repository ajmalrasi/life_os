# One-month email parsing pilot

Run the existing financial-email parsers against mail received during one
calendar month, without the live collector, SQL writes or cursor updates.
The pilot is restricted to `mail2ajmalrasi@gmail.com`.

```sh
PYTHONPATH=backend python backend/scripts/parse_history_month.py \
  --month 2026-05 --account mail2ajmalrasi@gmail.com \
  --output-dir /private/history/may-2026 --workers 2
```

The configured read-only Gmail provider lists every message in the IST month
window, excluding Gmail's default Spam/Trash scope. It fully paginates, paces
searches, retries quota failures and preserves progress. Recognised HDFC, ICICI,
Axis and SBI sender domains include historical aliases. Other messages remain
metadata-only; their bodies are not fetched or sent to a model.

Financial message bodies are privately cached. The four existing deterministic
expense parsers run in registry order; the first nonempty result wins. Remaining
bank-message bodies use the existing `LlmBankParser` and configured local model.
`--no-llm` leaves those messages pending. Model failures remain retryable; a
zero-row model result is recorded as uncertain, not proof of no transaction.
`--workers 1|2` bounds message processing; pagination and checkpoint updates stay
sequential. Repeating the same command reuses source-matching parser results.
Helper/parser changes invalidate cached results while retaining message bodies.

Outputs include `checkpoint.json`, `pages/`, `messages/`, `results/`, `report.json`
and `report.md`, outside the repository. Source/parser hashes bind active results.
The report separates received month from extracted transaction dates and exposes
errors, rule/model outcomes and gross debit/credit sums by currency.

This is an email-body parser pilot. Statement emails are explicitly listed as
attachment-pending; no PDF attachment is downloaded or parsed. Gross debit totals
are not purchase spending: transfers, card repayments, refunds/reversals and
multiple emails for the same event may still require normal accounting treatment.
The pilot does not invent missing rows or change production Money.
