# BettingInvestor Tips v0.7

Tabs: Επιλογές · Ημερήσια · Martingale. Live API on Render + offline seed fallback.

## Admin

- Email: `bettinginvestor@hotmail.com`
- Password: Render env `ADMIN_PASSWORD` only (never commit).

## Martingale (rules v2)

- Pool start **1000€**, base **25€** (admin-editable)
- All settled tips incl. Σημεία Ημέρας, chronological for progression
- Σημείο Ημέρας stake = **level × starMult** (default ×2 → 50€ at base)
- After **LOSS**: next level = **level × lossMult** (default ×10 on the pre-star level, not the doubled stake); then ×starMult again if next is Σημείο
- After **WIN**: level resets to base
- If desired stake > pool → stake entire pool + `poolExceeded` flag
- History UI: newest first; within day latest kickoff on top

## Live scores

- Backend polls ESPN public scoreboards ~60s (no API key)
- Tips API / `/api/live-scores` expose score + status when matched
- App shows red **LIVE** badge; polls ~45s. Optional auto-settle: `LIVE_AUTO_SETTLE=true`

## Backend

Required env: `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `JWT_SECRET`, `DATABASE_URL`, `STUB_BILLING`

```bash
cd backend && npm start
```

## Build

```bash
cd android && ./gradlew :app:assembleDebug
```
