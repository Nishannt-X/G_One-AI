// Console event contract. Mirrors docs/console-events.md and the Java records in
// backend/src/main/java/com/example/g_one/core/ConsoleEvent.java exactly. Change all three together, and only
// through a contract issue. A backend test checks that every interface here has the same fields as its Java record.
//
// Times named ...Ms are epoch milliseconds, except durationMs (a length in milliseconds). Language fields hold a
// Gnani language code such as "hi-IN". All data is synthetic; events never carry raw phone numbers or audio.

export type LegRole = 'farmer' | 'buyer'

export type LegSource = 'twilio' | 'simulator'

export type WhisperKind = 'price_reference' | 'caution' | 'tip'

/** draft: terms heard; readback: being read back; confirming: waiting for both yeses; closed: declined, expired or replaced. */
export type DealState = 'draft' | 'readback' | 'confirming' | 'agreed' | 'closed'

/** said: the farmer said it; inferred: the assistant inferred it. */
export type Origin = 'said' | 'inferred'

export interface Provenance {
  callId: string
  saidOrInferred: Origin
  atMs: number
}

export interface ProfileFact {
  field: string
  value: string
  provenance: Provenance
}

export interface CallGist {
  callId: string
  startedMs: number
  endedMs: number
  summary: string
  followUps: string[]
}

/** A farmer leg opened a new call. */
export interface CallStarted {
  type: 'call_started'
  callId: string
  atMs: number
  language: string
}

/** A leg joined the call: the farmer leg first, then a buyer leg after connect. */
export interface LegJoined {
  type: 'leg_joined'
  callId: string
  atMs: number
  legId: string
  role: LegRole
  source: LegSource
  language: string
}

/** A leg ended. reason is a short machine word such as "hangup", "stream_stopped" or "error". */
export interface LegEnded {
  type: 'leg_ended'
  callId: string
  atMs: number
  legId: string
  role: LegRole
  reason: string
}

/** A finished turn, in the speaker's own words. */
export interface TurnHeard {
  type: 'turn_heard'
  callId: string
  atMs: number
  legId: string
  role: LegRole
  language: string
  text: string
  startedMs: number
  endedMs: number
}

/** A heard turn translated for the other side. legId, role and turnStartedMs match the TurnHeard it translates. */
export interface TurnTranslated {
  type: 'turn_translated'
  callId: string
  atMs: number
  legId: string
  role: LegRole
  toRole: LegRole
  fromLanguage: string
  toLanguage: string
  text: string
  turnStartedMs: number
}

/** The assistant's own words, spoken on leg legId to role. */
export interface AssistantSaid {
  type: 'assistant_said'
  callId: string
  atMs: number
  legId: string
  role: LegRole
  language: string
  text: string
}

/** A private message spoken to the farmer only, on the farmer leg. The buyer never hears it. */
export interface WhisperSent {
  type: 'whisper_sent'
  callId: string
  atMs: number
  legId: string
  kind: WhisperKind
  language: string
  text: string
}

/** The assistant called a tool. result is the content handed back to the model. */
export interface ToolCalled {
  type: 'tool_called'
  callId: string
  atMs: number
  tool: string
  args: Record<string, unknown> | null
  ok: boolean
  result: string
  durationMs: number
}

/** Memory loaded at the start of the farmer leg. gists are newest first. */
export interface MemoryLoaded {
  type: 'memory_loaded'
  callId: string
  atMs: number
  callerKey: string
  returning: boolean
  facts: ProfileFact[]
  gists: CallGist[]
}

/** The deal summary changed. Terms are as spoken (null when not stated); price is rupees per unit. Nonbinding. */
export interface DealUpdated {
  type: 'deal_updated'
  callId: string
  atMs: number
  state: DealState
  commodity: string | null
  variety: string | null
  market: string | null
  quantity: string | null
  price: number | null
  unit: string | null
  pickup: string | null
  farmerYes: boolean
  buyerYes: boolean
}

/** The call is over (the farmer leg ended). */
export interface CallEnded {
  type: 'call_ended'
  callId: string
  atMs: number
  durationMs: number
  reason: string
}

export type ConsoleEvent =
  | CallStarted
  | LegJoined
  | LegEnded
  | TurnHeard
  | TurnTranslated
  | AssistantSaid
  | WhisperSent
  | ToolCalled
  | MemoryLoaded
  | DealUpdated
  | CallEnded

export type ConsoleEventType = ConsoleEvent['type']

// A Record keyed by the union, so the compiler fails if an event type is missing here or misspelled.
const KNOWN_TYPES: Record<ConsoleEventType, true> = {
  call_started: true,
  leg_joined: true,
  leg_ended: true,
  turn_heard: true,
  turn_translated: true,
  assistant_said: true,
  whisper_sent: true,
  tool_called: true,
  memory_loaded: true,
  deal_updated: true,
  call_ended: true,
}

export const CONSOLE_EVENT_TYPES = Object.keys(KNOWN_TYPES) as ConsoleEventType[]

/**
 * True when a parsed WebSocket message has a known event type. It checks only the discriminator, not every field:
 * the backend is the source of these messages.
 */
export function isConsoleEvent(value: unknown): value is ConsoleEvent {
  if (typeof value !== 'object' || value === null) return false
  const type = (value as { type?: unknown }).type
  return typeof type === 'string' && Object.hasOwn(KNOWN_TYPES, type)
}
