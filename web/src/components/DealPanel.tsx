import type { CallView } from '../lib/callState'
import type { DealState } from '../types/console'
import Panel from './Panel'

const STATE_LABEL: Record<DealState, string> = {
  draft: 'Terms heard',
  readback: 'Being read back',
  confirming: 'Waiting for both yeses',
  agreed: 'Agreed by both',
  closed: 'Closed',
}

/** The steps a deal moves through on the way to agreed. `closed` is a dead end shown separately. */
const STEPS: { state: Exclude<DealState, 'closed'>; label: string }[] = [
  { state: 'draft', label: 'Terms heard' },
  { state: 'readback', label: 'Read back' },
  { state: 'confirming', label: 'Both yes?' },
  { state: 'agreed', label: 'Agreed' },
]

function Row({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-300 py-2 dark:border-slate-700">
      <dt className="text-base font-semibold text-slate-700 dark:text-slate-300">{label}</dt>
      <dd className="text-right text-xl font-bold">{value ?? '—'}</dd>
    </div>
  )
}

function Steps({ state }: { state: DealState }) {
  const current = STEPS.findIndex((s) => s.state === state)
  return (
    <ol aria-label="Deal progress" className="grid grid-cols-4 gap-2">
      {STEPS.map((step, i) => {
        const done = state !== 'closed' && i < current
        const active = i === current
        const agreed = active && state === 'agreed'
        const tone = agreed
          ? 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950'
          : active
            ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900'
            : done
              ? 'border-2 border-emerald-700 text-emerald-900 dark:border-emerald-300 dark:text-emerald-200'
              : 'border-2 border-slate-300 text-slate-600 dark:border-slate-600 dark:text-slate-400'
        return (
          <li key={step.state} aria-current={active ? 'step' : undefined} className={`flex flex-col items-center gap-1 rounded-xl px-1 py-2 text-center ${tone}`}>
            <span aria-hidden="true" className="text-xl font-extrabold leading-none">{done || agreed ? '✓' : i + 1}</span>
            <span className="text-sm font-bold leading-tight">{step.label}</span>
            <span className="sr-only">{agreed ? 'reached' : active ? 'current step' : done ? 'done' : 'not yet'}</span>
          </li>
        )
      })}
    </ol>
  )
}

function Yes({ who, said }: { who: string; said: boolean }) {
  return (
    <p
      className={`flex items-center justify-between gap-2 rounded-xl px-3 py-2 text-lg font-bold ${
        said ? 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950' : 'border-2 border-slate-400 dark:border-slate-500'
      }`}
    >
      <span>{who}</span>
      <span>{said ? '✓ said yes' : 'not yet'}</span>
    </p>
  )
}

export default function DealPanel({ call }: { call: CallView }) {
  const deal = call.deal
  const agreed = deal?.state === 'agreed'
  const closed = deal?.state === 'closed'
  const frame = agreed ? 'ring-4 ring-emerald-700 dark:ring-emerald-300' : ''
  return (
    <Panel
      title="Deal summary"
      className={frame}
      aside={
        deal && (
          <span
            role="status"
            className={`rounded-full px-3 py-1 text-base font-extrabold ${
              agreed
                ? 'bg-emerald-700 text-white dark:bg-emerald-300 dark:text-emerald-950'
                : closed
                  ? 'bg-slate-700 text-white dark:bg-slate-300 dark:text-slate-900'
                  : 'border-2 border-slate-900 dark:border-slate-100'
            }`}
          >
            {agreed && '✓ '}
            {STATE_LABEL[deal.state]}
          </span>
        )
      }
    >
      {!deal ? (
        <p className="text-lg text-slate-600 dark:text-slate-300">No terms heard yet.</p>
      ) : (
        <>
          {closed ? (
            <p className="rounded-xl border-2 border-slate-500 p-3 text-lg font-bold">This deal was closed: declined, expired or replaced.</p>
          ) : (
            <Steps state={deal.state} />
          )}
          {agreed && (
            <p className="mt-3 rounded-xl bg-emerald-100 p-3 text-xl font-extrabold text-emerald-950 dark:bg-emerald-950 dark:text-emerald-100">
              Both sides said yes to these terms.
            </p>
          )}
          <dl className="mt-3">
            <Row label="Commodity" value={deal.commodity ? `${deal.commodity}${deal.variety ? ` (${deal.variety})` : ''}` : null} />
            <Row label="Quantity" value={deal.quantity} />
            <Row label="Price" value={deal.price === null ? null : `₹${deal.price}${deal.unit ? ` per ${deal.unit}` : ''}`} />
            <Row label="Market" value={deal.market} />
            <Row label="Pickup" value={deal.pickup} />
          </dl>
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            <Yes who="Farmer" said={deal.farmerYes} />
            <Yes who="Buyer" said={deal.buyerYes} />
          </div>
          <p className="mt-3 text-sm text-slate-700 dark:text-slate-300">A summary of what was said. Not a contract.</p>
        </>
      )}
    </Panel>
  )
}
