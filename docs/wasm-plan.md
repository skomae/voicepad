# VoicePad — WASM version plan

Status: **spike shipped** — see [`web/`](../web/README.md). A working
browser/PWA demo now runs the full record → live text → split → notes flow with
Whisper tiny.en (~41 MB, int8) through transformers.js, the same Silero VAD
file as Android (`silero_vad.onnx`), the same utterance rules (0.25 s pause,
0.25 s padding, 20 s cut) and the same 1.2 s live-preview tick.

What the spike settled:

- **TypeScript app, not Kotlin/Wasm**, for the demo: sherpa-onnx has no
  prebuilt browser ASR bundle (the WASM target bakes one model in at link
  time), so the pipeline had to be rebuilt in JS anyway. `src/asr.ts` keeps a
  `SpeechEngine`-shaped seam for a future sherpa WASM build.
- **Smallest English model:** whisper-tiny.en q8 (encoder 10 MB + decoder
  31 MB). ~17 s of speech transcribes in ~3.4 s on one WASM thread.
- **OPFS was not needed** for the demo: IndexedDB notes + WAV blobs are enough
  at demo scale; the recovery-writes-in-progress part of this plan is still
  open.
- **WebGPU is not a win by default**: without fp16 it falls back to WASM, so
  the demo defaults to WASM + q8 and keeps the download small.

Still open from this plan: translation, romanisation, OPFS streaming with torn
header recovery, the full `RecordingController` retry/revision state machine,
background-tab recording, and (if it matters) a hand-built sherpa-onnx WASM
bundle of Parakeet/SenseVoice for parity with the Android models.

## Goal

Ship VoicePad as a web app running in the browser, with the same core
behaviour as the Android app: recording starts immediately, speech is
transcribed on-device into a live bubble, silence is trimmed and pauses split
utterances, and every recording is recoverable even if the tab closes
mid-recording.

The key insight is that the speech-recognition stack already compiles to
WebAssembly: **sherpa-onnx** (the same library the Android app uses) ships an
official browser/WASM build and examples that do exactly what the Android AAR
does. Most of the work is replacing the four Android-only services (microphone,
ML Kit translation, ICU romanisation, file storage) with web equivalents.

## Scope decisions

