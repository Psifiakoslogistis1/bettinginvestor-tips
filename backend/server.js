const express = require('express');
const cors = require('cors');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const db = require('./db');
const liveScores = require('./liveScores');


function parseKickoffMinutes(time) {
  if (!time) return -1;
  let s = String(time).trim().replace(/^~/, '').trim();
  s = s.split(/[–—-]/)[0].trim();
  const m = s.match(/(\d{1,2}):(\d{2})/);
  if (!m) return -1;
  return parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
}
/** Newest tipDate first; within day latest kickoff first. */
function sortTipsNewestFirst(rows) {
  return [...rows].sort((a, b) => {
    const da = a.tip_date || a.tipDate || '';
    const db_ = b.tip_date || b.tipDate || '';
    if (da !== db_) return db_.localeCompare(da);
    const ta = parseKickoffMinutes(a.tip_time || a.time);
    const tb = parseKickoffMinutes(b.tip_time || b.time);
    if (ta !== tb) return tb - ta;
    return (b.id || 0) - (a.id || 0);
  });
}

const app = express();
const PORT = process.env.PORT || 3847;
const JWT_SECRET = process.env.JWT_SECRET || 'tips-dev-secret-change-me';
const STUB_BILLING = (process.env.STUB_BILLING || 'true').toLowerCase() !== 'false';
const DAILY_PASS_EUR = 0.5;

app.use(cors());
app.use(express.json({ limit: '1mb' }));

function todayAthens() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Athens', year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date());
}

function auth(req, res, next) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Απαιτείται σύνδεση' });
  try { req.user = jwt.verify(token, JWT_SECRET); next(); }
  catch { return res.status(401).json({ error: 'Άκυρο ή ληγμένο token' }); }
}

function optionalAuth(req, _res, next) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : null;
  if (token) { try { req.user = jwt.verify(token, JWT_SECRET); } catch { req.user = null; } }
  next();
}

function adminOnly(req, res, next) {
  if (req.user?.role !== 'admin') return res.status(403).json({ error: 'Μόνο διαχειριστής' });
  next();
}

function signUser(u) {
  return jwt.sign({ id: u.id, email: u.email, role: u.role, name: u.name }, JWT_SECRET, { expiresIn: '30d' });
}
function publicUser(u) { return { id: u.id, email: u.email, name: u.name, role: u.role }; }

function mapTipFull(t) {
  return {
    id: t.id, matchName: t.match_name, market: t.market, odds: t.odds, analysis: t.analysis,
    tipDate: t.tip_date, tipTime: t.tip_time, sport: t.sport, pickType: t.pick_type,
    starOfDay: !!t.star_of_day, stake: t.stake,
    resultWon: t.result_won === null || t.result_won === undefined ? null : !!t.result_won,
    resultDetail: t.result_detail, profit: t.profit, progressiveTotal: t.progressive_total, locked: false,
  };
}

function bucketStats(settled, keyFn) {
  const map = {};
  for (const t of settled) {
    const k = keyFn(t) || 'Άλλο';
    if (!map[k]) map[k] = { wins: 0, losses: 0, profit: 0 };
    if (t.result_won) map[k].wins++; else map[k].losses++;
    map[k].profit += t.profit || 0;
  }
  return Object.entries(map).map(([name, v]) => {
    const total = v.wins + v.losses;
    return {
      name, wins: v.wins, losses: v.losses, total,
      winPct: total ? Math.round((v.wins / total) * 1000) / 10 : 0,
      profit: Math.round(v.profit * 1000) / 1000,
    };
  }).sort((a, b) => b.total - a.total);
}

function buildStats(settled, systems) {
  const overall = bucketStats(settled, () => 'Γενική');
  const tipProfit = settled.reduce((s, t) => s + (t.profit || 0), 0);
  const systemProfit = (systems || []).reduce((s, x) => s + (x.profit || 0), 0);
  const lastProg = systems && systems.length ? systems[systems.length - 1].progressive : null;
  return {
    overall: overall[0] || { name: 'Γενική', wins: 0, losses: 0, total: 0, winPct: 0, profit: 0 },
    bySport: bucketStats(settled, (t) => t.sport),
    byPickType: bucketStats(settled, (t) => t.pick_type),
    tipsProfit: Math.round(tipProfit * 1000) / 1000,
    systemsProfit: Math.round(systemProfit * 1000) / 1000,
    systemsProgressive: lastProg,
    days: (systems || []).map((s) => ({
      tipDate: s.tip_date, systemName: s.system_name, stake: s.stake,
      profit: s.profit, progressive: s.progressive, dayProfit: s.day_profit,
    })),
    settledCount: settled.length,
  };
}

