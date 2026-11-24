# VoicePad

The Android app lives in [`android/`](android/README.md); this README covers
the iOS app.

An iOS app that **starts recording the moment you open it** and transcribes
your speech on-device into a chat-style bubble. Each bubble can be copied,
edited, and keeps a full revision history.

## Features

- **Instant capture** – recording begins on launch (and whenever the app is
  brought back to the foreground). Toggle this off in Settings if you prefer.
  Recording continues in the background.
- **On-device transcription**, selectable in Settings:
  - **Auto: Western** (default) – Parakeet TDT v3: English, French, Spanish,
    German and 21 more European languages, switching per sentence.
  - **Auto: Asian + English** – per sentence, SenseVoice decides: Mandarin,
    Cantonese, Japanese and Korean keep SenseVoice's text; English goes to
    Parakeet.
  - Single models: Parakeet v2 (English) and SenseVoice via
    [FluidAudio](https://github.com/FluidInference/FluidAudio); Whisper
    tiny / base / small (English), small (multilingual) and large-v3-turbo via
    [WhisperKit](https://github.com/argmaxinc/WhisperKit).

  Models download once on first use. Audio is captured right away regardless;
  the text catches up when the model is ready.
- **Silence trimming and pause-based splitting**: the Silero voice-activity
  model (via FluidAudio, a ~2 MB download on first launch) finds speech.
  Silence never reaches the model (no Whisper hallucinations on quiet
  stretches, less battery); Parakeet and SenseVoice get one call per
  utterance, and Whisper gets utterances packed into ≤25 s chunks split at the
  **longest pauses**. Committed audio is dropped from memory and saved
  recordings are streamed from disk, so length is limited only by storage.
- **Live bubble** – each utterance is committed as soon as its pause is
  detected, and the sentence in progress updates as you speak.
- **Live translation** – each finished utterance is translated right away and
  the sentence in progress is re-translated as its text changes, so the live
  bubble shows original, pinyin and English. Mixed Mandarin/English reads as
  one English text, and the finished note keeps it.
- **Split** (scissors next to the record button) – like a stopwatch lap: ends
  the current note and starts a new one at that instant without stopping the
  microphone. The finished part transcribes in the background while you keep
  talking. Stop never blocks starting a new recording.
- **Clear recording state** – while recording the navigation bar turns red
  with a pulsing REC, the elapsed time and the part number, the notes get a
  red frame, and the bar under the button says "Recording — tap ■ to stop".
  When idle it says "Not recording" and the button is a red dot in a ring.
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
- **Auto-split** (touch and hold the scissors): a new note after 2–30 s of
  silence or every 5/15/30 minutes (at the next pause); never splits off a
  note with nothing said. A badge on the scissors shows the rule.
- **Copy when done**: each finished recording or split-off part goes to the
  clipboard (Settings).
- **Theme**: System, Light, Dark or Pure black (OLED).
- **Conversation mode** (⋯ → Conversation mode): live two-way translation
  between two languages in big text. Each utterance is transcribed, assigned
  to one of the two languages and translated into the other; speech in
  progress is previewed. A sentence that stops mid-way ("…and", 然后) is held
  open for a thinking pause (a ring on the mic drains) before it's
  translated. Speakers are told apart by voice (FluidAudio's speaker model,
  downloaded once) or by language. Translations can be read aloud with the
  phone's voices; the mic ignores the phone while it speaks. Layouts: across
  (top/bottom or left/right, the far half upside down), side by side, or a
  transcript. Each session is saved as a note. Not on iOS yet: one mic
  channel per person, and the foldable cover screen.
- **Export all notes** (⋯): a ZIP with `notes.json`, `notes.md` and
  optionally the recordings, shared to Files, AirDrop and so on.
- **Archive and clean up**: archived notes leave the list (search still finds
  them) with their audio compressed to AAC (~10× smaller, checked before the
  WAV is deleted). Clean up archives, compresses or deletes notes by age, or
  deletes empty ones; pinned and busy notes are protected.
- **Background voices** (ear button): room microphone, evened-out loudness
  and Whisper turbo, for announcements or a TV across the room. **Model
  switcher**: tap the model name, top left.
- **Live Activity** on the Lock Screen and Dynamic Island while recording,
  with Stop and Split.
- **Your edits are final**: a transcription that finishes later goes behind
  your edit in the history; an empty result never replaces text.
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

### Install on your iPhone

1. Xcode → Settings → Accounts → add your Apple ID. A free account works
   (the app then expires after 7 days; running it from Xcode again renews
   it); a paid developer account lasts a year and allows TestFlight.
2. Create `Config/Local.xcconfig` (not committed, survives
   `xcodegen generate`) with your team ID (shown in Accounts) and, with a
   free account, a bundle ID of your own:

   ```
   DEVELOPMENT_TEAM = ABCDE12345
   VOICEPAD_BUNDLE_ID = com.yourname.voicepad
   ```
3. Plug in the iPhone, unlock it and tap Trust. Turn on Settings → Privacy &
   Security → Developer Mode and restart.
4. `xcodegen generate`, open `VoicePad.xcodeproj`, pick your iPhone as the
   run destination and press Run. With a free account, trust yourself under
   Settings → General → VPN & Device Management the first time.

The first launch downloads the speech model (about 500 MB), so use Wi-Fi.
Keep the same team and bundle ID afterwards: changing either installs a new
app with empty data, and the models download again. The simulator runs everything except live recording if the Mac's
audio input isn't reachable from it, and Apple's Translation framework
doesn't translate there.

Unit tests (crash recovery, WAV, search, pinning, pinyin, language
detection, translation state, chunk planning, joining live translations):

```sh
xcodebuild test -project VoicePad.xcodeproj -scheme VoicePad \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

CI (`.github/workflows/ios-build.yml`) builds for the simulator, runs the unit
tests and produces an unsigned `.ipa`. It is manual-only for now (no macOS
runner budget yet); start it from the Actions tab, or restore its `push` and
`pull_request` triggers to run it automatically.

## Layout

```
VoicePad/
  App/            App entry + RecordingController (recording ⇄ live transcription)
  Audio/          AVAudioEngine capture → 16 kHz mono Float32, crash-safe WAV, mic selection
  Transcription/  TranscriptionEngine protocol, Whisper, Parakeet + SenseVoice engines
  Translation/    Language detection, pinyin/romanisation, translation
  Conversation/   Conversation mode: turns, stage, languages, voices, read-aloud
  Models/         Note / Revision / status + JSON persistence, search
  Views/          SwiftUI bubbles, record bar, edit, history, settings
VoicePadTests/    Unit tests (pure model code, no host app)
```
