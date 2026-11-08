import { useEffect, useReducer, useState } from 'react'
import { isConsoleEvent } from '../types/console'
import { feedReducer, initialFeed } from './callState'

export type FeedStatus = 'connecting' | 'connected' | 'offline'

const RETRY_MIN_MS = 1000
const RETRY_MAX_MS = 5000

function feedUrl(): string {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/console`
}

/**
 * Follows /ws/console and keeps reconnecting. On every (re)connect the server sends the running calls again from
 * their start, so the feed's calls are cleared first. Dev mock calls are kept.
 */
export function useConsoleFeed() {
  const [feed, dispatch] = useReducer(feedReducer, initialFeed)
  const [status, setStatus] = useState<FeedStatus>('connecting')

  useEffect(() => {
    let socket: WebSocket | null = null
    let timer: ReturnType<typeof setTimeout> | undefined
    let stopped = false
    let retryMs = RETRY_MIN_MS

    const connect = () => {
      const ws = new WebSocket(feedUrl())
      socket = ws
      ws.onopen = () => {
        retryMs = RETRY_MIN_MS
        dispatch({ type: 'reset' })
        setStatus('connected')
      }
      ws.onmessage = (message) => {
        try {
          const event: unknown = JSON.parse(String(message.data))
          if (isConsoleEvent(event)) dispatch({ type: 'event', event })
        } catch {
          // A message that is not JSON is not ours: ignore it.
        }
      }
      ws.onclose = () => {
        if (stopped) return
        setStatus('offline')
        timer = setTimeout(connect, retryMs)
        retryMs = Math.min(retryMs * 2, RETRY_MAX_MS)
      }
    }
    connect()

    return () => {
      stopped = true
      clearTimeout(timer)
      if (socket) {
        socket.onclose = null
        socket.close()
      }
    }
  }, [])

  return { feed, status, dispatch }
}
