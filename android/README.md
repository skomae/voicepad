# VoicePad for Android

Same behaviour as the iOS app: **recording starts the moment the app opens**,
speech is transcribed on-device into a live bubble, and every bubble can be
copied, edited, re-transcribed and rolled back through its revision history.

- **Models** (Settings → Transcription model), all run on-device through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx):
  Parakeet TDT v3 (default, multilingual), Parakeet TDT v2 (English),
  Whisper tiny.en / base.en / small.en / large-v3 turbo.
  Each is downloaded once on first use (≈120–640 MB). Recording starts
  immediately; text catches up once the model is ready.
- **Background recording** continues via a foreground service with an
  ongoing notification.
- Notes are stored in `notes.json` and audio as 16 kHz WAV files in the app's
  private storage.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```sh
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # signed with the debug key unless the env vars below are set
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
