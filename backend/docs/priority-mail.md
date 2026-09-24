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

Every message is classified by the local Qwen model; there is no keyword
classifier and no guessed fallback. `app/priority/classifier.py` makes two
calls, both under the EdgeLLM build's server-side JSON-schema guided decoding
(`response_format: json_schema`, strict), so enums and field types always hold:

1. **Triage** (every message): a system prompt describing Ajmal and the five
   categories returns `{reason, category, confidence}`. `reason` comes first so
   the model states its evidence before deciding. `none` suppresses the mail.
2. **Details** (only mail that passed triage): `{state, summary, action,
   deadline, event_start, event_end, company, role, location}` with times as
   `YYYY-MM-DDTHH:MM` IST.

Categories: `interview`, `job`, `event`, `account`, `personal`. Android shows
a filter chip only for categories (and states) that currently have mail, and
folds pre-model labels (`work`, `security`, `travel`, ...) into these five.

Deterministic code only gathers facts for the prompt (sender, whether Ajmal is
in To/Cc, Gmail's own category and Important marker, reply-thread flag,
attachments, a parsed calendar invite, what LifeOS already recorded for the
thread; long URLs are reduced to `[link: domain]`) and validates the output:
times must parse and fall between 60 days before and two years after receipt;
a `scheduled` item without a usable time becomes an action or informational; a
structured ICS invite is authoritative for event time and cancellation. Times
are kept only when the model's `deadline_quote`/`event_quote` appears verbatim
in the email. Code also applies the stated policy to the model's own triage
answers (selling, or mass mail / routine notice without a hiring process or a
required action, is suppressed) and suppresses `job` mail relayed by a known
job board; board mail with a real interview slot or test stays `interview`.

The guided decoder occasionally fails on some inputs (HTTP 500 "JSON output
was incomplete at the token limit" on emoji-heavy marketing mail). `chat_json`
then retries once unconstrained and validates the reply against the same
schema locally. A message that still fails five times is marked `failed` and
left out of the feed; a full sync retries it.

The Jetson engine decodes two sequences together, so the sync runs two
messages in parallel (`openclaw_max_concurrency=2`) and newest mail first. A
sync stops starting new messages after `priority_sync_budget_seconds` (100 s)
so an on-demand check fits Android's request timeout; the hourly timer picks
up the rest. If the model is busy or unreachable the message stays `retry`
and is classified on a later run. Confirmations, reschedules, cancellations,
and follow-ups update one Priority thread while preserving source history.

Evaluate prompt changes against real mail, not HTTP success:

```bash
OPENCLAW_BASE_URL=http://192.168.3.30:11434 \
  python scripts/evaluate_priority.py corpus.json --out results.json
```

## Reading the source email

Gmail's Android app exposes no supported deep link to one message (message
URLs, `googlegmail://` and `content://gmail-ls` forms all land on the inbox),
so **Open Gmail** launches the Gmail app itself, never the browser. The full
body is readable in-app through `GET /priority/{id}/message`, which fetches it
from Gmail on demand and never stores it.

## Calendar behavior

- A plain scheduled email shows **Add to calendar** and opens Android's standard
  pre-filled calendar insert screen. The user chooses the calendar and saves.
- A structured ICS invitation shows **Open calendar invite** so the calendar
  provider owns acceptance and duplicate handling.
- An unscheduled interview with a scheduling link shows **Choose a time**.

LifeOS does not request broad Calendar permissions or silently create events.
