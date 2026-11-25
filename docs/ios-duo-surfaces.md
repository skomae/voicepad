# iOS "duo" surfaces

The foldable work on Android gave VoicePad a second screen relationship with the
phone: laptop/tabletop posture, a cover screen for the person opposite, and fold
state driving conversation mode. iPhone has no hinge, but it has four other ways
to be a second surface — the Live Activity, StandBy, the Watch and the Even G1
glasses. This file records which foldable ideas map where, what is already in the
iOS app, and what each piece of work involves.

Worktree: `/Users/skomae/projects/voicepad-ios-duo`, branch `ios/duo-surfaces`,
based on `ios/android-parity` (another agent works there — never edit it in place).

## What the foldable work did (Android reference)

| Idea | Android |
|---|---|
| Laptop/tabletop posture: transcript + LLM interpretation above the hinge, control pad below, amber PAUSED when stopped | `ui/TabletopScreen.kt`, `MainActivity.tabletopHinge` |
| Fold state drives conversation mode; unfolding opens it, folding returns to notes | `MainActivity` → `ConversationController.onFoldStateChanged` |
| Cover screen shows the opposite person their side of the conversation | `ui/ConversationScreen.kt` (`CoverScreen`) |
| Book posture rotates the app so the hinge runs across | `MainActivity.rotateForLaptop` |
| Control pad: prev/next note, auto-send cycle, split, send original / prompt 1 / prompt 2 | `docs/android-keyboard-and-llm-plan.md:392` |

## What the iPhone Duo actually gives us (measured, iOS 27.2 simulator)

`xcrun simctl list devicetypes` has an **iPhone Duo** (`modelIdentifier iPhone19,4`,
iOS 27.1+). Its profile (`/Library/Developer/CoreSimulator/Profiles/DeviceTypes/iPhone Duo.simdevicetype`)
declares two integrated displays plus a TV-out, a CarPlay screen and a resizable scene:

| Display | Size | Notes |
|---|---|---|
| `LCD` (screenID 1, "primary") | 1398×2034 @3x = 466×678 pt | corner radii 8/59/8/59 — the two square-ish corners are the hinge side |
| `LCD-1` (screenID 3, "primary-1") | 2007×2853 @3x = 669×951 pt | uniform 55 pt radius, `nativeRotation` 270 (inner, unfolded) |

Run on the simulator folded, the app reports:

    duo: screens=1 [466.0x678.0 @3.0] scene=466x678 insets=0,34,0,84

- Only one `UIScreen` exists at a time: the inner display is not addressable as a
  second screen (`simctl io --display=3` is pure black while folded), so the
  "cover screen shows the other person" idea cannot be two live windows on iOS.
- The hinge shows up as **geometry**: the scene is the outer display and 84 pt on
  its right edge is an inset — the part of the canvas the other half covers.
- There is no fold/hinge/posture symbol in the iOS 27.2 SDK: `grep -c "Hinge|Posture"`
  in SwiftUI's swiftinterface is 0, same for UIKit headers. Posture has to be
  inferred from window size, safe-area insets and scene resizes — which is enough
  for everything below, but there is no angle to read.

So the mapping is: small window with a large side inset = folded (outer); wide
window (~669–951 pt) = unfolded (inner); a scene resize between the two = the fold
event that opens or closes conversation mode.

## What iOS already has

- Conversation stage in four layouts, including the rotated-180 "their side" pane
  (`Views/ConversationView.swift`: `faceToFace`, `faceToFaceSideways`,
  `sideBySide`, `transcript`). This is the cover-screen idea already ported to a
  single slab.
- Live Activity with Dynamic Island, timer, part, latest transcript words and
  interactive Stop/Split intents (`Shared/RecordingActivity.swift`,
  `Widgets/RecordingLiveActivity.swift`, `App/LiveActivityPresenter.swift`).
- Widget extension target (`VoicePadWidgets`), no other widgets yet.
- Translation pipeline: `NoteTranslation` (language, `english`, romanization).

## 1. Live Activity as the cover screen — DONE on this branch

Today `ContentState.latest` carries only the source-language line, so a phone
lying face-up shows the person opposite a script in a language they may not read.
`ContentState.other` (plus `otherLanguage`) now carries the English translation of
the current line when the source is not English, and the widget shows it as a
second line — Lock Screen, and the expanded Dynamic Island.

- Where: `Shared/RecordingActivity.swift` (fields), `App/RecordingController.liveActivityState`
  (fills from `liveTranslation`), `Widgets/RecordingLiveActivity.swift` (renders).
- Verify: `-DuoPreview live` launch argument (DEBUG) fills the activity with a
  sample Chinese line and its translation.
