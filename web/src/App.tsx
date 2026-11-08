import { lazy, Suspense, useState } from 'react'
import CallBanner from './components/CallBanner'
import CallCard from './components/CallCard'
import CallPicker from './components/CallPicker'
import DealPanel from './components/DealPanel'
import Header from './components/Header'
import Lane from './components/Lane'
import MemoryPanel from './components/MemoryPanel'
import ToolChips from './components/ToolChips'
import { pickCall } from './lib/callState'
import { useTheme } from './lib/theme'
import { useConsoleFeed } from './lib/useConsoleFeed'
import { useNow } from './lib/useNow'

// The dev tools are loaded only when `import.meta.env.DEV` is true; the bundler removes this branch from production.
const DevTools = import.meta.env.DEV ? lazy(() => import('./dev/DevTools')) : null

export default function App() {
  const { feed, status, dispatch } = useConsoleFeed()
  const { theme, toggle } = useTheme()
  const [pinned, setPinned] = useState<string | null>(null)
  const call = pickCall(feed, pinned)
  const now = useNow(call !== null && call.ended === null)

  return (
    <div className="min-h-screen">
      <main className="mx-auto max-w-[1366px] space-y-5 p-4 md:space-y-6 md:p-8">
        <Header status={status} theme={theme} onToggleTheme={toggle} />
        {DevTools && (
          <Suspense fallback={null}>
            <DevTools emit={(event) => dispatch({ type: 'event', event, mock: true })} />
          </Suspense>
        )}
        {call ? (
          <>
            <CallBanner origin={call.origin} />
            <CallPicker calls={feed.order.map((id) => feed.calls[id])} selectedId={call.callId} onPick={setPinned} />
            <CallCard call={call} now={now} />
            <div className="grid gap-5 md:gap-6 md:grid-cols-2">
              <Lane call={call} role="farmer" />
              <Lane call={call} role="buyer" />
            </div>
            <div className="grid gap-5 md:gap-6 lg:grid-cols-2">
              <MemoryPanel call={call} />
              <div className="space-y-5 md:space-y-6">
                <DealPanel call={call} />
                <ToolChips call={call} />
              </div>
            </div>
          </>
        ) : (
          <section className="rounded-2xl border-2 border-slate-300 p-8 text-center dark:border-slate-600">
            <h2 className="text-3xl font-extrabold md:text-4xl">Waiting for a call</h2>
            <p className="mt-3 text-xl text-slate-700 dark:text-slate-300">
              {status === 'connected' ? 'The console is connected. A call shows up here when it starts.' : 'The console is not connected to the backend yet.'}
            </p>
          </section>
        )}
        <footer className="border-t-2 border-slate-300 pt-4 text-lg font-bold dark:border-slate-600">
          Speech by Gnani Prisma and Timbre
        </footer>
      </main>
    </div>
  )
}
