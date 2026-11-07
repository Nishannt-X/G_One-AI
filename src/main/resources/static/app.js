'use strict';
const $ = (id) => document.getElementById(id);
const TARGET_RATE = 16000;
const S = {
  cfg: null, roomId: null, token: null, role: null, ws: null, wsGen: 0, people: [], busy: false,
  events: [], rec: null, timer: null, lastWav: null, uploading: false, player: null, queue: [], playing: false,
  current: null, stopPlayback: null, seat: null, embed: false,
};
// Two same-origin frames of one tab share one sessionStorage. A demo-stage frame is told its seat in the URL, so its saved
// token never collides with the other seat's. No seat means a plain tab, which keeps the original key and room links.
const SEATS = ['farmer', 'buyer'];

const label = (code) => (S.cfg.languages.find((l) => l.code === code) || { label: code }).label;
const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const storeKey = () => 'g_one:' + S.roomId + (S.seat ? ':' + S.seat : '');
// Before the rename the key was 'aisetu:<room>'. A tab opened then still holds its token under that key, so read it once
// and move it, or a reload after the upgrade would lose the seat in the room.
const legacyStoreKey = () => 'aisetu:' + S.roomId;
const store = {
  get() {
    try {
      const current = sessionStorage.getItem(storeKey());
      if (current !== null) return JSON.parse(current);
      const former = S.seat ? null : sessionStorage.getItem(legacyStoreKey());
      if (former === null) return null;
      const saved = JSON.parse(former);
      try { sessionStorage.setItem(storeKey(), former); sessionStorage.removeItem(legacyStoreKey()); } catch (e) { /* still usable this time */ }
      return saved;
    } catch (e) { return null; }
  },
  set(v) { try { sessionStorage.setItem(storeKey(), JSON.stringify(v)); } catch (e) { /* optional */ } },
  clear() { try { sessionStorage.removeItem(storeKey()); sessionStorage.removeItem(legacyStoreKey()); } catch (e) { /* optional */ } },
};

async function api(path, opts = {}) {
  const headers = Object.assign({}, opts.headers);
  if (S.token) headers.Authorization = 'Bearer ' + S.token;
  if (opts.json) { headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(opts.json); }
  let res;
  try { res = await fetch(path, Object.assign({}, opts, { headers })); }
  catch (e) { throw { code: 'network', message: 'Cannot reach the server. Check your connection.' }; }
  if (!res.ok) {
    let err = {};
    try { err = await res.json(); } catch (e) { /* not json */ }
    throw { code: err.code || 'error', message: err.message || 'Request failed.' };
  }
  return res;
}

// ---- landing -------------------------------------------------------------
function fillLanguages(sel, selected) {
  sel.replaceChildren(...S.cfg.languages.map((l) => new Option(l.label, l.code, false, l.code === selected)));
}

async function init() {
  S.cfg = await (await fetch('/api/config')).json();
  const params = new URLSearchParams(location.search);
  S.roomId = params.get('room');
  S.seat = SEATS.includes(params.get('seat')) ? params.get('seat') : null;
  S.embed = !!S.seat && window.parent !== window;
  if (S.embed) document.body.classList.add('embed');
  fillLanguages($('langSel'));
  if (S.roomId) {
    const saved = store.get();
    // A seat only ever enters as its own role, whatever an edited storage entry says.
    if (saved && (!S.seat || saved.role === S.seat)) { S.token = saved.token; S.role = saved.role; return enterRoom(); }
  }
  if (S.seat) { $('seatless').hidden = false; return; }
  $('landing').hidden = false;
  $('roleRow').hidden = !!S.roomId;
  $('codeRow').hidden = !!S.roomId || !S.cfg.accessCodeRequired;
  $('landingTitle').textContent = S.roomId ? 'Join the conversation' : 'Start a conversation';
  $('landingHelp').textContent = S.roomId
    ? 'You will join as the other role. Pick the language you will speak and hear.'
    : 'Choose your role and language, then share the link with the other person.';
  $('goBtn').textContent = S.roomId ? 'Join' : 'Create room';
  if (!S.cfg.ready) $('landingMsg').textContent = 'Server is missing speech/translation credentials; turns will fail.';
  $('goBtn').onclick = landingGo;
}

