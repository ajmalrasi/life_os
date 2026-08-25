# Priority mail operations

Priority uses a first-class account registry. The existing Money mailbox keeps
the `finance` purpose; the second Gmail account receives only the `priority`
purpose. Tokens remain in gog's protected credential store and are never saved
in Postgres or Android.

## One-time second-account setup

Run on the Jetson from `/home/ajmalrasi/life_os/backend` after deploying the new
API image. Replace the example address and label:

```bash
docker compose run --rm api gog auth add career@example.com \
  --services gmail --gmail-scope readonly --manual

docker compose run --rm api python scripts/register_mail_account.py \
  --account career@example.com --purpose priority --label "Career inbox"

curl -fsS -X POST http://127.0.0.1:8000/priority/sync/check
curl -fsS http://127.0.0.1:8000/mail/accounts
```

The first sync scans the trailing 120 days. Full bodies are used transiently;
LifeOS stores minimal metadata, a short verification preview, extracted fields,
classification evidence, and the source Gmail identifiers.

## Hourly unattended sync

Install the supplied timer on the Jetson:

```bash
sudo cp ops/systemd/lifeos-priority-sync.service /etc/systemd/system/
sudo cp ops/systemd/lifeos-priority-sync.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now lifeos-priority-sync.timer
systemctl list-timers lifeos-priority-sync.timer
```

The Jetson polls Gmail independently of the Android phone. A failed account
authorization is recorded against that account and never reported as an empty,
successful inbox. Other mail consumers continue running.

## Classification behavior

- Structured calendar invitations and deterministic action/date extraction run
  before semantic classification.
- Job digests, generic application receipts, newsletters, and educational
  interview content are suppressed unless they contain a direct action.
- Direct human messages, interviews, appointments, deadlines, documents,
  security actions, and travel confirmations become Priority items.
- Ambiguous candidates enter Review. Local-model review is off by default to
  avoid competing with Ask; enable it with `PRIORITY_LLM_ENABLED=true` only if
  desired.
- Confirmations, reschedules, cancellations, and follow-ups update one Priority
  thread while preserving source-message history.

## Calendar behavior

- A plain scheduled email shows **Add to calendar** and opens Android's standard
  pre-filled calendar insert screen. The user chooses the calendar and saves.
- A structured ICS invitation shows **Open calendar invite** so the calendar
  provider owns acceptance and duplicate handling.
- An unscheduled interview with a scheduling link shows **Choose a time**.

LifeOS does not request broad Calendar permissions or silently create events.
