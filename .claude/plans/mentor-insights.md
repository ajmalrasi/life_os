# Mentor Insights + split Settings — implementation plan

Written 2026-09-24 by the planning model. Meant to be executed step by step by a
cheaper model. Read `AGENTS.md` first (deploy, build, device, and IST rules).
Do the stages in order; each stage ends in a verifiable state. Do not skip the
verification steps.

## Goal (in the user's words, condensed)

The Insights page is useless: it shows deterministic sleep/recovery/execution
correlation cards and "Key insights" built on thin evidence ("sleep averaged
22.2 over 3 days"). Replace it with **3-5 ranked, LLM-written insights that act
like a candid third-person mentor** across *all* data — money first, plus trips,
budgets, card bills, recurring charges, health, mood, and work. Examples the
user gave of the tone they want:

- "You spent ₹25,000 on electronics this month. That's too much for you."
- "Vehicle maintenance is costing you more than food delivery."
- "You're over your travel budget and have already booked more. Save instead."
- (ChatGPT once told them "you already have a car, you're making bad financial
  decisions" — that kind of honest judgement is the point.)

Also: income is not visible in the transaction emails, so the user will enter it
by hand. Settings must be split into sections: **General**, **Money** (income,
budgets, card reminders), and **Trips**.

## Non-negotiable rules

1. **The backend does all the arithmetic; the LLM only judges and phrases.**
   Every number in an insight must come from a fact computed in Python and
   passed in as evidence. Keep the existing citation rule: an insight whose
   `cites` has no valid evidence id is dropped.
2. **No canned/deterministic fallback insights.** If the model fails, keep the
   last good set (existing `SynthesizedInsightRepository.replace` already
   does this on empty input). Matches the AGENTS.md "no canned answers" rule.
3. **No specific investment/product recommendations** in the prompt output
   (no "buy this fund/stock"). Spending, saving, budgets, and habits only.
4. **Money figures must match the Money page.** Use the same effective
   category logic (`build_categorizer` + `spend_category_override`) and the same
   spend exclusions (`EXCLUDED_FROM_SPEND` from `app/services/reconcile.py`).
   EMI (`emi`, `emi_fees`) is included in totals but must be reported
   separately as "fixed commitments", never mixed into "discretionary" claims.
5. **IST dates.** Use `datetime.now(ZoneInfo("Asia/Kolkata")).date()` for
   "today", not `date.today()`.
6. **Model budget.** The runtime is Qwen3.5-4B via TensorRT EdgeLLM: 6,144 input
   tokens max, one sequence. Keep the evidence bundle under ~3,500 tokens
   (roughly ≤ 45 facts, each ≤ 200 chars). Use `InferenceWorkload.BACKGROUND`,
   `max_tokens=900`, temperature 0.5.
7. Commit messages must **not** contain Claude/Anthropic co-author lines
   (user's global rule).

---

## Stage 1 — Backend: money profile (income) storage

### 1a. Model + table
New file `backend/app/models/money_profile.py`:

```python
class MoneyProfile(Base):
    __tablename__ = "money_profile"
    id: Mapped[int] = mapped_column(primary_key=True)          # always 1 (singleton row)
    monthly_income: Mapped[Decimal | None] = mapped_column(Numeric(14, 2), nullable=True)
    savings_goal_pct: Mapped[int | None] = mapped_column(Integer, nullable=True)  # 0-90, optional
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=utcnow, onupdate=utcnow)
```
Register it in `app/models/__init__.py` (import + `__all__`). New table, so
`create_all` handles it — **no migration file needed**.

### 1b. Routes
New `backend/app/api/routes/money_profile.py`, prefix `/money/profile`:
- `GET /money/profile` → `{"monthly_income": float|null, "savings_goal_pct": int|null, "updated_at": iso|null}`.
  Returns nulls when no row exists (never 404).
- `PUT /money/profile` body `{"monthly_income": float|null, "savings_goal_pct": int|null}`.
  Validate: income `null` or `0 < x <= 10_000_000`; goal `null` or `0..90`. Upsert
  row id=1.
Pydantic schemas go in `app/schemas/` following the budgets pattern
(`app/api/routes/budgets.py` is the template). Register the router in
`app/main.py` **with** `dependencies=[Depends(require_token)]` like the others.

### 1c. Tests
`backend/tests/test_money_profile.py`: GET empty → nulls; PUT then GET
round-trips; invalid values → 422; PUT with nulls clears.

---

## Stage 2 — Backend: the evidence builder (`life_evidence.py`)

New file `backend/app/services/life_evidence.py`. Pure computation, no LLM.
Public function:

```python
def build_life_evidence(db: Session, today: date | None = None) -> list[EvidenceFact]
```
Reuse `EvidenceFact` from `insight_synthesizer.py`. Each builder below is a
private function wrapped in its own `try/except Exception: logger.debug(...)`
so one broken domain never kills the others. Format money as `₹12,345` (no
decimals). Ids are stable strings shown below.

Definitions used throughout:
- `mtd` = 1st of current month → today. `prev_mtd` = same day-range in the
  previous month (clamp the day to the previous month's length).
- `baseline` = the 3 full calendar months before the current month.
- "Spending transactions" = debit, `category not in EXCLUDED_FROM_SPEND`,
  effective category via `build_categorizer(db)` +
  `spend_category_override`. Write one helper `_spend_rows(db, start, end)`
  returning `(txn, effective_category)` tuples; reuse it everywhere. (Look at
  `money_query.py::_effective_category` and reuse it rather than re-implementing.)
- Days-into-month = `today.day`; days-in-month from `calendar.monthrange`.

### 2a. Income & savings (`money:income`, `money:savings`, `money:spend_vs_income`)
- If `MoneyProfile.monthly_income` set:
  - `money:income`: "Monthly take-home income (entered by the user): ₹X."
  - `money:spend_vs_income`: "Spent ₹A so far this month (day D of N), which is
    P% of monthly income. At this pace the month ends near ₹B (Q% of income)."
    Pace = `A / D * N`.
  - `money:savings`: last full month: income − last-month spend = saved ₹S
    (R% savings rate). If `savings_goal_pct` set, append "goal is G%".
- If income is NOT set: add one fact `money:income_missing`: "Monthly income is
  not set, so spending cannot be judged against income." (The prompt tells the
  model it may suggest setting it in Money settings, once, at most.)

### 2b. Month totals (`money:mtd`)
"This month so far: ₹A across N transactions, vs ₹B by the same day last month
(±P%). 3-month average full month: ₹C."

### 2c. Categories — the core of the mentor judgements
For each category with mtd spend ≥ ₹1,000 OR baseline monthly avg ≥ ₹1,000,
take the top 8 by mtd spend:
- `cat:<name>`: "<Label>: ₹A this month (S% of this month's spend), usual month
  ₹B (3-month avg). That is Xx the usual." Only include the "Xx" clause when
  baseline > 0; if baseline is 0, say "no spend in this category in the prior 3
  months".
- Use the same human labels as Android (`legal_fees` → "Professional services",
  etc.). Put a small `_CATEGORY_LABELS` dict in this file; check
  `android/.../presentation/money/` for the label source of truth and copy it.
- Mark `emi` / `emi_fees` facts with "(fixed EMI commitment, not
  discretionary)".

### 2d. Category comparisons (`cmp:<a>_vs_<b>`)
Precompute up to 3 pairwise comparisons so the 4B model doesn't have to do
arithmetic. Pick pairs among the top-6 mtd categories where the bigger one is
≥ 1.5x the smaller: "<A> (₹x) is Nx <B> (₹y) this month." Prefer pairs where
one side is an essential (`food`, `groceries`, `bills`, `utilities`,
`health`) and the other discretionary (`shopping`, `electronics`,
`entertainment`, `travel`, `vehicle`, `fuel`, `subscriptions`) — that is
exactly the "vehicle maintenance vs food" comparison the user wants.

### 2e. Big one-off purchases (`big:<txn_id>`)
Up to 3 single spending transactions in the last 30 days with amount
≥ max(₹5,000, 3x the median spend transaction of the baseline window):
"₹A to <counterparty> on <date> (<category>)."

### 2f. Budgets (`budget:<category>`)
From `budget_pace_summary(db, build_categorizer(db), today)`: "Budget for
<Label>: ₹L/month. Spent ₹S (P%) by day D; on pace for ₹Proj
[— crosses the limit around day X]." Include every budget (the user has few).

### 2g. Fixed commitments (`money:fixed`)
Sum of `emi`+`emi_fees` spend in the last full month + recurring charges
(`detect_recurring_charges(db, today=today)`, monthly = `median_amount`,
weekly ×4.3). "Fixed monthly commitments: EMIs ₹E + recurring ₹R = ₹T[, which is
P% of income]."
Also `recurring:price_up` for any charge with `price_change_pct ≥ 5`:
"<name> went up P% (₹old → ₹new)."
And `recurring:due_soon` for charges whose `next_expected_date` is within 7 days.

### 2h. Card bills (`cards:due`, `cards:cycle`)
- Unpaid `CardStatement` (paid_at null) with `due_date` between today and
  today+10: "Card bills due in the next 10 days: <bank ····last4> ₹X on <date>,
  …; total ₹T."
- `current_card_cycles(db, as_of=today).current_spend_total`: "Spend already on
  open credit-card cycles (not yet billed): ₹X."

### 2i. Trips (`trip:<id>`)
From `TripBackup` rows (deleted_at null). Payload shape (schema_version 2):
`trip{title,start_date,end_date,currency,status}`, `activities[{status,…}]`,
`expenses[{title,amount,currency,category,paid_at}]`. Include a trip if it is
active, starts within 45 days, or ended within the last 30 days (max 2 trips):
"Trip '<title>' <dates> (<active | starts in N days | ended N days ago>).
Trip expenses logged: ₹T (top: <cat> ₹x, <cat> ₹y). Activities done: d/t."
Only sum expenses in INR (or the trip currency; state it). Also add
`money:travel_vs_usual` if category `travel` exists: travel spend in the last
60 days vs the baseline monthly average ×2.

### 2j. Life signals (`health:*`, `mind:*`, `work:*`)
Last 7 days (ending yesterday) vs the 7 before, reusing
`ScoreRepository`/`MoodRepository`/`GithubRepository` and
`weekly_recap.week_bounds`:
- `health:sleep`: average sleep hours (sum `HealthRecordType.SLEEP_DURATION`
  per day via `HealthRepository.daily_values`, avg over days with data) and
  sleep score, both weeks, plus days with data.
- `health:activity`: avg steps and workout minutes, both weeks.
- `health:recovery`: avg recovery score, both weeks.
- `mind:pulse`: avg mood and stress, number of check-ins.
- `work:coding`: commits and active days, both weeks.
Skip any fact whose current week has < 3 days of data.
- `link:*`: include at most the 2 strongest rows from `InsightRepository.all()`
  with `sample_size >= 14`, as "Pattern in your data: <narrative or summary>
  (n days)". These are supporting context only.

### 2k. Budget the size
After building, if more than 45 facts, drop in this order until ≤ 45: `link:*`,
`work:*`, `health:activity`, extra `cat:*` beyond 6, `big:*` beyond 2.

### 2l. Tests — `backend/tests/test_life_evidence.py`
Seed SQLite with transactions over 4 months and assert on the strings:
- income set → `money:spend_vs_income` percent is correct; unset →
  `money:income_missing` present.
- category multiple ("3.0x the usual") computed correctly.
- transfers / `lent_returned` / `reversed_debit` rows are excluded.
- `spend_category_override` wins over the categorizer.
- comparison fact only emitted when ratio ≥ 1.5.
- budget fact present when a Budget row exists.
- trip fact for a trip starting in 10 days; ignored for one 90 days ago.
- a raising helper (monkeypatch one builder to raise) does not stop the others.
- fact count ≤ 45.

---

## Stage 3 — Backend: the mentor synthesizer

Rewrite `backend/app/services/insight_synthesizer.py` (keep module name, keep
`EvidenceFact`, keep `_parse`, keep the citation-grounding loop).

Changes:
- `_MAX_INSIGHTS = 5`. Do **not** pad: accept 3-5, fewer if the model returns
  fewer.
- `max_tokens=900`, temperature 0.5, timeout stays 45 s (background).
- New output object per item:
  `{"title": "<=8 words", "verdict": "1-2 sentences with the numbers",
    "action": "one concrete next step", "domain": "money|trip|health|mind|work|life",
    "severity": "high|medium|low", "cites": ["id", ...]}`
- Store: `title` → title, `body` → verdict + "\n" + action is **not** OK;
  instead add a nullable `action: String(300)` column and `severity:
  String(8)` column to `SynthesizedInsight`. **Existing table → requires a
  migration file** `backend/migrations/0003_insight_mentor_fields.sql`:
  ```sql
  ALTER TABLE synthesized_insights ADD COLUMN IF NOT EXISTS action VARCHAR(300);
  ALTER TABLE synthesized_insights ADD COLUMN IF NOT EXISTS severity VARCHAR(8);
  ```
  `theme` column is reused for `domain`. Update
  `SynthesizedInsightRepository.replace` to write the new fields, and
  `SynthesizedInsightOut` + `/analytics/synthesis` to return `action` and
  `severity` (nullable, so old rows/clients keep working).
- Ranking: keep model order but stable-sort by severity (high → medium → low).
- Validation: drop an item missing title/verdict/cites; clamp unknown domain to
  "life", unknown severity to "medium"; truncate action to 300.

System prompt (use this text; adjust wording only if a test forces it):

```
You are the user's personal mentor inside LifeOS: a sharp, honest third person
who looks at their real numbers and tells them what they need to hear. You are
given EVIDENCE: facts already computed from ONE user's own data (money,
budgets, card bills, recurring charges, trips, sleep, activity, mood, work).
Each fact has an "id". Money is Indian rupees.

Pick the 3 to 5 things that matter MOST right now and write one insight for
each. Prioritise: money problems first (overspending against income or
budget, a category far above its usual level, a big purchase, a costly
comparison between categories, bills due soon), then trips, then health, mood
and work. Skip anything that is normal or minor. Fewer, sharper insights beat
more.

For each insight:
- Give a clear judgement. Say plainly when something is too much, a bad
  decision, or a habit to fix, and say so when something is going well.
- Use the exact numbers from the facts you cite. Never invent or recompute a
  number, and never state a number that is not in a cited fact.
- Give one concrete, doable action (cut, cap, pause, move to savings, set a
  budget, sleep earlier, etc.).
- Treat patterns as associations, not causes.
- Do not recommend specific investments, funds, stocks or financial products.
- EMI facts are fixed commitments; do not call them impulsive spending.
- If monthly income is missing you may say, once, that setting it in Money
  settings would allow judging spending against income.
- Plain language, no markdown, no emojis. Talk to the user as "you".

Return ONLY a JSON array, no prose, no code fences. Each item exactly:
{"title": "<=8 words", "verdict": "1-2 sentences", "action": "one sentence",
 "domain": "money|trip|health|mind|work|life", "severity": "high|medium|low",
 "cites": ["id", ...]}
```

Include today's IST date as the first line of the user message
("Today is 2026-09-24 (day 24 of 30 in the month).") followed by the evidence
JSON (compact `json.dumps(payload, ensure_ascii=False)`, no indent — saves
tokens).

### 3b. Wiring
- `AnalyticsService.discover()` (in `analytics.py`): replace the
  `_build_evidence(...)` call with `build_life_evidence(self.db)`. Delete
  `_build_evidence` and its now-unused helpers (`_signal_label`, `_fmt`,
  `_avg`, `_week_trend`, `_SIGNAL_LABELS`) **only if** nothing else imports
  them (grep first). Correlation discovery + narrator stay (Today's
  spend-and-sleep card and Ask use them).
- New endpoint `POST /analytics/synthesis/refresh` in `analytics.py`: builds
  evidence, synthesizes, `replace`s, returns the same list as
  `GET /analytics/synthesis`. Guard with `try_job_lock` from
  `services/job_guard.py` (pattern: the `WEEKLY_RECAP_JOB` lock used by
  `POST /analytics/weekly-recap`), using a new `INSIGHTS_JOB` key; on
  collision return the current rows with HTTP 200. If synthesis returns `[]`,
  return current rows plus header `X-Insights-Stale: 1`.
- Ask: `chat_service.build_context` already includes `synthesized_insights`;
  add `action` there too.
- Remove the budget/insight overlap: nothing else.

### 3c. Tests
Update `backend/tests/test_insight_synthesis.py` (stub client returns canned
JSON):
- 7 items returned → 5 stored.
- item citing only unknown ids dropped.
- severity ordering high → low.
- missing action → stored with action null (not dropped).
- malformed JSON → `[]`, previous rows kept.
- `POST /analytics/synthesis/refresh` returns new rows; with stub raising
  `OpenClawUnavailable` returns old rows + stale header.
Run the full suite: `cd backend && pytest`. The known pre-existing failures
(`test_card_cycles`, `test_priority`, two in `test_statements`) are
acceptable; nothing else may fail.

---

## Stage 4 — Android: Insights page

Files: `presentation/insights/InsightsScreen.kt`, `InsightsViewModel.kt`,
`presentation/components/Components.kt` (`SynthesizedInsightCard`), plus the
data plumbing for `SynthesizedInsight` (grep lists: `di/AppModule.kt`,
`data/Mappers.kt`, `data/demo/DemoData.kt`, `data/local/Entities.kt`,
`data/local/Daos.kt`, `data/local/LifeOsDatabase.kt`,
`data/remote/dto/Dtos.kt`, `data/remote/LifeOsApi.kt`,
`domain/model/Models.kt`, `domain/repository/Repositories.kt`,
`data/repository/AnalyticsRepositoryImpl.kt`).

1. Add nullable `action: String?` and `severity: String?` to the DTO, Room
   entity, domain model, and mapper. Bump `LifeOsDatabase` version by 1 (it
   uses `fallbackToDestructiveMigration` — cache-only, safe).
2. `LifeOsApi`: `@POST("analytics/synthesis/refresh") suspend fun
   refreshSynthesis(): List<SynthesizedInsightDto>`.
   `AnalyticsRepository.refreshInsights()` calls it and replaces the Room
   cache.
3. `InsightsViewModel`: observe only synthesized insights (drop
   `observeInsights()` here). Expose `refreshing: Boolean`, `error: String?`.
   `fun refresh()` calls `refreshInsights()` inside `runCatching`. On init keep
   the existing cheap `refresh(90)` GET (does not hit the LLM).
4. `InsightsScreen`:
   - Header "Insights", subtitle "What your mentor thinks, from your money,
     trips, health and work."
   - `PullToRefreshBox` (Material3) wrapping the list → `viewModel.refresh()`.
     The LLM call can take up to ~45 s: show the indicator the whole time, and
     a small line "Thinking it over… this can take up to a minute."
   - Just the cards (max 5). **Remove** the "N correlations" section and
     `InsightCard` usage from this screen (leave `InsightCard` in
     Components.kt if used elsewhere; grep).
   - Empty state: "No insights yet. They're generated after each sync, or
     pull down to generate now." If income is unknown, nothing special here —
     the model will say it.
   - Offline: reuse `OfflineBanner(lastSyncAt)` pattern from Today.
5. `SynthesizedInsightCard` redesign:
   - Top row: domain chip (Money / Trip / Health / Mind / Work / Life) with a
     color per domain from existing `LifeOsColors`; a small severity dot
     (high = `LifeOsColors.Warning`/error tone, medium = accent, low = muted).
   - Title (titleSmall, SemiBold), verdict (bodyMedium).
   - If `action` present: a subtle row "→ <action>" in bodyMedium with the
     primary color, or a label "What to do" above it.
   - Remove the "Synthesized" label and "Grounded in N signals" line.
   - No en/em dashes in any copy (AGENTS.md rule).
6. Update `DemoData.synthesizedInsights` to the new shape (3 mentor-style
   examples) and delete `DemoData.insights` if unused after this change.

---

## Stage 5 — Android: split Settings into General / Money / Trips

Current `presentation/settings/SettingsScreen.kt` (658 lines) is one long
page. Restructure:

1. New routes in `presentation/LifeOsApp.kt`: `settings/money` and
   `settings/trips` (constants like `STATEMENT_DETAILS_ROUTE`). Map both to
   `Destination.SETTINGS` in the route→destination function near line 312 so
   the drawer highlights Settings and the shared back bar shows.
2. Top of the existing Settings screen: a group with two navigation rows,
   "Money" (subtitle "Income, budgets, bill reminders") and "Trips"
   (subtitle "Backup"). Everything else on the page stays as General.
3. **Move** the "Card due reminders" toggle (currently ~line 383, including its
   permission-launcher handling via `pendingPermissionTarget`) into the Money
   settings screen. Keep `SettingsViewModel` as the owner of that state, or
   share the same Hilt ViewModel — do not duplicate DataStore logic.
4. New `presentation/settings/MoneySettingsScreen.kt` +
   `MoneySettingsViewModel.kt`:
   - **Monthly income** (take-home, ₹) numeric field + optional **savings
     goal %** field + Save. Loads via `GET /money/profile`, saves via `PUT`.
     Add `MoneyProfileDto`, API calls, and methods on `MoneyRepository`.
     Not cached in Room (tiny, online-only; show an error text if offline).
     Helper text: "Used by Insights to judge spending against what you earn.
     Stored on your LifeOS server only."
   - **Budgets**: list all current budgets (`api.budgets()`), each row with
     category label, limit, and spent/pace line; tap to edit, and an
     "Add budget" button opening a category picker + amount. Reuse the existing
     `BudgetEditDialog` from the Money package (grep for it) and the same
     category list used by the Money categories sheet. After a change, refresh
     the Money budgets cache (`budgets_cache`) the same way the Money screen
     does, so Overview stays in sync.
   - **Card due reminders** toggle (moved here).
5. New `presentation/settings/TripSettingsScreen.kt`:
   - Toggle **"Back up trips automatically"** (DataStore key
     `trip_auto_backup`, default **on**), explained as "Lets Insights and Ask
     see your trips. Runs after you change a trip."
   - Implementation: in `TripRepository`, after any user edit that already
     goes through the repository (import, add/edit expense, activity status
     change, delete), if the setting is on, enqueue a one-time unique
     WorkManager job `trip_backup_<tripId>` (`ExistingWorkPolicy.REPLACE`,
     30 s initial delay, network-connected constraint) that calls
     `repository.backup(tripId)`. Create `TripBackupWorker` following
     `CardDueReminderWorker`'s Hilt worker pattern. Failures are silent but
     retried with WorkManager backoff (max 3).
   - Button "Back up all trips now" that enqueues the job for every trip.

---

## Stage 6 — Deploy & verify (do not skip)

1. Backend: `cd backend && pytest` (see acceptable failures above). Commit.
   Push. On the Jetson: `cd ~/life_os && git pull && cd backend && docker
   compose up -d --build api`, `curl -s localhost:8000/healthz`. Confirm the
   migration ran: `docker exec backend-db-1 psql -U lifeos -d lifeos -c
   "\d synthesized_insights"` shows `action` and `severity`.
2. Real-data check on the Jetson:
   - `curl -s -X PUT localhost:8000/money/profile -H 'content-type:
     application/json' -d '{"monthly_income": null}'` (leave unset until the
     user enters it from the app — do not invent their income).
   - Print the evidence: `docker exec backend-api-1 python -c "from
     app.database import SessionLocal; from app.services.life_evidence import
     build_life_evidence; db=SessionLocal(); [print(f.id, '|', f.statement)
     for f in build_life_evidence(db)]"` (check the actual session factory
     name in `app/database.py`). Cross-check 3 money numbers against the Money
     page (month total, top category, a budget).
   - `curl -s -X POST localhost:8000/analytics/synthesis/refresh | python3 -m
     json.tool`. Read every insight: are the numbers exactly from the
     evidence? Is there a clear judgement and an action? Money first? 3-5
     cards? If the output is bland or wrong, iterate on the prompt (not on
     canned text) and re-run. Record the final output in the commit/AGENTS.md
     note.
3. Android (from `android/`, with the JBR `JAVA_HOME` in AGENTS.md):
   `./gradlew testDebugUnitTest assembleDebug`, install with `adb install -r`,
   then **compare `adb shell dumpsys package com.lifeos.app | grep
   lastUpdateTime` against the APK mtime** (known pitfall). Screenshot and
   check: Insights page (light + dark), pull-to-refresh, Settings → Money
   (enter nothing for income; add then delete a test budget and confirm
   `GET /budgets` on the server), Settings → Trips toggle, and that
   Card due reminders now lives under Money.
4. Update `AGENTS.md` §7 with a dated entry describing this change (what
   moved, the new endpoints, the migration, the verified output), and update
   the memory note `lifeos-insights-pipeline.md` so it no longer says the
   Patterns screen shows correlations.

## Out of scope
- No changes to Ask's tool loop, Money accounting, card cycles, or the weekly
  recap (it can later reuse `build_life_evidence`, not now).
- No forecasting beyond the straight-line month pace that budgets already use.