async function hasPaidAccess(user) {
  if (!user) return false;
  if (user.role === 'admin') return true;
  return await db.hasUnlock(user.id, todayAthens());
}

app.get('/api/health', async (_req, res) => {
  res.json({ ok: true, stubBilling: STUB_BILLING, dailyPassEur: DAILY_PASS_EUR, today: todayAthens(), version: '0.7.0' });
});

app.post('/api/auth/register', async (req, res) => {
  const email = String(req.body.email || '').trim().toLowerCase();
  const password = String(req.body.password || '');
  const name = String(req.body.name || '').trim();
  if (!email || !password || password.length < 6 || !name) {
    return res.status(400).json({ error: 'Συμπλήρωσε όνομα, email και κωδικό (τουλάχιστον 6 χαρακτήρες)' });
  }
  if (await db.getUserByEmail(email)) return res.status(409).json({ error: 'Το email χρησιμοποιείται ήδη' });
  const user = await db.createUser({ email, password_hash: bcrypt.hashSync(password, 10), name, role: 'user' });
  res.status(201).json({ token: signUser(user), user: publicUser(user) });
});

app.post('/api/auth/login', async (req, res) => {
  const email = String(req.body.email || '').trim().toLowerCase();
  const password = String(req.body.password || '');
  const user = await db.getUserByEmail(email);
  if (!user || !bcrypt.compareSync(password, user.password_hash)) {
    return res.status(401).json({ error: 'Λάθος email ή κωδικός' });
  }
  res.json({ token: signUser(user), user: publicUser(user) });
});

app.get('/api/me', auth, async (req, res) => {
  const user = await db.getUserById(req.user.id);
  if (!user) return res.status(401).json({ error: 'Χρήστης δεν βρέθηκε' });
  const tipDate = String(req.query.date || todayAthens());
  const unlockRow = await db.getUnlock(user.id, tipDate);
  res.json({
    user: publicUser(user),
    unlock: {
      tipDate,
      unlocked: (await hasPaidAccess(user)) || (await db.hasUnlock(user.id, tipDate)),
      expiresAt: unlockRow?.expires_at || null,
    },
    stubBilling: STUB_BILLING,
    dailyPassEur: DAILY_PASS_EUR,
  });
});

app.get('/api/stats/star', async (_req, res) => {
  res.json(buildStats((await db.allSettledTips()).filter((t) => t.star_of_day), []));
});

app.get('/api/stats/paid', auth, async (req, res) => {
  const user = await db.getUserById(req.user.id);
  if (!await hasPaidAccess(user)) {
    return res.status(402).json({ error: 'Απαιτείται ημερήσιο πάσο 0,50€', paywall: true, dailyPassEur: DAILY_PASS_EUR });
  }
  res.json(buildStats((await db.allSettledTips()).filter((t) => !t.star_of_day), await db.allSystems()));
});

app.get('/api/stats', async (_req, res) => {
  res.json(buildStats((await db.allSettledTips()).filter((t) => t.star_of_day), []));
});


async function tipsWithLive(rows) {
  try {
    const unsettled = rows.filter((t) => t.result_won === null || t.result_won === undefined);
    const map = unsettled.length ? await liveScores.scoresForTips(unsettled) : {};
    return rows.map((t) => {
      const base = mapTipFull(t);
      const live = map[t.id];
      return live ? { ...base, live } : base;
    });
  } catch {
    return rows.map(mapTipFull);
  }
}