- Next, if it proves useful: show the *conversation* turn pair instead of the
  notes transcript when Conversation mode is driving the microphone.

## 2. The Duo's two postures — planned

On the foldable iPhone itself, the ideas map onto geometry rather than a hinge API:

- **Unfolded (inner, ~669–951 pt wide): laptop/tabletop layout.** Transcript and
  translation on one side, the control pad on the other — the foldable's
  `TabletopScreen`, triggered by a wide window instead of a hinge angle.
- **Folded (outer, 466×678 with the 84 pt right inset): glance mode.** Live line,
  translation, and Start/Stop/Split only; the notes list is a swipe away.
- **The fold event opens and closes conversation mode**, the way
  `onFoldStateChanged` does on Android: a scene resize from small to wide opens
  the four-pane stage, wide to small returns to notes.
- Requires landscape support on the inner display (`project.yml` currently pins
  `UIInterfaceOrientationPortrait`), and the layout must key off
  `windowGeometry`/size classes rather than `UIScreen`, which only ever reports
  the active display.

## 3. StandBy tabletop — planned

StandBy (charging, landscape, on a stand) is the iPhone's tabletop posture: the
phone stands like a half-folded laptop. The foldable layout maps directly: newest
line + translation on one tile, Start/Stop/Split and the send actions on the
other, amber "PAUSED" when idle rather than recording.

Work:
- An App Group (`group.`) shared store, because a widget cannot read the app's
  state directly. Nothing in the iOS tree uses one yet. **This needs the App Group
  capability on the bundle ids** — a developer-account change, not just code; the
  simulator will accept it, TestFlight/App Store will not until the capability is
  enabled for `com.voicepad` and `com.voicepad.widgets`.
- New widget(s) in `VoicePadWidgets` (systemSmall, `supportedFamilies` including
  `.systemSmall`), reading a small snapshot the app writes on every live-text
  change, plus a deep link (`voicepad://record`) to start recording.
- Decide how much of the pad to keep: prev/next note needs the notes list in the
  shared store; Start/Stop/Split only needs the recording flags.
- Verify on an iOS 17 simulator plus, ideally, a real iPhone on a MagSafe stand:
  confirm how the existing Live Activity renders in StandBy before designing the
  tiles around it (Apple's docs are thin here).

Effort: 2–3 days including the capability and the deep-link path.

## 4. Even G1 glasses on iOS — planned

The glasses are the truest "second surface": always in view, only the wearer can
see it, 5 lines × 40 columns. The Android design in `docs/g1-glasses.md` is
device-agnostic — `Stage → 5 lines × 40 columns → one write per changed page` —
and Even Realities expose the G1 as a BLE GATT peripheral, which CoreBluetooth can
drive as a central. There is no HID-peripheral requirement on this path, so the
one thing iOS cannot do (act as a Bluetooth keyboard) does not apply.

Work:
- `GlassesScreen` (pure layout: `Stage` → pages) ports as-is; it has no Android
  dependencies.
- `GlassesController` (page diffing, newest-wins, re-send after reconnect) ports
  as-is.
- `GlassesLink` needs a `CoreBluetooth` implementation: scan by service, connect,
  discover the two arm characteristics, write. Same protocol as Android, different
  API shape. Keep the existing `GlassesLink` protocol so the controller and tests
  do not change.
- Test the pure parts (page layout, diffing) in `VoicePadTests`; the BLE link is
  device-only and needs a real pair of glasses.
- Privacy: the HUD is the wearer's subtitle track; the phone keeps its own screen
  for the person opposite.

Effort: 3–5 days, most of it the BLE link and on-glasses timing.

## 5. Watch companion — later

The only true two-device duo: start/stop/split, elapsed, latest line, haptic on
split. New watchOS target + WatchConnectivity; the `LiveActivityIntent` command
shape already exists (`RecordingActions`). ~300 lines plus session plumbing.

## 6. iPad stage — later

The four conversation layouts already scale; add a two-pane iPad arrangement
(conversation + notes) for Stage Manager and landscape iPad. Small.

## What will not port

- **Hinge / fold-posture detection.** No iOS API. Substitutes: StandBy, the
  manual layout switch, the Watch.
- **Acting as a Bluetooth HID keyboard** (`BluetoothKeyboardSink`). Blocked on
  iOS, so laptop-mode "send with prompt" goes through the clipboard or Handoff.
- **A real cover display.** Live Activity, StandBy and the Watch stand in.

## Conventions for this branch

- One feature per commit; stack PRs on `ios/android-parity`.
- `xcodegen generate` after adding files, or the build silently skips them.
- Verify with `xcrun simctl` screenshots before claiming a UI change works; a
  debug launch argument is cheaper than tapping through the app.
