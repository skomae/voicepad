# VoicePad — Keyboard output & on-device LLM transformer (Android)

Status: draft (planning only, no code yet).

## Goal

Give VoicePad a "type as keyboard" mode: the text the app already produces
(live transcript, or a finished note) is sent to another device as if it were
typed on a real keyboard, with an optional small on-device LLM that cleans up /
rewrites the text first.

Two new capabilities, both Android-only for now:

1. **Bluetooth HID keyboard emulation** — the phone advertises itself as a
   Bluetooth keyboard so a laptop, tablet, phone or any Bluetooth host can
   "pair a keyboard" and receive VoicePad's text as keystrokes. No extra
   hardware. USB output is explicitly out of scope (see below).
2. **A small LLM transformer** — runs on the phone, on top of the text,
   before it is typed out (fix ASR errors, punctuation, casing, or rewrite
   into a chosen style). Sized so it runs *alongside* the speech model,
   not instead of it.

## Decision

**Bluetooth HID keyboard only.** It works on a normal, unrooted Android phone
(public API: `BluetoothHidDevice`, API 30 / Android 11 for the non-deprecated
`registerApp` form), needs no extra
hardware, and the phone pairs to the *host* as a Bluetooth keyboard.

**USB is out of scope.** Android phones are USB *peripherals*, not gadgets, and
there is no public API for a USB HID function — doing it directly needs root +
a gadget/kernel config. If USB ever becomes a requirement, the fallback is a
tiny MCU bridge (Pi Pico / ESP32-S3 / Pro Micro), but that is not being planned
now.

---

## Feature summary

- **Settings → "Type as keyboard"**: toggle Bluetooth HID on/off, see pairing
  state, and read pairing instructions.
- **Per-note and live "Type now"** action: takes the note's text (or the
  live preview), optionally runs the LLM transform, then types it out.
- **Auto-type (optional, off by default)**: when a note finishes transcribing,
  type it automatically.
- **What to type**: original transcript, English translation (if present), or
  romanization/pinyin — reusing the existing translation + romanization.
- **LLM transformer (optional, off by default)**: a small GGUF instruct model
  cleans up the text before transmission. Style presets: *Keep as-is*,
  *Clean up*, *Formal email*, *Friendly message*, *Bullet points*. The user
  picks the model; the picker recommends one based on the phone's specs and
  the ASR model currently selected.

## Pipeline

```
transcript ──► "what to type" selector (original / English / romanization)
                │
                ▼
        LLM transformer (optional)
                │
                ▼
          HID key map (text → HID keyboard reports)
                │
                ▼
          BluetoothHidDevice.sendReport()
```

The first two stages already exist (`Note.currentText`,
`NoteTranslation.english`/`romanization`); the new work is the last three.

---

## Component mapping

New packages sit beside the existing `audio`, `data`, `transcription`, `ui`:

| New file | Responsibility |
| --- | --- |
| `transmit/KeyboardSink.kt` | `interface KeyboardSink` with `connect()`, `disconnect()`, `type(text)`, connection state. Mirrors the `SpeechEngine` pattern. |
| `transmit/BluetoothKeyboardSink.kt` | `BluetoothHidDevice` implementation. Registers the HID keyboard SDP record and sends reports. |
| `transmit/HidKeyMap.kt` | Pure, unit-tested: text → sequence of HID keyboard reports (modifiers + keycodes), handling shift, space, newline, and non-ASCII via the existing `Romanizer`. |
| `transmit/TransmitManager.kt` | Owns the sink, serialises `type()` calls, reports status into `UiState`. |
| `llm/LlmEngine.kt` | `interface LlmEngine { suspend fun transform(text: String): String; fun release() }`. |
| `llm/LlamaEngine.kt` | llama.cpp-backed implementation (GGUF, CPU/GPU). |
| `llm/LlmChoice.kt` | Enum of downloadable models (name, URL, size), like `EngineChoice`. |
| `llm/LlmModelManager.kt` | Download + cache one GGUF file, reusing the `ModelManager` download pattern. |
| `llm/LlmPrompt.kt` | Prompt templates + style presets. Pure and unit-tested. |
| `llm/DeviceSpecs.kt` | Reads device capabilities: total RAM (`ActivityManager.MemoryInfo`), core count, SoC model. |
| `llm/LlmGuidance.kt` | Pure, unit-tested: maps (specs, selected ASR model) → per-LLM recommendation with a human-readable reason. |

### Controller changes (`RecordingController.kt`)

- Add to `UiState`: `transmitEnabled`, `transmitStatus` (idle/connecting/ready/
  typing/error + message), `llmEnabled`, `llmStatus` (like `engineStatus`),
  `llmChoice`, `typeWhat` (original/english/romanization), `typeStyle`,
  `deviceSpecs` (total RAM, cores, SoC).