app.get('/api/tips', optionalAuth, async (req, res) => {
  const kind = String(req.query.kind || 'star');
  const tipDate = req.query.date ? String(req.query.date) : null;
  const today = todayAthens();
  let user = null;
  if (req.user) user = await db.getUserById(req.user.id);

  const paidOk = await hasPaidAccess(user);

  let rows = tipDate ? await db.tipsByDate(tipDate) : await db.allTips();
  rows = sortTipsNewestFirst(rows);
  if (kind === 'star') {
    rows = rows.filter((t) => t.star_of_day);
    return res.json({
      kind: 'star',
      tipDate,
      today,
      paywall: false,
      dayUnlocked: true,
      tips: await tipsWithLive(rows),
      count: rows.length,
      dailyPassEur: DAILY_PASS_EUR,
    });
  }

  // paid / remaining bets
  rows = rows.filter((t) => !t.star_of_day);
  if (!paidOk) {
    return res.json({
      kind: 'paid',
      tipDate,
      today,
      paywall: true,
      dayUnlocked: false,
      tips: [],
      count: 0,
      system: null,
      dailyPassEur: DAILY_PASS_EUR,
      message: 'Ξεκλείδωσε με ημερήσιο πάσο 0,50€ για τα υπόλοιπα tips, ιστορικό και στατιστικά.',
    });
  }

  const system = tipDate ? await db.getSystem(tipDate) : null;
  res.json({
    kind: 'paid',
    tipDate,
    today,
    paywall: false,
    dayUnlocked: true,
    tips: await tipsWithLive(rows),
    count: rows.length,
    system: system
      ? {
          tipDate: system.tip_date, systemName: system.system_name, stake: system.stake,
          profit: system.profit, progressive: system.progressive, dayProfit: system.day_profit, locked: false,
        }
      : null,
    systems: tipDate ? null : (await db.allSystems()).map((s) => ({
      tipDate: s.tip_date, systemName: s.system_name, stake: s.stake,
      profit: s.profit, progressive: s.progressive, dayProfit: s.day_profit, locked: false,
    })),
    dailyPassEur: DAILY_PASS_EUR,
  });
});

app.post('/api/tips', auth, adminOnly, async (req, res) => {
  const matchName = String(req.body.matchName || '').trim();
  const market = String(req.body.market || '').trim();
  const odds = Number(req.body.odds);
  const analysis = String(req.body.analysis || '').trim();
  const tipDate = String(req.body.tipDate || todayAthens()).trim();
  const tipTime = req.body.tipTime ? String(req.body.tipTime).trim() : null;
  const sport = String(req.body.sport || 'Άλλο').trim();
  const pickType = req.body.pickType ? String(req.body.pickType).trim() : null;
  const starOfDay = !!req.body.starOfDay;
  const stake = req.body.stake != null ? Number(req.body.stake) : null;
  if (!matchName || !market || !Number.isFinite(odds) || odds <= 1) {
    return res.status(400).json({ error: 'Συμπλήρωσε αγώνα, αγορά και απόδοση (>1)' });
  }
  const tip = await db.createTip({
    match_name: matchName, market, odds, analysis, tip_date: tipDate, tip_time: tipTime,
    sport, pick_type: pickType, star_of_day: starOfDay, stake, created_by: req.user.id,
  });
  res.status(201).json(mapTipFull(tip));
});

app.put('/api/tips/:id', auth, adminOnly, async (req, res) => {
  const id = Number(req.params.id);
  const existing = await db.getTip(id);
  if (!existing) return res.status(404).json({ error: 'Δεν βρέθηκε' });
  const patch = {
    match_name: String(req.body.matchName ?? existing.match_name).trim(),
    market: String(req.body.market ?? existing.market).trim(),
    odds: Number(req.body.odds ?? existing.odds),
    analysis: String(req.body.analysis ?? existing.analysis).trim(),
    tip_date: String(req.body.tipDate ?? existing.tip_date).trim(),
    tip_time: req.body.tipTime !== undefined ? (String(req.body.tipTime || '') || null) : existing.tip_time,
    sport: String(req.body.sport ?? existing.sport).trim(),
    pick_type: req.body.pickType !== undefined ? (req.body.pickType ? String(req.body.pickType) : null) : existing.pick_type,
    star_of_day: req.body.starOfDay !== undefined ? (req.body.starOfDay ? 1 : 0) : existing.star_of_day,
    stake: req.body.stake !== undefined ? (req.body.stake == null ? null : Number(req.body.stake)) : existing.stake,
  };
  if (req.body.resultWon !== undefined) {
    patch.result_won = req.body.resultWon === null ? null : req.body.resultWon ? 1 : 0;
  }
  if (req.body.resultDetail !== undefined) patch.result_detail = req.body.resultDetail;
  if (req.body.profit !== undefined) patch.profit = req.body.profit;
  res.json(mapTipFull(await db.updateTip(id, patch)));
});


