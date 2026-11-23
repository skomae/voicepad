# VoicePad

An iOS app that **starts recording the moment you open it** and transcribes
your speech on-device into a chat-style bubble. Each bubble can be copied,
edited, and keeps a full revision history.

## Features

- **Instant capture** – recording begins on launch (and whenever the app is
  brought back to the foreground). Toggle this off in Settings if you prefer.
  Recording continues in the background.
- **On-device transcription**, selectable in Settings:
  - **Parakeet TDT v3** (default, 25 European languages) and **v2** (English),
    and **SenseVoice** (Mandarin, Cantonese, English, Japanese, Korean) via
    [FluidAudio](https://github.com/FluidInference/FluidAudio)
  - **Whisper** tiny / base / small (English), small (multilingual) and
    large-v3-turbo via [WhisperKit](https://github.com/argmaxinc/WhisperKit)

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
- **Recordings are never lost to a model failure.** Each note saves its
  transcription status (recording → pending → transcribing → done/failed)
  with attempts, last error and partial text. The WAV header is rewritten and
  synced every ~5 s, so on the next launch a recording cut off by a crash is
  repaired and queued, and a transcription interrupted mid-run is marked
  failed rather than retried in a loop. Waiting recordings are retried
  automatically when a model becomes ready (up to 3 attempts), or per note /
  with **Retry all**. `notes.json` keeps a `.bak` copy.
- **Translate to English** (iOS 18+, on by default): the spoken language is
  detected (speech model tag, NaturalLanguage, Chinese-character check) and
  non-English notes get an on-device English translation via Apple's
  Translation framework, plus pinyin with tone marks for Mandarin (or Latin
  transliteration for other scripts). Translations are saved per revision,
  redone after edits, and retried like transcriptions.
- **Pin** notes into a Pinned section; **play** or **share** any recording
  (WAV plus transcript); deleting asks first.
- **Full-text search** over current text, translations, pinyin (tones
  optional), earlier revisions and partial text. All terms must match,
  `"quoted phrases"` match as written, case and accents are ignored, matches
  are highlighted, and a match only in an older version links to its history.
- **Microphone picker** in Settings: shows the mic in use, lets you choose
  one, and switches back when a chosen mic is reconnected.

Everything stays on the device: notes in `Documents/notes.json`, audio as
16 kHz WAV in `Documents/Recordings/`.

## Building

Requires Xcode 16+ and iOS 17+ (translation needs iOS 18).

```sh
brew install xcodegen
xcodegen generate
open VoicePad.xcodeproj
```

Pick your development team under *Signing & Capabilities*, then run on a
device. The simulator runs everything except live recording if the Mac's
audio input isn't reachable from it, and Apple's Translation framework
doesn't translate there.

Unit tests (crash recovery, WAV, search, pinning, pinyin, language
detection, translation state):

```sh
xcodebuild test -project VoicePad.xcodeproj -scheme VoicePad \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

CI (`.github/workflows/ios-build.yml`) builds for the simulator and produces
an unsigned `.ipa` artifact on every push.

## Layout

```
VoicePad/
  App/            App entry + RecordingController (recording ⇄ live transcription)
  Audio/          AVAudioEngine capture → 16 kHz mono Float32, crash-safe WAV, mic selection
  Transcription/  TranscriptionEngine protocol, Whisper, Parakeet + SenseVoice engines
  Translation/    Language detection, pinyin/romanisation, English translation
  Models/         Note / Revision / status + JSON persistence, search
  Views/          SwiftUI bubbles, record bar, edit, history, settings
VoicePadTests/    Unit tests (pure model code, no host app)
```
