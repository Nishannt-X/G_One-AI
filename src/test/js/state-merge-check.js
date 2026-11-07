'use strict';
// Runs the real static/app.js against minimal browser stubs. Usage: node state-merge-check.js <path to app.js>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function element() {
  return { classList: { toggle() {} }, dataset: {}, style: {}, append() {}, replaceChildren() {}, select() {} };
}
const els = {}; // getElementById returns the same stub per id so the check can read what the page would show
const ctx = {
  document: { body: {}, getElementById: (id) => els[id] || (els[id] = element()), createElement: element },
  fetch: () => Promise.reject(new Error('offline')), // init() stops at the config fetch
  location: { protocol: 'http:', host: 'x', search: '' },
  Option: function Option() {},
};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8') + '\n;this.app = { S, onMessage, obsolete, currentDeal };', ctx);
const { S, onMessage, obsolete, currentDeal } = ctx.app;
S.cfg = { languages: [{ code: 'hi-IN', label: 'Hindi' }] };
S.queue = []; S.playing = true; // enqueue() only records; playNext() returns early

const turn = { type: 'turn', turnId: 't1', speaker: 'buyer', transcript: 'x', translation: 'y' };
const warning = { type: 'warning', turnId: 't1', text: 'Caution', audioUrl: '/a/w-t1' };
const state = (events) => ({ type: 'state', you: 'farmer', participants: [], busy: false, events });
const types = () => S.events.map((e) => e.type);

// Race: the live warning reaches the phone before the state snapshot that was taken without it.
S.events = [];
onMessage(warning);
onMessage(state([turn]));
assert.deepEqual(types(), ['turn', 'warning'], 'a warning received before state must survive the snapshot');
assert.equal(S.queue.length, 1, 'its voice was queued once, when it arrived');

// Snapshot already contains the warning: no duplicate, no second playback.
onMessage(state([turn, warning]));
assert.deepEqual(types(), ['turn', 'warning']);
assert.equal(S.queue.length, 1);

// Normal reconnect: history replaces nothing that is missing locally and adds what was missed.
S.events = [turn];
onMessage(state([turn, warning]));
assert.deepEqual(types(), ['turn', 'warning']);
// Agreement events: each has its own id (several can share one turnId), none is duplicated on replay, voice queues once.
const open = { type: 'agreement', id: 't2-voided', turnId: 't2', state: 'closed', outcome: 'voided', terms: null };
const reopened = { type: 'agreement', id: 't2-opened', turnId: 't2', state: 'offer_open', terms: null };
const readback = { type: 'agreement_readback', id: 't2-readback', text: 'Please check', audioUrl: '/a/ag1' };
const final = { type: 'agreement_final', id: 't3-agreed', text: 'Summary', delivery: 'not_sent', audioUrl: '/a/ag2' };
S.events = []; S.queue = [];
for (const m of [open, reopened, readback, final]) onMessage(m);
assert.deepEqual(types(), ['agreement', 'agreement', 'agreement_readback', 'agreement_final'],
  'two agreement states from one turn are both kept');
assert.equal(S.queue.length, 2, 'readback and final voices queued once each');
onMessage(state([open, reopened, readback, final])); // reconnect replays the same history
for (const m of [readback, final, reopened]) onMessage(m); // and late duplicates arrive
assert.deepEqual(types(), ['agreement', 'agreement', 'agreement_readback', 'agreement_final'], 'no duplicate summary');
assert.equal(S.queue.length, 2, 'nothing replays aloud');
// The shared deal panel: terms exactly as spoken (unstated parts say so), who has said yes, and a nonbinding footing.
const terms = { commodity: 'rice', variety: null, market: 'Burdwan APMC', price: '3400', currency: 'INR', unit: 'quintal' };
S.events = [];
onMessage({ type: 'agreement', id: 't5-confirming', turnId: 't5', state: 'confirming', version: 1, terms,
  confirmed: { farmer: true, buyer: false } });
