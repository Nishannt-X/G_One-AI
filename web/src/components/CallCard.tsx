import type { CallView } from '../lib/callState'
import { duration, languageLabel, shortId } from '../lib/format'
import Panel from './Panel'

const SOURCE = { twilio: 'Phone call (Twilio)', simulator: 'Browser simulator' } as const

export default function CallCard({ call, now }: { call: CallView; now: number }) {
  const elapsed = call.ended ? call.ended.durationMs : now - call.startedMs
  const live = !call.ended
  return (
    <Panel
      title={live ? 'Active call' : 'Call ended'}
      aside={
        <span
          className={`rounded-full px-3 py-1 text-base font-bold ${
            live ? 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950' : 'bg-slate-700 text-white dark:bg-slate-300 dark:text-slate-900'
          }`}
        >
          {live ? '● On the line' : `Ended · ${call.ended?.reason}`}
        </span>
      }
    >
      <dl className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <div>
          <dt className="text-base font-semibold text-slate-600 dark:text-slate-300">Duration</dt>
          <dd className="font-mono text-4xl font-extrabold tabular-nums md:text-5xl">{duration(elapsed)}</dd>
        </div>
        <div>
          <dt className="text-base font-semibold text-slate-600 dark:text-slate-300">Starting language</dt>
          <dd className="text-2xl font-bold md:text-3xl">{languageLabel(call.language)}</dd>
        </div>
        <div className="col-span-2">
          <dt className="text-base font-semibold text-slate-600 dark:text-slate-300">Call</dt>
          <dd className="break-all font-mono text-lg font-bold md:text-xl">{shortId(call.callId, 28)}</dd>
        </div>
      </dl>
      <ul className="mt-5 flex flex-wrap gap-3" aria-label="Legs">
        {call.legs.length === 0 && <li className="text-lg text-slate-600 dark:text-slate-300">No leg has joined yet.</li>}
        {call.legs.map((leg) => (
          <li
            key={leg.legId}
            className={`rounded-xl border-2 px-4 py-2 text-lg ${
              leg.role === 'farmer'
                ? 'border-emerald-700 bg-emerald-50 dark:border-emerald-400 dark:bg-emerald-950'
                : 'border-sky-700 bg-sky-50 dark:border-sky-400 dark:bg-sky-950'
            }`}
          >
            <span className="font-extrabold capitalize">{leg.role}</span> · {languageLabel(leg.language)} · {SOURCE[leg.source]}
            <span className="ml-2 font-semibold">{leg.ended ? `ended (${leg.ended.reason})` : 'connected'}</span>
          </li>
        ))}
      </ul>
    </Panel>
  )
}
