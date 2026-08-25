# Priority mail operations

Priority uses a first-class account registry. The existing Money mailbox keeps
the `finance` purpose; the second Gmail account receives only the `priority`
purpose. Tokens remain in gog's protected credential store and are never saved
in Postgres or Android.

## In-app account setup

Settings now owns two independent connection slots: **Money email** and
**Priority email**. Connect, change, or reauthorize either one from the Android
app. Google owns account selection; LifeOS does not ask the user to type an
address before opening the consent page. The app listens briefly on a
phone-only loopback address for Google's callback.

The Jetson starts and completes a native Google OAuth flow with S256 PKCE.
Android receives only the authorization link and a short-lived pairing secret.
After Google returns to the phone, the Jetson exchanges the one-time code,
verifies the selected identity through Gmail, and imports the refresh token into
gog's encrypted file keyring through standard input. The token is never returned
to Android or written to Postgres. A connection session expires after five
minutes and cannot be replayed.

The OAuth client secret also remains in gog's encrypted file keyring. The API
image includes a narrow read-only helper that retrieves only that client secret
into the API process pipe for Google's token exchange; it is never copied into
the image, `.env`, logs, Android, or Postgres.

The existing Money account is bootstrapped into the account registry on the
first Settings load. Changing a slot disables its prior purpose assignment but
does not delete transactions, statements, Priority items, or mail history.

## Command-line fallback

The command-line flow remains available for recovery. Run on the Jetson from
`/home/ajmalrasi/life_os/backend` and replace the example address and label:

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
