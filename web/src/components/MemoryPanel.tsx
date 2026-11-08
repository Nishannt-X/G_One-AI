import type { CallView } from '../lib/callState'
import { humanize, shortDate, shortId } from '../lib/format'
import type { CallGist, ProfileFact } from '../types/console'
import Panel from './Panel'

interface Node {
  callId: string
  /** When this call happened (the gist's start, else the earliest fact). */
  atMs: number
  current: boolean
  gist: CallGist | null
  facts: ProfileFact[]
}

/** Groups facts and gists by the call they come from, this call first and then newest first, so the memory reads as a timeline. */
function buildTimeline(call: CallView): Node[] {
  const memory = call.memory
  if (!memory) return []
  const nodes = new Map<string, Node>()
  const node = (callId: string, atMs: number): Node => {
    const existing = nodes.get(callId)
    if (existing) {
      existing.atMs = Math.min(existing.atMs, atMs)
      return existing
    }
    const created: Node = { callId, atMs, current: callId === call.callId, gist: null, facts: [] }
    nodes.set(callId, created)
    return created
  }
  for (const gist of memory.gists) node(gist.callId, gist.startedMs).gist = gist
  for (const fact of memory.facts) node(fact.provenance.callId, fact.provenance.atMs).facts.push(fact)
  return [...nodes.values()].sort((a, b) => (a.current === b.current ? b.atMs - a.atMs : a.current ? -1 : 1))
}

function Origin({ said }: { said: boolean }) {
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-sm font-bold ${
        said ? 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950' : 'bg-amber-700 text-white dark:bg-amber-300 dark:text-amber-950'
      }`}
    >
      {said ? 'said by the farmer' : 'inferred'}
    </span>
  )
}

export default function MemoryPanel({ call }: { call: CallView }) {
  const memory = call.memory
  const timeline = buildTimeline(call)
  return (
    <Panel
      title="Farmer memory"
      aside={memory && <span className="text-base font-bold">{memory.returning ? 'Returning caller' : 'First call'}</span>}
    >
      {!memory ? (
        <p className="text-lg text-slate-600 dark:text-slate-300">No memory loaded for this call.</p>
      ) : (
        <div className="space-y-4">
          <p className="text-base text-slate-700 dark:text-slate-300">
            Caller key <span className="font-mono font-bold">{memory.callerKey.slice(0, 8)}…</span> (a hash, not the phone number)
          </p>
          {timeline.length === 0 ? (
            <p className="text-lg text-slate-600 dark:text-slate-300">Nothing remembered yet. This is a first call.</p>
          ) : (
            <ol aria-label="Memory timeline, this call first, then newest" className="ml-3 border-l-4 border-slate-400 dark:border-slate-500">
              {timeline.map((n) => (
                <li key={n.callId} className="relative pb-6 pl-6 last:pb-0">
                  <span
                    aria-hidden="true"
                    className={`absolute -left-[0.8125rem] top-1 h-5 w-5 rounded-full border-4 border-white dark:border-slate-900 ${
                      n.current ? 'bg-emerald-700 dark:bg-emerald-300' : 'bg-slate-700 dark:bg-slate-300'
                    }`}
                  />
                  <p className="text-lg font-extrabold">
                    {n.current ? 'This call' : shortDate(n.atMs)}
                    <span className="ml-2 text-sm font-semibold text-slate-700 dark:text-slate-300">{n.current ? shortDate(n.atMs) : shortId(n.callId, 16)}</span>
                  </p>
                  {n.gist ? (
                    <div className="mt-1">
                      <p className="text-lg">{n.gist.summary}</p>
                      {n.gist.followUps.length > 0 && (
                        <ul className="mt-1 list-disc pl-6 text-base">
                          {n.gist.followUps.map((f) => (
                            <li key={f}>Follow up: {f}</li>
                          ))}
                        </ul>
                      )}
                    </div>
                  ) : (
                    !n.current && <p className="mt-1 text-base text-slate-700 dark:text-slate-300">No summary kept for this call.</p>
                  )}
                  {n.facts.length > 0 && (
                    <ul className="mt-2 space-y-2">
                      {n.facts.map((fact) => (
                        <li key={fact.field} className="rounded-xl border-2 border-slate-300 p-3 dark:border-slate-600">
                          <p className="text-base font-semibold capitalize text-slate-700 dark:text-slate-300">{humanize(fact.field)}</p>
                          <p className="text-xl font-bold md:text-2xl">{fact.value}</p>
                          <p className="mt-1">
                            <Origin said={fact.provenance.saidOrInferred === 'said'} />
                          </p>
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ol>
          )}
        </div>
      )}
    </Panel>
  )
}
