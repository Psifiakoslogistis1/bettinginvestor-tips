const bcrypt = require('bcryptjs');
const path = require('path');
const fs = require('fs');

const DATABASE_URL = process.env.DATABASE_URL || process.env.POSTGRES_URL || '';
const usePg = !!DATABASE_URL;

function todaySeedAdmin() {
  return {
    email: (process.env.ADMIN_EMAIL || 'bettinginvestor@hotmail.com').toLowerCase(),
    password: process.env.ADMIN_PASSWORD || (() => { throw new Error('ADMIN_PASSWORD env required'); })(),
    name: process.env.ADMIN_NAME || 'Evangelos',
  };
}

/** ---------- JSON fallback (local/dev) ---------- */
function createJsonDb() {
  const dataDir = process.env.DATA_DIR || path.join(__dirname, 'data');
  fs.mkdirSync(dataDir, { recursive: true });
  const dbPath = path.join(dataDir, 'tips-store.json');

  function load() {
    if (!fs.existsSync(dbPath)) {
      return {
        users: [], tips: [], daily_systems: [], unlocks: [],
        martingale: { pool: 1000, baseStake: 25, cycleStep: 1, cycleLosses: 0, peakPool: 1000, maxDrawdown: 0, bets: [] },
        seq: { users: 1, tips: 1, unlocks: 1, systems: 1, mg: 1 },
      };
    }
    const store = JSON.parse(fs.readFileSync(dbPath, 'utf8'));
    if (!store.martingale) {
      store.martingale = { pool: 1000, baseStake: 25, cycleStep: 1, cycleLosses: 0, peakPool: 1000, maxDrawdown: 0, bets: [] };
    }
    if (!store.seq) store.seq = { users: 1, tips: 1, unlocks: 1, systems: 1, mg: 1 };
    if (!store.seq.mg) store.seq.mg = 1;
    return store;
  }
  function save(store) { fs.writeFileSync(dbPath, JSON.stringify(store, null, 2)); }
  let store = load();

  function ensureAdmin() {
    const { email, password, name } = todaySeedAdmin();
    let u = store.users.find((x) => x.email.toLowerCase() === email);
    if (!u) {
      u = {
        id: store.seq.users++, email, password_hash: bcrypt.hashSync(password, 10),
        name, role: 'admin', created_at: new Date().toISOString(),
      };
      store.users.push(u); save(store);
      console.log('[seed] Admin created:', email);
    } else {
      u.role = 'admin';
      if (process.env.ADMIN_PASSWORD) u.password_hash = bcrypt.hashSync(password, 10);
      save(store);
    }
    return u.id;
  }

  function seedHistory(adminId) {
    if (store.tips.length > 0) return;
    const seedPath = path.join(__dirname, 'seed-data.json');
    if (!fs.existsSync(seedPath)) return;
    const data = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
    for (const t of data.tips || []) {
      store.tips.push({
        id: store.seq.tips++, match_name: t.matchName, market: t.market, odds: t.odds,
        analysis: t.analysis || '', tip_date: t.tipDate, tip_time: t.time || null,
        sport: t.sport || 'Άλλο', pick_type: t.pickType || null, star_of_day: t.starOfDay ? 1 : 0,
        stake: t.stake ?? null,
        result_won: t.resultWon === true ? 1 : t.resultWon === false ? 0 : null,
        result_detail: t.resultDetail || null, profit: t.profit ?? null,
        progressive_total: t.progressiveTotal ?? null, created_by: adminId,
        created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
      });
    }
    for (const s of data.systems || []) {
      store.daily_systems.push({
        id: store.seq.systems++, tip_date: s.tipDate, system_name: s.systemName,
        stake: s.stake ?? null, profit: s.profit ?? null, progressive: s.progressive ?? null, day_profit: s.dayProfit ?? null,
      });
    }
    save(store);
    console.log(`[seed] Loaded ${(data.tips || []).length} tips`);
  }

  function seedMartingale() {
    const mgPath = path.join(__dirname, 'martingale-seed.json');
    if (!fs.existsSync(mgPath)) return;
    // Only seed if empty bets
    if (store.martingale && store.martingale.bets && store.martingale.bets.length) return;
    const mg = JSON.parse(fs.readFileSync(mgPath, 'utf8'));
    store.martingale = {
      pool: mg.pool, baseStake: mg.baseStake || 25, cycleStep: mg.cycleStep,
      cycleLosses: mg.cycleLosses, peakPool: mg.peakPool, maxDrawdown: mg.maxDrawdown,
      bets: mg.bets || [],
    };
    store.seq.mg = mg.nextId || ((mg.bets || []).length + 1);
    save(store);
    console.log(`[seed] Martingale ${store.martingale.bets.length} bets, pool=${store.martingale.pool}`);
  }

  const adminId = ensureAdmin();
  seedHistory(adminId);
  seedMartingale();

  const api = {
    async ready() { return true; },
    getUserByEmail(email) {
      return store.users.find((u) => u.email.toLowerCase() === String(email).toLowerCase()) || null;
    },
    getUserById(id) { return store.users.find((u) => u.id === id) || null; },
    createUser({ email, password_hash, name, role }) {
      const u = { id: store.seq.users++, email: email.toLowerCase(), password_hash, name, role: role || 'user', created_at: new Date().toISOString() };
      store.users.push(u); save(store); return u;
    },
    tipsByDate(tipDate) {
      return store.tips.filter((t) => t.tip_date === tipDate).sort((a, b) => (b.star_of_day - a.star_of_day) || (a.id - b.id));
    },
    allTips() {
      return [...store.tips].sort((a, b) => b.tip_date.localeCompare(a.tip_date) || (b.star_of_day - a.star_of_day) || (a.id - b.id));
    },
    allSettledTips() { return store.tips.filter((t) => t.result_won !== null && t.result_won !== undefined); },
    getTip(id) { return store.tips.find((t) => t.id === id) || null; },
    createTip(fields) {
      const tip = {
        id: store.seq.tips++, match_name: fields.match_name, market: fields.market, odds: fields.odds,
        analysis: fields.analysis || '', tip_date: fields.tip_date, tip_time: fields.tip_time || null,
        sport: fields.sport || 'Άλλο', pick_type: fields.pick_type || null, star_of_day: fields.star_of_day ? 1 : 0,
        stake: fields.stake ?? null, result_won: fields.result_won ?? null, result_detail: fields.result_detail || null,
        profit: fields.profit ?? null, progressive_total: fields.progressive_total ?? null, created_by: fields.created_by,
        created_at: new Date().toISOString(), updated_at: new Date().toISOString(),
      };
      store.tips.push(tip); save(store); return tip;
    },
    updateTip(id, patch) {
      const tip = store.tips.find((t) => t.id === id); if (!tip) return null;
      Object.assign(tip, patch, { updated_at: new Date().toISOString() }); save(store); return tip;
    },
    deleteTip(id) {
      const before = store.tips.length; store.tips = store.tips.filter((t) => t.id !== id); save(store);
      return before !== store.tips.length;
    },
    getSystem(tipDate) { return store.daily_systems.find((s) => s.tip_date === tipDate) || null; },
    allSystems() { return [...store.daily_systems].sort((a, b) => a.tip_date.localeCompare(b.tip_date)); },
    hasUnlock(userId, tipDate) {
      const row = store.unlocks.find((u) => u.user_id === userId && u.tip_date === tipDate);
      if (!row) return false;
      return new Date(row.expires_at.replace(' ', 'T') + 'Z') > new Date() || new Date(row.expires_at) > new Date();
    },
    getUnlock(userId, tipDate) { return store.unlocks.find((u) => u.user_id === userId && u.tip_date === tipDate) || null; },
    upsertUnlock({ user_id, tip_date, expires_at, purchase_token, stub }) {
      let row = store.unlocks.find((u) => u.user_id === user_id && u.tip_date === tip_date);
      if (!row) {
        row = { id: store.seq.unlocks++, user_id, tip_date, unlocked_at: new Date().toISOString(), expires_at, purchase_token, stub };
        store.unlocks.push(row);
      } else Object.assign(row, { expires_at, purchase_token, stub, unlocked_at: new Date().toISOString() });
      save(store); return row;
    },
    getMartingale() { return store.martingale; },
    saveMartingale(mg) { store.martingale = mg; save(store); return store.martingale; },
    nextMgId() { const id = store.seq.mg++; save(store); return id; },
  };
  // Promise-wrap so server can always await
  return new Proxy(api, {
    get(target, prop) {
      const v = target[prop];
      if (typeof v !== 'function') return v;
      return (...args) => Promise.resolve(v.apply(target, args));
    },
  });
}