- Persist all toggles in the existing `settings` `SharedPreferences`.
- New methods: `setTransmitEnabled`, `setLlmEnabled`, `setLlmChoice`,
  `setTypeWhat`, `setTypeStyle`, `typeNote(noteId)`, `typeLiveNow()`.
- Expose `llmGuidance(choice)` from `LlmGuidance` so the UI can label each
  model "Recommended" / "OK" / "Tight" / "Not recommended" against the current
  ASR choice, with a short reason.
- `typeNote`/`typeLiveNow` run on the controller scope:
  `select text → (optional) llm.transform → sink.type` behind a transmit `Mutex`
  so only one typing session happens at a time.

### UI changes

- `Bubbles.kt`: add **"Type as keyboard"** to the note `DropdownMenu` and a
  small action icon; add a **type-now** button to `LiveBubble`.
- `Sheets.kt`: new **"Keyboard & LLM"** section in `SettingsSheet`:
  transmit toggle + status + pairing instructions, LLM toggle + model picker +
  style picker + "what to type" picker. The model picker shows each model's
  size, the per-device recommendation (`LlmGuidance`), and the reason (e.g.
  "6 GB RAM + SenseVoice — 1.5B will work but may stutter live transcription").
  The choice is always the user's; the label is guidance only.
- `MainActivity.kt`: request `BLUETOOTH_CONNECT` (API 31+) alongside
  `RECORD_AUDIO` in the existing permission launcher.

---

## Bluetooth HID keyboard — details

### API

- `BluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)`
  to get a `BluetoothHidDevice`.
- `BluetoothHidDevice.registerApp(BluetoothHidDeviceAppSdpSettings(...), null, null, executor, callback)`
  (the `Executor` overload is the only one still exposed at compileSdk 36)
  with the HID keyboard report descriptor (see below).
- `BluetoothHidDevice.Callback` reports `onAppStatusChanged`, `onConnectionStateChanged`,
  `onGetReport`, `onSetReport`, etc.
- Type via `BluetoothHidDevice.sendReport(device, reportId, data)`; disconnect
  with `disconnect(device)`, teardown with `unregisterApp()`.

### Report descriptor

Use the standard **boot keyboard** HID report descriptor (the same one every
off-the-shelf Bluetooth keyboard advertises). It describes an 8-byte input
report: 1 modifier byte, 1 reserved byte, 6 keycode slots. This maximises host
compatibility (Windows/macOS/iOS/Android all accept it without drivers).

Reference descriptor (Usage Page 0x01 → Keyboard, Usage Page 0x07 → Keypad,
6KRO array of up to 6 concurrent keycodes). The exact bytes live in a constant
in `BluetoothKeyboardSink.kt`, copied from the USB HID spec / any boot keyboard
example.

### Key map (`HidKeyMap`)

- Map each character to `(modifier, keycode)` using the **USB HID Usage Tables**
  keypad page (0x07): letters `a–z` → `0x04–0x1D`, digits → `0x1E–0x27`,
  Enter → `0x28`, Space → `0x2C`, punctuation with Shift, etc.
- Each character becomes a key-down report (modifier + keycode) followed by a
  key-up report (all zeros), paced ~5–10 ms apart so no host drops reports.
- **Non-ASCII limitation:** a boot keyboard only types the host's US layout.
  Non-Latin text is typed as its romanization/pinyin via the existing
  `Romanizer` (so 银行 is typed `yín háng`), rather than attempting Unicode HID
  reports, which almost no host supports. This is a deliberate, documented
  behaviour, not a bug.
- Unit tests (`HidKeyMapTest`) cover shift handling, newlines, pacing-relevant
  grouping, and the romanization fallback.

### Pairing UX (important)

The phone *cannot* initiate the connection in this role; the **host** pairs to
the phone as a keyboard. So the on-screen instructions are:

1. Open Settings → Keyboard & LLM → enable **Type as keyboard**.
2. On the computer/tablet, go to Bluetooth → *Add device* → *Keyboard*.
3. Select **VoicePad** and confirm.

`TransmitManager` surfaces `onConnectionStateChanged` as `transmitStatus`
("Ready — connected to MacBook Pro", "Advertising — waiting for a device",
"Typing…", "Not supported on this phone").

### Limitations / risks

- **Not all devices support the HID Device profile.** Detect via the
  `registerApp` result and show "This phone can't act as a Bluetooth keyboard".
- **Older than Android 11 (API 30):** feature disabled (app `minSdk` is 26, so
  gate it and show a message).
