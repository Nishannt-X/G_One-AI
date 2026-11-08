import { lazy, Suspense, useSyncExternalStore } from 'react'
import App from '../App'

// The call simulator lives at #/simulator. A hash route needs nothing from the server, and the console stays at /.
const SimulatorPage = lazy(() => import('./SimulatorPage'))

function subscribeToHash(onChange: () => void) {
  window.addEventListener('hashchange', onChange)
  return () => window.removeEventListener('hashchange', onChange)
}

/** The console, or the call simulator when the address ends in #/simulator. */
export default function Root() {
  const hash = useSyncExternalStore(subscribeToHash, () => location.hash)
  return hash === '#/simulator' ? (
    <Suspense fallback={null}>
      <SimulatorPage />
    </Suspense>
  ) : (
    <App />
  )
}
