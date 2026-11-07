import { useEffect, useState } from 'react'

type Health = 'checking' | 'up' | 'down'

/** Placeholder console page. The live call view arrives with the console issues (#21, #28). */
export default function App() {
  const [health, setHealth] = useState<Health>('checking')

  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/health', { signal: controller.signal })
      .then((res) => (res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`))))
      .then((body: { ok?: boolean }) => setHealth(body.ok === true ? 'up' : 'down'))
      .catch((err: unknown) => {
        if (!controller.signal.aborted) {
          console.warn('Backend health check failed', err)
          setHealth('down')
        }
      })
    return () => controller.abort()
  }, [])

  const label = { checking: 'Checking backend…', up: 'Backend is up', down: 'Backend is not reachable' }[health]
  const dot = { checking: 'bg-amber-400', up: 'bg-emerald-500', down: 'bg-red-500' }[health]

  return (
    <main className="mx-auto flex min-h-svh max-w-3xl flex-col gap-6 px-4 py-12">
      <header>
        <h1 className="text-3xl font-semibold tracking-tight">G-One console</h1>
        <p className="mt-2 text-slate-600 dark:text-slate-400">
          Live call view, farmer memory and the buyer directory will appear here.
        </p>
      </header>
      <p className="flex items-center gap-2 text-sm" role="status" data-testid="backend-health">
        <span className={`inline-block size-2.5 rounded-full ${dot}`} aria-hidden="true" />
        {label}
      </p>
    </main>
  )
}
