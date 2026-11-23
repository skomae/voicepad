# VoicePad

An iOS app that **starts recording the moment you open it** and transcribes
your speech on-device into a chat-style bubble. Each bubble can be copied,
edited, and keeps a full revision history.

## Features

- **Instant capture** – recording begins on launch (and whenever the app is
  brought back to the foreground). Toggle this off in Settings if you prefer.
  Recording continues in the background.
- **On-device transcription**, selectable in Settings:
  - **Parakeet TDT v3** (default, multilingual) and **v2** (English) via
    [FluidAudio](https://github.com/FluidInference/FluidAudio)
  - **Whisper** tiny / base / small / large-v3-turbo via
    [WhisperKit](https://github.com/argmaxinc/WhisperKit)

  Models download once on first use. Audio is captured right away regardless;
  the text catches up when the model is ready.
- **Live bubble** – text streams in while you speak. Audio is processed in
  ~25 s chunks cut at the quietest moment, so long recordings stay fast.
- **Copy / edit / history** – tap a bubble (or use its buttons / long-press
  menu) to edit. Every save adds a revision; the history sheet shows every
  version with its source (transcribed, edited, restored, re-transcribed) and
  lets you copy or restore any of them.
- **Re-transcribe** a saved recording with a different model; the result is
  added as a new revision.

Everything stays on the device: notes in `Documents/notes.json`, audio as
16 kHz WAV in `Documents/Recordings/`.

## Building

Requires Xcode 16+ and iOS 17+.

```sh
brew install xcodegen
xcodegen generate
open VoicePad.xcodeproj
```

Pick your development team under *Signing & Capabilities*, then run on a
device (the simulator works too, using your Mac's microphone).

CI (`.github/workflows/ios-build.yml`) builds for the simulator and produces
an unsigned `.ipa` artifact on every push.

## Layout

```
VoicePad/
  App/            App entry + RecordingController (recording ⇄ live transcription)
  Audio/          AVAudioEngine capture → 16 kHz mono Float32 + WAV file
  Transcription/  TranscriptionEngine protocol, Whisper + Parakeet engines
  Models/         Note / Revision + JSON persistence
  Views/          SwiftUI bubbles, record bar, edit, history, settings
```
