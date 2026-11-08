import type { CallOrigin } from '../lib/callState'

/** Marks a call whose events are not from a real call, so a screenshot can never pass as one. */
export default function CallBanner({ origin }: { origin: CallOrigin }) {
  if (origin === 'feed') return null
  const text =
    origin === 'mock'
      ? 'MOCK DATA: played by the dev-only mock replayer in this browser. Not a real call.'
      : 'SAMPLE: a synthetic call replayed by the backend for development. Not a real call.'
  return (
    <p role="note" className="rounded-xl border-4 border-dashed border-fuchsia-800 bg-fuchsia-100 px-4 py-3 text-lg font-extrabold text-fuchsia-950 dark:border-fuchsia-300 dark:bg-fuchsia-950 dark:text-fuchsia-50">
      {text}
    </p>
  )
}
