import { useCallback, useSyncExternalStore } from 'react'

export type Theme = 'light' | 'dark'

const KEY = 'g-one-theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

function stored(): Theme | null {
  try {
    const value = localStorage.getItem(KEY)
    return value === 'light' || value === 'dark' ? value : null
  } catch {
    return null
  }
}

function apply(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
  document.documentElement.style.colorScheme = theme
}

/** Call once before the first render, so the page never flashes the wrong theme. Follows the system until a pick is stored. */
export function initTheme() {
  const media = window.matchMedia(DARK_QUERY)
  apply(stored() ?? (media.matches ? 'dark' : 'light'))
  media.addEventListener('change', (e) => {
    if (stored() === null) apply(e.matches ? 'dark' : 'light')
  })
}

function subscribe(onChange: () => void) {
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  return () => observer.disconnect()
}

function current(): Theme {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

export function useTheme() {
  const theme = useSyncExternalStore(subscribe, current, () => 'light' as Theme)
  const toggle = useCallback(() => {
    const next: Theme = current() === 'dark' ? 'light' : 'dark'
    apply(next)
    try {
      localStorage.setItem(KEY, next)
    } catch {
      // Storage is blocked: the choice lasts for this page view only.
    }
  }, [])
  return { theme, toggle }
}
