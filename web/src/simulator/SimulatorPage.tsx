import { useEffect, useState, type ReactNode } from 'react'
import { isSimulatorConfig, type Role, type SimulatorConfig } from './protocol'
import { useCallSimulator, type CallStatus } from './useCallSimulator'

const WITHHELD = ''

const STATUS_TEXT: Record<CallStatus, string> = {
  idle: 'Not on a call',
  connecting: 'Connecting…',
  live: 'On a call',
  ended: 'Call ended',
  error: 'Call failed',
}

const FIELD =
  'w-full rounded-xl border-2 border-slate-400 bg-white px-4 py-3 text-lg font-semibold disabled:opacity-60 dark:border-slate-500 dark:bg-slate-900'
const FOCUS = 'focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

function useSimulatorConfig(): { config: SimulatorConfig | null; error: boolean } {
  const [config, setConfig] = useState<SimulatorConfig | null>(null)
  const [error, setError] = useState(false)
  useEffect(() => {
    let cancelled = false
    fetch('/api/simulator/config')
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((body: unknown) => {
        if (cancelled) return
        if (isSimulatorConfig(body)) setConfig(body)
        else setError(true)
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
    return () => {
      cancelled = true
    }
  }, [])
  return { config, error }
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block space-y-2">
      <span className="block text-lg font-bold">{label}</span>
      {children}
      {hint && <span className="block text-base text-slate-700 dark:text-slate-300">{hint}</span>}
    </label>
  )
}

export default function SimulatorPage() {
  const { config, error } = useSimulatorConfig()
  const { state, start, stop, setMuted } = useCallSimulator()
  const [role, setRole] = useState<Role>('farmer')
  const [lang, setLang] = useState('hi-IN')
  const [caller, setCaller] = useState('+919876543210')
  const [callId, setCallId] = useState('')

  const inCall = state.status === 'connecting' || state.status === 'live'
  const languages = config?.languages ?? []
  // Use the chosen language only if the backend offers it; otherwise its first one.
  const language = languages.some((l) => l.code === lang) ? lang : (languages[0]?.code ?? lang)
  const seconds = (n: number) => (n * 0.02).toFixed(1)

  return (
    <div className="min-h-screen">
      <main className="mx-auto max-w-3xl space-y-5 p-4 md:space-y-6 md:p-8">
        <header className="space-y-1">
          <p className="text-base font-bold uppercase tracking-widest text-emerald-700 dark:text-emerald-300">G-One</p>
          <h1 className="text-3xl font-extrabold md:text-5xl">Call simulator</h1>
          <a href="#/" className={`inline-block rounded text-lg font-semibold underline underline-offset-4 ${FOCUS}`}>
            Back to the call console
          </a>
        </header>

        <section
          aria-label="Simulator notice"
          className="rounded-2xl border-4 border-amber-700 bg-amber-100 p-5 text-amber-950 dark:border-amber-400 dark:bg-amber-950 dark:text-amber-50"
        >
          <p className="text-3xl font-extrabold md:text-4xl">Simulator, not a phone call</p>
          <p className="mt-2 text-lg font-semibold">
            Your browser microphone stands in for a phone line. No number is dialled and Twilio is not used. The audio goes
            to the same call pipeline a phone call uses. Wear headphones, or the assistant hears itself.
          </p>
        </section>

        {error && (
          <p role="alert" className="rounded-xl border-2 border-red-700 p-4 text-lg font-bold text-red-800 dark:border-red-400 dark:text-red-200">
            The backend did not answer, so there are no caller numbers or languages to pick. Start it and reload this page.
          </p>
        )}

        <form
          className="space-y-5 rounded-2xl border-2 border-slate-300 p-5 dark:border-slate-600"
          onSubmit={(e) => {
            e.preventDefault()
            if (inCall) stop()
            else void start({ role, lang: language, caller: caller === WITHHELD ? null : caller, callId })
          }}
        >
          <fieldset disabled={inCall} className="space-y-5">
            <legend className="sr-only">Call settings</legend>
            <div role="radiogroup" aria-label="Who are you on this call?" className="grid grid-cols-2 gap-3">
              {(['farmer', 'buyer'] as const).map((r) => (
                <label
                  key={r}
                  className={`cursor-pointer rounded-xl border-2 px-4 py-3 text-center text-xl font-bold has-[:focus-visible]:outline-3 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-emerald-600 ${
                    role === r
                      ? 'border-slate-900 bg-slate-900 text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900'
                      : 'border-slate-400 dark:border-slate-500'
                  } ${inCall ? 'opacity-60' : ''}`}
                >
                  <input type="radio" name="role" value={r} checked={role === r} onChange={() => setRole(r)} className="sr-only" />
                  {r === 'farmer' ? 'Farmer' : 'Buyer'}
                </label>
              ))}
            </div>

            <Field label="Language">
              <select value={language} onChange={(e) => setLang(e.target.value)} className={`${FIELD} ${FOCUS}`}>
                {languages.length === 0 && <option value={language}>{language}</option>}
                {languages.map((l) => (
                  <option key={l.code} value={l.code}>
                    {l.label} ({l.code})
                  </option>
                ))}
              </select>
            </Field>

            <Field
              label="Caller number (synthetic)"
              hint="Pick the same number on another call and the assistant remembers the earlier calls. “Withheld” keeps no memory."
            >
              <select value={caller} onChange={(e) => setCaller(e.target.value)} className={`${FIELD} ${FOCUS}`}>
                {(config?.callers ?? []).map((c) => (
                  <option key={c.number} value={c.number}>
                    {c.label}
                  </option>
                ))}
                <option value={WITHHELD}>Withheld, no memory</option>
              </select>
            </Field>

            {role === 'buyer' && (
              <Field
                label="Call to join"
                hint="Paste the call id shown on the farmer’s tab. Leave it empty to join the one call that is waiting for a buyer."
              >
                <input value={callId} onChange={(e) => setCallId(e.target.value)} placeholder="call-…" autoComplete="off" spellCheck={false} className={`${FIELD} ${FOCUS}`} />
              </Field>
            )}
          </fieldset>

          <div className="flex flex-wrap items-center gap-3">
            <button
              type="submit"
              disabled={!inCall && config === null}
              className={`rounded-full border-2 px-8 py-3 text-xl font-extrabold disabled:opacity-50 ${FOCUS} ${
                inCall
                  ? 'border-red-800 bg-red-800 text-white hover:bg-red-900 dark:border-red-300 dark:bg-red-300 dark:text-red-950'
                  : 'border-emerald-800 bg-emerald-800 text-white hover:bg-emerald-900 dark:border-emerald-300 dark:bg-emerald-300 dark:text-emerald-950'
              }`}
            >
              {inCall ? 'Hang up' : 'Start simulated call'}
            </button>
            {state.status === 'live' && (
              <button
                type="button"
                aria-pressed={state.muted}
                onClick={() => setMuted(!state.muted)}
                className={`rounded-full border-2 border-slate-400 px-6 py-3 text-xl font-bold hover:bg-slate-100 dark:border-slate-500 dark:hover:bg-slate-800 ${FOCUS}`}
              >
                {state.muted ? 'Unmute microphone' : 'Mute microphone'}
              </button>
            )}
          </div>
        </form>

        <section aria-label="Call status" className="space-y-3 rounded-2xl border-2 border-slate-300 p-5 dark:border-slate-600">
          <p role="status" className="text-2xl font-extrabold">
            {STATUS_TEXT[state.status]}
            {state.status === 'live' && state.muted && ' (microphone muted)'}
          </p>
          {state.message && <p className="text-lg font-semibold">{state.message}</p>}
          {state.callId && (
            <dl className="grid gap-x-4 gap-y-1 text-lg sm:grid-cols-[max-content_1fr]">
              <dt className="font-bold">Call id</dt>
              <dd className="break-all font-mono">{state.callId}</dd>
              <dt className="font-bold">Memory</dt>
              <dd>{state.memory ? 'On for this number' : 'Off, caller ID withheld'}</dd>
              <dt className="font-bold">Sent</dt>
              <dd>
                {state.framesSent} frames of 20 ms ({seconds(state.framesSent)} s of speech and silence)
              </dd>
              <dt className="font-bold">Received</dt>
              <dd>
                {state.framesReceived} frames ({seconds(state.framesReceived)} s)
                {state.assistantSpeaking && ' — the assistant is speaking'}
              </dd>
            </dl>
          )}
        </section>
      </main>
    </div>
  )
}
