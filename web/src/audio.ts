/**
 * Microphone capture: getUserMedia → AudioWorklet → 16 kHz mono frames of 512
 * samples (the contract the Silero detector expects, same as AudioRecorder on
 * Android). Includes a ScriptProcessor fallback for browsers without
 * AudioWorklet (older iOS).
 */

export const SAMPLE_RATE = 16000;
export const FRAME = 512;

const WORKLET_SOURCE = `
class PCM16k extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const target = (options.processorOptions && options.processorOptions.target) || 16000;
    this.ratio = sampleRate / target;
    this.pos = 0;
    this.frame = new Float32Array(512);
    this.fill = 0;
  }
  process(inputs) {
    const input = inputs[0];
    const ch = input && input[0];
    if (!ch) return true;
    while (this.pos < ch.length) {
      const i = Math.floor(this.pos);
      const frac = this.pos - i;
      const a = ch[i] !== undefined ? ch[i] : 0;
      const b = ch[i + 1] !== undefined ? ch[i + 1] : a;
      this.frame[this.fill++] = a + (b - a) * frac;
      if (this.fill === 512) {
        this.port.postMessage(this.frame.slice());
        this.fill = 0;
      }
      this.pos += this.ratio;
    }
    this.pos -= ch.length;
    return true;
  }
}
registerProcessor('pcm16k', PCM16k);
`;

export function rmsToLevel(rms: number): number {
  // −60 dBFS → 0, 0 dBFS → 1, with a gentle curve like the Android level meter.
  const db = 20 * Math.log10(Math.max(rms, 1e-8));
  return Math.max(0, Math.min(1, (db + 60) / 60));
}

export class MicCapture {
  private constructor(
    private ctx: AudioContext,
    private stream: MediaStream,
    private node: AudioNode | null,
    private source: MediaStreamAudioSourceNode,
    private script: ScriptProcessorNode | null,
    private onFrame: (frame: Float32Array) => void,
  ) {}

  level = 0;
  /** Smoothed 0..1 level, for the meter. */
  private smoothed = 0;

  static async start(onFrame: (frame: Float32Array) => void): Promise<MicCapture> {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: 1,
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    });
    const ctx = new AudioContext();
    if (ctx.state === 'suspended') await ctx.resume();
    const source = ctx.createMediaStreamSource(stream);

    let node: AudioNode | null = null;
    let script: ScriptProcessorNode | null = null;

    if (ctx.audioWorklet) {
      const url = URL.createObjectURL(new Blob([WORKLET_SOURCE], { type: 'application/javascript' }));
      await ctx.audioWorklet.addModule(url);
      URL.revokeObjectURL(url);
      const worklet = new AudioWorkletNode(ctx, 'pcm16k', {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        outputChannelCount: [1],
        processorOptions: { target: SAMPLE_RATE },
      });
      worklet.port.onmessage = (e) => onFrame(e.data as Float32Array);
      source.connect(worklet);
      // Keep the graph alive without echoing anything.
      const mute = ctx.createGain();
      mute.gain.value = 0;
      worklet.connect(mute).connect(ctx.destination);
      node = worklet;
    } else {
      // Fallback: ScriptProcessor at native rate, resampled here.
      const ratio = ctx.sampleRate / SAMPLE_RATE;
      let pos = 0;
      let acc = new Float32Array(FRAME);
      let fill = 0;
      script = ctx.createScriptProcessor(1024, 1, 1);
      script.onaudioprocess = (ev) => {
        const input = ev.inputBuffer.getChannelData(0);
        while (pos < input.length) {
          const i = Math.floor(pos);
          const frac = pos - i;
          const a = input[i] ?? 0;
          const b = input[i + 1] ?? a;
          acc[fill++] = a + (b - a) * frac;
          if (fill === FRAME) {
            onFrame(acc);
            acc = new Float32Array(FRAME);
            fill = 0;
          }
          pos += ratio;
        }
        pos -= input.length;
      };
      source.connect(script);
      script.connect(ctx.destination);
      node = script;
    }

    return new MicCapture(ctx, stream, node, source, script, onFrame);
  }

  /** Call once per frame with the frame's RMS to drive the meter. */
  pushLevel(rms: number) {
    const target = rmsToLevel(rms);
    this.smoothed = target > this.smoothed ? this.smoothed + (target - this.smoothed) * 0.6 : this.smoothed + (target - this.smoothed) * 0.12;
    this.level = this.smoothed;
  }

  async stop(): Promise<void> {
    try {
      if (this.script) this.script.onaudioprocess = null;
      if (this.node) this.node.disconnect();
      this.source.disconnect();
    } catch {
      /* already torn down */
    }
    this.stream.getTracks().forEach((t) => t.stop());
    await this.ctx.close().catch(() => {});
  }
}

/** Decodes any audio file the browser can play into 16 kHz mono float samples. */
export async function decodeAudioFile(file: File | Blob): Promise<Float32Array> {
  const data = await file.arrayBuffer();
  const ctx = new OfflineAudioContext(1, 1, SAMPLE_RATE);
  const decoded = await ctx.decodeAudioData(data);
  if (decoded.sampleRate === SAMPLE_RATE) {
    return decoded.getChannelData(0).slice();
  }
  // Resample through a second offline context at 16 kHz.
  const frames = Math.ceil(decoded.duration * SAMPLE_RATE);
  const off = new OfflineAudioContext(1, frames, SAMPLE_RATE);
  const src = off.createBufferSource();
  src.buffer = decoded;
  src.connect(off.destination);
  src.start();
  const rendered = await off.startRendering();
  return rendered.getChannelData(0).slice();
}

/**
 * A rolling window of recent audio addressed by absolute sample index, so an
 * utterance can be sliced out at any point. Keeps the last [seconds].
 */
export class SampleRing {
  private buf: Float32Array;
  private start = 0; // absolute index of buf[0]
  private end = 0; // absolute index of the next write

  constructor(private seconds = 60, private rate = SAMPLE_RATE) {
    this.buf = new Float32Array(seconds * rate);
  }

  get total(): number {
    return this.end;
  }

  push(frame: Float32Array) {
    if (this.end - this.start + frame.length > this.buf.length) {
      // Drop the oldest audio to make room.
      const keep = this.buf.length - frame.length;
      this.buf.copyWithin(0, this.end - this.start - keep, this.end - this.start);
      this.start = this.end - keep;
    }
    const at = this.end - this.start;
    this.buf.set(frame, at);
    this.end += frame.length;
  }

  slice(from: number, to: number): Float32Array {
    const a = Math.max(from, this.start);
    const b = Math.min(to, this.end);
    if (b <= a) return new Float32Array(0);
    return this.buf.slice(a - this.start, b - this.start);
  }
}