async function landingGo() {
  unlockAudio(); // must happen inside this tap so phones allow later playback
  $('landingMsg').textContent = '';
  $('goBtn').disabled = true;
  try {
    const language = $('langSel').value;
    const res = await api(S.roomId ? `/api/rooms/${S.roomId}/join` : '/api/rooms', {
      method: 'POST',
      json: S.roomId ? { language } : { role: $('roleSel').value, language, accessCode: $('codeInput').value },
    });
    const body = await res.json();
    S.roomId = body.roomId; S.token = body.token; S.role = body.role;
    store.set({ token: S.token, role: S.role });
    history.replaceState(null, '', '?room=' + encodeURIComponent(S.roomId));
    $('landing').hidden = true;
    enterRoom();
  } catch (e) {
    $('landingMsg').textContent = e.message;
    $('goBtn').disabled = false;
  }
}

// ---- room ----------------------------------------------------------------
function enterRoom() {
  $('landing').hidden = true;
  $('room').hidden = false;
  $('shareLink').value = location.origin + '/?room=' + encodeURIComponent(S.roomId);
  $('copyBtn').onclick = async () => {
    try { await navigator.clipboard.writeText($('shareLink').value); $('copyBtn').textContent = 'Copied'; }
    catch (e) { $('shareLink').select(); }
  };
  $('micBtn').onclick = toggleRecording;
  $('retryBtn').onclick = () => upload(S.lastWav);
  $('rerecordBtn').onclick = () => { S.lastWav = null; $('failBox').hidden = true; setStatus(''); refresh(); };
  $('myLang').onchange = async () => {
    try { await api(`/api/rooms/${S.roomId}/language`, { method: 'POST', json: { language: $('myLang').value } }); }
    catch (e) { setStatus(e.message); }
  };
  connect();
}

function connect() {
  const gen = ++S.wsGen;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${proto}//${location.host}/ws?room=${encodeURIComponent(S.roomId)}&token=${encodeURIComponent(S.token)}`);
  S.ws = ws;
  ws.onopen = () => { $('conn').textContent = 'Connected'; tell({ kind: 'conn', state: 'open' }); };
  ws.onmessage = (m) => onMessage(JSON.parse(m.data));
  ws.onclose = (ev) => {
    if (gen !== S.wsGen) return;
    if (ev.code === 1008) { // room gone or token rejected
      store.clear(); S.token = null;
      $('conn').textContent = 'Session ended';
      setStatus('This room has expired. Ask for a new link.');
      tell({ kind: 'conn', state: 'ended' });
      return refresh();
    }
    $('conn').textContent = 'Reconnecting…';
    tell({ kind: 'conn', state: 'reconnecting' });
    setTimeout(() => { if (gen === S.wsGen) connect(); }, 1500);
  };
}

// The server sends `state` (a history snapshot) right after a socket opens, but an event recorded in that window can
// reach this phone first. Keep such events: type + id (agreement events) or turnId identifies one, so no duplicates.
// ponytail: events evicted from server history but still held here are appended last; fine for a short demo room.
const eventKey = (e) => e.type + ':' + (e.id || e.turnId);
function mergeEvents(snapshot, local) {
  const known = new Set(snapshot.map(eventKey));
  return snapshot.concat(local.filter((e) => !known.has(eventKey(e))));
}

function onMessage(m) {
  switch (m.type) {
    case 'state':
      S.role = m.you; S.people = m.participants; S.busy = m.busy; S.events = mergeEvents(m.events, S.events);
      dropObsolete(); resumeReadback(); break;
    case 'presence':
      S.people = m.participants; S.busy = m.busy; break;
    case 'turn_started':
      S.busy = true;
      if (m.speaker !== S.role) setStatus(cap(m.speaker) + ' spoke. Translating…');
      break;
    case 'turn':
      S.busy = false; setStatus('');
      if (!S.events.some((e) => e.type === 'turn' && e.turnId === m.turnId)) {
        S.events.push(m);
        if (m.audioUrl) enqueue(m.audioUrl);
      }
      break;
    case 'advisory':
    case 'warning':
    case 'agreement':
    case 'agreement_readback':
    case 'agreement_retry':
    case 'agreement_notice':
    case 'agreement_final':
      if (!S.events.some((e) => eventKey(e) === eventKey(m))) {
        S.events.push(m);
        if (m.type === 'agreement') dropObsolete();
        if (m.audioUrl && !obsolete(m)) enqueue(m.audioUrl, m);
      }
      break;
    case 'turn_failed':
      S.busy = false;
      if (m.speaker !== S.role) setStatus(cap(m.speaker) + "'s message failed. They will try again.");
      break;
    default: break;
  }
  // Only live events are reported; the `state` snapshot replays history and must not look like new activity, and
  // presence is not a pipeline event.
  if (m.type !== 'state' && m.type !== 'presence') tell({ kind: 'event', type: m.type, key: eventKey(m), meta: eventMeta(m) });
  refresh();
}

