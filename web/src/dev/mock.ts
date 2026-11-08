// Development only: App imports this file behind `import.meta.env.DEV`, so production builds never contain it.
// All names, numbers and prices are invented.
import type { ConsoleEvent } from '../types/console'

interface Step {
  /** Milliseconds after the previous event, before the speed-up. */
  delayMs: number
  event: (at: number) => ConsoleEvent
}

const HI = 'hi-IN'
const EN = 'en-IN'
const DAY = 86_400_000

function script(callId: string, startMs: number): Step[] {
  const farmer = `${callId}-farmer`
  const buyer = `${callId}-buyer`
  const earlier1 = 'mock-earlier-1'
  const earlier2 = 'mock-earlier-2'
  // Start time of the buyer turn and of the farmer's second turn, which their translations point at.
  const turn = { buyer: 0, farmer: 0 }
  return [
    { delayMs: 0, event: (at) => ({ type: 'call_started', callId, atMs: at, language: HI }) },
    { delayMs: 300, event: (at) => ({ type: 'leg_joined', callId, atMs: at, legId: farmer, role: 'farmer', source: 'simulator', language: HI }) },
    {
      delayMs: 500,
      event: (at) => ({
        type: 'memory_loaded',
        callId,
        atMs: at,
        callerKey: '0badc0de'.repeat(8),
        returning: true,
        facts: [
          { field: 'district', value: 'Nashik', provenance: { callId: earlier1, saidOrInferred: 'said', atMs: startMs - 10 * DAY } },
          { field: 'main_crop', value: 'onion', provenance: { callId: earlier1, saidOrInferred: 'said', atMs: startMs - 10 * DAY } },
          { field: 'usual_buyer_type', value: 'mandi trader', provenance: { callId: earlier2, saidOrInferred: 'inferred', atMs: startMs - 3 * DAY } },
        ],
        gists: [
          {
            callId: earlier2,
            startedMs: startMs - 3 * DAY,
            endedMs: startMs - 3 * DAY + 300_000,
            summary: 'Asked the onion price at Lasalgaon; did not sell, wanted to wait for a better price.',
            followUps: ['Check the price again before the next call'],
          },
        ],
      }),
    },
    { delayMs: 1200, event: (at) => ({ type: 'assistant_said', callId, atMs: at, legId: farmer, role: 'farmer', language: HI, text: 'नमस्ते! पिछली बार आपने प्याज का भाव पूछा था। आज क्या बेचना है?' }) },
    { delayMs: 2500, event: (at) => ({ type: 'turn_heard', callId, atMs: at, legId: farmer, role: 'farmer', language: HI, text: 'नमस्ते, आज मुझे प्याज बेचना है, करीब दस क्विंटल।', startedMs: at - 3000, endedMs: at }) },
    {
      delayMs: 600,
      event: (at) => ({
        type: 'tool_called',
        callId,
        atMs: at,
        tool: 'mandi_price',
        args: { commodity: 'onion', market: 'Lasalgaon' },
        ok: true,
        result: 'Onion modal price 2150 rupees per quintal at Lasalgaon, sample snapshot dated 2026-11-06.',
        durationMs: 420,
      }),
    },
    { delayMs: 900, event: (at) => ({ type: 'whisper_sent', callId, atMs: at, legId: farmer, kind: 'price_reference', language: HI, text: 'लासलगांव में प्याज का भाव करीब इक्कीस सौ पचास रुपये क्विंटल था। इससे कम पर जल्दी हाँ मत कहिए।' }) },
    { delayMs: 1200, event: (at) => ({ type: 'assistant_said', callId, atMs: at, legId: farmer, role: 'farmer', language: HI, text: 'ठीक है, मैं एक खरीदार को लाइन पर लेता हूँ।' }) },
    {
      delayMs: 1500,
      event: (at) => ({
        type: 'tool_called',
        callId,
        atMs: at,
        tool: 'buyer_match',
        args: { commodity: 'onion', quantity: '10 quintal' },
        ok: true,
        result: "Matched buyer 'Sharma Traders' (sample directory).",
        durationMs: 310,
      }),
    },
    { delayMs: 800, event: (at) => ({ type: 'leg_joined', callId, atMs: at, legId: buyer, role: 'buyer', source: 'simulator', language: EN }) },
    { delayMs: 1500, event: (at) => ({ type: 'assistant_said', callId, atMs: at, legId: buyer, role: 'buyer', language: EN, text: 'Hello, a farmer from Nashik has ten quintals of onion to sell.' }) },
    {
      delayMs: 2500,
      event: (at) => {
        turn.buyer = at - 3500
        return { type: 'turn_heard', callId, atMs: at, legId: buyer, role: 'buyer', language: EN, text: 'Hello. I can take ten quintals at twenty two rupees a kilo.', startedMs: turn.buyer, endedMs: at }
      },
    },
    { delayMs: 700, event: (at) => ({ type: 'turn_translated', callId, atMs: at, legId: buyer, role: 'buyer', toRole: 'farmer', fromLanguage: EN, toLanguage: HI, text: 'नमस्ते। मैं बाईस रुपये किलो पर दस क्विंटल ले सकता हूँ।', turnStartedMs: turn.buyer }) },
    { delayMs: 500, event: (at) => ({ type: 'deal_updated', callId, atMs: at, state: 'draft', commodity: 'onion', variety: null, market: 'Lasalgaon', quantity: '10 quintal', price: 22, unit: 'kg', pickup: null, farmerYes: false, buyerYes: false }) },
    { delayMs: 900, event: (at) => ({ type: 'whisper_sent', callId, atMs: at, legId: farmer, kind: 'tip', language: HI, text: 'यह भाव सैंपल मंडी भाव के बराबर है। चाहें तो उठाने की जगह और दिन भी तय कर लीजिए।' }) },
    {
      delayMs: 2500,
      event: (at) => {
        turn.farmer = at - 4000
        return { type: 'turn_heard', callId, atMs: at, legId: farmer, role: 'farmer', language: HI, text: 'ठीक है, बाईस रुपये किलो पर हाँ। कल सुबह खेत से उठा लें।', startedMs: turn.farmer, endedMs: at }
      },
    },
    { delayMs: 700, event: (at) => ({ type: 'turn_translated', callId, atMs: at, legId: farmer, role: 'farmer', toRole: 'buyer', fromLanguage: HI, toLanguage: EN, text: 'Okay, yes at twenty two rupees a kilo. Please pick it up from the farm tomorrow morning.', turnStartedMs: turn.farmer }) },
    { delayMs: 600, event: (at) => ({ type: 'deal_updated', callId, atMs: at, state: 'confirming', commodity: 'onion', variety: null, market: 'Lasalgaon', quantity: '10 quintal', price: 22, unit: 'kg', pickup: 'farm, tomorrow morning', farmerYes: true, buyerYes: false }) },
    { delayMs: 1800, event: (at) => ({ type: 'turn_heard', callId, atMs: at, legId: buyer, role: 'buyer', language: EN, text: 'Yes, agreed. We will come tomorrow morning.', startedMs: at - 2500, endedMs: at }) },
    { delayMs: 500, event: (at) => ({ type: 'deal_updated', callId, atMs: at, state: 'agreed', commodity: 'onion', variety: null, market: 'Lasalgaon', quantity: '10 quintal', price: 22, unit: 'kg', pickup: 'farm, tomorrow morning', farmerYes: true, buyerYes: true }) },
    { delayMs: 1500, event: (at) => ({ type: 'leg_ended', callId, atMs: at, legId: buyer, role: 'buyer', reason: 'hangup' }) },
    { delayMs: 800, event: (at) => ({ type: 'leg_ended', callId, atMs: at, legId: farmer, role: 'farmer', reason: 'hangup' }) },
    { delayMs: 200, event: (at) => ({ type: 'call_ended', callId, atMs: at, durationMs: at - startMs, reason: 'hangup' }) },
  ]
}

/**
 * Plays a synthetic sale into `emit`, one event after another. `speed` 1 takes about 25 seconds.
 * @returns a function that stops the replay
 */
export function playMock(emit: (event: ConsoleEvent) => void, speed = 1): () => void {
  const startMs = Date.now()
  const steps = script(`mock-${startMs}`, startMs)
  let index = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  const next = () => {
    const step = steps[index]
    index += 1
    emit(step.event(Date.now()))
    if (index < steps.length) timer = setTimeout(next, steps[index].delayMs / speed)
  }
  next()
  return () => clearTimeout(timer)
}