app.get('/api/live-scores', optionalAuth, async (req, res) => {
  const tipDate = String(req.query.date || todayAthens());
  const rows = await db.tipsByDate(tipDate);
  // also include unsettled nearby dates
  const all = rows.length ? rows : (await db.allTips()).filter((t) => t.tip_date === tipDate);
  const unsettled = (await db.allTips()).filter((t) => t.result_won === null || t.result_won === undefined);
  const tips = [...new Map([...all, ...unsettled].map((t) => [t.id, t])).values()];
  const map = await liveScores.scoresForTips(tips);
  // optional auto-settle finals (admin override later via tip edit)
  if (process.env.LIVE_AUTO_SETTLE === 'true') {
    for (const tip of tips) {
      const live = map[tip.id];
      if (!live || !live.final) continue;
      if (tip.result_won !== null && tip.result_won !== undefined) continue;
      const inferred = liveScores.inferResult(tip, live);
      if (inferred === null) continue;
      const stake = tip.stake || 0;
      const profit = inferred ? Number(((stake) * (tip.odds - 1)).toFixed(3)) : -Number(stake);
      await db.updateTip(tip.id, { result_won: inferred ? 1 : 0, profit, result_detail: inferred ? 'Νίκη (auto live)' : 'Ήττα (auto live)' });
      live.autoSettled = inferred;
    }
  }
  res.json({ date: tipDate, updatedAt: new Date().toISOString(), pollSeconds: 60, scores: map });
});

app.delete('/api/tips/:id', auth, adminOnly, async (req, res) => {
  if (!await db.deleteTip(Number(req.params.id))) return res.status(404).json({ error: 'Δεν βρέθηκε' });
  res.json({ ok: true });
});

app.post('/api/unlock', auth, async (req, res) => {
  const tipDate = String(req.body.tipDate || todayAthens());
  const stub = !!req.body.stub;
  const purchaseToken = req.body.purchaseToken ? String(req.body.purchaseToken) : null;
  const productId = String(req.body.productId || 'daily_pass');
  if (productId !== 'daily_pass') return res.status(400).json({ error: 'Άγνωστο προϊόν' });
  if (stub) {
    if (!STUB_BILLING) return res.status(403).json({ error: 'Το stub billing είναι απενεργοποιημένο στον server' });
  } else {
    if (!purchaseToken) return res.status(400).json({ error: 'Απαιτείται purchaseToken ή stub:true' });
    if (process.env.PLAY_VERIFY_DISABLED !== 'true') {
      return res.status(501).json({ error: 'Η επαλήθευση Google Play δεν έχει ρυθμιστεί ακόμα. Χρησιμοποίησε stub mode.' });
    }
  }
  const expiresAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().replace('T', ' ').slice(0, 19);
  await db.upsertUnlock({ user_id: req.user.id, tip_date: tipDate, expires_at: expiresAt, purchase_token: purchaseToken, stub: stub ? 1 : 0 });
  res.json({ ok: true, tipDate, expiresAt, stub, dailyPassEur: DAILY_PASS_EUR });
});


