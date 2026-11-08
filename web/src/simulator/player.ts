import { WIRE_RATE } from './protocol'

/** How far ahead of "now" the first piece after silence is scheduled, so the next pieces arrive before it ends. */
const LEAD_SECONDS = 0.06

/**
 * Plays 8 kHz PCM16 pieces back to back with no gaps. Each piece is converted to the context's rate here, carrying the
 * position across pieces, so the joins are as smooth as the audio itself. {@link clear} stops everything not yet heard
 * (barge-in).
 */
export class PcmPlayer {
  private readonly step: number // input samples per output sample
  private nextStart = 0
  private position = 0 // fractional index into the next piece; -1..0 means "between the last sample and the next piece's first"
  private previous = 0
  private sources = new Set<AudioBufferSourceNode>()
  private last: AudioBufferSourceNode | null = null
  private waiters: (() => void)[] = []

  private readonly context: AudioContext
  private readonly destination: AudioNode

  constructor(context: AudioContext, destination: AudioNode) {
    this.context = context
    this.destination = destination
    this.step = WIRE_RATE / context.sampleRate
  }

  /** True while audio is scheduled or sounding. */
  get playing(): boolean {
    return this.sources.size > 0
  }

  enqueue(pcm16le: ArrayBuffer): void {
    const input = new Int16Array(pcm16le, 0, Math.floor(pcm16le.byteLength / 2))
    if (input.length === 0) return
    const output = new Float32Array(Math.ceil((input.length - this.position) / this.step) + 1)
    let written = 0
    let pos = this.position
    while (pos < input.length - 1) {
      const i = Math.floor(pos)
      const frac = pos - i
      const a = i < 0 ? this.previous : input[i]
      output[written++] = ((a * (1 - frac) + input[i + 1] * frac) / 0x8000)
      pos += this.step
    }
    this.position = pos - input.length
    this.previous = input[input.length - 1]
    if (written === 0) return

    const buffer = this.context.createBuffer(1, written, this.context.sampleRate)
    buffer.copyToChannel(output.subarray(0, written), 0)
    const source = this.context.createBufferSource()
    source.buffer = buffer
    source.connect(this.destination)

    const now = this.context.currentTime
    if (this.nextStart < now) {
      this.nextStart = now + LEAD_SECONDS // we ran dry (or this is the first piece)
    }
    source.start(this.nextStart)
    this.nextStart += buffer.duration
    this.sources.add(source)
    this.last = source
    source.onended = () => {
      this.sources.delete(source)
      if (this.last === source) {
        this.last = null
        const waiting = this.waiters
        this.waiters = []
        waiting.forEach((w) => w())
      }
    }
  }

  /** Runs {@code callback} once everything queued so far has been heard; at once when nothing is queued. Dropped by {@link clear}. */
  whenPlayed(callback: () => void): void {
    if (this.last === null) callback()
    else this.waiters.push(callback)
  }

  /** Stops everything queued or sounding, immediately. */
  clear(): void {
    for (const source of this.sources) {
      source.onended = null
      try {
        source.stop()
      } catch {
        // Already stopped.
      }
      source.disconnect()
    }
    this.sources = new Set()
    this.last = null
    this.waiters = []
    this.nextStart = 0
    this.position = 0
    this.previous = 0
  }
}
