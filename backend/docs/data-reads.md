# Native health and money read APIs

`GET /health/records` accepts `days` (default 14, 1–90), `start_date` and
`end_date`. Dates are inclusive, in Asia/Kolkata. The result contains supported
daily health measurements, workouts and freshness, using the same shared
`services/health_query.py` function as Ask. Missing readings stay unknown.

`POST /expenses/query` carries a validated `MoneyQuery` JSON body: mode,
inclusive dates, optional comparison dates, category/payee/account/source/amount
filters, grouping, sorting and a 1–50 detail-row limit. It uses Ask's existing
`services/money_query.py`. Full-window totals are computed before the row limit.
Spending excludes reconciled transfers/returns and includes EMI, as existing Ask
does. Statement detail rows and isolated history candidates are not added.

These routes are native LifeOS code. The backend does not install or import the
MCP package. Both follow normal `API_TOKEN` authentication, with the existing LAN
behavior when unset, and use database-enforced read-only request transactions.
PostgreSQL queries have a 15-second SQL statement timeout. No schema/config change,
model call, phone connection, collector or recompute is required.

LifeOS Ask currently calls the shared functions directly. MCP is a separate HTTP
client of these routes; migrating Ask's tool transport to MCP remains independent.
