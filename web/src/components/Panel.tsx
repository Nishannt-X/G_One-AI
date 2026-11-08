import type { ReactNode } from 'react'

/** A titled card; the page's basic building block. */
export default function Panel({ title, aside, children, className = '' }: { title: string; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-2xl border-2 border-slate-300 bg-white p-4 dark:border-slate-600 dark:bg-slate-900 md:p-6 ${className}`}>
      <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-xl font-bold md:text-2xl">{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  )
}