- **Pairing is host-driven**, so the flow is more awkward than the mic chooser.
  Mitigate with clear instructions and live status.
- **Report pacing** matters: too fast and hosts drop keys. Start conservative
  (~8 ms) and make it configurable.

---

## USB bridge (out of scope)

If USB-only hosts ever matter, the phone can't be the USB device itself, so the
fallback would be a ~$5 MCU bridge (Pi Pico / ESP32-S3 / Pro Micro) presenting a
USB HID keyboard to the host while the phone streams text to it over BLE or
USB-OTG serial. `HidKeyMap` and the `KeyboardSink` interface were designed so
this could be added later, but it is not part of this plan.

---

## On-device LLM transformer

### Engine choice

| Option | Notes | Verdict |
| --- | --- | --- |
| **llama.cpp (GGUF)** | Runs 0.5B–1.5B quantised models on CPU/GPU on any phone; de-facto standard for on-device LLMs; supports mmap so it shares RAM efficiently with the ASR model. | ✅ **Recommended** |
| MediaPipe LLM / Gemini Nano (AICore) | Best quality but only on a subset of Pixel/Samsung devices; too narrow a target. | ❌ Device coverage |
| ONNX Runtime / ExecuTorch | Good for tiny decoder models, but GGUF via llama.cpp is more mature for quantised LLM text-gen. | Backup |

### Instruction-following models (Qwen / Gemma)

Favour families with strong instruction adherence (Qwen, Gemma), and pick the
**smallest** model from each that still follows the cleanup/rewrite instruction
reliably. All run in Q4 GGUF:

| Model | Approx. Q4 size | RAM at inference (≈) | Notes |
| --- | --- | --- | --- |
| **Qwen2.5-0.5B-Instruct** | ~0.4 GB | ~0.6 GB | Default — very good adherence for its size |
| **Qwen2.5-1.5B-Instruct** | ~0.9 GB | ~1.2 GB | Better rewrite quality; still light |
| **Gemma-3-1B-it** | ~0.6 GB | ~0.9 GB | Strong adherence, good multilingual cleanup |
| Gemma-2-2B-it | ~1.6 GB | ~2.0 GB | Best quality, but heavy to run beside ASR on mid phones |

**There is no fixed default — the user chooses**, and the picker recommends one
based on their device (see below). **2B+ is not offered** — it eats the RAM the
speech model needs on a phone.

### Device-aware model guidance

The picker recommends a model from the user's own hardware, not a static
default:

- **`DeviceSpecs`** reads `ActivityManager.MemoryInfo.totalMem` (or
  `ActivityManager.memoryClass` as a floor), the CPU core count (already used by
  `SherpaEngine`), and the SoC name (`Build.SOC_MODEL` / `Build.HARDWARE`).
- **`LlmGuidance.recommend(specs, asrChoice, candidates)`** is a pure function
  that returns a verdict per model and a human-readable reason. It budgets:

  `available ≈ totalRam − OS/foreground (~2 GB) − ASR footprint − llmInferenceRam`

  and maps the result to **Recommended** (comfortable headroom), **OK**,
  **Tight** (works, but may stutter live transcription), and **Not recommended**
  (likely to be killed / starve ASR). Ties go to the smallest model with
  acceptable quality.
- The ASR footprint is taken from the **currently selected** `EngineChoice`, so
  the guidance updates live if the user switches ASR models (e.g. picking
  Whisper turbo tightens the LLM budget).
- The UI shows the verdict + reason next to each model and lets the user
  override freely — the recommendation is advice, never a lock.

### Running the LLM alongside the speech model

The hard constraint is that the **ASR model and the LLM may both be resident at
once**, so the budget is shared, not additive-on-demand:

- **Memory**: ASR (SenseVoice ~165 MB → Whisper turbo ~560 MB weights, plus
  sherpa-onnx runtime and audio buffers) already sits in native RAM. llama.cpp
  opens GGUF with **mmap**, so the LLM's weights are demand-paged and can share
  pages; budget the LLM's *KV cache + activations* on top (~0.2–0.4 GB for a
  0.5B–1.5B model), not the full file size.
- **Threads**: sherpa-onnx already takes 2–4 threads (`SherpaEngine`). Run
  llama.cpp with **2 threads** (or 1 while recording) so a live transcription
  tick is never starved by a transform.
- **Priority / scheduling**: the LLM transform runs only on demand
  (`typeNote` / `typeLiveNow` / auto-type at note finish), never on the live
  preview tick, and behind its own `llmMutex` (same pattern as `engineMutex`).
  If a recording is in progress, the transform runs at lower priority; if the
  device is tight, defer it until recording stops.
