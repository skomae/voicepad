/**
 * Voice activity detection with the same Silero model bundled in the Android
 * app (android/app/src/main/assets/silero_vad.onnx), run through
 * onnxruntime-web, plus the utterance planner that mirrors
 * Android's SpeechDetector + ChunkPlanner:
 *
 *   - speech starts above 0.5, ends after 0.25 s below 0.35
 *   - utterances shorter than 0.25 s are misfires and are dropped
 *   - speech longer than 20 s without a pause is cut at its quietest moment
 *   - 0.25 s of context is kept either side of each utterance
 */

import * as ort from 'onnxruntime-web';
import { FRAME, SAMPLE_RATE } from './audio';

export interface Span {
  /** Absolute sample index into the stream. */
  start: number;
  end: number;
}

const THRESHOLD = 0.5;
const NEG_THRESHOLD = 0.35;
const MIN_SPEECH = Math.round(0.25 * SAMPLE_RATE);
const MIN_SILENCE = Math.round(0.25 * SAMPLE_RATE);
const MAX_SPEECH = Math.round(20 * SAMPLE_RATE);
const PAD = Math.round(0.25 * SAMPLE_RATE);

export class SileroVad {
  private h = new Float32Array(2 * 1 * 64);
  private c = new Float32Array(2 * 1 * 64);

  private constructor(private session: ort.InferenceSession) {}

  static async create(modelUrl: string): Promise<SileroVad> {
    const session = await ort.InferenceSession.create(modelUrl, {
      executionProviders: ['wasm'],
      graphOptimizationLevel: 'all',
    });
    return new SileroVad(session);
  }

  /** Runs one 512-sample window (16 kHz) and returns speech probability. */
  async prob(frame: Float32Array): Promise<number> {
    const feeds = {
      x: new ort.Tensor('float32', frame, [1, FRAME]),
      h: new ort.Tensor('float32', this.h, [2, 1, 64]),
      c: new ort.Tensor('float32', this.c, [2, 1, 64]),
    };
    const out = await this.session.run(feeds);
    this.h = out.new_h.data as Float32Array<ArrayBuffer>;
    this.c = out.new_c.data as Float32Array<ArrayBuffer>;
    return (out.prob.data as Float32Array)[0];
  }

  reset() {
    this.h = new Float32Array(2 * 1 * 64);
    this.c = new Float32Array(2 * 1 * 64);
  }
}

/**
 * Turns a stream of per-frame speech probabilities into finished utterances.
 * Pure logic (no model), so the same planner can run over a live mic or a
 * decoded file.
 */
export class UtteranceTracker {
  private frames = 0;
  private openStart: number | null = null;
  private lastSpeechFrame = 0;
  private silenceRun = 0;
  private closeAtFrame: number | null = null;
  /** probabilities since the current utterance opened, for the quietest cut */
  private window: { frame: number; p: number }[] = [];

  get inSpeech(): boolean {
    return this.openStart !== null;
  }

  /** Absolute sample index where the open utterance started, if any. */
  get openFrom(): number | null {
    return this.openStart === null ? null : this.openStart * FRAME;
  }

  get position(): number {
    return this.frames * FRAME;
  }

  /** Feeds one frame's probability; returns utterances finished at this frame. */
  push(p: number): Span[] {
    const frame = this.frames++;
    if (this.openStart === null) {
      if (p > THRESHOLD) {
        this.openStart = frame;
        this.lastSpeechFrame = frame;
        this.silenceRun = 0;
        this.closeAtFrame = null;
        this.window = [{ frame, p }];
      }
      return [];
    }

    this.window.push({ frame, p });
    if (p > THRESHOLD) this.lastSpeechFrame = frame;

    const isSpeech = p > NEG_THRESHOLD;
    this.silenceRun = isSpeech ? 0 : this.silenceRun + FRAME;

    if (this.closeAtFrame === null && this.silenceRun >= MIN_SILENCE) {
      this.closeAtFrame = this.lastSpeechFrame;
    }

    const length = (this.lastSpeechFrame - this.openStart) * FRAME;
    if (this.closeAtFrame === null && length >= MAX_SPEECH) {
      // Long stretch with no pause: cut at the quietest recent frame.
      const from = this.lastSpeechFrame - Math.round(4 * SAMPLE_RATE) / FRAME;
      let best = this.window[this.window.length - 1];
      for (const w of this.window) {
        if (w.frame >= from && w.p < best.p) best = w;
      }
      const cut = Math.min(best.frame, this.lastSpeechFrame - 1);
      const done = this.finish(this.openStart, cut);
      this.openStart = cut;
      this.silenceRun = 0;
      this.closeAtFrame = null;
      this.window = this.window.filter((w) => w.frame >= cut);
      return done ? [done] : [];
    }

    if (this.closeAtFrame !== null) {
      const span = this.finish(this.openStart, this.closeAtFrame);
      this.openStart = null;
      this.closeAtFrame = null;
      this.window = [];
      return span ? [span] : [];
    }
    return [];
  }

  /** Ends the stream, returning the utterance still open (if any). */
  flush(): Span[] {
    if (this.openStart === null) return [];
    const span = this.finish(this.openStart, Math.max(this.lastSpeechFrame, this.openStart));
    this.openStart = null;
    this.window = [];
    return span ? [span] : [];
  }

  private finish(startFrame: number, endFrame: number): Span | null {
    const length = (endFrame - startFrame) * FRAME;
    if (length < MIN_SPEECH) return null;
    return {
      start: Math.max(0, startFrame * FRAME - PAD),
      end: endFrame * FRAME + PAD,
    };
  }
}