// ---- demo stage ------------------------------------------------------------
// Inside a stage frame, tell the parent page (same origin only) which real events this seat received. Metadata only:
// never transcripts, translations, tokens or audio links. The stage validates every field again.
const META_FIELDS = ['speaker', 'sourceLanguage', 'targetLanguage', 'state', 'outcome', 'delivery', 'code'];
function eventMeta(m) {
  const meta = {};
  for (const f of META_FIELDS) if (typeof m[f] === 'string') meta[f] = m[f];
  return meta;
}
function tell(msg) {
  if (!S.embed) return;
  try { window.parent.postMessage(Object.assign({ source: 'g-one-seat', v: 1, seat: S.seat }, msg), location.origin); }
  catch (e) { /* the stage is optional; the app works without it */ }
}

function refresh() {
  const me = S.people.find((p) => p.role === S.role);
  const peer = S.people.find((p) => p.role !== S.role);
  $('whoami').textContent = S.role ? `You: ${cap(S.role)}` : '';
  $('peer').textContent = !peer ? 'Waiting for partner'
    : `${cap(peer.role)} (${label(peer.language)}) ${peer.connected ? 'online' : 'offline'}`;
  $('shareBox').hidden = !!peer;
  const sel = $('myLang');
  if (me && sel.dataset.ready !== '1') { fillLanguages(sel, me.language); sel.dataset.ready = '1'; }
  if (me) sel.value = me.language;
  sel.disabled = S.busy || !!S.rec;

  const mic = $('micBtn');
  mic.classList.toggle('recording', !!S.rec);
  mic.disabled = !S.token || (!S.rec && (!peer || !peer.connected || S.busy || S.uploading || !!S.lastWav));
  if (!S.rec) {
    mic.textContent = S.uploading ? 'Translating…'
      : !peer ? 'Waiting for the other person' : 'Tap to start speaking';
  }
  renderDeal();
  renderLog();
}

// ---- agreement -------------------------------------------------------------
const dealTerms = (t) => t ? [cap(t.commodity), t.variety ? 'variety ' + t.variety : 'variety not stated',
  t.market ? t.market : 'market not stated', '₹' + Number(t.price).toLocaleString('en-IN') + ' per ' + t.unit].join(' · ') : '';
const DEAL_CLOSED = {
  declined: 'Declined. Nothing was agreed.',
  expired: 'Time ran out. Nothing was agreed.',
  voided: 'The terms changed. Earlier confirmations are cancelled. Nothing was agreed. The buyer must state the new offer clearly to start again.',
  rejected: 'The offer was declined.',
  lapsed: 'The offer was not answered and is no longer open.',
};
function dealView(d) {
  const yes = (r) => (d.confirmed && d.confirmed[r] ? 'said yes' : 'not yet');
  switch (d.state) {
    case 'offer_open':
      return [d.outcome === 'readback_failed' ? 'The terms could not be spoken to both of you. Farmer: say yes again to retry.'
        : 'Offer noted. The farmer can accept it by voice.', ''];
    case 'readback': {
      const done = (r) => (d.readbackPlayed && d.readbackPlayed[r] ? 'played' : 'waiting');
      return ['Playing the terms on both phones. Nobody can confirm until both have finished. If yours is silent, tap ▶ Play on the card below.',
        `Farmer: ${done('farmer')} · Buyer: ${done('buyer')}`];
    }
    case 'confirming': return ['Each of you: say "Yes, I agree" or "No".', `Farmer: ${yes('farmer')} · Buyer: ${yes('buyer')}`];
    case 'agreed': return ['Both said yes by voice.', ''];
    default: return [DEAL_CLOSED[d.outcome] || 'Closed.', ''];
  }
}
// The `agreement` event with the highest seq is the deal's current state (two server threads may deliver out of order);
// the cards below carry what was said and sent.
function currentDeal() {
  return S.events.filter((e) => e.type === 'agreement').reduce((a, e) => (!a || (e.seq || 0) >= (a.seq || 0) ? e : a), null);
}
// A read-back or retry belongs to one draft version: once that draft has moved on it must not play or be acted on.
function obsolete(e) {
  if (e.version === undefined) return false;
  const d = currentDeal();
  return !!d && (d.version !== e.version || !['readback', 'confirming', 'agreed'].includes(d.state));
}
function dropObsolete() {
  S.queue = S.queue.filter((item) => !(item.event && obsolete(item.event)));
  if (S.current && S.current.event && obsolete(S.current.event) && S.stopPlayback) S.stopPlayback();
}
// A phone that missed its read-back (socket dropped, page reloaded) still owes it: nothing can be confirmed until it plays.
function resumeReadback() {
  const d = currentDeal();
  if (!d || d.state !== 'readback' || (d.readbackPlayed && d.readbackPlayed[S.role])) return;
  const e = S.events.filter((x) => x.type === 'agreement_readback' && x.version === d.version && x.audioUrl).pop();
  if (!e) return;
  const same = (item) => item && item.event && eventKey(item.event) === eventKey(e);
  if (!same(S.current) && !S.queue.some(same)) enqueue(e.audioUrl, e);
}
function renderDeal() {
  const d = currentDeal();
  $('deal').hidden = !d;
  if (!d) return;
  const [status, who] = dealView(d);
  $('dealTerms').textContent = dealTerms(d.terms);
  $('dealStatus').textContent = status;
  $('dealWho').textContent = who;
}

