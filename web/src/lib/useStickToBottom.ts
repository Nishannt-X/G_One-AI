import { useCallback, useEffect, useRef } from 'react'

/**
 * For a scrolling list that grows at the bottom: follows new entries while the viewer is at the bottom, and leaves
 * them alone once they scroll up to read.
 */
export function useStickToBottom(changeKey: number) {
  const ref = useRef<HTMLDivElement>(null)
  const pinned = useRef(true)

  const onScroll = useCallback(() => {
    const el = ref.current
    if (el) pinned.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48
  }, [])

  useEffect(() => {
    const el = ref.current
    if (el && pinned.current) el.scrollTop = el.scrollHeight
  }, [changeKey])

  return { ref, onScroll }
}
