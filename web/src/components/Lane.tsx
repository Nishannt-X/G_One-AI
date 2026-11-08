import { legByRole, translationKey, type CallView, type TimelineItem } from '../lib/callState'
import { languageLabel, offset, shortId } from '../lib/format'
import type { LegRole, WhisperKind } from '../types/console'
import { useStickToBottom } from '../lib/useStickToBottom'
import Panel from './Panel'

const WHISPER_LABEL: Record<WhisperKind, string> = {
  price_reference: 'Price reference',
  caution: 'Caution',
  tip: 'Tip',
}

const ACCENT: Record<LegRole, { border: string; label: string }> = {
  farmer: { border: 'border-emerald-700 dark:border-emerald-400', label: 'text-emerald-800 dark:text-emerald-300' },
  buyer: { border: 'border-sky-700 dark:border-sky-400', label: 'text-sky-800 dark:text-sky-300' },
}

function LockIcon() {
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-6 w-6 shrink-0" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  )
}

function Entry({ item, call, role }: { item: TimelineItem; call: CallView; role: LegRole }) {
  const when = offset(item.atMs, call.startedMs)
  if (item.kind === 'whisper') {
    return (
      <li className="rounded-xl border-4 border-dashed border-amber-700 bg-amber-100 p-4 text-amber-950 dark:border-amber-300 dark:bg-amber-950 dark:text-amber-50">
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-base font-extrabold uppercase tracking-wide">
          <span className="inline-flex items-center gap-2 rounded-full bg-amber-900 px-3 py-1 text-white dark:bg-amber-200 dark:text-amber-950">
            <LockIcon />
            Farmer only
          </span>
          <span>Private whisper · {WHISPER_LABEL[item.whisperKind]}</span>
        </p>
        <p className="mt-2 text-sm font-semibold">The buyer does not hear this and does not see it. {when}</p>
        <p lang={item.language} className="mt-2 text-xl leading-snug md:text-2xl">{item.text}</p>
      </li>
    )
  }
  if (item.kind === 'assistant') {
    return (
      <li className="rounded-xl border-2 border-indigo-700 bg-indigo-50 p-4 dark:border-indigo-300 dark:bg-indigo-950">
        <p className="text-base font-extrabold text-indigo-900 dark:text-indigo-200">
          G-One assistant → {item.role} · {languageLabel(item.language)} <span className="font-semibold">{when}</span>
        </p>
        <p lang={item.language} className="mt-1 text-xl leading-snug md:text-2xl">{item.text}</p>
      </li>
    )
  }
  const translation = call.translations[translationKey(item.legId, item.startedMs)]
  if (item.role !== role) {
    // The other side spoke: show what this leg hears (the translation), with the original underneath.
    const heard = translation && translation.toRole === role ? translation : null
    return (
      <li className="rounded-xl border-2 border-dashed border-slate-400 p-3 dark:border-slate-500">
        <p className={`text-base font-extrabold ${ACCENT[item.role].label}`}>
          Heard from the {item.role}
          {heard ? ` · translated to ${languageLabel(heard.toLanguage)}` : ' · translation pending'}{' '}
          <span className="font-semibold text-slate-700 dark:text-slate-300">{when}</span>
        </p>
        {heard && <p lang={heard.toLanguage} className="mt-1 text-xl leading-snug md:text-2xl">{heard.text}</p>}
        <div className="mt-2 rounded-lg bg-slate-100 p-3 dark:bg-slate-800">
          <p className="text-sm font-bold uppercase tracking-wide text-slate-700 dark:text-slate-300">Original · {languageLabel(item.language)}</p>
          <p lang={item.language} className="mt-1 text-lg leading-snug md:text-xl">{item.text}</p>
        </div>
      </li>
    )
  }
  return (
    <li className={`border-l-8 py-1 pl-4 ${ACCENT[role].border}`}>
      <p className={`text-base font-extrabold capitalize ${ACCENT[role].label}`}>
        {item.role} said · {languageLabel(item.language)} <span className="font-semibold text-slate-700 dark:text-slate-300">{when}</span>
      </p>
      <p lang={item.language} className="mt-1 text-xl leading-snug md:text-2xl">{item.text}</p>
      {translation && (
        <div className="mt-2 rounded-lg bg-slate-100 p-3 dark:bg-slate-800">
          <p className="text-sm font-bold uppercase tracking-wide text-slate-700 dark:text-slate-300">
            Sent to the {translation.toRole} as {languageLabel(translation.toLanguage)}
          </p>
          <p lang={translation.toLanguage} className="mt-1 text-lg leading-snug md:text-xl">{translation.text}</p>
        </div>
      )}
    </li>
  )
}

/** One leg's transcript: what the person said with its translation, the assistant's lines to them, and (farmer only) private whispers. */
export default function Lane({ call, role }: { call: CallView; role: LegRole }) {
  const leg = legByRole(call, role)
  // Own turns, the assistant's lines to this leg, and the other side's turns as this leg hears them. Whispers only on the farmer leg.
  const items = call.items.filter((item) => (item.kind === 'whisper' ? role === 'farmer' : item.kind === 'turn' || item.role === role))
  const { ref, onScroll } = useStickToBottom(items.length)
  const title = role === 'farmer' ? 'Farmer leg' : 'Buyer leg'
  return (
    <Panel
      title={title}
      aside={
        <span className="text-base font-semibold text-slate-700 dark:text-slate-300">
          {leg ? `${languageLabel(leg.language)} · ${leg.ended ? 'ended' : 'connected'}` : 'not connected'}
        </span>
      }
    >
      <div ref={ref} onScroll={onScroll} tabIndex={0} role="log" aria-label={`${title} transcript`} className="max-h-[55vh] overflow-y-auto pr-1 focus-visible:outline-3 focus-visible:outline-emerald-600">
        {items.length === 0 ? (
          <p className="text-lg text-slate-600 dark:text-slate-300">
            {leg ? 'Nothing said on this leg yet.' : role === 'buyer' ? `No buyer has joined ${shortId(call.callId, 12)} yet.` : 'The farmer has not joined yet.'}
          </p>
        ) : (
          <ul className="space-y-4">
            {items.map((item) => (
              <Entry key={item.key} item={item} call={call} role={role} />
            ))}
          </ul>
        )}
      </div>
      <p className="mt-3 text-sm font-semibold text-slate-700 dark:text-slate-300">
        {role === 'farmer' ? 'Amber boxes are private whispers: only the farmer hears them.' : 'Private whispers to the farmer are never shown or spoken on this leg.'}
      </p>
    </Panel>
  )
}
