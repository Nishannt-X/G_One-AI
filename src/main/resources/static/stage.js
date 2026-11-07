'use strict';
// Demo stage: creates one room, seats a farmer and a buyer in it, and frames the real app twice (same origin).
// Nothing here simulates activity: the rail and lamps only react to events the two frames report from the server.
(function () {
  const core = window.GOneStageCore;
  const $ = (id) => document.getElementById(id);
  if (!core || !$('stageForm')) return;

  const STAGE_KEY = 'g_one:stage';
  const SEAT_TEXT = {
    idle: 'Idle. Start a session to fill this seat.',
    loading: 'Opening…',
    open: 'Connected to the server.',
    reconnecting: 'Reconnecting…',
    ended: 'Session ended (the room expired or was closed).',
  };
  const frames = { farmer: null, buyer: null };
  let cfg = null;
  let rail = core.createRail();

  const ss = {
    get(k) { try { return sessionStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { sessionStorage.setItem(k, v); return true; } catch (e) { return false; } },
    del(k) { try { sessionStorage.removeItem(k); } catch (e) { /* optional */ } },
  };

  const say = (id, text) => { $(id).textContent = text; };
  const seatStatus = (seat, state) => say('seat-' + seat, SEAT_TEXT[state]);

  async function call(path, body) {
    let res;
    try {
      res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    } catch (e) { throw new Error('Cannot reach the G-One server. Check your connection.'); }
    let data = {};
    try { data = await res.json(); } catch (e) { /* not json */ }
    if (!res.ok) throw new Error(data.message || 'The server refused the request (' + res.status + ').');
    return data;
  }

  function fillLanguages(sel, preferred) {
    const langs = cfg.languages;
    sel.replaceChildren(...langs.map((l) => new Option(l.label, l.code)));
    const pick = langs.find((l) => l.code === preferred) || langs[0];
    if (pick) sel.value = pick.code;
  }

  // ---- seats ---------------------------------------------------------------
  function mount(roomId) {
    for (const seat of core.SEATS) {
      const frame = document.createElement('iframe');
      frame.title = (seat === 'farmer' ? 'Farmer' : 'Buyer') + ' seat: the G-One app';
      frame.setAttribute('allow', 'microphone');
      frame.src = '/app.html?room=' + encodeURIComponent(roomId) + '&seat=' + seat;
      $('phone-' + seat).replaceChildren(frame);
      frames[seat] = frame;
      $('idle-' + seat).hidden = true;
      seatStatus(seat, 'loading');
    }
    $('startBtn').hidden = true;
    $('endBtn').hidden = false;
    $('stageFields').hidden = true;
  }

  function unmount(roomId) {
    for (const seat of core.SEATS) {
      $('phone-' + seat).replaceChildren();
      frames[seat] = null;
      $('idle-' + seat).hidden = false;
      seatStatus(seat, 'idle');
      if (roomId) ss.del(core.seatKey(roomId, seat));
    }
    ss.del(STAGE_KEY);
    $('startBtn').hidden = false;
    $('endBtn').hidden = true;
    $('stageFields').hidden = false;
    say('stageInfo', '');
    rail = core.createRail();
    renderRail();
  }

  async function start(e) {
    e.preventDefault();
    say('stageMsg', '');
    $('startBtn').disabled = true;
    try {
      const code = $('accessCode').value;
      const farmer = await call('/api/rooms', { role: 'farmer', language: $('farmerLang').value, accessCode: code });
      const buyer = await call('/api/rooms/' + encodeURIComponent(farmer.roomId) + '/join', { language: $('buyerLang').value });
      if (buyer.roomId !== farmer.roomId || buyer.role !== 'buyer') throw new Error('The server did not seat the buyer.');
      const saved = [ss.set(core.seatKey(farmer.roomId, 'farmer'), JSON.stringify({ token: farmer.token, role: 'farmer' })),
        ss.set(core.seatKey(farmer.roomId, 'buyer'), JSON.stringify({ token: buyer.token, role: 'buyer' }))];
      if (saved.includes(false)) throw new Error('This browser blocks session storage, so the seats cannot sign in. Allow site data and try again.');
      ss.set(STAGE_KEY, JSON.stringify({ roomId: farmer.roomId }));
      $('accessCode').value = '';
      rail = core.createRail();
      renderRail();
      mount(farmer.roomId);
    } catch (err) {
      say('stageMsg', err.message);
    } finally {
      $('startBtn').disabled = false;
    }
  }

  // ---- rail ----------------------------------------------------------------
  const time = (t) => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

  function renderRail() {
    $('lamps').replaceChildren(...core.LAMPS.map((l) => {
      const n = rail.counts[l.id];
      const li = document.createElement('li');
      li.className = 'lamp' + (n ? ' seen' : '') + (l.id === 'failed' && n ? ' bad' : '');
      const name = document.createElement('span');
      name.textContent = l.label;
      const state = document.createElement('strong');
      state.textContent = n ? 'seen ×' + n : 'waiting';
      li.append(name, state);
      return li;
    }));
    $('railEmpty').hidden = rail.entries.length > 0;
    $('rail').replaceChildren(...rail.entries.map((entry) => {
      const d = core.describe(entry);
      const li = document.createElement('li');
      const head = document.createElement('div');
      head.className = 'rail-head';
      const t = document.createElement('time');
      t.textContent = time(entry.at);
      const title = document.createElement('strong');
      title.textContent = d.title;
      head.append(title, t);
      li.append(head);
      for (const line of [d.detail, d.received]) {
        if (!line) continue;
        const p = document.createElement('p');
        p.textContent = line;
        li.append(p);
      }
      return li;
    }));
  }

  window.addEventListener('message', (ev) => {
    const msg = core.accept(ev, location.origin, { farmer: frames.farmer && frames.farmer.contentWindow, buyer: frames.buyer && frames.buyer.contentWindow });
    if (!msg) return;
    if (msg.kind === 'conn') return seatStatus(msg.seat, msg.state);
    rail.record(msg, Date.now());
    renderRail();
  });

  // ---- boot ----------------------------------------------------------------
  async function boot() {
    renderRail();
    $('endBtn').onclick = () => {
      let id = null;
      try { id = JSON.parse(ss.get(STAGE_KEY)).roomId; } catch (e) { /* none saved */ }
      unmount(id);
    };
    try {
      cfg = await (await fetch('/api/config')).json();
    } catch (e) {
      say('stageMsg', 'Cannot reach the G-One server, so the stage cannot start. Is it running?');
      return;
    }
    fillLanguages($('farmerLang'), 'hi-IN');
    fillLanguages($('buyerLang'), 'en-IN');
    $('codeRow').hidden = !cfg.accessCodeRequired;
    $('accessCode').required = !!cfg.accessCodeRequired;
    if (!cfg.ready) {
      say('stageNotice', 'This server has no speech or translation credentials configured. You can open both seats, but every turn will fail.');
    }
    $('stageForm').onsubmit = start;
    $('startBtn').disabled = false;

    // Reload in the same tab: take the seats back if both are still saved. The server may have expired the room;
    // the seats then say so themselves.
    let saved = null;
    try { saved = JSON.parse(ss.get(STAGE_KEY)); } catch (e) { /* none */ }
    if (saved && saved.roomId && core.SEATS.every((s) => ss.get(core.seatKey(saved.roomId, s)))) {
      say('stageInfo', 'Resumed the session from this tab. The event rail shows only what happens from now on.');
      mount(saved.roomId);
    } else if (saved) {
      ss.del(STAGE_KEY);
    }
  }
  boot();
})();
