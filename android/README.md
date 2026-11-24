# VoicePad for Android

Same behaviour as the iOS app: **recording starts the moment the app opens**,
speech is transcribed on-device into a live bubble, and every bubble can be
copied, edited, re-transcribed and rolled back through its revision history.

- **Models** (Settings → Transcription model), all run on-device through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):
  - **Auto: Western** (default) — Parakeet TDT v3: English, French, Spanish,
    German and 21 more European languages, switching per sentence.
  - **Auto: Asian + English** — per sentence, SenseVoice decides: Mandarin,
    Cantonese, Japanese and Korean keep SenseVoice's text; English goes to
    Parakeet.
  - Single models: Parakeet v2 (English), SenseVoice, Whisper tiny.en /
    base.en / small.en, Whisper small (multilingual), large-v3 turbo.

  Each downloads once on first use. Recording starts immediately; text
  catches up once the model is ready.
- **Quick model switcher**: tap the model name (top left) to switch between
  models already on the phone, e.g. Auto: Western ↔ Auto: Asian + English.
- **Background voices** (ear button, top bar; also in Settings and the
  switcher): for announcements, a TV or someone across the room. Records
  the room instead of just your voice (raw or camcorder microphone, noise
  suppression off), evens out loudness, and switches to Whisper turbo. In
  testing on distant, echoey speech with background noise, Whisper turbo
  made 10% word errors where Parakeet made 41% (30% vs 65% in heavier
  noise). A speech-enhancement model (GTCRN) was tried and left out: it made
  results worse. Works mid-recording; notes remember the mode so
  re-transcription treats them the same way.
- **Silence trimming and pause-based splitting**: a bundled Silero
  voice-activity detector finds speech. Silence never reaches the model (no
  Whisper hallucinations on quiet stretches, less battery); Parakeet and
  SenseVoice get one call per utterance, and Whisper gets utterances packed
  into ≤25 s chunks split at the **longest pauses**. Committed audio is
  dropped from memory and saved recordings are streamed from disk, so
  recording length is limited only by storage.
- **Live translation**: while you speak, each finished utterance (detected
  at its pause) is translated and the sentence still in progress is
  re-translated as its text updates, so the live bubble shows original,
  pinyin and English side by side. Mixed Mandarin/English recordings read
  as one English text; the finished note keeps that translation.
- **Split** (scissors next to the record button): like a stopwatch lap —
  ends the current note and starts a new one at that instant without
  stopping the microphone, so nothing is lost between them. The finished
  part transcribes in the background while you keep talking.
- **Theme** (Settings): System, Light, Dark or **Pure black (OLED)** —
  true #000000 background, bars and sheets, with cards on near-black so they
  stay readable. Status-bar icons follow the app's theme.
- **Copy when done** (Settings, on by default): after you press Stop and the
  transcript is finished, it's copied to the clipboard with a toast. Split
  parts aren't copied, so auto-split doesn't keep overwriting the clipboard.
- **Auto-split**: drag up on the scissors (or long-press) to pick a rule —
  after 10 s / 30 s of silence, or every 5 / 15 / 30 minutes (at the first
  pause after the interval, or a minute later regardless). Release on an
  option to choose it; drag back to the button to cancel. A badge on the
  scissors shows the active rule. A note with nothing said in it is never
  split off. Tap still splits immediately.
- **Clear recording state**: while recording, the top bar turns red with a
  pulsing REC, elapsed time and part number, the screen gets a red frame,
  and the bar under the button says "Recording — tap ■ to stop". When idle
  it says "Not recording" and the button shows a red dot in a ring.
- **Live notification** while recording: elapsed timer, the latest words
  (expandable), and **Stop** / **Split** buttons; on Android 16 it's also a
  **Live Update** with a "REC" chip in the status bar. After you stop it
  shows "Finishing transcript…" until background transcription is done,
  which also keeps Android from killing that work.
