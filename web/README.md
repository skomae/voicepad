# VoicePad — web demo (PWA)

A browser/PWA version of VoicePad, built to show the full app's behaviour with
the smallest English model that runs on-device: **Whisper tiny.en, 8-bit,
~41 MB**. Everything — voice detection, transcription, notes, audio — stays in
the browser; no audio is uploaded.

This is the spike from [`docs/wasm-plan.md`](../docs/wasm-plan.md), shipped far
enough to use: record, live text, pause splitting, split button, notes with
audio, installable + offline.

```bash
cd web
pnpm install      # also copies the Silero VAD + onnxruntime WASM into place
pnpm dev          # http://localhost:5173
pnpm build        # dist/, then served by the voicepad-web container
```

## What it does

- **Recording starts on tap**, level meter on the record button, elapsed time,
  red frame and `Recording — tap ■ to stop`, like the Android app.
- **Silence trimming + pause splitting** with Silero VAD — the *same*
  `silero_vad.onnx` from `android/app/src/main/assets/`: 0.25 s of silence ends
  an utterance, utterances under 0.25 s are dropped, speech longer than 20 s is
  cut at its quietest moment, 0.25 s of padding either side.
- **Each finished utterance is transcribed as soon as its pause is detected**,
  and the sentence in progress is re-transcribed every 1.2 s into a dashed
  "live" bubble (the Android app's live preview tick).
- **Split** ends the current note at the instant of the tap and starts a new
  one without interrupting the stream.
- **Notes** live in IndexedDB with their 16 kHz WAV: reopen, copy, download the
  WAV, delete, **pin** (pinned sort first) and **search** across text and date.
- **Export**: one JSON document for a note or all of them (Settings → *Export
  all notes*), with text, per-segment timing, duration, pin state and status —
  the shape the app can import. Audio stays on the device; per-note WAV
  download covers that.
- **No sample or upload button**: the demo records, nothing else. Test clips
  stay out of the product surface (see *Testing without a microphone*).
- **Introduction bubble**: the grey, left-aligned message a new visitor sees,
  plus the app-store funnel line. Its copy lives in one place — `INTRO_TEXT` in
  `src/main.ts` — and `STORE_URL` there turns the "in the app stores soon" line
  into a live *Get the app* link the moment the listing exists.
- **PWA**: installable, app shell precached, model cached in the browser after
  first load, works offline.

## Model choice

The Android app uses sherpa-onnx natively. There is **no prebuilt sherpa-onnx
browser ASR bundle**: its WASM target preloads the model into the emscripten
filesystem at link time, so one model per hand-built binary, and k2-fsa only
publishes TTS/VAD WASM artifacts. Building one means emsdk + a 20–60 min
compile per model.

So the demo uses the smallest English-only ONNX model available for the
browser, through [transformers.js](https://github.com/huggingface/transformers.js)
(onnxruntime-web underneath):

| | size |
| --- | --- |
| `encoder_model_quantized.onnx` | 10.1 MB |
| `decoder_model_merged_quantized.onnx` | 30.7 MB |
| tokenizer + configs | 2.3 MB |
| **total** | **~43 MB** |

Alternatives considered: sherpa-onnx zipformer-en-20M (43 MB int8, but no
browser build), Moonshine tiny int8 (70 MB), Whisper base.en (78 MB).

Measured on this machine (WASM, 1 thread, no WebGPU): **17 s of speech
transcribed in ~3.4 s** across two utterances, i.e. ~5× real time — text
appears well before you finish the next sentence. WebGPU would be faster but
needs fp16 support (this GPU's WebGPU has none, so the app silently falls back
to WASM).

## Layout

```
index.html          app shell
src/main.ts         UI + session state machine (recorder → VAD → commits → notes)
src/audio.ts        getUserMedia → AudioWorklet → 16 kHz frames, sample ring, file decode
src/vad.ts          Silero VAD over onnxruntime-web + UtteranceTracker (ChunkPlanner port)
src/asr.ts          SpeechEngine — whisper-tiny.en via transformers.js
src/notes.ts        IndexedDB note store (web subset of Note.kt)
src/wav.ts          WAV writing, duration/date formatting
scripts/            asset staging (Silero model, ort runtime), dist tidy
deploy/nginx.conf   static config used by the voicepad-web container
```

`SpeechEngine` is the same interface shape as `SpeechEngine.kt`
(`transcribe(samples) -> text`), so a sherpa-onnx WASM bundle can replace
`src/asr.ts` without touching the pipeline or the UI.

## Parity with Android

| Android | here |
| --- | --- |
| AudioRecord + resample | `getUserMedia` + AudioWorklet (ScriptProcessor fallback) |
| Silero VAD (sherpa-onnx) | same `silero_vad.onnx` via onnxruntime-web |
| SpeechDetector / ChunkPlanner | `vad.ts` `UtteranceTracker` (same thresholds, padding, 20 s cut, 0.25 s misfire rule) |
| Parakeet / SenseVoice / Whisper | Whisper tiny.en q8 |
| live preview tick (1.2 s) | same |
| note + WAV storage | IndexedDB note + WAV blob |
| foreground service | tab must stay open (no browser equivalent) |
| Split | Split |
| translation, romanisation, conversation mode, background voices | not in the demo |

## Proving it stays on the device

A green **0 B sent** badge sits in the header; tapping it opens *Nothing leaves
this device*:

- audio captured so far, bytes sent anywhere, and every network request the page
  made, grouped by host (the model download from `huggingface.co` shows up once,
  then comes from cache);
- a red alarm if any request with a body is ever made **while recording** —
  `fetch`, `XMLHttpRequest`, `sendBeacon` and `WebSocket` are all wrapped at boot
  in `src/privacy.ts`, so the claim is measured, not asserted;
- how to verify independently: airplane mode → reload → record; the words still
  appear, because the model was downloaded once and the mic never leaves the tab.

## Deploying

Built with `pnpm build`; the static `dist/` is served from **fr1** at
<https://voicepad.fr1.x0t.cc> — the same pattern as the other PWAs there:

- `/var/www/voicepad` on fr1 holds the build (`rsync -az --delete`).
- The Caddy block is [`deploy/caddy-voicepad.conf`](deploy/caddy-voicepad.conf):
  SPA fallback, no-cache for `index.html` / `sw.js` / the manifest, immutable
  caching for `/assets` and `/ort`, and the MIME types Caddy gets wrong or
  missing (`.wasm`, `.mjs`, `.webmanifest`).

```bash
pnpm build
rsync -az --delete -e "ssh -p 51821" dist/ skomae@fr1.x0t.cc:/var/www/voicepad/
```

HTTPS there is a real Let's Encrypt cert on the wildcard DNS, which is what
iOS needs for *Add to Home Screen* plus microphone access. (Serving from shilin
over plain tailnet HTTP would not work: the mic and service worker both require
a secure context.)

## Testing without a microphone

In `pnpm dev` only, the app exposes a harness that pushes a decoded file through
the exact live path (frames → VAD → commits → note → WAV):

```js
await window.__vp.feed('/fixtures/speech.wav')   // → Note with segments
```

Test clips live in `dev-fixtures/` and are served at `/fixtures/` by a
dev-only middleware (the harness uses Moonshine's `test_wavs`); they are never
part of a build.

## Known limits

- Background recording stops when the tab is closed or suspended (no
  foreground-service equivalent); a wake-lock would only help while visible.
- Whisper tiny.en is the demo's accuracy ceiling — noticeably weaker than
  Parakeet on accents and noisy rooms.
- No translation, romanisation, conversation mode, speaker handling, revision
  history or archive yet. Translation was prototyped with Bergamot (en→es,
  ~35 MB) and pulled: it works on desktop, but on iOS it takes the page's WASM
  memory and kills dictation. It needs a model-swap design first.
- The first load downloads ~43 MB of model plus ~27 MB of WASM runtime, then
  serves both from cache, offline included.
