# VoicePad for Android

Same behaviour as the iOS app: **recording starts the moment the app opens**,
speech is transcribed on-device into a live bubble, and every bubble can be
copied, edited, re-transcribed and rolled back through its revision history.

- **Models** (Settings → Transcription model), all run on-device through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):
  Parakeet TDT v3 (default; 25 European languages), Parakeet TDT v2 (English),
  SenseVoice (Mandarin, Cantonese, English, Japanese, Korean),
  Whisper tiny.en / base.en / small.en, Whisper small (multilingual) and
  large-v3 turbo (multilingual).
  Each is downloaded once on first use (≈120–640 MB). Recording starts
  immediately; text catches up once the model is ready.
- **Background recording** continues via a foreground service with an
  ongoing notification.
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
