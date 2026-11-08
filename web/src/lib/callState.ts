import type {
  DealUpdated,
  LegRole,
  LegSource,
  MemoryLoaded,
  ConsoleEvent,
  WhisperKind,
} from '../types/console'

/** Where a call's events came from: the real feed, the dev-only mock replayer, or the backend's synthetic replay. */
export type CallOrigin = 'feed' | 'mock' | 'sample'

export interface LegView {
  legId: string
  role: LegRole
  source: LegSource
  language: string
  joinedMs: number
  ended: { atMs: number; reason: string } | null
}

export interface Translation {
  toRole: LegRole
  toLanguage: string
  text: string
}

export type TimelineItem =
  | { kind: 'turn'; key: string; legId: string; role: LegRole; language: string; text: string; startedMs: number; atMs: number }
  | { kind: 'assistant'; key: string; legId: string; role: LegRole; language: string; text: string; atMs: number }
  | { kind: 'whisper'; key: string; legId: string; whisperKind: WhisperKind; language: string; text: string; atMs: number }

export interface ToolView {
  key: string
  tool: string
  args: Record<string, unknown> | null
  ok: boolean
  result: string
  durationMs: number
  atMs: number
}

export interface CallView {
  callId: string
  origin: CallOrigin
  startedMs: number
  language: string
  legs: LegView[]
  items: TimelineItem[]
  tools: ToolView[]
  /** Translations by `translationKey(legId, turnStartedMs)`, so they can arrive in any order. */
  translations: Record<string, Translation>
  memory: MemoryLoaded | null
  deal: DealUpdated | null
  ended: { atMs: number; durationMs: number; reason: string } | null
  /** Counts the events applied to this call; makes each timeline entry's key unique. */
  seq: number
}

export interface FeedState {
  calls: Record<string, CallView>
  /** Call ids, oldest first. */
  order: string[]
}

export type FeedAction =
  | { type: 'event'; event: ConsoleEvent; mock?: boolean }
  /** The feed reconnected and the server is about to resend the running calls: forget the feed's calls. */
  | { type: 'reset' }

export const initialFeed: FeedState = { calls: {}, order: [] }

/** Bounds browser memory on a very long call. */
const MAX_ITEMS = 400
const MAX_CALLS = 20

export function translationKey(legId: string, turnStartedMs: number): string {
  return `${legId}:${turnStartedMs}`
}

function originOf(callId: string, mock: boolean): CallOrigin {
  if (mock) return 'mock'
  return callId.startsWith('replay-') ? 'sample' : 'feed'
}

function cap<T>(list: T[], next: T, max: number): T[] {
  const grown = [...list, next]
  return grown.length > max ? grown.slice(grown.length - max) : grown
}

function applyToCall(call: CallView, event: ConsoleEvent): CallView {
  const key = `${call.seq}-${event.type}`
  switch (event.type) {
    case 'call_started':
      return call
    case 'leg_joined': {
      const leg: LegView = {
        legId: event.legId,
        role: event.role,
        source: event.source,
        language: event.language,
        joinedMs: event.atMs,
        ended: null,
      }
      return { ...call, legs: [...call.legs.filter((l) => l.legId !== event.legId), leg] }
    }
    case 'leg_ended':
      return {
        ...call,
        legs: call.legs.map((l) => (l.legId === event.legId ? { ...l, ended: { atMs: event.atMs, reason: event.reason } } : l)),
      }
    case 'turn_heard':
      return {
        ...call,
        items: cap(
          call.items,
          { kind: 'turn', key, legId: event.legId, role: event.role, language: event.language, text: event.text, startedMs: event.startedMs, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'turn_translated':
      return {
        ...call,
        translations: {
          ...call.translations,
          [translationKey(event.legId, event.turnStartedMs)]: { toRole: event.toRole, toLanguage: event.toLanguage, text: event.text },
        },
      }
    case 'assistant_said':
      return {
        ...call,
        items: cap(
          call.items,
          { kind: 'assistant', key, legId: event.legId, role: event.role, language: event.language, text: event.text, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'whisper_sent':
      return {
        ...call,
        items: cap(
          call.items,
          { kind: 'whisper', key, legId: event.legId, whisperKind: event.kind, language: event.language, text: event.text, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'tool_called':
      return {
        ...call,
        tools: cap(
          call.tools,
          { key, tool: event.tool, args: event.args, ok: event.ok, result: event.result, durationMs: event.durationMs, atMs: event.atMs },
          MAX_ITEMS,
        ),
      }
    case 'memory_loaded':
      return { ...call, memory: event }
    case 'deal_updated':
      return { ...call, deal: event }
    case 'call_ended':
      return { ...call, ended: { atMs: event.atMs, durationMs: event.durationMs, reason: event.reason } }
  }
}

export function feedReducer(state: FeedState, action: FeedAction): FeedState {
  if (action.type === 'reset') {
    const kept = state.order.filter((id) => state.calls[id].origin === 'mock')
    return { calls: Object.fromEntries(kept.map((id) => [id, state.calls[id]])), order: kept }
  }
  const { event } = action
  if (event.type === 'call_started') {
    const call: CallView = {
      callId: event.callId,
      origin: originOf(event.callId, action.mock === true),
      startedMs: event.atMs,
      language: event.language,
      legs: [],
      items: [],
      tools: [],
      translations: {},
      memory: null,
      deal: null,
      ended: null,
      seq: 0,
    }
    const order = [...state.order.filter((id) => id !== event.callId), event.callId]
    const calls = { ...state.calls, [event.callId]: call }
    while (order.length > MAX_CALLS) delete calls[order.shift()!]
    return { calls, order }
  }
  const existing = state.calls[event.callId]
  // The server resends a running call from its call_started, so an event for an unknown call is stale: ignore it.
  if (!existing) return state
  return { ...state, calls: { ...state.calls, [event.callId]: applyToCall({ ...existing, seq: existing.seq + 1 }, event) } }
}

export function legByRole(call: CallView, role: LegRole): LegView | undefined {
  return call.legs.find((l) => l.role === role)
}

/** The call to show: the pinned one, else the newest running call, else the newest call. */
export function pickCall(state: FeedState, pinned: string | null): CallView | null {
  if (pinned && state.calls[pinned]) return state.calls[pinned]
  const newestFirst = [...state.order].reverse().map((id) => state.calls[id])
  return newestFirst.find((c) => !c.ended) ?? newestFirst[0] ?? null
}
