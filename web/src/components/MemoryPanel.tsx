import type { CallView } from '../lib/callState'
import { humanize, shortDate, shortId } from '../lib/format'
import Panel from './Panel'

export default function MemoryPanel({ call }: { call: CallView }) {
  const memory = call.memory
  return (
    <Panel
      title="Farmer memory"
      aside={memory && <span className="text-base font-bold">{memory.returning ? 'Returning caller' : 'First call'}</span>}
    >
      {!memory ? (
        <p className="text-lg text-slate-600 dark:text-slate-300">No memory loaded for this call.</p>
      ) : (
        <div className="space-y-5">
          <p className="text-base text-slate-700 dark:text-slate-300">
            Caller key <span className="font-mono font-bold">{memory.callerKey.slice(0, 8)}…</span> (a hash, not the phone number)
          </p>
          <div>
            <h3 className="text-lg font-extrabold">Profile facts</h3>
            {memory.facts.length === 0 ? (
              <p className="mt-1 text-lg text-slate-600 dark:text-slate-300">Nothing remembered yet.</p>
            ) : (
              <ul className="mt-2 space-y-3">
                {memory.facts.map((fact) => (
                  <li key={fact.field} className="rounded-xl border-2 border-slate-300 p-3 dark:border-slate-600">
                    <p className="text-base font-semibold capitalize text-slate-700 dark:text-slate-300">{humanize(fact.field)}</p>
                    <p className="text-xl font-bold md:text-2xl">{fact.value}</p>
                    <p className="mt-1 text-sm font-semibold text-slate-700 dark:text-slate-300">
                      <span
                        className={`mr-2 rounded-full px-2 py-0.5 font-bold ${
                          fact.provenance.saidOrInferred === 'said'
                            ? 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950'
                            : 'bg-amber-700 text-white dark:bg-amber-300 dark:text-amber-950'
                        }`}
                      >
                        {fact.provenance.saidOrInferred === 'said' ? 'said by the farmer' : 'inferred'}
                      </span>
                      {fact.provenance.callId === call.callId ? 'this call' : `${shortId(fact.provenance.callId, 16)} · ${shortDate(fact.provenance.atMs)}`}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div>
            <h3 className="text-lg font-extrabold">Recent calls</h3>
            {memory.gists.length === 0 ? (
              <p className="mt-1 text-lg text-slate-600 dark:text-slate-300">No earlier call.</p>
            ) : (
              <ul className="mt-2 space-y-3">
                {memory.gists.map((gist) => (
                  <li key={gist.callId} className="rounded-xl border-2 border-slate-300 p-3 dark:border-slate-600">
                    <p className="text-sm font-semibold text-slate-700 dark:text-slate-300">{shortDate(gist.startedMs)}</p>
                    <p className="text-lg">{gist.summary}</p>
                    {gist.followUps.length > 0 && (
                      <ul className="mt-1 list-disc pl-6 text-base">
                        {gist.followUps.map((f) => (
                          <li key={f}>Follow up: {f}</li>
                        ))}
                      </ul>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      )}
    </Panel>
  )
}