- **Recordings are never lost to a model failure.** Every note saves its
  transcription status (recording → pending → transcribing → done/failed).
  Audio stays on disk until it has text; failed or waiting recordings show
  *Retry* (and *Retry all*), and are retried automatically when a model
  becomes ready — up to 3 times, so a model that crashes the app can't trap
  it in a loop. The WAV header is checkpointed every ~5 s, so even a recording
  interrupted by a crash or a killed app is repaired and transcribed on the
  next launch.
- **Play or share** any recording (share sends the WAV plus its transcript,
  e.g. to Drive or email). Deleting asks for confirmation.
- **Auto-translation to English** (Settings, on by default): the spoken
  language is detected (from the speech model, falling back to ML Kit's text
  language ID) and non-English notes show the English translation next to
  the original — side by side when there's room, stacked on a phone held
  upright — plus **pinyin with tone marks** for Mandarin (with corrections
  for common words ICU reads character-by-character, e.g. 银行 *yín háng*)
  and Latin transliteration for other non-Latin scripts. Translation runs on
  the phone with Google ML Kit; each language's pack (~30 MB) downloads once.
  Translations are saved per revision, redone when the text is edited,
  retried like transcriptions if they fail, and included in search.
  For Mandarin use SenseVoice or a multilingual Whisper — Parakeet doesn't
  understand Chinese.
- **Microphone choice** (Settings → Microphone): see every connected mic
  (built-in, wired, USB, Bluetooth headset), which one is recording right
  now, and pick one — or leave it on *Automatic*. The choice is remembered;
  if that mic is unplugged the phone's default is used, and recording
  switches back when it reconnects.
- **Archive**: long-press a note → Archive. It leaves the main list (search
  still finds it; ⋮ → Archived notes lists them) and its audio is compressed
  from WAV to AAC (~1.9 → ~0.18 MB a minute). The compressed copy is checked
  for the right length before the WAV is deleted; if anything is off the
  note is left untouched. Archived notes can still be played, shared,
  unarchived and re-transcribed.
- **Clean up** (⋮ → Clean up…), for notes older than 1, 7 or 30 days or
  any age:
  - **Archive** unpinned notes (hidden, audio compressed);
  - **Compress audio** of every uncompressed recording, pinned ones too,
    keeping them in the list — shows the space before → after;
  - **Delete empty** "No speech detected" notes and their audio (never ones
    still waiting for or retrying transcription);
  - **Delete** unpinned notes.
  Notes being recorded or transcribed are always skipped.
- **Your edits are final**: editing or restoring a note marks it done, a
  transcription that finishes afterwards goes into the history behind your
  edit, and an empty result never replaces text.
- **Pin** notes to keep them in a *Pinned* section at the top.
- **Full-text search** across every note — current text, every earlier
  revision and text recovered from unfinished transcriptions. All terms must
  match; `"quoted phrases"` match as written; case and accents are ignored.
  Matches are highlighted.
- Notes are stored in `notes.json` (with a `.bak` copy) and audio as 16 kHz
  WAV files in the app's private storage. **Uninstalling the app deletes
  them** — share anything you want to keep first.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```sh
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # signed with the debug key unless the env vars below are set
./gradlew testDebugUnitTest    # recovery, search, pinning, mic naming, pinyin and translation tests
```

The sherpa-onnx library (`app/libs/sherpa-onnx-2.0.3.aar`) is downloaded
automatically from GitHub Releases on the first build.

## Releases

`.github/workflows/android-release.yml` builds on every push/PR that touches
`android/`. To publish an APK to **GitHub Releases**, either push a tag:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

or run the **Android** workflow from the Actions tab and enter a version.

### Signing (one-time setup)

Without signing secrets the release APK uses a throwaway debug key, which
installs fine but can't be updated in place by a later build. To sign
properly, create a keystore once and keep it safe — losing it means users
must uninstall to update:

```sh
keytool -genkeypair -v -keystore voicepad-release.jks -alias voicepad \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 voicepad-release.jks   # macOS: base64 -i voicepad-release.jks
```

Then add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 output above |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `voicepad` |
| `ANDROID_KEY_PASSWORD` | key password |
