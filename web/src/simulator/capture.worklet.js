/* global AudioWorkletProcessor, registerProcessor, sampleRate */
// Runs on the audio thread. Turns the microphone (at the browser's own rate, often 44.1 or 48 kHz) into 8 kHz mono
// PCM16 and posts it to the page in frames of 20 ms (160 samples, 320 bytes). Downsampling averages the input samples
// that fall into each output sample, which is enough of a low-pass filter for speech.
const TARGET_RATE = 8000
const FRAME_SAMPLES = 160

class PcmCapture extends AudioWorkletProcessor {
  constructor() {
    super()
    this.ratio = sampleRate / TARGET_RATE // input samples per output sample, not a whole number for 44.1 kHz
    this.progress = 0 // how far into the current output sample we are, in input samples
    this.sum = 0
    this.count = 0
    this.frame = new Int16Array(FRAME_SAMPLES)
    this.filled = 0
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0]
    if (!channel) return true
    for (let i = 0; i < channel.length; i++) {
      this.sum += channel[i]
      this.count++
      this.progress++
      if (this.progress >= this.ratio) {
        this.progress -= this.ratio
        const level = Math.max(-1, Math.min(1, this.sum / this.count))
        this.frame[this.filled++] = level < 0 ? level * 0x8000 : level * 0x7fff
        this.sum = 0
        this.count = 0
        if (this.filled === FRAME_SAMPLES) {
          this.port.postMessage(this.frame.buffer, [this.frame.buffer])
          this.frame = new Int16Array(FRAME_SAMPLES)
          this.filled = 0
        }
      }
    }
    return true
  }
}

registerProcessor('pcm-capture', PcmCapture)