const DELIVERY = { sent: 'WhatsApp copy: sent.', not_sent: 'WhatsApp copy: not sent.' };
const CARD_TITLE = {
  agreement_readback: 'Please check these terms', agreement_retry: 'Please confirm clearly',
  agreement_notice: 'Agreement update', agreement_final: 'Nonbinding summary',
};

function renderLog() {
  const log = $('log');
  log.replaceChildren(...S.events.filter((e) => e.type !== 'agreement').slice().reverse().map((e) => {
    const card = document.createElement('div');
    card.className = 'card' + (e.type === 'advisory' ? ' advice' : e.type === 'warning' ? ' warning'
      : e.type.startsWith('agreement') ? ' deal' : '');
    const who = document.createElement('div');
    who.className = 'who';
    const body = [];
    if (e.type === 'warning') {
      who.textContent = 'Private caution for you';
      const t = document.createElement('div');
      t.className = 'dst'; t.textContent = e.text; body.push(t);
    } else if (e.type === 'advisory') {
      who.textContent = 'Private advice for you (dated reference)';
      const t = document.createElement('div');
      t.className = 'dst'; t.textContent = e.text; body.push(t);
    } else if (e.type.startsWith('agreement')) {
      who.textContent = CARD_TITLE[e.type] || 'Agreement';
      const t = document.createElement('div');
      t.className = 'dst'; t.textContent = e.text; body.push(t);
      if (obsolete(e)) {
        const o = document.createElement('div');
        o.className = 'src'; o.textContent = 'No longer valid: these terms were replaced or closed.'; body.push(o);
      }
      if (e.type === 'agreement_final') {
        const d = document.createElement('div');
        d.className = 'src'; d.textContent = DELIVERY[e.delivery] || 'WhatsApp copy: not sent.'; body.push(d);
      }
    } else {
      who.textContent = `${cap(e.speaker)} · ${label(e.sourceLanguage)} → ${label(e.targetLanguage)}`;
      const src = document.createElement('div'); src.className = 'src'; src.textContent = e.transcript;
      const dst = document.createElement('div'); dst.className = 'dst'; dst.textContent = e.translation;
      body.push(src, dst);
    }
    card.append(who, ...body);
    if (e.audioUrl && !obsolete(e)) {
      const b = document.createElement('button');
      b.textContent = '▶ Play';
      b.onclick = () => { unlockAudio(); enqueue(e.audioUrl, e); };
      card.append(b);
    }
    return card;
  }));
}

function setStatus(t) { $('status').textContent = t; }

// ---- recording -> 16 kHz mono WAV ---------------------------------------
async function toggleRecording() {
  unlockAudio();
  if (S.rec) return stopRecording();
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } });
    const mime = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm', 'audio/ogg;codecs=opus']
      .find((t) => window.MediaRecorder && MediaRecorder.isTypeSupported(t));
    const rec = new MediaRecorder(stream, mime ? { mimeType: mime } : {});
    const chunks = [];
    rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data); };
    rec.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      finishRecording(new Blob(chunks, { type: rec.mimeType }));
    };
    S.rec = rec;
    rec.start();
    const started = Date.now();
    const limit = S.cfg.maxTurnSeconds - 1; // headroom so the clip stays under the server cap
    S.timer = setInterval(() => {
      const sec = Math.floor((Date.now() - started) / 1000);
      $('micBtn').textContent = `Tap to stop (${sec}s / ${limit}s)`;
      if (sec >= limit) stopRecording();
    }, 250);
    $('micBtn').textContent = 'Tap to stop (0s)';
    setStatus('Recording…');
    refresh();
  } catch (e) {
    S.rec = null;
    setStatus('Microphone unavailable. Allow microphone access (the page must be opened over HTTPS).');
  }
}

