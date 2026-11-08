import { useCallback, useEffect, useRef, useState } from 'react'
import workletUrl from './capture.worklet.js?worker&url'
import { PcmPlayer } from './player'
import { parseServerMessage, socketUrl, type SocketParams } from './protocol'

export type CallStatus = 'idle' | 'connecting' | 'live' | 'ended' | 'error'

export interface SimulatorState {
  status: CallStatus
  /** Why the call ended or failed, in words for the person. */
  message: string
  callId: string | null
  /** True when the call is saved to and read from a farmer's memory. */
  memory: boolean
  muted: boolean
  assistantSpeaking: boolean
  framesSent: number
  framesReceived: number
}

const IDLE: SimulatorState = {
  status: 'idle',
  message: '',
  callId: null,
  memory: false,
  muted: false,
  assistantSpeaking: false,
  framesSent: 0,
  framesReceived: 0,
}

/** Do not queue more than about a second of microphone audio when the socket is slow; drop instead. */
const MAX_BUFFERED_BYTES = 8000 * 2

interface Session {
  socket: WebSocket
  context: AudioContext
  stream: MediaStream
  player: PcmPlayer
  sent: number
  received: number
  muted: boolean
  /** Stops everything and returns the audio resources. Safe to call twice. */
  teardown: () => void
}

/**
 * One simulated phone leg: microphone in as 20 ms frames of 8 kHz PCM16, assistant audio out through Web Audio.
 * `start` must run from a click, because browsers only allow audio and the microphone after a gesture.
 */
export function useCallSimulator() {
  const [state, setState] = useState<SimulatorState>(IDLE)
  const session = useRef<Session | null>(null)

  const finish = useCallback((status: 'ended' | 'error', message: string) => {
    session.current?.teardown()
    session.current = null
    setState((s) => (s.status === 'ended' || s.status === 'error' ? s : { ...s, status, message, assistantSpeaking: false }))
  }, [])

  const stop = useCallback(() => {
    const s = session.current
    if (!s) return
    if (s.socket.readyState === WebSocket.OPEN) {
      try {
        s.socket.send(JSON.stringify({ type: 'hangup' }))
      } catch {
        // The socket closed under us; teardown below does the rest.
      }
    }
    finish('ended', 'You hung up.')
  }, [finish])

  const start = useCallback(
    async (params: SocketParams) => {
      if (session.current) return
      setState({ ...IDLE, status: 'connecting', message: 'Asking for the microphone…' })

      let stream: MediaStream
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        })
      } catch (error) {
        const denied = error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'SecurityError')
        setState({
          ...IDLE,
          status: 'error',
          message: denied
            ? 'The microphone is blocked. Allow it for this page in the browser, then try again.'
            : 'No microphone was found or it could not be opened.',
        })
        return
      }

      let context: AudioContext | null = null
      try {
        context = new AudioContext()
        await context.resume()
        await context.audioWorklet.addModule(workletUrl)
        const capture = new AudioWorkletNode(context, 'pcm-capture', { numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1 })
        // The capture node must be pulled by the graph; a zero-gain sink keeps it running without playing the microphone.
        const sink = context.createGain()
        sink.gain.value = 0
        capture.connect(sink).connect(context.destination)
        context.createMediaStreamSource(stream).connect(capture)

        const socket = new WebSocket(socketUrl(params))
        socket.binaryType = 'arraybuffer'
        const player = new PcmPlayer(context, context.destination)
        const live: Session = {
          socket,
          context,
          stream,
          player,
          sent: 0,
          received: 0,
          muted: false,
          teardown: () => {
            window.clearInterval(ticker)
            capture.port.onmessage = null
            player.clear()
            socket.onmessage = socket.onclose = socket.onerror = null
            if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) socket.close()
            stream.getTracks().forEach((t) => t.stop())
            void context?.close()
          },
        }
        // Counters change 50 times a second; show them twice a second.
        const ticker = window.setInterval(() => {
          setState((s) => ({
            ...s,
            framesSent: live.sent,
            framesReceived: live.received,
            assistantSpeaking: player.playing,
          }))
        }, 500)
        session.current = live
        setState((s) => ({ ...s, message: 'Connecting to the call…' }))

        capture.port.onmessage = (event: MessageEvent<ArrayBuffer>) => {
          if (live.muted || socket.readyState !== WebSocket.OPEN || socket.bufferedAmount > MAX_BUFFERED_BYTES) return
          socket.send(event.data)
          live.sent++
        }
        socket.onmessage = (event: MessageEvent<string | ArrayBuffer>) => {
          if (typeof event.data !== 'string') {
            live.received++
            player.enqueue(event.data)
            return
          }
          const message = parseServerMessage(event.data)
          if (!message) return
          switch (message.type) {
            case 'ready':
              setState((s) => ({ ...s, status: 'live', message: 'On the call.', callId: message.callId, memory: message.memory }))
              break
            case 'mark':
              // Tell the server once what came before this mark has been heard.
              player.whenPlayed(() => {
                if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'mark', id: message.id }))
              })
              break
            case 'clear':
              player.clear()
              break
            case 'end':
              finish('ended', 'The call ended.')
              break
          }
        }
        socket.onclose = (event) => {
          finish(event.code === 1000 ? 'ended' : 'error', event.reason || (event.code === 1000 ? 'The call ended.' : 'The connection to the backend was lost.'))
        }
        socket.onerror = () => finish('error', 'Could not reach the backend. Is it running?')
      } catch (error) {
        if (session.current) session.current.teardown()
        else {
          stream.getTracks().forEach((t) => t.stop())
          void context?.close()
        }
        session.current = null
        setState({
          ...IDLE,
          status: 'error',
          message: error instanceof Error && error.name === 'NotSupportedError' ? 'This browser cannot capture audio here.' : 'The audio could not be started in this browser.',
        })
      }
    },
    [finish],
  )

  const setMuted = useCallback((muted: boolean) => {
    if (session.current) session.current.muted = muted
    setState((s) => ({ ...s, muted }))
  }, [])

  // Leaving the page hangs up.
  useEffect(
    () => () => {
      session.current?.teardown()
      session.current = null
    },
    [],
  )

  return { state, start, stop, setMuted }
}
