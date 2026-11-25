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

## 2. The Duo's two postures — tabletop layout DONE on this branch

On the foldable iPhone itself, the ideas map onto geometry rather than a hinge API.
`Views/DuoPosture.swift` turns a window size into a posture: 600 pt on the short
side is the boundary, chosen between the Duo's outer 466 pt and inner 669 pt so the
two postures never overlap and a resize across it is the fold itself. `DuoPostureTests`
pins those numbers (folded, unfolded, unfolded landscape, folded landscape, iPad,
and the zero size SwiftUI proposes before first layout).

Done:
- **Wide window = tabletop layout** (`Views/TabletopView.swift`): what is being said
  above in large type with the English line underneath, a pad below (Notes, older /
  newer note, Latest). The record bar stays where it was, in `ContentView`'s bottom
  inset. The notes list is one tap away and the toolbar has a way back.
- Landscape is enabled for iPad in `project.yml`; iPhone stays portrait so a normal
  phone never turns into a tabletop.

Still to do:
- **Glance mode on the folded display.** The outer display is 466×678 with an 84 pt
  inset on the right, which already squeezes the toolbar; a shorter version of the
  notes screen (live line + controls) would suit it better than the full list.
- **The fold event drives conversation mode**, the way `onFoldStateChanged` does on
  Android: wide → conversation stage, folded → notes. Needs care: `ConversationView`
  is a separate full-screen mode, so the fold would overrule whatever the user
  opened.
- Verified on the iPad simulator (wide window: tabletop renders, one record bar) and
  on the Duo folded (compact: the notes list, unchanged). The Duo *unfolded* could
  not be exercised here — this Xcode install has no Simulator.app, so nothing can
  fold the device; the same code path is what the iPad screenshots show.

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

## 4. Even G1 glasses on iOS — ported, needs hardware

Ported on this branch, from the Android implementation in
`android/.../glasses/`:

| Piece | File | State |
|---|---|---|
| 5 lines x 40 columns, column-aware wrapping, gutters, pinyin line | `Glasses/GlassesScreen.swift` | done, `GlassesScreenTests` |
| `0x4E` page packets, `0x25` heartbeat, battery, acks, arm-name parsing | `Glasses/GlassesProtocol.swift` | done, `GlassesProtocolTests` |
| Scan, pair the two arms, write left then right, heartbeat every 8 s | `Glasses/GlassesLink.swift` | written, never run against a pair |
| Page conflation, 350 ms spacing, re-send after reconnect, scope | `Glasses/GlassesController.swift` | done |
| Settings: on/off, Them/Both, status, 5x40 green preview | `GlassesSection` | done |

Two things iOS forced:

- **Chunking is character-aware.** The protocol carries 191 bytes per packet and
  the Android sender cuts at byte offsets; 191 is not a multiple of 3, so a Chinese
  page splits a character in half and the glasses show a replacement mark at the
  end of every page. The Swift version packs whole characters up to 191 bytes, and
  `GlassesProtocolTests.testWideTextSplitsByBytesNotCharacters` pins it. Worth
  fixing on Android too.
- **The link is not `@MainActor`.** `CBCentralManagerDelegate` requirements are
  nonisolated; the manager is created on the main queue so every callback already
  arrives there, and the controller stays on the main actor.

Untested against real glasses, exactly like the Android side: scanning against the
real advertisements, the page write being acknowledged on current firmware, the
heartbeat holding the link, the battery answer, and whether 40 columns really fit
the waveguide. There were no G1s to hand.

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
