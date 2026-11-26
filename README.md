# voicepad

On-device voice notes: recording starts the moment the app opens, speech is
transcribed locally into live bubbles, silence is trimmed and pauses split
utterances.

- [`android/`](android/README.md) — the Android app (sherpa-onnx: Parakeet,
  SenseVoice, Whisper; live translation; conversation mode).
- [`web/`](web/README.md) — browser/PWA demo of the same flow with the smallest
  English model (Whisper tiny.en, ~41 MB), installable and offline-capable:
  <https://voicepad.fr1.x0t.cc>.
- [`docs/wasm-plan.md`](docs/wasm-plan.md) — how the web version maps onto the
  Android architecture, and what is still open.