function stopRecording() {
  clearInterval(S.timer);
  const rec = S.rec;
  S.rec = null;
  if (rec && rec.state !== 'inactive') rec.stop();
  setStatus('Processing recording…');
  refresh();
}

async function finishRecording(blob) {
  try {
    const wav = await blobToWav(blob);
    S.lastWav = wav;
    await upload(wav);
  } catch (e) {
    S.lastWav = null;
    setStatus('Could not read the recording. Record again.');
    refresh();
  }
}

async function blobToWav(blob) {
  const Ctx = window.AudioContext || window.webkitAudioContext;
  const ctx = new Ctx();
  let decoded;
  try { decoded = await ctx.decodeAudioData(await blob.arrayBuffer()); } finally { ctx.close(); }
  const off = new OfflineAudioContext(1, Math.max(1, Math.ceil(decoded.duration * TARGET_RATE)), TARGET_RATE);
  const src = off.createBufferSource();
  src.buffer = decoded; src.connect(off.destination); src.start();
  const rendered = await off.startRendering();
  return encodeWav(rendered.getChannelData(0), TARGET_RATE);
}

function encodeWav(samples, rate) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); str(8, 'WAVE'); str(12, 'fmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, 'data'); v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) {
    const x = Math.max(-1, Math.min(1, samples[i]));
    v.setInt16(44 + i * 2, x < 0 ? x * 0x8000 : x * 0x7fff, true);
  }
  return new Blob([buf], { type: 'audio/wav' });
}

async function upload(wav) {
  S.uploading = true;
  $('failBox').hidden = true;
  setStatus('Sending and translating…');
  refresh();
  try {
    const form = new FormData();
    form.append('audio', wav, 'turn.wav');
    await api(`/api/rooms/${S.roomId}/turns`, { method: 'POST', body: form });
    S.lastWav = null; // success: the recording is discarded
    setStatus('');
  } catch (e) {
    $('failText').textContent = e.message;
    // A too-short/too-long/invalid recording cannot succeed on retry.
    $('retryBtn').hidden = ['bad_audio', 'too_long', 'no_speech'].includes(e.code);
    $('failBox').hidden = false;
    setStatus('');
  } finally {
    S.uploading = false;
    refresh();
  }
}

// ---- playback ------------------------------------------------------------
function unlockAudio() {
  if (S.player) return;
  S.player = new Audio();
  const silent = encodeWav(new Float32Array(800), 8000);
  S.player.src = URL.createObjectURL(silent);
  S.player.play().catch(() => { /* unlock is best-effort */ });
}

function enqueue(url, event) { S.queue.push({ url, event: event || null }); playNext(); }

const STOPPED = {};
async function playNext() {
  if (S.playing || !S.queue.length) return;
  S.playing = true;
  const item = S.queue.shift();
  let objectUrl;
  try {
    if (item.event && obsolete(item.event)) return;
    S.current = item;
    const res = await api(item.url);
    objectUrl = URL.createObjectURL(await res.blob());
    if (item.event && obsolete(item.event)) return;
    if (!S.player) S.player = new Audio();
    S.player.src = objectUrl;
    await new Promise((resolve, reject) => {
      S.player.onended = resolve; S.player.onerror = reject;
      S.stopPlayback = () => { S.player.pause(); reject(STOPPED); };
      S.player.play().catch(reject);
    });
    if (item.event && item.event.type === 'agreement_readback') reportReadbackPlayed(item.event, 0);
  } catch (e) {
    if (e !== STOPPED) setStatus('Tap ▶ Play on the message to hear it (the browser blocked automatic playback).');
  } finally {
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    S.current = null; S.stopPlayback = null;
    S.playing = false;
    playNext();
  }
}

// Only the browser's `ended` event gets here: the clip played to its end on this phone. It cannot tell whether anyone
// heard it (volume, headphones). The server ignores a report for an old draft or a repeated one.
async function reportReadbackPlayed(event, attempt) {
  if (obsolete(event)) return;
  try {
    await api(`/api/rooms/${S.roomId}/agreement/readback-played`, { method: 'POST', json: { version: event.version } });
  } catch (e) {
    if (attempt < 3) setTimeout(() => reportReadbackPlayed(event, attempt + 1), 1500);
    else setStatus('The server could not be told the terms finished playing. Tap ▶ Play on the terms again.');
  }
}

init().catch(() => { document.body.textContent = 'Cannot load the app. Is the server running?'; });
