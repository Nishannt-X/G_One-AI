// Development only: App loads this file behind `import.meta.env.DEV`, so production builds never contain it.
import { useEffect, useRef, useState } from 'react'
import type { ConsoleEvent } from '../types/console'
import { playMock } from './mock'

/** Buttons that fill the console with synthetic calls. */
export default function DevTools({ emit }: { emit: (event: ConsoleEvent) => void }) {
  const stop = useRef<(() => void) | null>(null)
  const [message, setMessage] = useState('')

  useEffect(() => () => stop.current?.(), [])

  const mock = (speed: number) => {
    stop.current?.()
    stop.current = playMock(emit, speed)
  }

  const backendSample = () => {
    setMessage('')
    fetch('/api/dev/console/replay', { method: 'POST' })
      .then((res) => setMessage(res.ok ? 'Backend sample started.' : `Backend replay is off or failed (HTTP ${res.status}). Start the backend with G_ONE_CONSOLE_REPLAY_ENABLED=true.`))
      .catch(() => setMessage('The backend is not reachable.'))
  }

  const button = 'rounded-lg border-2 border-fuchsia-800 px-4 py-2 text-base font-bold hover:bg-fuchsia-100 dark:border-fuchsia-300 dark:hover:bg-fuchsia-950'
  return (
    <aside aria-label="Development tools" className="rounded-2xl border-2 border-dashed border-fuchsia-800 p-4 dark:border-fuchsia-300">
      <p className="text-base font-extrabold text-fuchsia-900 dark:text-fuchsia-200">Dev tools (not in production builds). Everything these buttons add is synthetic.</p>
      <div className="mt-3 flex flex-wrap gap-3">
        <button type="button" className={button} onClick={() => mock(1)}>Play mock call (25 s)</button>
        <button type="button" className={button} onClick={() => mock(1000)}>Show finished mock call</button>
        <button type="button" className={button} onClick={backendSample}>Replay backend sample</button>
      </div>
      {message && <p role="status" className="mt-2 text-base">{message}</p>}
    </aside>
  )
}
