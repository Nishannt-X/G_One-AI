import type { CallView } from '../lib/callState'
import { offset } from '../lib/format'
import Panel from './Panel'

export default function ToolChips({ call }: { call: CallView }) {
  return (
    <Panel title="Tool calls" aside={<span className="text-base font-semibold text-slate-700 dark:text-slate-300">What the assistant looked up</span>}>
      {call.tools.length === 0 ? (
        <p className="text-lg text-slate-600 dark:text-slate-300">No tool called yet.</p>
      ) : (
        <ul className="flex flex-wrap gap-3">
          {call.tools.map((tool) => (
            <li key={tool.key} className="max-w-full open:basis-full">
              <details className="group rounded-xl border-2 border-slate-400 bg-slate-50 dark:border-slate-500 dark:bg-slate-800">
                <summary className="flex cursor-pointer list-none flex-wrap items-center gap-2 rounded-xl px-4 py-2 text-lg font-bold focus-visible:outline-3 focus-visible:outline-emerald-600">
                  <span aria-hidden="true" className={tool.ok ? 'text-emerald-700 dark:text-emerald-300' : 'text-red-700 dark:text-red-300'}>
                    {tool.ok ? '✓' : '✕'}
                  </span>
                  <span className="font-mono">{tool.tool}</span>
                  <span className="text-base font-semibold text-slate-700 dark:text-slate-300">
                    {tool.ok ? 'ok' : 'failed'} · {tool.durationMs} ms · {offset(tool.atMs, call.startedMs)}
                  </span>
                </summary>
                <div className="space-y-2 border-t-2 border-slate-300 px-4 py-3 dark:border-slate-600">
                  <p className="text-base font-bold">Arguments</p>
                  <pre className="overflow-x-auto rounded-lg bg-white p-3 font-mono text-base dark:bg-slate-900">{JSON.stringify(tool.args, null, 2)}</pre>
                  <p className="text-base font-bold">Result given to the assistant</p>
                  <p className="text-lg">{tool.result}</p>
                </div>
              </details>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  )
}
