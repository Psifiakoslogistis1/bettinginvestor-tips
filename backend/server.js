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
  const h = parseInt(m[1], 10);
  const min = parseInt(m[2], 10);
  if (!Number.isFinite(h) || !Number.isFinite(min)) return -1;
  return h * 60 + min;
}
/**
 * Slate-relative minutes for tipDate ordering.
 * Times 00:00–07:59 are after-midnight NBA/etc. → treat as END of the slate (+24h)
 * so ~05:00 comes after ~20:30 on the same tipDate.
 * Unknown times → -1 (sort last in ascending / first-ish avoided via id).
 */
function slateSortMinutes(time) {
  const m = parseKickoffMinutes(time);
  if (m < 0) return -1;
  if (m < 8 * 60) return m + 24 * 60;
  return m;
}
/** Newest tipDate first; within day latest real kickoff first (after-midnight last on slate = top when newest-first). */
function sortTipsNewestFirst(rows) {
  return [...rows].sort((a, b) => {
    const da = a.tip_date || a.tipDate || '';
    const db_ = b.tip_date || b.tipDate || '';
    if (da !== db_) return db_.localeCompare(da);
    const ta = slateSortMinutes(a.tip_time || a.time);
    const tb = slateSortMinutes(b.tip_time || b.time);
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
  res.json({ ok: true, stubBilling: STUB_BILLING, dailyPassEur: DAILY_PASS_EUR, today: todayAthens(), version: '0.7.3b' });
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
    // Include settled tips too so FINAL scores remain visible on cards
    const map = rows.length ? await liveScores.scoresForTips(rows) : {};
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
  try { await syncMartingaleFromTips(); } catch (e) { console.warn('[mg-sync]', e.message); }
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
  const updated = await db.updateTip(id, patch);
  try { await syncMartingaleFromTips(); } catch (e) { console.warn('[mg-sync]', e.message); }
  res.json(mapTipFull(updated));
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
  try { await syncMartingaleFromTips(); } catch (e) { console.warn('[mg-sync]', e.message); }
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
  if (mg.rulesVersion !== 2 && level === 0) level = base;
  const mode = String(mg.starAfterLoss || 'x10only').toLowerCase();
  mg.baseStake = base;
  mg.starMultiplier = starMult;
  mg.lossMultiplier = lossMult;
  mg.levelStake = level;
  mg.cycleLosses = level;
  mg.rulesVersion = 2;
  mg.starAfterLoss = (mode === 'x10x2') ? 'x10x2' : 'x10only';
  mg.startingPool = mg.startingPool || 1000;
  return mg;
}
/** In recovery (after loss, level > base or step > 1). */
function mgInRecovery(mg) {
  mgNorm(mg);
  return mg.cycleStep > 1 || mg.levelStake > mg.baseStake + 1e-9;
}
/**
 * Desired stake. starAfterLoss:
 *  - x10only (default): Σημείο at base = level×starMult; after loss = level only (already ×10), no extra ×2
 *  - x10x2: always level×starMult for Σημείο
 */
function mgDesiredStake(mg, starOfDay) {
  mgNorm(mg);
  if (!starOfDay) return mgRound(mg.levelStake);
  if (mg.starAfterLoss === 'x10only' && mgInRecovery(mg)) return mgRound(mg.levelStake);
  return mgRound(mg.levelStake * mg.starMultiplier);
}
function mgComputeStake(mg, starOfDay) {
  const desired = mgDesiredStake(mg, starOfDay);
  const poolExceeded = desired > mg.pool + 1e-9;
  const stake = poolExceeded ? mgRound(mg.pool) : desired;
  let starMultApplied = 1;
  if (starOfDay) {
    starMultApplied = (mg.starAfterLoss === 'x10only' && mgInRecovery(mg)) ? 1 : mg.starMultiplier;
  }
  return { desired, stake, poolExceeded, levelBefore: mg.levelStake, starMultiplierApplied: starMultApplied };
}
function mgSortBetsNewestFirst(bets) {
  return [...(bets || [])].sort((a, b) => {
    const ka = tipDateTimeKey({ tip_date: a.tipDate, tip_time: a.time, id: a.id });
    const kb = tipDateTimeKey({ tip_date: b.tipDate, tip_time: b.time, id: b.id });
    if (ka !== kb) return kb - ka;
    return (b.id || 0) - (a.id || 0);
  });
}
function mgPublic(mg) {
  mgNorm(mg);
  const summary = mg.starAfterLoss === 'x10only'
    ? 'Base B. Σημείο στο base = B×starMult (×2→50). Μετά ήττα: level = level×lossMult (×10). Σημείο σε recovery = μόνο το level (όχι επιπλέον ×2). Νίκη → base. Stake>pool → όλο το pool.'
    : 'Base B. Σημείο = level×starMult πάντα. Μετά ήττα level×lossMult, μετά ×starMult αν Σημείο. Νίκη → base.';
  return {
    rulesVersion: 2,
    starAfterLoss: mg.starAfterLoss,
    ruleSummary: summary,
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
    bets: mgSortBetsNewestFirst(mg.bets),
    poolHistory: [mg.startingPool || 1000, ...[...(mg.bets || [])].filter((b) => b.poolAfter != null).sort((a, b) => a.id - b.id).map((b) => b.poolAfter)],
  };
}


function mgTipKey(t) {
  return `${t.tip_date || t.tipDate}|${String(t.match_name || t.matchName || '').trim().toLowerCase()}|${String(t.market || '').trim().toLowerCase()}`;
}
function mgBetKey(b) {
  return `${b.tipDate}|${String(b.matchName || '').trim().toLowerCase()}|${String(b.market || '').trim().toLowerCase()}`;
}
function mgApplySettle(mg, bet, won) {
  if (bet.resultWon !== null && bet.resultWon !== undefined) return;
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
  bet.resultWon = won;
  bet.profit = profit;
  bet.poolAfter = mg.pool;
}

function tipDateTimeKey(tip, kickoffMsMap) {
  const id = tip.id;
  if (kickoffMsMap && kickoffMsMap[id]) return kickoffMsMap[id];
  // Fallback: tipDate + tip_time; 00:00–07:59 → +1 calendar day (after-midnight end of slate)
  const dateStr = String(tip.tip_date || tip.tipDate || '1970-01-01');
  const mins = parseKickoffMinutes(tip.tip_time || tip.time);
  let day = dateStr;
  let m = mins;
  if (m >= 0 && m < 8 * 60) {
    // after-midnight → next calendar morning after tipDate evening
    const dt = new Date(dateStr + 'T12:00:00Z');
    dt.setUTCDate(dt.getUTCDate() + 1);
    day = dt.toISOString().slice(0, 10);
  }
  if (m < 0) m = 12 * 60; // unknown → midday
  return Date.parse(day + 'T00:00:00+03:00') + m * 60 * 1000;
}

function tipsChrono(tips, kickoffMsMap) {
  return [...tips].sort((a, b) => {
    const ka = tipDateTimeKey(a, kickoffMsMap);
    const kb = tipDateTimeKey(b, kickoffMsMap);
    if (ka !== kb) return ka - kb;
    return (a.id || 0) - (b.id || 0);
  });
}

/** Full rebuild from pool 1000 using all tips, sequential stakes, starAfterLoss rules. */
async function rebuildMartingaleFromTips(opts = {}) {
  const tips = tipsChrono(await db.allTips());
  const base = Number(opts.baseStake) > 0 ? Number(opts.baseStake) : 25;
  const starMult = Number(opts.starMultiplier) > 0 ? Number(opts.starMultiplier) : 2;
  const lossMult = Number(opts.lossMultiplier) > 0 ? Number(opts.lossMultiplier) : 10;
  const starAfterLoss = (String(opts.starAfterLoss || 'x10only').toLowerCase() === 'x10x2') ? 'x10x2' : 'x10only';
  const mg = mgNorm({
    rulesVersion: 2,
    starAfterLoss,
    pool: 1000,
    baseStake: base,
    starMultiplier: starMult,
    lossMultiplier: lossMult,
    levelStake: base,
    cycleStep: 1,
    cycleLosses: base,
    peakPool: 1000,
    maxDrawdown: 0,
    bets: [],
    startingPool: 1000,
  });
  let nextId = 1;
  for (const t of tips) {
    const star = !!t.star_of_day;
    const settled = t.result_won !== null && t.result_won !== undefined;
    const { desired, stake, poolExceeded, levelBefore, starMultiplierApplied } = mgComputeStake(mg, star);
    if (stake <= 0 && !settled) continue;
    const bet = {
      id: nextId++,
      tipId: t.id,
      tipDate: t.tip_date,
      matchName: t.match_name,
      market: t.market,
      odds: t.odds,
      stake: stake > 0 ? stake : mgRound(Math.min(desired, Math.max(mg.pool, 0))),
      desiredStake: desired,
      resultWon: null,
      profit: null,
      poolAfter: null,
      cycleStep: mg.cycleStep,
      levelBefore,
      cycleLossesBefore: levelBefore,
      starOfDay: star,
      starMultiplierApplied,
      lossMultiplier: mg.lossMultiplier,
      poolExceeded,
      time: t.tip_time || null,
      starAfterLoss: mg.starAfterLoss,
    };
    mg.bets.push(bet);
    if (settled) mgApplySettle(mg, bet, !!t.result_won);
  }
  mg._nextId = nextId;
  await db.saveMartingale(mg);
  return mg;
}

/** Append missing tips in chrono order with sequential stakes; settle from tip results. */
async function syncMartingaleFromTips() {
  const mg = mgNorm(await db.getMartingale());
  if (!mg.bets) mg.bets = [];
  // If empty, full rebuild
  if (!mg.bets.length) {
    return rebuildMartingaleFromTips({
      baseStake: mg.baseStake,
      starMultiplier: mg.starMultiplier,
      lossMultiplier: mg.lossMultiplier,
      starAfterLoss: mg.starAfterLoss,
    });
  }
  const tips = tipsChrono(await db.allTips());
  let changed = false;
  for (const t of tips) {
    const key = mgTipKey(t);
    let bet = mg.bets.find((b) => b.tipId === t.id || mgBetKey(b) === key);
    const settled = t.result_won !== null && t.result_won !== undefined;
    if (!bet) {
      const star = !!t.star_of_day;
      const { desired, stake, poolExceeded, levelBefore, starMultiplierApplied } = mgComputeStake(mg, star);
      if (stake <= 0 && !settled) continue;
      bet = {
        id: await db.nextMgId(),
        tipId: t.id,
        tipDate: t.tip_date,
        matchName: t.match_name,
        market: t.market,
        odds: t.odds,
        stake: stake > 0 ? stake : mgRound(Math.min(desired, Math.max(mg.pool, 0))),
        desiredStake: desired,
        resultWon: null,
        profit: null,
        poolAfter: null,
        cycleStep: mg.cycleStep,
        levelBefore,
        cycleLossesBefore: levelBefore,
        starOfDay: star,
        starMultiplierApplied,
        lossMultiplier: mg.lossMultiplier,
        poolExceeded,
        time: t.tip_time || null,
        starAfterLoss: mg.starAfterLoss,
      };
      mg.bets.push(bet);
      changed = true;
      if (settled) mgApplySettle(mg, bet, !!t.result_won);
    } else {
      if (bet.tipId == null) { bet.tipId = t.id; changed = true; }
      if (bet.time == null && t.tip_time) { bet.time = t.tip_time; changed = true; }
      if ((bet.resultWon === null || bet.resultWon === undefined) && settled) {
        mgApplySettle(mg, bet, !!t.result_won);
        changed = true;
      }
    }
  }
  if (changed) await db.saveMartingale(mg);
  return mg;
}

async function mgPublicWithLive(mg) {
  const pub = mgPublic(mg);
  try {
    const today = todayAthens();
    const tips = (await db.allTips()).filter((t) =>
      t.tip_date === today || t.result_won === null || t.result_won === undefined
    );
    const map = tips.length ? await liveScores.scoresForTips(tips) : {};
    pub.bets = pub.bets.map((b) => {
      const live = (b.tipId != null && map[b.tipId]) || null;
      return live ? { ...b, live } : b;
    });
  } catch (_) { /* ignore */ }
  return pub;
}


app.get('/api/martingale', auth, async (req, res) => {
  const user = await db.getUserById(req.user.id);
  if (!(user.role === 'admin' || await db.hasUnlock(user.id, todayAthens()))) {
    return res.status(402).json({ error: 'Απαιτείται ημερήσιο πάσο 0,50€ για Martingale (όλες οι επιλογές)', paywall: true, dailyPassEur: DAILY_PASS_EUR });
  }
  const mg = await syncMartingaleFromTips();
  res.json(await mgPublicWithLive(mg));
});

app.put('/api/martingale/settings', auth, adminOnly, async (req, res) => {
  const mg = mgNorm(await db.getMartingale());
  const base = Number(req.body.baseStake);
  const star = Number(req.body.starMultiplier);
  const loss = Number(req.body.lossMultiplier);
  if (Number.isFinite(base) && base > 0) mg.baseStake = base;
  if (Number.isFinite(star) && star > 0) mg.starMultiplier = star;
  if (Number.isFinite(loss) && loss > 0) mg.lossMultiplier = loss;
  if (req.body.starAfterLoss != null) {
    const m = String(req.body.starAfterLoss).toLowerCase();
    mg.starAfterLoss = m === 'x10x2' ? 'x10x2' : 'x10only';
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
  const starAfterLoss = (String(req.body.starAfterLoss || 'x10only').toLowerCase() === 'x10x2') ? 'x10x2' : 'x10only';
  const mg = {
    rulesVersion: 2, starAfterLoss,
    pool: 1000, baseStake: base, starMultiplier: star, lossMultiplier: loss,
    levelStake: base, cycleStep: 1, cycleLosses: base,
    peakPool: 1000, maxDrawdown: 0, bets: [], startingPool: 1000,
  };
  await db.saveMartingale(mg);
  res.json(mgPublic(mg));
});

app.post('/api/martingale/reseed', auth, adminOnly, async (req, res) => {
  const starAfterLoss = req.body && req.body.starAfterLoss ? String(req.body.starAfterLoss) : 'x10only';
  const rebuilt = await rebuildMartingaleFromTips({
    baseStake: req.body?.baseStake,
    starMultiplier: req.body?.starMultiplier,
    lossMultiplier: req.body?.lossMultiplier,
    starAfterLoss,
  });
  res.json(await mgPublicWithLive(rebuilt));
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
    console.log(`Tips API :${PORT} v0.7.3b stubBilling=${STUB_BILLING} today=${todayAthens()}`);
  });
  // background live-score poll (ESPN public JSON, no key)
  const tick = async () => {
    try {
      const today = todayAthens();
      const rows = (await db.allTips()).filter((t) => t.tip_date === today || t.result_won === null || t.result_won === undefined);
      await liveScores.scoresForTips(rows.slice(0, 80));
      await syncMartingaleFromTips();
    } catch (e) { console.warn('[live]', e.message); }
  };
  tick();
  setInterval(tick, liveScores.POLL_MS);
}
start().catch((e) => { console.error(e); process.exit(1); });