# BettingInvestor Tips v0.6

Live API + offline demo. Tabs: Επιλογές · Ημερήσια · Martingale.

## Admin

- Email: `bettinginvestor@hotmail.com`
- Password: set only via Render env `ADMIN_PASSWORD` (never commit secrets). Local/demo: put the same value in your private secrets file.

## Martingale

- Pool start: 1000€
- Base stake: **25€**
- Seeded with all settled Excel tips (31 bets, chronological)
- After loss: stake = (cycleLosses + base) / (odds − 1); win resets to base

## Backend (Render)

- Repo: `backend/` + free Postgres `DATABASE_URL`
- Required env: `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `DATABASE_URL`, `STUB_BILLING`

## Build

```bash
cd android && ./gradlew :app:assembleDebug
```
