'use strict';
// Runs the real static/app.js against a fake sessionStorage. Usage: node session-store-check.js <path to app.js>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function element() {
  return { classList: { toggle() {} }, dataset: {}, style: {}, append() {}, replaceChildren() {}, select() {} };
}
function storage(failWrites) {
  const m = new Map();
  return {
    m,
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem(k, v) { if (failWrites) throw new Error('quota'); m.set(k, String(v)); },
    removeItem(k) { m.delete(k); },
  };
}
function load(sessionStorage) {
  const ctx = {
    document: { body: {}, getElementById: element, createElement: element },
    fetch: () => Promise.reject(new Error('offline')),
    location: { protocol: 'http:', host: 'x', search: '' },
    Option: function Option() {},
    sessionStorage,
  };
  vm.createContext(ctx);
  vm.runInContext(fs.readFileSync(process.argv[2], 'utf8') + '\n;this.app = { S, store };', ctx);
  ctx.app.S.roomId = 'r1';
  return ctx.app;
}

// A tab opened before the rename: the token sits under the old key and is found, moved, and not left behind.
let ss = storage();
ss.setItem('aisetu:r1', JSON.stringify({ token: 'tok', role: 'farmer' }));
let { store } = load(ss);
assert.equal(JSON.stringify(store.get()), JSON.stringify({ token: 'tok', role: 'farmer' }), 'the old key is still read');
assert.equal(ss.getItem('g_one:r1'), JSON.stringify({ token: 'tok', role: 'farmer' }), 'and copied to the new key');
assert.equal(ss.getItem('aisetu:r1'), null, 'the old key is removed after the move');
assert.equal(JSON.stringify(store.get()), JSON.stringify({ token: 'tok', role: 'farmer' }), 'a second read uses the new key');

// The new key wins over a stale old one.
ss = storage();
ss.setItem('g_one:r1', JSON.stringify({ token: 'new', role: 'buyer' }));
ss.setItem('aisetu:r1', JSON.stringify({ token: 'old', role: 'farmer' }));
({ store } = load(ss));
assert.equal(store.get().token, 'new');

// Another room's old entry is not picked up, and nothing stored means nothing returned.
ss = storage();
ss.setItem('aisetu:other', JSON.stringify({ token: 'x', role: 'farmer' }));
({ store } = load(ss));
assert.equal(store.get(), null);
assert.equal(ss.getItem('aisetu:other') !== null, true, 'other rooms are left alone');

// Clearing (room expired) also drops an old entry, so it cannot bring the dead token back.
ss = storage();
ss.setItem('aisetu:r1', JSON.stringify({ token: 'tok', role: 'farmer' }));
ss.setItem('g_one:r1', JSON.stringify({ token: 'tok', role: 'farmer' }));
({ store } = load(ss));
store.clear();
assert.equal(ss.getItem('aisetu:r1'), null);
assert.equal(ss.getItem('g_one:r1'), null);
assert.equal(store.get(), null);

// Storage that cannot be written still yields the saved seat once; unreadable data yields null, never a throw.
ss = storage(true);
ss.m.set('aisetu:r1', JSON.stringify({ token: 'tok', role: 'farmer' }));
({ store } = load(ss));
assert.equal(store.get().token, 'tok');
ss = storage();
ss.m.set('aisetu:r1', '{not json');
({ store } = load(ss));
assert.equal(store.get(), null);

console.log('session-store-check ok');
