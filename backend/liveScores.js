/**
 * Free live scores via ESPN public site APIs (no key).
 * Polls ~60s; fuzzy-matches tip matchName to events.
 */
const https = require('https');

const CACHE = { at: 0, byTipId: new Map(), events: [] };
const POLL_MS = 60_000;

const ESPN = {
  basketball_nba: 'https://site.api.espn.com/apis/site/v2/sports/basketball/nba/scoreboard',
  basketball_euroleague: 'https://site.api.espn.com/apis/site/v2/sports/basketball/euroleague/scoreboard',
  soccer_eng: 'https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/scoreboard',
  soccer_esp: 'https://site.api.espn.com/apis/site/v2/sports/soccer/esp.1/scoreboard',
  soccer_ita: 'https://site.api.espn.com/apis/site/v2/sports/soccer/ita.1/scoreboard',
  soccer_uefa: 'https://site.api.espn.com/apis/site/v2/sports/soccer/uefa.champions/scoreboard',
  soccer_gre: 'https://site.api.espn.com/apis/site/v2/sports/soccer/gre.1/scoreboard',
  tennis_atp: 'https://site.api.espn.com/apis/site/v2/sports/tennis/atp/scoreboard',
  tennis_wta: 'https://site.api.espn.com/apis/site/v2/sports/tennis/wta/scoreboard',
  baseball_mlb: 'https://site.api.espn.com/apis/site/v2/sports/baseball/mlb/scoreboard',
};

