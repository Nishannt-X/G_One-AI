import type { FeedStatus } from '../lib/useConsoleFeed'
import type { Theme } from '../lib/theme'

const STATUS: Record<FeedStatus, { text: string; dot: string }> = {
  connected: { text: 'Console connected', dot: 'bg-emerald-600 dark:bg-emerald-400' },
  connecting: { text: 'Connecting to the console…', dot: 'bg-amber-600 dark:bg-amber-400' },
  offline: { text: 'Console offline, retrying', dot: 'bg-red-600 dark:bg-red-400' },
}

export default function Header({ status, theme, onToggleTheme }: { status: FeedStatus; theme: Theme; onToggleTheme: () => void }) {
  const s = STATUS[status]
  return (
    <header className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
      <div>
        <p className="text-base font-bold uppercase tracking-widest text-emerald-700 dark:text-emerald-300">G-One</p>
        <h1 className="text-3xl font-extrabold md:text-5xl">Call console</h1>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <p className="rounded-full border-2 border-slate-900 bg-slate-900 px-4 py-2 text-base font-bold text-white dark:border-slate-100 dark:bg-slate-100 dark:text-slate-900 md:text-lg">
          Speech by Gnani Prisma and Timbre
        </p>
        <p role="status" className="flex items-center gap-2 text-base font-semibold md:text-lg">
          <span aria-hidden="true" className={`h-3 w-3 rounded-full ${s.dot}`} />
          {s.text}
        </p>
        <a
          href="#/simulator"
          className="rounded-full border-2 border-slate-400 px-4 py-2 text-base font-semibold hover:bg-slate-100 focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:border-slate-500 dark:hover:bg-slate-800"
        >
          Call simulator
        </a>
        <button
          type="button"
          onClick={onToggleTheme}
          className="rounded-full border-2 border-slate-400 px-4 py-2 text-base font-semibold hover:bg-slate-100 focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:border-slate-500 dark:hover:bg-slate-800"
        >
          {theme === 'dark' ? 'Light mode' : 'Dark mode'}
        </button>
      </div>
    </header>
  )
}
