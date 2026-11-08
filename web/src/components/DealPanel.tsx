import type { CallView } from '../lib/callState'
import Panel from './Panel'

const STATE_LABEL = {
  draft: 'Terms heard',
  readback: 'Being read back',
  confirming: 'Waiting for both yeses',
  agreed: 'Agreed by both',
  closed: 'Closed',
} as const

function Row({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between gap-4 border-b border-slate-300 py-2 dark:border-slate-700">
      <dt className="text-base font-semibold text-slate-700 dark:text-slate-300">{label}</dt>
      <dd className="text-right text-xl font-bold">{value ?? '—'}</dd>
    </div>
  )
}

export default function DealPanel({ call }: { call: CallView }) {
  const deal = call.deal
  return (
    <Panel title="Deal summary" aside={deal && <span className="text-base font-bold">{STATE_LABEL[deal.state]}</span>}>
      {!deal ? (
        <p className="text-lg text-slate-600 dark:text-slate-300">No terms heard yet.</p>
      ) : (
        <>
          <dl>
            <Row label="Commodity" value={deal.commodity} />
            <Row label="Quantity" value={deal.quantity} />
            <Row label="Price" value={deal.price === null ? null : `₹${deal.price}${deal.unit ? ` per ${deal.unit}` : ''}`} />
            <Row label="Market" value={deal.market} />
            <Row label="Pickup" value={deal.pickup} />
          </dl>
          <p className="mt-3 text-lg font-bold">
            Farmer said yes: {deal.farmerYes ? '✓ yes' : 'not yet'} · Buyer said yes: {deal.buyerYes ? '✓ yes' : 'not yet'}
          </p>
          <p className="mt-2 text-sm text-slate-700 dark:text-slate-300">A summary of what was said. Not a contract.</p>
        </>
      )}
    </Panel>
  )
}
