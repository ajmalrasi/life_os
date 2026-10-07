# LifeOS web

A mobile web client for iPhone 17 and iPhone 12 mini, served privately from the Raspberry Pi. The Jetson remains the source of truth. No cloud hosting, developer subscription, or new frontend build tool is required.

## Included

- Today: model-written brief, workout coaching, Pulse check-in and optional note, Money snapshot, scores with their date, upcoming bills, and budget attention.
- Money: Overview / Transactions / Cards; day, week, month, six-month, year and custom ranges with previous-period comparisons and chart drilldowns; all transactions with sorting, multi-category, currency and proven bank-UPI filters; optional EMI; full statement history and line items, exact current-cycle purchases/refunds/net totals, latest unpaid and past-due bills; recurring charges with price changes; editable budgets, income and savings goal; category edits, refund and repayment matching with Undo, and recorded paid/unpaid bill status. Sync Money pulls bank mail and statements on the Pi and continues when the page closes.
- Priority: active mail, deadlines, event times, full messages on demand, and meeting/action links.
- Ask: streamed model answers, successful-read citations, and interrupted-answer handling. Conversation history lasts only in the open page.
- More: existing Insights, weekly recap, backed-up trip itineraries, and per-source freshness.

This is an online LAN client. It does not track location, access HealthKit, edit trips, or claim an offline cache. Existing Jetson collectors and iPhone Health Auto Export keep feeding LifeOS. Refresh reads stored data. No service worker is registered because the LAN HTTP address is not a secure browser context. Add to Home Screen is supported through the Apple web-app metadata and icon.

## Run

```sh
python3 web/server.py --host 127.0.0.1 --port 8090
```

The backend defaults to `http://192.168.3.30:8000`. Override with `LIFEOS_BACKEND_URL`. Set `LIFEOS_API_TOKEN` on the Pi if the Jetson's bearer-token gate is enabled. The token never enters frontend assets. `LIFEOS_ALLOWED_HOSTS` is a comma-separated hostname/IP allowlist, without ports.

## Pi deployment

Copy `server.py`, `public/`, and `ops/` to `/home/ajmalrasi/apps/lifeos-web/` on `rpi`. Install `ops/lifeos-web.service` in `~/.config/systemd/user/`, then:

```sh
systemctl --user daemon-reload
systemctl --user enable --now lifeos-web.service
```

Optional environment settings belong in `~/.config/lifeos-web.env` (mode 600). The service starts at boot when user linger is enabled. Port 8090 avoids existing apps on ports 80, 8000, and 8080. Open `http://192.168.3.20:8090/` on home Wi-Fi; in Safari choose Share → Add to Home Screen.

Access follows the existing trusted home-LAN model: this web app has no separate login. Do not expose or port-forward it to the public internet. For future remote access, add authenticated private HTTPS rather than exposing this HTTP listener. API requests pass through an allowlisted same-origin proxy; only Pulse, Ask and explicit Money record/settings actions are enabled. These actions annotate LifeOS records; they do not transfer money or pay bills. Cross-site requests are refused, API responses are not cached, email HTML is rendered as escaped text, and remote action links allow only HTTP(S). Existing app and backend configuration are untouched.

## Checks

```sh
node --test web/test_logic.mjs web/test_money.mjs
node --input-type=module --check < web/public/app.js
python3 -m unittest discover -s web -p 'test_server.py'
```

Money edits were verified in the browser against the isolated synthetic ledger in `test_browser_fixture.py` (port 8092), never the production database. Location history was lost with the wiped Android and cannot be inferred. Background bill notifications require a future secure iPhone notification setup; current due warnings appear in Cards.
