# VoicePad on Even Realities G1 glasses

Conversation mode, on the glasses: the same listening, transcription,
language choice, speaker labels and translation, but rendered for a display
that is 640×200 green pixels rather than a phone screen.

## The display decides the design

| Fact | Consequence |
|---|---|
| Five lines of 40 columns (a 16-pixel character cell; wide scripts take two columns) | One page is about 200 characters, 2–3 short turns. There is no scrolling and no touch input. |
| The wearer is the only one who can see it | The glasses are **your** subtitle track. The phone keeps its own screen for the person opposite, exactly as now. |
| Text arrives faster than anyone reads a transcript | It's a rolling window: newest words at the bottom, older lines scroll off the top. Nothing to page through. |
| Monochrome, no styling | Speakers are marked with a two-column gutter (`> ` for them, `- ` for you) rather than colour. |

**Them** (default) shows only the other person's speech, translated into your
language — the part you can't understand by ear. **Both** shows the whole
conversation in your language with the gutter marking who spoke. While their
translation is still on its way the page keeps the last one up and puts `…`
where the new one will be, rather than flashing a script you may not read; a
translation that fails shows what was actually said instead.

Nothing is drawn while nobody has spoken: the page says which pair of
languages is in use and whether the app is listening.

## How it's wired

```
ConversationController.UiState.stage   (turns + live turn + phase)
        │
        ▼
GlassesScreen.of(...)                  pure: Stage → 5 lines × 40 columns
        │
        ▼
GlassesController                      one write per changed page, newest wins,
        │                              re-sends after a reconnect
        ▼
GlassesLink / BasisGlassesLink         scan, pair, connect, write both arms
        │
        ▼
glasses/basis/                         vendored Basis protocol (G1.kt, G1Device.kt,
                                       G1BLEManager.kt, protocol.kt) — see its README
```

- `GlassesScreen` is pure Kotlin and unit-tested (`GlassesScreenTest`): wrapping
  is column-aware (`Character.isIdeographic`, kana, hangul, full-width forms),
  never leaves a closing mark at the start of a line, cuts over-long tokens by
  width, and always returns exactly five lines.
- `GlassesController` mirrors the conversation: it maps the state to a page,
  drops duplicates, and conflates — a page still in flight is abandoned rather
  than queued behind a newer one. Writes are spaced at least 350 ms apart and
  time out after 5 s.
- `BasisGlassesLink` pairs the two arms by the channel number in their
  advertised names and writes the page to both: left first, then right once
  the left has acknowledged, as Even Realities' demo app does. A page is cut
  into 191-byte `0x4E` packets, each acknowledged (`G1Protocol`, unit-tested in
  `G1ProtocolTest`). Each arm gets the official `0x25` heartbeat every 8 s
  (the link drops after ~30 s of silence) and a battery check every ~30 s.
- A link lost mid-conversation shows as *Connecting* while Android reconnects
  it; after 45 s without it the glasses are let go and shown as disconnected,
  and *Look for the glasses again* scans afresh at any point.
- The settings sheet draws the same page as a green-on-black preview, so the
  5×40 limit is visible while choosing the scope.

Only pages that changed are written — the glasses hold the last page until the
next one or until conversation mode closes (which sends their exit command and
returns them to their own idle screen).

## What we changed in the vendored protocol

See `android/app/src/main/java/com/voicepad/app/glasses/basis/README.md`.
Short version: both advertised-name prefixes are accepted (upstream only
matched `Even G1_...`, the official app's units say `G1_45_L_92333`), pairing
works across scan batches, the notification subscription is actually written,
writes no longer block (and fail) on the main thread, pages are split into
packets the glasses accept with acknowledgements actually checked, and the
official heartbeat keeps the link up.

## Limits

- One pair of glasses at a time, and the Even Realities app holds their
  connection; they can be paired with VoicePad or with that app, not both.
- The two arms are always written together: one conversation per pair.

## Listening with the screen off

Conversation mode runs under a foreground service
(`conversation/ConversationService.kt`, type `microphone`), so the microphone
keeps working with the app in the background or the phone locked — otherwise
Android stops giving audio the moment the activity is stopped. A *Conversation*
notification stays in the status bar with the newest line and **Pause** /
**Leave**; it's private, so the lock screen shows nothing until unlocked, and on
Android 16 it's promoted to a Live Update ("LIVE" chip). Pausing or leaving
conversation mode stops the service; nothing runs in the background after that.

If the system refuses to start the service (Android 12+ won't start a foreground
service from the background), listening falls back to pausing when the app goes
away, and restarts when it comes back. The service always calls
`startForeground` before deciding to stop, and a refusal inside it is caught
rather than crashing the app.

## Not yet verified — needs the hardware

Everything below compiles and is unit-tested, but none of it has run against a
real pair of G1s (there were none to hand):

- scanning and pairing against the glasses' real advertisements;
- the 0x4E page write being accepted and acknowledged on current firmware;
- the heartbeat actually holding the link for long conversations;
- the `0x25` heartbeat's answer on current firmware (taken from the official
  demo app; a missed answer is harmless, it only retries once);
- how much text the waveguide really fits at 40 columns (Basis's own limit,
  and the source of the "5 lines × 40" figure). The official app measures with
  a proportional font, 21 px in a 488 px line, so a line of wide Latin letters
  (`W`, `M`) may clip before 40 — if lines come out clipped, lower
  `GlassesScreen.COLUMNS` and the wrapping follows;
- the foreground service holding the microphone with the phone locked (the
  Conversation notification, its Pause/Leave actions and the Live Update chip);
- Bluetooth permission prompts on Android 12+ when the glasses are first
  switched on.

First thing to try on hardware: open conversation mode, tap the glasses icon,
and check that the settings sheet's *Connected to G1 45 · 87%* line appears;
then say something and watch the preview and the glasses show the same page.
