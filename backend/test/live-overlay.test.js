const assert = require('assert');
const { inferResult } = require('../liveScores');

function shouldShow(live) {
  if (!live) return false;
  return !!(live.live || live.final || (live.score && String(live.score).length) || (live.status && String(live.status).length));
}

// FINAL settled tip must show
assert.strictEqual(shouldShow({ live: false, final: true, score: 'Lehecka bt Altmaier 7-5 6-4', status: 'Final' }), true);
// LIVE
assert.strictEqual(shouldShow({ live: true, final: false, score: '6-7 4-3', status: '2nd' }), true);
// empty
assert.strictEqual(shouldShow(null), false);
assert.strictEqual(shouldShow({ live: false, final: false, score: null, status: '' }), false);

// Tennis auto-settle Lehecka ML
const tip = { market: 'Lehecka ML', match_name: 'Altmaier – Lehecka (Basel)' };
const live = {
  final: true,
  home: 'Daniel Altmaier',
  away: 'Jiri Lehecka',
  scoreHome: '5-4',
  scoreAway: '7-6',
  homeWinner: false,
  awayWinner: true,
  score: '(8) Jiri Lehecka (CZE) bt Daniel Altmaier (GER) 7-5 6-4',
  status: 'Final',
};
assert.strictEqual(inferResult(tip, live), true, 'Lehecka ML should win');

// kickoff sort minutes
function kickoffMinutes(time) {
  if (!time) return -1;
  let s = String(time).trim();
  if (s.startsWith('~')) s = s.slice(1).trim();
  s = s.split(/[–—-]/)[0].trim();
  const m = s.match(/(\d{1,2}):(\d{2})/);
  if (!m) return -1;
  return (+m[1]) * 60 + (+m[2]);
}
assert.strictEqual(kickoffMinutes('~13:00'), 13 * 60);
assert.strictEqual(kickoffMinutes('21:00–22:00'), 21 * 60);
assert.strictEqual(kickoffMinutes(null), -1);
const times = ['~05:00', '~20:30', '~13:00'].sort((a, b) => kickoffMinutes(b) - kickoffMinutes(a));
assert.deepStrictEqual(times, ['~20:30', '~13:00', '~05:00']);

console.log('OK live-overlay.test.js');
