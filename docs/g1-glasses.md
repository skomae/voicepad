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
  time out after 3 s.
- `BasisGlassesLink` pairs the two arms by the channel number in their
  advertised names, writes the page to both, and keeps the Basis heartbeat
  going (the link drops after ~30 s of silence).
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
and lost acknowledgements no longer block the write queue for 15 s.

## Limits

- Conversation mode listens only while the app is in front (it pauses when the
  app is backgrounded), so "phone in a pocket" means unlocked, screen on, with
  VoicePad in front — `keepScreenOn` keeps it that way. Running the
  conversation from a foreground service is the next step if the phone should
  be able to lock.
- The Even Realities app holds the glasses' connection; they can be paired
  with VoicePad or with that app, not both.
- One conversation per pair of glasses: the two arms are written together.

## Not yet verified — needs the hardware

Everything below compiles and is unit-tested, but none of it has run against a
real pair of G1s (there were none to hand):

- scanning and pairing against the glasses' real advertisements;
- the 0x4E page write being accepted and acknowledged on current firmware;
- the heartbeat actually holding the link for long conversations;
- how much text the waveguide really fits at 40 columns (Basis's own limit,
  and the source of the "5 lines × 40" figure) — if lines come out clipped,
  lower `GlassesScreen.COLUMNS` and the wrapping follows;
- Bluetooth permission prompts on Android 12+ when the glasses are first
  switched on.

First thing to try on hardware: open conversation mode, tap the glasses icon,
and check that the settings sheet's *Connected to G1 45 · 87%* line appears;
then say something and watch the preview and the glasses show the same page.