function mgRound(v) { return Math.round(v * 100) / 100; }
function mgNorm(mg) {
  const base = Number(mg.baseStake) > 0 ? Number(mg.baseStake) : 25;
  const starMult = Number(mg.starMultiplier) > 0 ? Number(mg.starMultiplier) : 2;
  const lossMult = Number(mg.lossMultiplier) > 0 ? Number(mg.lossMultiplier) : 10;
  let level = mg.levelStake != null ? Number(mg.levelStake) : Number(mg.cycleLosses);
  if (!Number.isFinite(level) || level <= 0) level = base;
  // legacy recovery-formula seeds used cycleLosses as accumulated €; rules v2 uses it as levelStake
  if (mg.rulesVersion !== 2 && level === 0) level = base;
  mg.baseStake = base;
  mg.starMultiplier = starMult;
  mg.lossMultiplier = lossMult;
  mg.levelStake = level;
  mg.cycleLosses = level; // persisted in PG cycle_losses column
  mg.rulesVersion = 2;
  mg.startingPool = mg.startingPool || 1000;
  return mg;
}
/** Desired stake before pool cap. starOfDay applies starMultiplier on current levelStake. */
function mgDesiredStake(mg, starOfDay) {
  mgNorm(mg);
  return mgRound(mg.levelStake * (starOfDay ? mg.starMultiplier : 1));
}
function mgComputeStake(mg, starOfDay) {
  const desired = mgDesiredStake(mg, starOfDay);
  const poolExceeded = desired > mg.pool + 1e-9;
  const stake = poolExceeded ? mgRound(mg.pool) : desired;
  return { desired, stake, poolExceeded, levelBefore: mg.levelStake, starMultiplierApplied: starOfDay ? mg.starMultiplier : 1 };
}
function mgPublic(mg) {
  mgNorm(mg);
  return {
    rulesVersion: 2,
    ruleSummary:
      'Base B. Σημείο Ημέρας = level×starMult (προεπιλογή ×2). Μετά από ήττα: next level = level×lossMult (προεπιλογή ×10), μετά ×starMult αν το επόμενο είναι Σημείο. Νίκη → reset στο base. Αν το stake > pool, ποντάρουμε όλο το pool (flag). Το ×10 εφαρμόζεται στο base-level (πριν το ×2), όχι στο διπλασιασμένο ποσό που παίχτηκε.',
    pool: mg.pool,
    baseStake: mg.baseStake,
    starMultiplier: mg.starMultiplier,
    lossMultiplier: mg.lossMultiplier,
    levelStake: mg.levelStake,
    cycleStep: mg.cycleStep,
    cycleLosses: mg.levelStake,
    peakPool: mg.peakPool,
    maxDrawdown: mg.maxDrawdown,
    startingPool: mg.startingPool || 1000,
    bets: [...(mg.bets || [])].sort((a, b) => b.id - a.id),
    poolHistory: [mg.startingPool || 1000, ...[...(mg.bets || [])].filter((b) => b.poolAfter != null).sort((a, b) => a.id - b.id).map((b) => b.poolAfter)],
  };
}

app.get('/api/martingale', auth, async (req, res) => {
  const user = await db.getUserById(req.user.id);
  if (!(user.role === 'admin' || await db.hasUnlock(user.id, todayAthens()))) {
    return res.status(402).json({ error: 'Απαιτείται ημερήσιο πάσο 0,50€', paywall: true });
  }
  res.json(mgPublic(await db.getMartingale()));
});

app.put('/api/martingale/settings', auth, adminOnly, async (req, res) => {
  const mg = mgNorm(await db.getMartingale());
  const base = Number(req.body.baseStake);
  const star = Number(req.body.starMultiplier);
  const loss = Number(req.body.lossMultiplier);
  if (Number.isFinite(base) && base > 0) mg.baseStake = base;
  if (Number.isFinite(star) && star > 0) mg.starMultiplier = star;
  if (Number.isFinite(loss) && loss > 0) mg.lossMultiplier = loss;
  // if currently at "fresh" level equal to old base intuition, keep level at base when base changes and step==1 with no open recovery
  if (mg.cycleStep === 1 && Number.isFinite(base) && base > 0) {
    // only bump level if it still equals previous default-ish; safer: if level was old base, sync
  }
  await db.saveMartingale(mg);
  res.json(mgPublic(mg));
});

app.put('/api/martingale/base', auth, adminOnly, async (req, res) => {
  const base = Number(req.body.baseStake);
  if (!Number.isFinite(base) || base <= 0) return res.status(400).json({ error: 'Άκυρο base stake' });
  const mg = mgNorm(await db.getMartingale());
  mg.baseStake = base;
  await db.saveMartingale(mg);
  res.json(mgPublic(mg));
});

