/**
 * The speech engine for the web demo.
 *
 * Android runs sherpa-onnx natively (Parakeet / SenseVoice / Whisper). The
 * browser has no prebuilt sherpa-onnx ASR bundle — that build bakes the model
 * into the WASM binary at link time and k2-fsa only publishes TTS/VAD wasm
 * artifacts — so the demo uses the smallest English-only model that runs in a
 * browser today: Whisper tiny.en, int8-quantised ONNX (encoder 10 MB +
 * decoder 31 MB), through transformers.js (onnxruntime-web under the hood).
 *
 * The interface is the same shape as SpeechEngine.kt: give it 16 kHz mono
 * float samples, get text back. Swapping in a sherpa-onnx WASM bundle later
 * only touches this file.
 */

import * as ort from 'onnxruntime-web';
import type { AutomaticSpeechRecognitionPipeline } from '@huggingface/transformers';

// Point onnxruntime's WASM runtime at our own /ort directory. transformers.js
// imports the same onnxruntime-web build, so this covers the speech model too,
// and the app needs no CDN — or duplicated 27 MB runtime in the bundle.
ort.env.wasm.wasmPaths = '/ort/';
ort.env.wasm.numThreads = 1;
ort.env.wasm.proxy = false;

const { env, pipeline } = await import('@huggingface/transformers');

env.allowLocalModels = false;
env.useBrowserCache = true;

// One thread and no memory arena keeps the WASM heap small — the difference
// between loading and "RangeError: Out of memory" on an iPhone.
try {
  const wasm: any = (env.backends.onnx as any)?.wasm;
  if (wasm) {
    wasm.numThreads = 1;
    wasm.proxy = false;
    if (!wasm.wasmPaths) wasm.wasmPaths = '/ort/';
  }
} catch {
  /* older transformers builds: nothing to configure */
}

export interface ModelInfo {
  id: string;
  label: string;
  sizeMB: number;
  note: string;
}

export const MODEL: ModelInfo = {
  id: 'onnx-community/whisper-tiny.en',
  label: 'Whisper Tiny (English)',
  sizeMB: 41,
  note: 'Encoder + decoder, 8-bit quantised. English only, the smallest Whisper that runs in a browser.',
};

export type Device = 'wasm' | 'webgpu';

export interface LoadProgress {
  /** 0..1, or -1 while unknown */
  progress: number;
  file?: string;
}

export function webgpuAvailable(): boolean {
  return typeof navigator !== 'undefined' && 'gpu' in navigator;
}

export class SpeechEngine {
  private constructor(
    private pipe: AutomaticSpeechRecognitionPipeline,
    readonly device: Device,
  ) {}

  static async load(onProgress: (p: LoadProgress) => void, device: Device = 'wasm'): Promise<SpeechEngine> {
    if (device === 'webgpu' && !webgpuAvailable()) device = 'wasm';

    const totals = new Map<string, { loaded: number; total: number }>();
    const report = (file: string, loaded: number, total: number) => {
      totals.set(file, { loaded, total });
      let l = 0;
      let t = 0;
      for (const v of totals.values()) {
        l += v.loaded;
        t += v.total;
      }
      onProgress({ progress: t > 0 ? l / t : -1, file });
    };

    const dtype = device === 'webgpu' ? 'fp16' : 'q8';
    const pipe = await pipeline('automatic-speech-recognition', MODEL.id, {
      device,
      dtype,
      // Smaller peak heap while the sessions are built.
      session_options: {
        enableCpuMemArena: false,
        enableMemPattern: false,
        executionMode: 'sequential',
      },
      progress_callback: (event: any) => {
        if (event?.status === 'progress' && event.file) {
          report(event.file, event.loaded ?? 0, event.total ?? 0);
        }
      },
    });

    return new SpeechEngine(pipe as AutomaticSpeechRecognitionPipeline, device);
  }

  /**
   * Transcribes one utterance (≤25 s of 16 kHz mono audio). Short, near-silent
   * clips return "" instead of Whisper's usual invented text.
   */
  async transcribe(samples: Float32Array): Promise<string> {
    if (samples.length < 1600) return '';
    let energy = 0;
    for (let i = 0; i < samples.length; i++) energy += samples[i] * samples[i];
    if (Math.sqrt(energy / samples.length) < 0.0015) return '';

    const out: any = await this.pipe(samples, {
      chunk_length_s: 25,
      stride_length_s: 3,
      return_timestamps: false,
    });
    return clean(out?.text ?? '');
  }
}

/** Whisper's inventory of stock phrases on silence/noise, minus the audio. */
function clean(text: string): string {
  const t = text.trim().replace(/\s+/g, ' ');
  if (!t) return '';
  const junk = [
    /^\[.*\]$/,
    /^\(.*\)$/,
    /^you$/i,
    /^thank you\.?$/i,
    /^thanks for watching.*$/i,
    /^subtitles by.*$/i,
  ];
  return junk.some((r) => r.test(t)) ? '' : t;
}
