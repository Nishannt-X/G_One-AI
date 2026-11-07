'use strict';
// Runs the real static/app.js as a demo-stage seat. Usage: node embed-check.js <app.js> <stage-core.js>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const core = (() => {
  const c = {}; vm.createContext(c); vm.runInContext(fs.readFileSync(process.argv[3], 'utf8'), c); return c.GOneStageCore;
})();

function storage(seed = {}) {
  const m = new Map(Object.entries(seed));
  return { m, getItem: (k) => (m.has(k) ? m.get(k) : null), setItem(k, v) { m.set(k, String(v)); }, removeItem(k) { m.delete(k); } };
}

async function load({ search, store, embedded = true }) {
  const els = {};
  const element = () => ({ classList: { toggle() {}, add(c) { this.added = (this.added || []).concat(c); } }, dataset: {}, style: {}, append() {}, replaceChildren() {}, select() {} });
  const posted = [];
  const parent = { postMessage: (data, target) => posted.push({ data: JSON.parse(JSON.stringify(data)), target }) };
  const win = {};
  win.parent = embedded ? parent : win;
  class WS { constructor(url) { WS.last = this; this.url = url; } }
  const ctx = {
    window: win, WebSocket: WS, URLSearchParams, Option: function Option() {},
    document: { body: element(), getElementById: (id) => els[id] || (els[id] = element()), createElement: element },
    location: { protocol: 'http:', host: 'x', origin: 'http://x', search },
    fetch: async (url) => {
      if (url === '/api/config') return { json: async () => ({ languages: [{ code: 'hi-IN', label: 'Hindi' }, { code: 'en-IN', label: 'English' }], maxTurnSeconds: 30, accessCodeRequired: false, ready: true }) };
      throw new Error('offline');
    },
    sessionStorage: store, history: { replaceState() {} },
  };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(process.argv[2], 'utf8') + '\n;this.app = { S, store };', ctx);
  await new Promise((r) => setTimeout(r, 25)); // init() is async
  return { app: ctx.app, els, posted, ws: () => WS.last, body: ctx.document.body };
}
const send = (ws, m) => ws.onmessage({ data: JSON.stringify(m) });
const SEAT = (role, token = 'tok') => JSON.stringify({ token, role });

(async () => {
  // A seat frame reads its own namespaced key (the one the stage writes) and enters the room as that role.
  let store = storage({ [core.seatKey('r1', 'farmer')]: SEAT('farmer', 'ftok'), 'g_one:r1': SEAT('buyer', 'plain') });
  let t = await load({ search: '?room=r1&seat=farmer', store });
  assert.equal(t.app.S.seat, 'farmer');
  assert.equal(t.app.S.token, 'ftok', 'the farmer seat uses the farmer entry, not the unnamespaced one');
  assert.match(t.ws().url, /token=ftok/);
  assert.ok(t.body.classList.added.includes('embed'), 'embedded seat drops the page chrome');

  // Only live pushes are reported, as metadata only. Spoken text, tokens and audio links never leave the frame.
  t.ws().onopen();
  send(t.ws(), { type: 'state', you: 'farmer', participants: [], busy: false, events: [{ type: 'turn', turnId: 'old', speaker: 'buyer', sourceLanguage: 'en-IN', targetLanguage: 'hi-IN', transcript: 'old words', translation: 'old' }] });
  send(t.ws(), { type: 'turn', turnId: 't1', speaker: 'buyer', sourceLanguage: 'en-IN', targetLanguage: 'hi-IN', transcript: 'SECRET SPOKEN WORDS', translation: 'SECRET TRANSLATION', audioUrl: '/api/rooms/r1/audio/k1' });
  send(t.ws(), { type: 'warning', turnId: 't1', text: 'PRIVATE CAUTION TEXT', audioUrl: '/api/rooms/r1/audio/k2' });
  const kinds = t.posted.map((p) => p.data.kind + ':' + (p.data.type || p.data.state));
  assert.deepEqual(kinds, ['conn:open', 'event:turn', 'event:warning'], 'the state snapshot is not reported as new activity');
  assert.ok(t.posted.every((p) => p.target === 'http://x'), 'posted to the page origin only, never "*"');
  assert.ok(t.posted.every((p) => p.data.source === 'g-one-seat' && p.data.v === 1 && p.data.seat === 'farmer'));
  const wire = JSON.stringify(t.posted);
  for (const secret of ['SECRET', 'PRIVATE', 'ftok', 'audio/k', 'old words']) assert.ok(!wire.includes(secret), 'must not leak ' + secret);
  assert.deepEqual(t.posted[1].data.meta, { speaker: 'buyer', sourceLanguage: 'en-IN', targetLanguage: 'hi-IN' });

  // The room being gone is reported too.
  t.ws().onclose({ code: 1008 });
  assert.equal(t.posted.at(-1).data.state, 'ended');

  // A seat with nothing saved for it never joins by itself, even if the other seat's entry exists.
  store = storage({ [core.seatKey('r1', 'farmer')]: SEAT('farmer') });
  t = await load({ search: '?room=r1&seat=buyer', store });
  assert.equal(t.app.S.token, null);
  assert.equal(t.ws(), undefined, 'no socket was opened');
  assert.equal(t.els.seatless.hidden, false, 'it says the seat is not signed in');
  assert.equal(t.els.landing, undefined, 'and never touches (so never shows) the join form');

  // An edited entry cannot put a frame into the other role.
  store = storage({ [core.seatKey('r1', 'buyer')]: SEAT('farmer') });
  t = await load({ search: '?room=r1&seat=buyer', store });
  assert.equal(t.app.S.token, null);
  assert.equal(t.els.seatless.hidden, false);

  // Unknown seat names are ignored: the frame behaves like a plain tab (original key, no reports).
  store = storage({ 'g_one:r1': SEAT('buyer', 'plain') });
  t = await load({ search: '?room=r1&seat=admin', store });
  assert.equal(t.app.S.seat, null);
  assert.equal(t.app.S.token, 'plain');
  t.ws().onopen();
  assert.equal(t.posted.length, 0);

  // A plain tab with an old room link (no seat) works exactly as before and reports nothing, even if it is framed.
  store = storage({ 'g_one:r1': SEAT('buyer', 'plain') });
  t = await load({ search: '?room=r1', store });
  assert.equal(t.app.S.token, 'plain');
  assert.equal(t.app.store.get().token, 'plain');
  t.ws().onopen(); send(t.ws(), { type: 'turn', turnId: 't9', speaker: 'farmer', sourceLanguage: 'hi-IN', targetLanguage: 'en-IN', transcript: 'x', translation: 'y' });
  assert.equal(t.posted.length, 0);

  // A seat frame opened directly in its own tab (not framed) keeps its namespaced storage but reports to nobody.
  store = storage({ [core.seatKey('r1', 'farmer')]: SEAT('farmer', 'ftok') });
  t = await load({ search: '?room=r1&seat=farmer', store, embedded: false });
  assert.equal(t.app.S.token, 'ftok');
  t.ws().onopen();
  assert.equal(t.posted.length, 0);
  assert.equal(t.body.classList.added, undefined, 'and keeps its normal page chrome');

  console.log('embed-check ok');
})().catch((e) => { console.error(e); process.exit(1); });