function fetchJson(url) {
  return new Promise((resolve, reject) => {
    const req = https.get(url, { headers: { 'User-Agent': 'bettinginvestor-tips/0.7' }, timeout: 12000 }, (res) => {
      let buf = '';
      res.on('data', (c) => { buf += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(buf)); }
        catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
  });
}

function norm(s) {
  return String(s || '')
    .toLowerCase()
    .normalize('NFD').replace(/\p{M}/gu, '')
    .replace(/[^a-z0-9\s]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

const ALIASES = {
  'olympiakos': 'olympiacos',
  'olympiacós': 'olympiacos',
  'fenerbahçe': 'fenerbahce',
  'man city': 'manchester city',
  'psg': 'paris saint germain',
  'inter': 'internazionale',
  'bayern': 'bayern munich',
  'thunder': 'oklahoma city thunder',
  'nuggets': 'denver nuggets',
  'clippers': 'la clippers',
  '76ers': 'philadelphia 76ers',
  'bulls': 'chicago bulls',
  'bucks': 'milwaukee bucks',
  'celtics': 'boston celtics',
  'nets': 'brooklyn nets',
  'wizards': 'washington wizards',
  'cavaliers': 'cleveland cavaliers',
  'mavericks': 'dallas mavericks',
  'magic': 'orlando magic',
  'timberwolves': 'minnesota timberwolves',
  'pelicans': 'new orleans pelicans',
  'portland': 'portland trail blazers',
  'memphis': 'memphis grizzlies',
  'lehecka': 'jiri lehecka',
  'altmaier': 'daniel altmaier',
  'djokovic': 'novak djokovic',
  'struff': 'jan-lennard struff',
  'shelton': 'ben shelton',
  'kym': 'jerome kym',
};

function expand(n) {
  n = norm(n);
  for (const [k, v] of Object.entries(ALIASES)) {
    if (n.includes(norm(k))) n = n.replace(norm(k), norm(v));
  }
  return n;
}

function splitMatch(matchName) {
  const raw = String(matchName || '').replace(/\([^)]*\)/g, ' ').trim();
  const parts = raw.split(/\s+@\s+|\s+[–—-]\s+/);
  if (parts.length >= 2) return [expand(parts[0]), expand(parts[1])];
  return [expand(raw), ''];
}

function competitorName(c) {
  return c?.team?.displayName || c?.athlete?.displayName || c?.team?.shortDisplayName
    || c?.athlete?.shortName || c?.athlete?.fullName || '';
}

function linescoreText(competitor) {
  const ls = competitor?.linescores || [];
  if (!ls.length) return competitor?.score != null ? String(competitor.score) : null;
  return ls.map((s) => {
    const v = s.value != null ? String(Math.trunc(s.value)) : '0';
    return s.tiebreak != null ? `${v}(${s.tiebreak})` : v;
  }).join('-');
}

function pushCompetition(out, comp, fallbackId) {
  if (!comp) return;
  const competitors = comp.competitors || [];
  if (!competitors.length) return;
  const home = competitors.find((c) => c.homeAway === 'home') || competitors[1] || competitors[0];
  const away = competitors.find((c) => c.homeAway === 'away') || competitors[0];
  const stWrap = comp.status || {};
  const status = stWrap.type || {};
  const state = status.state || '';
  const shortDetail = status.shortDetail || status.detail || '';
  const nameHome = competitorName(home);
  const nameAway = competitorName(away);
  if (!nameHome && !nameAway) return;
  let scoreHome = home?.score != null ? String(home.score) : null;
  let scoreAway = away?.score != null ? String(away.score) : null;
  // tennis: prefer set linescores as readable score string pieces
  const lsHome = linescoreText(home);
  const lsAway = linescoreText(away);
  if ((scoreHome == null || scoreHome === '') && lsHome) scoreHome = lsHome;
  if ((scoreAway == null || scoreAway === '') && lsAway) scoreAway = lsAway;
  const note = ((comp.notes || [])[0] || {}).text || '';
  out.push({
    id: comp.id || fallbackId,
    name: note || `${nameAway} at ${nameHome}`,
    nameHome: expand(nameHome),
    nameAway: expand(nameAway),
    rawHome: nameHome,
    rawAway: nameAway,
    scoreHome,
    scoreAway,
    state,
    detail: shortDetail || note || '',
    clock: stWrap.displayClock || '',
    period: stWrap.period,
    completed: !!status.completed,
  });
}

function parseEvents(data) {
  const out = [];
  const events = data.events || [];
  for (const ev of events) {
    const topComps = ev.competitions || [];
    if (topComps.length) {
      for (const comp of topComps) pushCompetition(out, comp, ev.id);
    }
    // Tennis (and some nested boards): matches live under groupings[].competitions
    for (const g of ev.groupings || []) {
      for (const comp of g.competitions || []) pushCompetition(out, comp, `${ev.id}-${comp.id}`);
    }
  }
  return out;
}

async function refreshBoard() {
  const urls = Object.values(ESPN);
  const all = [];
  await Promise.all(urls.map(async (url) => {
    try {
      const data = await fetchJson(url);
      all.push(...parseEvents(data));
    } catch (_) { /* ignore per-source failures */ }
  }));
  CACHE.events = all;
  CACHE.at = Date.now();
  return all;
}

function scoreEvent(tipSides, ev) {
  const [a, b] = tipSides;
  if (!a) return 0;
  const names = [ev.nameHome, ev.nameAway].filter(Boolean);
  let hits = 0;
  for (const side of [a, b].filter(Boolean)) {
    const tokens = side.split(' ').filter((t) => t.length > 2);
    const ok = names.some((n) => n.includes(side) || side.includes(n) || tokens.some((t) => n.includes(t)));
    if (ok) hits += 1;
  }
  // prefer both sides
  return hits;
}

function matchTip(tip, events) {
  const matchName = tip.match_name || tip.matchName || '';
  const sides = splitMatch(matchName);
  let best = null;
  let bestScore = 0;
  for (const ev of events) {
    const s = scoreEvent(sides, ev);
    if (s > bestScore) { bestScore = s; best = ev; }
  }
  if (bestScore < 1) return null;
  // require at least one solid hit; for two-team sports prefer 2
  if (sides[1] && bestScore < 2) {
    // allow 1 if unique-ish long token
    const long = sides[0].split(' ').filter((t) => t.length >= 5);
    if (!long.length) return null;
  }
  const live = best.state === 'in';
  const final_ = best.state === 'post' || best.completed;
  let score = null;
  if (best.scoreAway != null && best.scoreHome != null) {
    score = `${best.rawAway} ${best.scoreAway} – ${best.rawHome} ${best.scoreHome}`;
  }
  // Prefer human tennis note when present (cleaner set scores)
  if (best.name && /\bbt\b|tied with|leads/i.test(best.name)) {
    score = best.name;
  }
  return {
    tipId: tip.id,
    live,
    final: final_,
    status: best.detail || (live ? 'LIVE' : final_ ? 'FT' : 'Scheduled'),
    score,
    scoreHome: best.scoreHome,
    scoreAway: best.scoreAway,
    home: best.rawHome,
    away: best.rawAway,
    clock: best.clock,
    period: best.period,
    state: best.state,
  };
}

async function ensureFresh() {
  if (Date.now() - CACHE.at > POLL_MS - 1000) {
    await refreshBoard();
  }
}

async function scoresForTips(tips) {
  await ensureFresh();
  const map = {};
  for (const tip of tips) {
    const m = matchTip(tip, CACHE.events);
    if (m) map[tip.id] = m;
  }
  CACHE.byTipId = new Map(Object.entries(map));
  return map;
}

function getCached(tipId) {
  return CACHE.byTipId.get(String(tipId)) || CACHE.byTipId.get(tipId) || null;
}

/** Infer tip result from final score vs market (ML / player). Conservative — only simple ML. */
function inferResult(tip, live) {
  if (!live || !live.final || live.scoreHome == null || live.scoreAway == null) return null;
  const market = String(tip.market || tip.match_name || '').toLowerCase();
  const home = norm(live.home);
  const away = norm(live.away);
  const sh = Number(live.scoreHome);
  const sa = Number(live.scoreAway);
  if (!Number.isFinite(sh) || !Number.isFinite(sa)) return null;
  // ML: market contains team name
  const homeWon = sh > sa;
  const awayWon = sa > sh;
  if (sh === sa) return null; // draw — skip auto
  if (market.includes('ml') || market.includes('νίκη') || market.includes('1') || market.includes('2') || true) {
    // pick side from market text vs home/away
    const m = expand(market);
    const picksHome = home.split(' ').filter((t) => t.length > 3).some((t) => m.includes(t)) || m.includes(home);
    const picksAway = away.split(' ').filter((t) => t.length > 3).some((t) => m.includes(t)) || m.includes(away);
    if (picksHome && !picksAway) return homeWon;
    if (picksAway && !picksHome) return awayWon;
  }
  return null;
}

module.exports = { scoresForTips, getCached, ensureFresh, refreshBoard, inferResult, POLL_MS };