assert.equal(els.deal.hidden, false);
assert.equal(els.dealTerms.textContent, 'Rice · variety not stated · Burdwan APMC · ₹3,400 per quintal');
assert.equal(els.dealWho.textContent, 'Farmer: said yes · Buyer: not yet');
onMessage({ type: 'agreement', id: 't6-closed', turnId: 't6', state: 'closed', outcome: 'expired', version: 1, terms,
  confirmed: { farmer: true, buyer: false } });
assert.match(els.dealStatus.textContent, /^Time ran out\. Nothing was agreed\.$/);
assert.equal(els.dealWho.textContent, '');
onMessage({ type: 'agreement', id: 't7-agreed', turnId: 't7', state: 'agreed', version: 2, terms,
  confirmed: { farmer: true, buyer: true } });
assert.equal(els.dealStatus.textContent, 'Both said yes by voice.');

// ---- read-back playback: only a clip played to its end, on a live draft, is ever reported ------------------------
const deal = (version, seq, st, extra) => Object.assign({ type: 'agreement', id: 'd' + seq, state: st, version, seq, terms,
  confirmed: { farmer: false, buyer: false }, readbackPlayed: { farmer: false, buyer: false } }, extra);
const rb = (version) => ({ type: 'agreement_readback', id: 'rb' + version, text: 'Please check', version, audioUrl: '/a/rb' + version });
const fresh = () => { S.events = []; S.queue = []; S.playing = true; S.current = null; S.stopPlayback = null; };

// Two server threads can deliver state updates out of order: the highest seq is the deal's state.
fresh();
onMessage(deal(2, 5, 'offer_open'));
onMessage(deal(1, 3, 'readback'));
assert.equal(currentDeal().seq, 5);
// A read-back for an older draft, whenever it arrives, does not play and cannot be replayed.
onMessage(rb(1));
assert.equal(S.queue.length, 0, 'an obsolete read-back is not queued');
assert.equal(obsolete(rb(1)), true);
// One queued before the newer draft is removed from the queue when that draft arrives.
fresh();
onMessage(deal(1, 1, 'readback'));
onMessage(rb(1));
assert.equal(S.queue.length, 1);
assert.equal(obsolete(rb(1)), false);
onMessage(deal(2, 2, 'offer_open'));
assert.equal(S.queue.length, 0, 'the queued read-back is dropped');
// A closed draft's read-back is obsolete too; an agreed one's is still the agreed terms.
fresh();
onMessage(deal(1, 1, 'closed', { outcome: 'declined' }));
assert.equal(obsolete(rb(1)), true);
fresh();
onMessage(deal(1, 1, 'agreed'));
assert.equal(obsolete(rb(1)), false);
// A reconnect that finds an unplayed read-back of the live draft queues it once; a played one stays quiet.
fresh();
S.role = 'farmer';
onMessage(state([deal(1, 1, 'readback'), rb(1)]));
assert.equal(S.queue.length, 1, 'a read-back missed while offline is owed');
onMessage(state([deal(1, 1, 'readback'), rb(1)]));
assert.equal(S.queue.length, 1, 'not queued twice');
fresh();
onMessage(state([deal(1, 1, 'readback', { readbackPlayed: { farmer: true, buyer: false } }), rb(1)]));
assert.equal(S.queue.length, 0, 'already played on this phone');

// The real playback path with a stub player and server.
const tick = () => new Promise((r) => setTimeout(r, 10));
function server() {
  const posts = [];
  ctx.fetch = async (path, opts) => {
    if (opts && opts.method === 'POST') posts.push({ path, body: opts.body });
    return { ok: true, blob: async () => ({}), json: async () => ({}) };
  };
  return posts;
}
ctx.URL = { createObjectURL: () => 'blob:x', revokeObjectURL() {} };
ctx.setTimeout = setTimeout;
S.roomId = 'r1'; S.token = 'tok';