/** ---------- Postgres ---------- */
function createPgDb() {
  const { Pool } = require('pg');
  const pool = new Pool({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false } });

  async function q(text, params) {
    const r = await pool.query(text, params);
    return r;
  }

  async function init() {
    await q(`
      CREATE TABLE IF NOT EXISTS users (
        id SERIAL PRIMARY KEY,
        email TEXT NOT NULL UNIQUE,
        password_hash TEXT NOT NULL,
        name TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'user',
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE IF NOT EXISTS tips (
        id SERIAL PRIMARY KEY,
        match_name TEXT NOT NULL,
        market TEXT NOT NULL,
        odds DOUBLE PRECISION NOT NULL,
        analysis TEXT NOT NULL DEFAULT '',
        tip_date TEXT NOT NULL,
        tip_time TEXT,
        sport TEXT NOT NULL DEFAULT 'Άλλο',
        pick_type TEXT,
        star_of_day INT NOT NULL DEFAULT 0,
        stake DOUBLE PRECISION,
        result_won INT,
        result_detail TEXT,
        profit DOUBLE PRECISION,
        progressive_total DOUBLE PRECISION,
        created_by INT,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
      );
      CREATE TABLE IF NOT EXISTS daily_systems (
        id SERIAL PRIMARY KEY,
        tip_date TEXT NOT NULL UNIQUE,
        system_name TEXT NOT NULL,
        stake DOUBLE PRECISION,
        profit DOUBLE PRECISION,
        progressive DOUBLE PRECISION,
        day_profit DOUBLE PRECISION
      );
      CREATE TABLE IF NOT EXISTS unlocks (
        id SERIAL PRIMARY KEY,
        user_id INT NOT NULL,
        tip_date TEXT NOT NULL,
        unlocked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        expires_at TIMESTAMPTZ NOT NULL,
        purchase_token TEXT,
        stub INT NOT NULL DEFAULT 0,
        UNIQUE(user_id, tip_date)
      );
      CREATE TABLE IF NOT EXISTS martingale_state (
        id INT PRIMARY KEY DEFAULT 1,
        pool DOUBLE PRECISION NOT NULL DEFAULT 1000,
        base_stake DOUBLE PRECISION NOT NULL DEFAULT 25,
        cycle_step INT NOT NULL DEFAULT 1,
        cycle_losses DOUBLE PRECISION NOT NULL DEFAULT 0,
        peak_pool DOUBLE PRECISION NOT NULL DEFAULT 1000,
        max_drawdown DOUBLE PRECISION NOT NULL DEFAULT 0,
        bets_json JSONB NOT NULL DEFAULT '[]'::jsonb,
        next_mg_id INT NOT NULL DEFAULT 1
      );
      INSERT INTO martingale_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
      CREATE INDEX IF NOT EXISTS idx_tips_date ON tips(tip_date);
    `);

    const { email, password, name } = todaySeedAdmin();
    const existing = await q('SELECT id, role FROM users WHERE lower(email)=$1', [email]);
    if (existing.rowCount === 0) {
      const hash = bcrypt.hashSync(password, 10);
      await q('INSERT INTO users (email, password_hash, name, role) VALUES ($1,$2,$3,$4)', [email, hash, name, 'admin']);
      console.log('[seed] Admin created:', email);
    } else {
      await q('UPDATE users SET role=$1, password_hash=$2 WHERE lower(email)=$3', [
        'admin', bcrypt.hashSync(password, 10), email,
      ]);
    }

    const tipCount = await q('SELECT COUNT(*)::int AS c FROM tips');
    if (tipCount.rows[0].c === 0) {
      const seedPath = path.join(__dirname, 'seed-data.json');
      if (fs.existsSync(seedPath)) {
        const data = JSON.parse(fs.readFileSync(seedPath, 'utf8'));
        const admin = (await q('SELECT id FROM users WHERE role=$1 LIMIT 1', ['admin'])).rows[0];
        for (const t of data.tips || []) {
          await q(
            `INSERT INTO tips (match_name, market, odds, analysis, tip_date, tip_time, sport, pick_type, star_of_day, stake, result_won, result_detail, profit, progressive_total, created_by)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)`,
            [
              t.matchName, t.market, t.odds, t.analysis || '', t.tipDate, t.time || null, t.sport || 'Άλλο', t.pickType || null,
              t.starOfDay ? 1 : 0, t.stake ?? null,
              t.resultWon === true ? 1 : t.resultWon === false ? 0 : null,
              t.resultDetail || null, t.profit ?? null, t.progressiveTotal ?? null, admin?.id || null,
            ],
          );
        }
        for (const s of data.systems || []) {
          await q(
            `INSERT INTO daily_systems (tip_date, system_name, stake, profit, progressive, day_profit)
             VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (tip_date) DO UPDATE SET system_name=EXCLUDED.system_name`,
            [s.tipDate, s.systemName, s.stake ?? null, s.profit ?? null, s.progressive ?? null, s.dayProfit ?? null],
          );
        }
        console.log(`[seed] Loaded ${(data.tips || []).length} tips into Postgres`);
      }
    }

    const mgRow = await q('SELECT jsonb_array_length(bets_json) AS n FROM martingale_state WHERE id=1');
    if ((mgRow.rows[0]?.n || 0) === 0) {
      const mgPath = path.join(__dirname, 'martingale-seed.json');
      if (fs.existsSync(mgPath)) {
        const mg = JSON.parse(fs.readFileSync(mgPath, 'utf8'));
        await q(
          `UPDATE martingale_state SET pool=$1, base_stake=$2, cycle_step=$3, cycle_losses=$4, peak_pool=$5, max_drawdown=$6, bets_json=$7::jsonb, next_mg_id=$8 WHERE id=1`,
          [mg.pool, mg.baseStake || 25, mg.cycleStep, mg.cycleLosses, mg.peakPool, mg.maxDrawdown, JSON.stringify(mg.bets || []), mg.nextId || 1]
        );
        console.log(`[seed] Martingale PG ${mg.bets.length} bets, pool=${mg.pool}`);
      }
    }
  }

  function mapUser(r) {
    if (!r) return null;
    return { id: r.id, email: r.email, password_hash: r.password_hash, name: r.name, role: r.role, created_at: r.created_at };
  }
  function mapTip(r) {
    if (!r) return null;
    return {
      id: r.id, match_name: r.match_name, market: r.market, odds: r.odds, analysis: r.analysis,
      tip_date: r.tip_date, tip_time: r.tip_time, sport: r.sport, pick_type: r.pick_type,
      star_of_day: r.star_of_day, stake: r.stake, result_won: r.result_won, result_detail: r.result_detail,
      profit: r.profit, progressive_total: r.progressive_total, created_by: r.created_by,
      created_at: r.created_at, updated_at: r.updated_at,
    };
  }

  const api = {
    async ready() { await init(); return true; },
    getUserByEmail(email) {
      return q('SELECT * FROM users WHERE lower(email)=$1', [String(email).toLowerCase()]).then((r) => mapUser(r.rows[0]));
    },
    getUserById(id) {
      return q('SELECT * FROM users WHERE id=$1', [id]).then((r) => mapUser(r.rows[0]));
    },
    createUser({ email, password_hash, name, role }) {
      return q(
        'INSERT INTO users (email, password_hash, name, role) VALUES ($1,$2,$3,$4) RETURNING *',
        [email.toLowerCase(), password_hash, name, role || 'user'],
      ).then((r) => mapUser(r.rows[0]));
    },
    tipsByDate(tipDate) {
      return q('SELECT * FROM tips WHERE tip_date=$1 ORDER BY star_of_day DESC, id ASC', [tipDate]).then((r) => r.rows.map(mapTip));
    },
    allTips() {
      return q('SELECT * FROM tips ORDER BY tip_date DESC, star_of_day DESC, id ASC').then((r) => r.rows.map(mapTip));
    },
    allSettledTips() {
      return q('SELECT * FROM tips WHERE result_won IS NOT NULL').then((r) => r.rows.map(mapTip));
    },
    getTip(id) {
      return q('SELECT * FROM tips WHERE id=$1', [id]).then((r) => mapTip(r.rows[0]));
    },
    createTip(fields) {
      return q(
        `INSERT INTO tips (match_name, market, odds, analysis, tip_date, tip_time, sport, pick_type, star_of_day, stake, result_won, result_detail, profit, progressive_total, created_by)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) RETURNING *`,
        [
          fields.match_name, fields.market, fields.odds, fields.analysis || '', fields.tip_date, fields.tip_time || null,
          fields.sport || 'Άλλο', fields.pick_type || null, fields.star_of_day ? 1 : 0, fields.stake ?? null,
          fields.result_won ?? null, fields.result_detail || null, fields.profit ?? null, fields.progressive_total ?? null, fields.created_by,
        ],
      ).then((r) => mapTip(r.rows[0]));
    },
    updateTip(id, patch) {
      const keys = Object.keys(patch);
      if (!keys.length) return api.getTip(id);
      const sets = keys.map((k, i) => `${k}=$${i + 1}`);
      sets.push(`updated_at=NOW()`);
      const vals = keys.map((k) => patch[k]);
      vals.push(id);
      return q(`UPDATE tips SET ${sets.join(',')} WHERE id=$${vals.length} RETURNING *`, vals).then((r) => mapTip(r.rows[0]));
    },
    deleteTip(id) {
      return q('DELETE FROM tips WHERE id=$1', [id]).then((r) => r.rowCount > 0);
    },
    getSystem(tipDate) {
      return q('SELECT * FROM daily_systems WHERE tip_date=$1', [tipDate]).then((r) => {
        const s = r.rows[0]; if (!s) return null;
        return { tip_date: s.tip_date, system_name: s.system_name, stake: s.stake, profit: s.profit, progressive: s.progressive, day_profit: s.day_profit };
      });
    },
    allSystems() {
      return q('SELECT * FROM daily_systems ORDER BY tip_date').then((r) => r.rows.map((s) => ({
        tip_date: s.tip_date, system_name: s.system_name, stake: s.stake, profit: s.profit, progressive: s.progressive, day_profit: s.day_profit,
      })));
    },
    hasUnlock(userId, tipDate) {
      return q(`SELECT id FROM unlocks WHERE user_id=$1 AND tip_date=$2 AND expires_at > NOW()`, [userId, tipDate]).then((r) => r.rowCount > 0);
    },
    getUnlock(userId, tipDate) {
      return q(`SELECT * FROM unlocks WHERE user_id=$1 AND tip_date=$2`, [userId, tipDate]).then((r) => {
        const u = r.rows[0]; if (!u) return null;
        return { ...u, expires_at: u.expires_at };
      });
    },
    upsertUnlock({ user_id, tip_date, expires_at, purchase_token, stub }) {
      return q(
        `INSERT INTO unlocks (user_id, tip_date, expires_at, purchase_token, stub)
         VALUES ($1,$2,$3::timestamptz,$4,$5)
         ON CONFLICT (user_id, tip_date) DO UPDATE SET expires_at=EXCLUDED.expires_at, purchase_token=EXCLUDED.purchase_token, stub=EXCLUDED.stub, unlocked_at=NOW()
         RETURNING *`,
        [user_id, tip_date, expires_at.includes('T') ? expires_at : expires_at.replace(' ', 'T') + 'Z', purchase_token, stub],
      ).then((r) => r.rows[0]);
    },
    getMartingale() {
      return q('SELECT * FROM martingale_state WHERE id=1').then((r) => {
        const m = r.rows[0];
        return {
          pool: m.pool, baseStake: m.base_stake, cycleStep: m.cycle_step, cycleLosses: m.cycle_losses,
          peakPool: m.peak_pool, maxDrawdown: m.max_drawdown, bets: m.bets_json || [], _nextId: m.next_mg_id,
        };
      });
    },
    saveMartingale(mg) {
      return q(
        `UPDATE martingale_state SET pool=$1, base_stake=$2, cycle_step=$3, cycle_losses=$4, peak_pool=$5, max_drawdown=$6, bets_json=$7::jsonb, next_mg_id=$8 WHERE id=1`,
        [mg.pool, mg.baseStake, mg.cycleStep, mg.cycleLosses, mg.peakPool, mg.maxDrawdown, JSON.stringify(mg.bets || []), mg._nextId || 1],
      ).then(() => mg);
    },
    nextMgId() {
      return q(`UPDATE martingale_state SET next_mg_id = next_mg_id + 1 WHERE id=1 RETURNING next_mg_id`).then((r) => r.rows[0].next_mg_id - 1);
    },
  };

  // Wrap sync-looking calls: server currently uses sync API. We'll convert server to async await.
  return api;
}

const db = usePg ? createPgDb() : createJsonDb();
console.log('[db] mode=', usePg ? 'postgres' : 'json');
module.exports = db;