- **Failure is non-blocking**: if the LLM can't load or is uninstalled to free
  space, type the untransformed text. The keyboard must never wait on the LLM.

### Integration

- Compile **llama.cpp** for `arm64-v8a` (and `x86_64` for the emulator) via
  CMake/NDK, exposing a minimal JNI surface: `load(path)`, `complete(prompt)`,
  `release()`. Keep it behind `LlamaEngine` so the rest of the app never sees
  JNI.
- Alternatively, if a maintained prebuilt `llama.cpp` AAR exists, fetch it in
  `build.gradle.kts` exactly like the sherpa-onnx AAR is fetched today — this is
  the lower-effort path and should be checked first.
- **Models** are single GGUF files, so `LlmModelManager` is simpler than the
  existing tar.bz2 `ModelManager`: `fetch()` to a temp file, fsync, rename,
  write a `.complete` marker, with the same progress + discard-on-failure UX.

### Prompt & style

`LlmPrompt` builds a small system prompt from the chosen style:

```
System: You are a careful editor. Correct speech-recognition mistakes,
punctuation and capitalisation. Keep the meaning and language. Do not add or
remove information. Reply with only the corrected text.
User: <text>
```

Style presets only change the system instruction (e.g. *Formal email* adds
"rewrite as a concise formal email"). Sampling is greedy/low-temperature
(≈0) for deterministic output, `n_predict` capped at ~512 tokens.

### Integration points

- `typeNote`/`typeLiveNow` call `llm.transform(text)` before `sink.type(...)`.
- The transform runs on `Dispatchers.Default` behind an `llmMutex` (same pattern
  as the existing `engineMutex`), and is skipped when the LLM is disabled or
  not ready.
- Add a fallback: if the LLM fails to load, type the untransformed text and
  surface the failure once — never block typing on the LLM.

---

## Manifest & permissions

```xml
<!-- HID keyboard emulation -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" /> <!-- API 31+ runtime -->
<uses-feature android:name="android.hardware.bluetooth" android:required="false" />
```

- `BLUETOOTH_CONNECT` is a runtime "Nearby devices" permission on API 31+;
  request it in `MainActivity`'s existing `wanted` list.
- Keep everything optional so the feature is invisible on devices without
  Bluetooth or below API 30.

---

## Suggested phases

1. **Spike (1 day):** `BluetoothHidDevice` registers a boot keyboard, and a
   hard-coded `HidKeyMap` types "hello" into a laptop. *Go/no-go on device
   support and report pacing.*
2. **Core typing (2–3 days):** `HidKeyMap` (with tests) + `BluetoothKeyboardSink`
   + `TransmitManager` + `typeNote`/`typeLiveNow` + UI action + settings toggle
   and pairing instructions.
3. **LLM spike (1–2 days):** llama.cpp JNI (or prebuilt AAR) loading the
   Qwen2.5-0.5B GGUF and running the cleanup prompt on-device, **with the ASR
   model loaded at the same time**. Validate latency, memory and thread budget.
4. **LLM pipeline (2–3 days):** `LlmChoice` + `LlmModelManager` + `LlamaEngine`
   + `LlmPrompt` styles + "what to type" selector, wired into the transmit path
   and Settings, including the "run at lower priority while recording" rule and
   the `DeviceSpecs`/`LlmGuidance` recommendation in the model picker.

## Risks and mitigations

- **HID Device profile support varies by OEM.** Detect and show a clear
  "not supported" message; don't crash. Keep the feature off by default.
- **Pairing is host-driven** (awkward UX). Mitigate with step-by-step
  instructions and a live status readout.
- **Report pacing drops keys on some hosts.** Start slow, test across
  Windows/macOS/iOS, make the delay a setting if needed.
- **LLM + ASR contention (the main new risk).** A transform competing with a
  live transcription tick for CPU/RAM could stutter live text. Mitigate: small
  mmap'd GGUF, 2 llama.cpp threads, transforms only on demand, lower priority
  while recording, and never block typing on the LLM.
- **Non-ASCII output.** Boot keyboard is US-layout; type romanization/pinyin
  via the existing `Romanizer` and document it.
- **Battery/thermal** from llama.cpp on long rewrites. Cap `n_predict` (~512),
  greedy sampling, run short transforms, and stop the LLM's threads when idle.

## Open questions

- Default "what to type": transcript, English translation, or romanization?
- Should auto-type after transcription be opt-in at first launch?
- Guidance thresholds: how much RAM headroom should separate "Recommended"
  from "Tight", and what OS/foreground reserve (1.5 GB vs 2 GB) is realistic?
- Should the recommendation re-check after the user picks a heavier ASR model
  (and warn if the current LLM choice no longer fits)?