(async () => {
  // Played to the end: reported once, with the draft's version, on this phone's token.
  let posts = server();
  fresh(); S.playing = false;
  S.player = { play() { setTimeout(() => this.onended(), 0); return Promise.resolve(); }, pause() {} };
  onMessage(deal(3, 1, 'readback'));
  onMessage(rb(3));
  await tick(); await tick();
  assert.equal(posts.length, 1, 'ended reports once');
  assert.equal(posts[0].path, '/api/rooms/r1/agreement/readback-played');
  assert.equal(posts[0].body, '{"version":3}');

  // The browser blocked playback: nothing is reported and the person is told to tap Play.
  posts = server();
  fresh(); S.playing = false;
  S.player = { play: () => Promise.reject(new Error('NotAllowedError')), pause() {} };
  onMessage(deal(4, 1, 'readback'));
  onMessage(rb(4));
  await tick(); await tick();
  assert.equal(posts.length, 0, 'blocked playback is not reported');
  assert.match(els.status.textContent, /Tap ▶ Play/);

  // A clip that fails to load or errors mid-way is not reported either.
  posts = server();
  fresh(); S.playing = false;
  S.player = { play() { setTimeout(() => this.onerror(new Error('decode')), 0); return Promise.resolve(); }, pause() {} };
  onMessage(deal(5, 1, 'readback'));
  onMessage(rb(5));
  await tick(); await tick();
  assert.equal(posts.length, 0, 'an interrupted clip is not reported');

  // New terms arrive mid-playback: playback stops and is never reported, even if `ended` fires afterwards.
  posts = server();
  fresh(); S.playing = false;
  let paused = 0;
  const hanging = { play: () => Promise.resolve(), pause() { paused++; } };
  S.player = hanging;
  onMessage(deal(6, 1, 'readback'));
  onMessage(rb(6));
  await tick(); await tick();
  onMessage(deal(7, 2, 'offer_open'));
  await tick();
  if (hanging.onended) hanging.onended();
  await tick();
  assert.equal(paused, 1, 'the obsolete read-back stops playing');
  assert.equal(posts.length, 0, 'an obsolete read-back is never reported');

  // The voice of an ordinary turn never reports anything.
  posts = server();
  fresh(); S.playing = false;
  S.player = { play() { setTimeout(() => this.onended(), 0); return Promise.resolve(); }, pause() {} };
  onMessage({ type: 'turn', turnId: 't9', speaker: 'buyer', transcript: 'x', translation: 'y', audioUrl: '/a/t9' });
  await tick(); await tick();
  assert.equal(posts.length, 0);

  // A report that fails is retried a bounded number of times, then the person is told; never a guess that it landed.
  ctx.setTimeout = (f) => setTimeout(f, 0);
  let attempts = 0;
  ctx.fetch = async (path, opts) => {
    if (opts && opts.method === 'POST') { attempts++; throw new Error('offline'); }
    return { ok: true, blob: async () => ({}), json: async () => ({}) };
  };
  fresh(); S.playing = false;
  S.player = { play() { setTimeout(() => this.onended(), 0); return Promise.resolve(); }, pause() {} };
  onMessage(deal(8, 1, 'readback'));
  onMessage(rb(8));
  for (let i = 0; i < 8; i++) await tick();
  assert.equal(attempts, 4, 'first try plus three retries');
  assert.match(els.status.textContent, /could not be told/);

  // A retry stops as soon as the draft has moved on: the phone cannot report for an obsolete version.
  attempts = 0;
  let moveOn = () => {};
  ctx.fetch = async (path, opts) => {
    if (opts && opts.method === 'POST') { attempts++; moveOn(); throw new Error('offline'); }
    return { ok: true, blob: async () => ({}), json: async () => ({}) };
  };
  moveOn = () => onMessage(deal(10, 2, 'offer_open'));
  fresh(); S.playing = false;
  S.player = { play() { setTimeout(() => this.onended(), 0); return Promise.resolve(); }, pause() {} };
  onMessage(deal(9, 1, 'readback'));
  onMessage(rb(9));
  for (let i = 0; i < 8; i++) await tick();
  assert.equal(attempts, 1, 'no retry for an obsolete draft');

  console.log('state-merge-check ok');
})().catch((e) => { console.error(e); process.exit(1); });
