# BettingInvestor Tips v0.7.1

Tabs: **Σημείο Ημέρας** (δωρεάν) · **Martingale** (0,50€/ημέρα — όλες οι επιλογές) · **Πληροφορίες**.

## Admin
- Email: `bettinginvestor@hotmail.com`
- Password: Render env `ADMIN_PASSWORD` only (never commit).

## Martingale (rules v2)
- Pool start **1000€**, base **25€**
- Auto-sync από όλα τα published tips (ημερήσια + Σημείο Ημέρας)
- Σημείο Ημέρας stake = level ×2 · μετά ήττα next level = level ×10 · νίκη → base
- Live scores στα pending bets · auto-settle όταν `LIVE_AUTO_SETTLE=true`

## Live scores
- ESPN public boards (~60s), incl. tennis `groupings[].competitions`
- App LIVE badge · poll ~45s

## Backend env
`ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `DATABASE_URL`, `STUB_BILLING`, `LIVE_AUTO_SETTLE`