- **v1 keeps transcription, VAD silence-trimming, split, notes, recovery and
  romanisation.** Translation is the one part with no drop-in WASM equivalent
  (see [Translation](#translation)); ship it behind an interface, optionally in
  v2, rather than blocking v1 on it.
- **Same model files.** The exact `*.int8.onnx` + `tokens.txt` archives the
  Android app downloads are reused unchanged.
- **Same note schema.** `notes.json` (with `.bak`) and 16 kHz mono WAV files
  keep their format so behaviour — and future import/export — stays consistent.

## Target platform decision

| | Kotlin/Wasm (KMP) | TypeScript/JS web app |
| --- | --- | --- |
| Reuse | Shares `ChunkPlanner`, `Romanizer` logic, `Note`/`NoteStore` schema, controller state machine | Rewrites everything; ports algorithm *design* only |
| Risk | Compose Multiplatform Web + `java.*` unavailability friction | Very mature tooling (Vite, sherpa-onnx JS API, OPFS libs) |
| Verdict | Best if the long-term goal is one codebase across Android/iOS/web | Fastest, lowest-risk path to a working web app |

**Recommendation:** a Kotlin Multiplatform `commonMain` core plus JS/WASM
platform glue. Move the pure logic below into `commonMain`, keep Android
working as-is, and add a `wasmJs` target. If the team prefers not to adopt KMP,
fall back to a standalone TypeScript app — the rest of this plan is identical
either way; only the language of the shared core changes.

### Portable to `commonMain` as-is

- `ChunkPlanner` — pure Kotlin, already unit-tested.
- `WavFile` — pure byte layout; swap `File`/`RandomAccessFile` for
  `expect`/`actual` byte-slice operations.
- `Note` / `Revision` / `NoteTranslation` and their `kotlinx.serialization`
  schema — kotlinx.serialization works on wasmJs.
- `NoteStore`'s JSON, atomic-save and recovery logic — swap `File` for an
  `expect` blobby key-value store.
- `Romanizer`'s `PINYIN_WORDS` map and `tidy` logic — the word-correction map
  is exactly what makes pinyin better than character-by-character transliteration.
- The `RecordingController` state machine — transcription statuses
  (`RECORDING → PENDING → IN_PROGRESS → DONE/FAILED`), automatic retry with
  `MAX_AUTO_ATTEMPTS`, Split, and the live-preview re-transcription tick.

### Not portable (needs a rewrite)

- `AudioRecorder` (`AudioRecord` JNI/`MediaRecorder`).
- `SpeechDetector` and `SherpaEngine` (sherpa-onnx JNI).
- `ModelManager` (`HttpURLConnection` + Apache Commons Compress tar.bz2).
- `EnglishTranslator` (Google ML Kit) and ML Kit language-id.
- `Romanizer`'s ICU `Transliterator` backend.
- `AudioInputs` (Android mic enumeration/routing).
- All Compose UI.

## Component mapping

| Android | WASM equivalent |
| --- | --- |
| `AudioRecord` + `AudioRecorder` | `navigator.mediaDevices.getUserMedia({audio})` → `AudioContext` → **AudioWorklet** producing Float32 frames; resample 48k→16k; same RMS level meter |
| `SpeechDetector` (Silero VAD) | sherpa-onnx `wasm/vad` — same `silero_vad.onnx`, same 0.25 s / 20 s parameters |
| `SherpaEngine` (Parakeet/SenseVoice/Whisper) | sherpa-onnx `wasm/asr` offline recognizer — same model configs, same int8 models |
| `AutoEngine` | identical logic (SenseVoice language-tag gate → Parakeet for English); ports as-is |
| `ModelManager` (tar.bz2 download + unpack) | `fetch()` + `bzip2`/`tar` in JS, or pre-extracted files; cache in **Cache API / OPFS** |
| ML Kit `EnglishTranslator` | **Bergamot** (Marian NMT, WASM) or **onnxruntime-web** (OPUS-MT / distilled NLLB) — see [Translation](#translation) |
| ML Kit language-id | ASR model's `lang` first, then `franc`/`ld` fallback |
| ICU `Transliterator` romanisation | `pinyin-pro` (pinyin + tone marks, word-level) + `uroman` (Any-Latin); keep `PINYIN_WORDS` overrides |
| `NoteStore` (notes.json + WAV files) | **IndexedDB** for notes (same schema), **OPFS** for WAVs |
| `WavFile` RandomAccessFile header patching | OPFS **`createSyncAccessHandle`** in a worker (random-access write ≈ `RandomAccessFile`) |
| Foreground service (background recording) | Web Worker + AudioWorklet + Screen Wake Lock (best effort — see [Risks](#risks-and-mitigations)) |
| Compose UI | Compose Multiplatform Web (KMP) or Svelte/React (TS path) |

## Audio capture

1. Call `getUserMedia({ audio: true })`. This requires a user gesture and a
   secure context (HTTPS or localhost) — the web equivalent of the Android
   `RECORD_AUDIO` runtime permission.
2. Create an `AudioContext`. Browsers do not guarantee 16 kHz output, so run an
   **AudioWorklet** that receives native-rate frames and downsamples to 16 kHz
   mono Float32 (linear resampling is sufficient for ASR).
3. Feed 512-sample windows to the VAD — the same contract as
   `SpeechDetector.feed`. Compute RMS → dB → 0..1 level exactly like
   `AudioRecorder.readLoop`.
4. Simultaneously append Int16 PCM to the OPFS WAV (via a worker holding a
   `createSyncAccessHandle`), checkpointing the 44-byte header every ~5 s so a
   tab close mid-recording is recoverable, exactly like the Android app.

## ASR + VAD

- Use the `sherpa-onnx` npm package (v2.0.3, matching the Android AAR version);
  it exposes `createOfflineRecognizer` and the VAD.
- Configs are structurally identical to `SherpaEngine.kt`:
  `OfflineTransducerModelConfig` (Parakeet), `OfflineSenseVoiceModelConfig`,
  `OfflineWhisperModelConfig`, all pointed at the same `*.int8.onnx` +
  `tokens.txt`.
- `AutoEngine` (SenseVoice → Parakeet gate) and `ChunkPlanner` are reused
  verbatim, so silence trimming, pause splitting, 25 s chunks and the live
  re-transcription tick behave identically.
- Prefer **WebGPU** when available (sherpa-onnx wasm uses it for much faster
  inference), fall back to WASM SIMD + threads. Mid-range hardware is
  comparable to a phone; large-v3 turbo will be slow without WebGPU — the same
  trade-off as Android.

## Model download & caching

The Android `ModelManager` streams a `.tar.bz2` from GitHub Releases and keeps
only `*.int8.onnx` + `tokens.txt`. The web version does the same but:

- downloads with `fetch()` (with progress via `ReadableStream`),
- decompresses `bzip2` + untars in JS (or serves pre-extracted raw files from a
  static CDN),
- caches in **Cache API** (for URL-addressed model files) or **OPFS** (for
  byte-addressed blobs).

Keep the same UX: recording starts immediately and text catches up once the
model is ready, with progress surfaced in the UI.

## Translation

ML Kit has no WASM equivalent with the same language coverage. Options, in
order of preference:

1. **Bergamot** (Mozilla's local machine translation, pure WASM) — closest in
   spirit, but model coverage (mostly zh/en plus a few European pairs) and
   maintenance are limited.
2. **onnxruntime-web + distilled NLLB-200 / OPUS-MT ONNX** — covers the
   Asian + European set, but multilingual NLLB is ~300 MB even distilled and
   quantized.
3. **Drop translation in v1** and keep only transcription + romanisation
   (SenseVoice/Whisper already produce the original text; pinyin comes from
   `pinyin-pro`). Add translation in v2 behind the chosen engine.

Language ID: use the ASR model's reported language first (SenseVoice and
Whisper both report it), fall back to `franc` for short text, and keep the
existing `LanguageTags.decide` Han/kana heuristic.

## Persistence & recovery

- **notes.json + `.bak`**: IndexedDB with an atomic write-then-swap pattern
  mirroring `NoteStore.save` (write `tmp`, copy current → `.bak`, rename).
  Same `@Serializable` schema under KMP.
- **WAV files**: OPFS in a dedicated worker using `createSyncAccessHandle` for
  append plus random-access header patch — the web analogue of
  `RandomAccessFile`. Keep `WavFile.repair` / `sampleCount` / `readRange` byte
  logic; only the I/O backend changes.
- **Recovery after reload**: same `recoverAfterRestart` behaviour —
  `RECORDING` → repair header + `PENDING`, `IN_PROGRESS` → `FAILED`,
  `DONE`-but-empty → `PENDING`. A tab close mid-recording leaves a torn header
  in OPFS just like a killed Android app leaves one on disk.

## UI

- **KMP path:** Compose Multiplatform Web (Material3 support is decent now) —
  most of `VoicePadScreen` / `RecordBar` / `Bubbles` structure carries over;
  permission handling, mic chooser and share intents become platform
  `expect`/`actual`.
- **TS path:** Svelte or React + Vite, re-implementing the same screens. Faster
  to a polished result; loses code sharing.

## Suggested phases

1. **Spike (1–2 days):** sherpa-onnx wasm running a SenseVoice + Silero VAD
   mic → text loop in a Vite app. Validates WebGPU/CPU performance and model
   download. *Go/no-go on feasibility and performance.*
2. **Core pipeline (3–5 days):** AudioWorklet capture + VAD + `ChunkPlanner` +
   offline recognizer + `AutoEngine` + live-preview tick, behind a
   `SpeechEngine` interface mirroring the Android one.
3. **Persistence (2–3 days):** OPFS WAV streaming + IndexedDB notes + recovery +
   retry logic. Port the `RecordingController` state machine.
4. **Romanisation + translation (2–5 days):** `pinyin-pro`/`uroman` first
   (easy); translation behind an interface, shipped as optional.
5. **UI + PWA (3–5 days):** screens, notes list, search, pinning,
   playback/share, service worker + wake lock + manifest.

## Risks and mitigations

- **Background throttling:** browsers freeze background tabs; there is no true
  Android foreground-service equivalent. Mitigation: Web Worker + AudioWorklet +
  Wake Lock keeps recording while the tab is *open*; document that the tab must
  stay open (or accept a "record only while visible" model). This is the biggest
  behavioural gap.
- **Model size / download UX:** 120–650 MB per model over the web. Cache in
  OPFS/Cache API; show the same "recording starts immediately, text catches up"
  behaviour. Consider defaulting to smaller SenseVoice / Whisper-tiny.en for
  first load.
- **WebGPU availability:** Safari/Firefox coverage is improving but
  inconsistent; keep a solid CPU (WASM-SIMD) fallback and per-model size guidance.
- **`java.*` APIs in Kotlin/Wasm:** `Locale`, `Character.UnicodeScript`,
  `java.io.*` do not exist on wasmJs. Replace script detection with Unicode-range
  checks, language names with `Intl.DisplayNames`, and I/O with `expect`/`actual`.
  This is the main hidden cost of the KMP path.
- **OPFS requires a worker + secure context:** no `file://` usage; deploy over
  HTTPS (or localhost in development).

## Open questions

- Adopt KMP, or build a standalone TypeScript app?
- Ship translation in v1 (and which engine), or defer to v2?
- Accept the "tab must stay open" background-recording limitation, or invest in
  a more aggressive Wake Lock / notification strategy?
- Default model for first-run on the web (given download size), or keep
  `ParaKEET_V3`?
