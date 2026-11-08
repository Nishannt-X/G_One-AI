import type { CallView } from '../lib/callState'
import { shortId } from '../lib/format'

/** Shown only when the console knows more than one call. */
export default function CallPicker({ calls, selectedId, onPick }: { calls: CallView[]; selectedId: string; onPick: (callId: string) => void }) {
  if (calls.length < 2) return null
  return (
    <nav aria-label="Calls" className="flex flex-wrap items-center gap-2">
      <span className="text-base font-bold">Calls:</span>
      {calls.map((call) => (
        <button
          key={call.callId}
          type="button"
          aria-pressed={call.callId === selectedId}
          onClick={() => onPick(call.callId)}
          className="rounded-full border-2 border-slate-500 px-3 py-1 font-mono text-base font-semibold aria-pressed:border-slate-900 aria-pressed:bg-slate-900 aria-pressed:text-white dark:aria-pressed:border-slate-100 dark:aria-pressed:bg-slate-100 dark:aria-pressed:text-slate-900"
        >
          {shortId(call.callId, 16)}
          {call.ended ? ' (ended)' : ''}
        </button>
      ))}
    </nav>
  )
}
