'use strict';
// Runs the real static/stage-core.js. Usage: node stage-core-check.js <path to stage-core.js>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const ctx = {};
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(process.argv[2], 'utf8'), ctx);
const core = ctx.GOneStageCore;
const ORIGIN = 'http://localhost:8080';
const farmerWin = { name: 'farmer-window' };
const buyerWin = { name: 'buyer-window' };
const frames = { farmer: farmerWin, buyer: buyerWin };
const post = (over = {}, from = farmerWin, origin = ORIGIN) => ({
  origin, source: from,
  data: Object.assign({ source: 'g-one-seat', v: 1, seat: from === farmerWin ? 'farmer' : 'buyer', kind: 'event', type: 'turn', key: 'turn:t1',
    meta: { speaker: 'buyer', sourceLanguage: 'en-IN', targetLanguage: 'hi-IN' } }, over),
});
const plain = (x) => JSON.parse(JSON.stringify(x)); // values built inside the vm context have another Array prototype
const accept = (ev) => core.accept(ev, ORIGIN, frames);

// A well-formed message from a seat frame is accepted, and the seat comes from the window.
assert.deepEqual(JSON.parse(JSON.stringify(accept(post()))), {
  seat: 'farmer', kind: 'event', type: 'turn', key: 'turn:t1', meta: { speaker: 'buyer', sourceLanguage: 'en-IN', targetLanguage: 'hi-IN' } });
assert.equal(accept(post({}, buyerWin)).seat, 'buyer');

// Everything else is ignored: other origin, unknown window, no window at all, claiming the other seat, wrong envelope.
assert.equal(accept(post({}, farmerWin, 'https://evil.example')), null, 'foreign origin');
assert.equal(accept(post({}, { name: 'other' })), null, 'a window that is not a seat frame');
assert.equal(core.accept(post(), ORIGIN, { farmer: null, buyer: null }), null, 'no frames mounted yet');
assert.equal(accept(post({ seat: 'buyer' })), null, 'a frame cannot speak for the other seat');
assert.equal(accept(post({ source: 'something-else' })), null);
assert.equal(accept(post({ v: 2 })), null);
assert.equal(accept(post({ kind: 'script' })), null);
assert.equal(accept({ origin: ORIGIN, source: farmerWin, data: 'text' }), null);
assert.equal(accept(null), null);

// Unknown or prototype-ish event types and unsafe keys are dropped.
for (const type of ['constructor', '__proto__', 'toString', 'made_up']) assert.equal(accept(post({ type })), null, type);
assert.equal(accept(post({ key: 'has spaces and a whole sentence someone said' })), null);
assert.equal(accept(post({ key: 'x'.repeat(81) })), null);
assert.equal(accept(post({ key: 7 })), null);

// Metadata: only whitelisted fields with short safe values survive, so spoken text cannot ride along.
const m = accept(post({ meta: { speaker: 'buyer', transcript: 'hello', translation: 'namaste', outcome: 'I will not let you sell to anyone', state: 'offer_open' } }));
assert.deepEqual(JSON.parse(JSON.stringify(m.meta)), { speaker: 'buyer', state: 'offer_open' });
assert.deepEqual(JSON.parse(JSON.stringify(accept(post({ meta: null })).meta)), {});

// Connection messages.
assert.equal(accept(post({ kind: 'conn', state: 'open' })).state, 'open');
assert.equal(accept(post({ kind: 'conn', state: 'bogus' })), null);

// Rail and lamps start empty: nothing is shown as activity before a real event.
let rail = core.createRail();
assert.equal(rail.entries.length, 0);
assert.ok(core.LAMPS.every((l) => rail.counts[l.id] === 0), 'every lamp starts at waiting');

// One server event reported by both seats is one entry that lists both; a private one lists a single seat.
const ev = (seat, type, key, meta = {}) => ({ seat, kind: 'event', type, key, meta });
rail.record(ev('farmer', 'turn_started', 'turn_started:t1', { speaker: 'buyer' }), 1000);
rail.record(ev('buyer', 'turn_started', 'turn_started:t1', { speaker: 'buyer' }), 1001);
rail.record(ev('farmer', 'turn', 'turn:t1', { speaker: 'buyer', sourceLanguage: 'en-IN', targetLanguage: 'hi-IN' }), 1002);
rail.record(ev('buyer', 'turn', 'turn:t1'), 1003);
rail.record(ev('farmer', 'advisory', 'advisory:t1'), 1004);
assert.equal(rail.entries.length, 3);
assert.deepEqual(plain(rail.entries.map((e) => e.type)), ['advisory', 'turn', 'turn_started'], 'newest first');
assert.deepEqual(plain(rail.entries[0].seats), ['farmer'], 'the advisory was reported by the farmer seat only');
assert.deepEqual(plain(rail.entries[1].seats), ['farmer', 'buyer']);
assert.equal(rail.counts.capture, 1);
assert.equal(rail.counts.delivered, 1);
assert.equal(rail.counts.advice, 1);
assert.equal(rail.counts.caution, 0, 'no caution was received, so that lamp stays waiting');
assert.equal(rail.record({ seat: 'farmer', kind: 'conn', state: 'open' }, 5), null, 'connection changes are not rail events');

// Several agreement event types share one lamp but stay separate entries.
rail.record(ev('farmer', 'agreement', 'agreement:a1', { state: 'offer_open' }), 2000);
rail.record(ev('farmer', 'agreement_final', 'agreement_final:a2', { delivery: 'not_sent' }), 2001);
assert.equal(rail.counts.agreement, 2);

// The rail keeps at most 60 entries.
rail = core.createRail();
for (let i = 0; i < 75; i++) rail.record(ev('farmer', 'turn_started', 'turn_started:n' + i), i);
assert.equal(rail.entries.length, 60);
assert.equal(rail.entries[0].key, 'turn_started:n74');

// Wording says what was observed and no more.
const d = core.describe(rail.entries[0]);
assert.equal(d.title, 'Turn started');
assert.equal(d.received, 'Received by: Farmer seat');
const both = core.describe({ type: 'agreement_final', seats: ['farmer', 'buyer'], meta: { delivery: 'not_sent' } });
assert.equal(both.received, 'Received by: Farmer seat and Buyer seat');
assert.equal(both.detail, 'WhatsApp copy not sent');
for (const k of Object.keys(core.EVENTS)) {
  const text = core.EVENTS[k].label.toLowerCase();
  assert.ok(!/intercept|block|shield|protect|safe|real.?time|instant|locked/.test(text), 'no claim words in ' + k);
}

// The storage key the stage writes is the one the app reads (app.js is checked against the same string in embed-check.js).
assert.equal(core.seatKey('r1', 'farmer'), 'g_one:r1:farmer');

console.log('stage-core-check ok');
