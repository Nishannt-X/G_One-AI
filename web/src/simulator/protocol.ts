/**
 * The messages on /ws/simulator. Audio is binary: mono PCM16 little-endian at 8000 Hz, 20 ms per message. Everything
 * else is JSON text. The backend side of this is SimulatorLeg.
 */

export const WIRE_RATE = 8000
export const FRAME_SAMPLES = 160
export const FRAME_MS = 20

export type Role = 'farmer' | 'buyer'

/** What the server sends as text. */
export type ServerMessage =
  | { type: 'ready'; callId: string; legId: string; role: Role; language: string; sampleRate: number; memory: boolean }
  | { type: 'mark'; id: number }
  | { type: 'clear' }
  | { type: 'end' }

export function parseServerMessage(text: string): ServerMessage | null {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    return null
  }
  if (typeof value !== 'object' || value === null) return null
  const m = value as Record<string, unknown>
  switch (m.type) {
    case 'ready':
      if (typeof m.callId !== 'string' || typeof m.legId !== 'string') return null
      return {
        type: 'ready',
        callId: m.callId,
        legId: m.legId,
        role: m.role === 'buyer' ? 'buyer' : 'farmer',
        language: typeof m.language === 'string' ? m.language : '',
        sampleRate: typeof m.sampleRate === 'number' ? m.sampleRate : WIRE_RATE,
        memory: m.memory === true,
      }
    case 'mark':
      return typeof m.id === 'number' ? { type: 'mark', id: m.id } : null
    case 'clear':
    case 'end':
      return { type: m.type }
    default:
      return null
  }
}

/** The synthetic callers and languages the backend offers (GET /api/simulator/config). */
export interface SimulatorConfig {
  callers: { number: string; label: string }[]
  languages: { code: string; label: string }[]
}

export function isSimulatorConfig(value: unknown): value is SimulatorConfig {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  const list = (x: unknown, a: string, b: string) =>
    Array.isArray(x) &&
    x.every((e) => typeof e === 'object' && e !== null && typeof (e as Record<string, unknown>)[a] === 'string' && typeof (e as Record<string, unknown>)[b] === 'string')
  return list(v.callers, 'number', 'label') && list(v.languages, 'code', 'label')
}

export interface SocketParams {
  role: Role
  lang: string
  /** A synthetic number from the config, or null to withhold the caller ID (no memory). */
  caller: string | null
  /** A buyer may name the farmer's call; blank joins the one call that is waiting. */
  callId: string
}

export function socketUrl(p: SocketParams, loc: Pick<Location, 'protocol' | 'host'> = location): string {
  const query = new URLSearchParams({ role: p.role, lang: p.lang })
  if (p.caller) query.set('caller', p.caller) // URLSearchParams encodes the leading + as %2B
  if (p.role === 'buyer' && p.callId.trim()) query.set('callId', p.callId.trim())
  return `${loc.protocol === 'https:' ? 'wss' : 'ws'}://${loc.host}/ws/simulator?${query.toString().replace(/\+/g, '%20')}`
}