app.post('/api/martingale/reset', auth, adminOnly, async (req, res) => {
  const base = Number(req.body.baseStake) || 25;
  const star = Number(req.body.starMultiplier) || 2;
  const loss = Number(req.body.lossMultiplier) || 10;
  const mg = {
    rulesVersion: 2,
    pool: 1000, baseStake: base, starMultiplier: star, lossMultiplier: loss,
    levelStake: base, cycleStep: 1, cycleLosses: base,
    peakPool: 1000, maxDrawdown: 0, bets: [], startingPool: 1000,
  };
  await db.saveMartingale(mg);
  res.json(mgPublic(mg));
});

app.post('/api/martingale/reseed', auth, adminOnly, async (req, res) => {
  const seeded = await db.reseedMartingale();
  res.json(mgPublic(seeded));
});

app.post('/api/martingale/picks', auth, adminOnly, async (req, res) => {
  const matchName = String(req.body.matchName || '').trim();
  const market = String(req.body.market || '').trim();
  const odds = Number(req.body.odds);
  const tipDate = String(req.body.tipDate || todayAthens());
  const starOfDay = !!req.body.starOfDay;
  if (!matchName || !market || !Number.isFinite(odds) || odds <= 1) {
    return res.status(400).json({ error: 'Συμπλήρωσε αγώνα, αγορά, απόδοση' });
  }
  const mg = mgNorm(await db.getMartingale());
  const { desired, stake, poolExceeded, levelBefore, starMultiplierApplied } = mgComputeStake(mg, starOfDay);
  if (stake <= 0) return res.status(400).json({ error: 'Το pool είναι 0 — δεν μπορεί να πονταριστεί' });
  const bet = {
    id: await db.nextMgId(), tipDate, matchName, market, odds, stake, desiredStake: desired,
    resultWon: null, profit: null, poolAfter: null,
    cycleStep: mg.cycleStep, levelBefore, cycleLossesBefore: levelBefore,
    starOfDay, starMultiplierApplied, lossMultiplier: mg.lossMultiplier, poolExceeded,
  };
  mg.bets.push(bet);
  await db.saveMartingale(mg);
  res.status(201).json(bet);
});

app.post('/api/martingale/picks/:id/settle', auth, adminOnly, async (req, res) => {
  const id = Number(req.params.id);
  const won = !!req.body.won;
  const mg = mgNorm(await db.getMartingale());
  const bet = mg.bets.find((b) => b.id === id);
  if (!bet) return res.status(404).json({ error: 'Δεν βρέθηκε' });
  if (bet.resultWon !== null && bet.resultWon !== undefined) return res.status(400).json({ error: 'Ήδη settled' });
  const profit = won ? mgRound(bet.stake * (bet.odds - 1)) : mgRound(-bet.stake);
  mg.pool = mgRound(mg.pool + profit);
  mg.peakPool = Math.max(mg.peakPool, mg.pool);
  mg.maxDrawdown = Math.max(mg.maxDrawdown, mgRound(mg.peakPool - mg.pool));
  const levelBefore = Number(bet.levelBefore != null ? bet.levelBefore : mg.levelStake);
  if (won) {
    mg.levelStake = mg.baseStake;
    mg.cycleLosses = mg.baseStake;
    mg.cycleStep = 1;
  } else {
    mg.levelStake = mgRound(levelBefore * mg.lossMultiplier);
    mg.cycleLosses = mg.levelStake;
    mg.cycleStep += 1;
  }
  bet.resultWon = won; bet.profit = profit; bet.poolAfter = mg.pool;
  await db.saveMartingale(mg);
  res.json({ bet, state: mgPublic(mg) });
});


async function start() {
  if (db.ready) await db.ready();
  app.listen(PORT, () => {
    console.log(`Tips API :${PORT} v0.7.0 stubBilling=${STUB_BILLING} today=${todayAthens()}`);
  });
  // background live-score poll (ESPN public JSON, no key)
  const tick = async () => {
    try {
      const today = todayAthens();
      const rows = (await db.allTips()).filter((t) => t.tip_date === today || t.result_won === null || t.result_won === undefined);
      await liveScores.scoresForTips(rows.slice(0, 80));
    } catch (e) { console.warn('[live]', e.message); }
  };
  tick();
  setInterval(tick, liveScores.POLL_MS);
}
start().catch((e) => { console.error(e); process.exit(1); });