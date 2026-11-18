# LifeOS

LifeOS is a private dashboard for spending, health, and the email that needs your attention. Use it on Android or in Safari on iPhone.

- **Money** shows spending, transactions, budgets, and credit card bills.
- **Today and Pulse** bring together Apple Health trends, a daily brief, and quick mood check-ins.
- **Priority** surfaces emails with deadlines, appointments, or actions.
- **Ask** answers questions about your data with a local AI model.

The API, database, and model run on a Jetson. A Raspberry Pi serves the iPhone web app on the home network.

## Get started

**iPhone** · On home Wi-Fi, open `http://192.168.3.20:8090/` in Safari and choose **Share → Add to Home Screen**.

**Android** · Open `android/` in Android Studio, run the app, and enter your LifeOS server address in **Settings**.

**Backend** · From the repository root, run the following with Docker Compose installed.

```sh
cd backend
cp -n .env.example .env
# Set POSTGRES_PASSWORD in .env before starting the services.
docker compose up -d --build
```

Once it starts, open `http://localhost:8000/docs` for the API reference.

## More details

[Web setup](web/README.md) · [Money](backend/docs/expenses-api.md) · [Priority mail](backend/docs/priority-mail.md) · [Ask](backend/docs/ask.md)
