'use strict';
// Pure logic of the demo stage (no DOM), so Node can run it: which messages the stage believes, and how the event rail
// and pipeline lamps are derived from them. Everything here comes from real events a seat received from the server.
(function (root) {
  const SEATS = ['farmer', 'buyer'];
  const KINDS = ['conn', 'event'];
  const CONN_STATES = ['open', 'reconnecting', 'ended'];
  const SAFE = /^[A-Za-z0-9_.:-]{1,80}$/; // ids, language codes and state names; nothing a person said can match a long free text
  const META = ['speaker', 'sourceLanguage', 'targetLanguage', 'state', 'outcome', 'delivery', 'code'];

  // Server event type -> pipeline lamp and rail wording.
  const EVENTS = {
    turn_started: { lamp: 'capture', label: 'Turn started' },
    turn: { lamp: 'delivered', label: 'Translated and voiced' },
    turn_failed: { lamp: 'failed', label: 'Turn failed' },
    advisory: { lamp: 'advice', label: 'Private advice' },
    warning: { lamp: 'caution', label: 'Private caution' },
    agreement: { lamp: 'agreement', label: 'Agreement state' },
    agreement_readback: { lamp: 'agreement', label: 'Terms read back' },
    agreement_retry: { lamp: 'agreement', label: 'Asked to confirm again' },
    agreement_notice: { lamp: 'agreement', label: 'Agreement notice' },
    agreement_final: { lamp: 'agreement', label: 'Nonbinding summary' },
  };
  const LAMPS = [
    { id: 'capture', label: 'Speech in' },
    { id: 'delivered', label: 'Translated + voiced' },
    { id: 'advice', label: 'Farmer advice' },
    { id: 'caution', label: 'Farmer caution' },
    { id: 'agreement', label: 'Agreement' },
    { id: 'failed', label: 'Failure' },
  ];
  const MAX_ENTRIES = 60;

  // The session-storage key the app page uses for one seat; the stage writes the seat's credentials here.
  const seatKey = (roomId, seat) => 'g_one:' + roomId + ':' + seat;

  /**
   * Returns a cleaned message, or null if the stage must ignore it. A message counts only if it came from the same
   * origin AND from the window of one of the two seat frames; the seat is taken from that window, never from what the
   * message claims, and a frame that claims the other seat is dropped.
   */
  function accept(ev, origin, frames) {
    if (!ev || ev.origin !== origin) return null;
    const seat = SEATS.find((s) => frames[s] && ev.source === frames[s]);
    const d = ev.data;
    if (!seat || !d || typeof d !== 'object' || d.source !== 'g-one-seat' || d.v !== 1) return null;
    if (d.seat !== seat || !KINDS.includes(d.kind)) return null;
    if (d.kind === 'conn') return CONN_STATES.includes(d.state) ? { seat, kind: 'conn', state: d.state } : null;
    if (!Object.prototype.hasOwnProperty.call(EVENTS, d.type) || typeof d.key !== 'string' || !SAFE.test(d.key)) return null;
    const meta = {};
    const raw = d.meta && typeof d.meta === 'object' ? d.meta : {};
    for (const f of META) if (typeof raw[f] === 'string' && SAFE.test(raw[f])) meta[f] = raw[f];
    return { seat, kind: 'event', type: d.type, key: d.key, meta };
  }

  const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

  /** Rail + lamps. One entry per server event, listing every seat that reported receiving it. */
  function createRail() {
    const entries = []; // newest first
    const byKey = new Map();
    const counts = Object.fromEntries(LAMPS.map((l) => [l.id, 0]));
    return {
      entries,
      counts,
      record(msg, now) {
        if (msg.kind !== 'event') return null;
        let entry = byKey.get(msg.key);
        if (!entry) {
          entry = { key: msg.key, type: msg.type, at: now, seats: [], meta: msg.meta };
          byKey.set(msg.key, entry);
          entries.unshift(entry);
          counts[EVENTS[msg.type].lamp] += 1;
          if (entries.length > MAX_ENTRIES) byKey.delete(entries.pop().key);
        }
        if (!entry.seats.includes(msg.seat)) entry.seats.push(msg.seat);
        return entry;
      },
    };
  }

  /** Plain-text line for one rail entry. Says only what was observed: who reported receiving it. */
  function describe(entry) {
    const base = EVENTS[entry.type].label;
    const m = entry.meta || {};
    const parts = [];
    if (m.speaker) parts.push(cap(m.speaker) + ' speaking');
    if (m.sourceLanguage && m.targetLanguage) parts.push(m.sourceLanguage + ' to ' + m.targetLanguage);
    if (m.state) parts.push(m.state.replace(/_/g, ' '));
    if (m.outcome) parts.push(m.outcome.replace(/_/g, ' '));
    if (m.delivery) parts.push('WhatsApp copy ' + m.delivery.replace(/_/g, ' '));
    if (m.code) parts.push(m.code.replace(/_/g, ' '));
    const who = entry.seats.map((s) => cap(s) + ' seat').join(' and ');
    return { title: base, detail: parts.join(' · '), received: 'Received by: ' + who };
  }

  root.GOneStageCore = { SEATS, EVENTS, LAMPS, seatKey, accept, createRail, describe };
})(typeof window !== 'undefined' ? window : globalThis);
