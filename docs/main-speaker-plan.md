# VoicePad — Telling the main user apart from everyone else (Android)

Status: draft (planning only, no code yet).

## Goal

The phone belongs to one person. VoicePad should know which speech is **theirs**
and which comes from **anyone else**, and act on that. The hard case is a mixed-use
office with an open floor plan:

- colleagues at the next desks, 1–3 m away, talking to each other or on calls;
- people walking past mid-sentence;
- meetings at a nearby table, a TV or PA, general babble;
- the owner sometimes talking *with* a colleague (a conversation they do want
  captured) and sometimes dictating while others talk nearby (a conversation they
  don't want captured).

What this unlocks:

1. **"Only me" dictation**: notes contain the owner's words and nothing else.
   Others' speech isn't transcribed, and optionally isn't stored. That fixes
   accuracy (no stray sentences in a dictated email) and limits how much
   colleagues' speech the app captures without their consent.
2. **"Me" labels**: with *Separate speakers* (merged in skomae/voicepad#30), the owner's
   paragraphs read `Me:` instead of an arbitrary `Speaker 2:`, and the numbers
   stay stable from note to note.
3. **Smarter automation**: auto-split, auto-record and "copy when done" key off
   the owner's speech, so a colleague's chatter doesn't keep a note open or
   start a new one.
4. **Conversation mode**: the owner's turns are known to be "me". That fixes the
   language direction and the speaker colours without relying on which mic
   channel picked them up.

Non-goals: identifying *which* colleague is speaking by name, sharing voice
profiles between devices, and any server-side processing (everything stays on the
phone, as it does today).

---

## What we already have

| Piece | Where | Use here |
| --- | --- | --- |
| CAM++ speaker embeddings (3D-Speaker, zh+en, 28 MB) | `conversation/SpeakerEmbedder.kt` | Voice fingerprint for enrolment and verification |
| Online clustering + cosine helpers | `conversation/SpeakerClustering.kt` | Scoring, centroid maths |
| Per-note speaker labelling | `conversation/SpeakerTranscript.kt` | Gets an "owner" anchor |
| Silero VAD, 0.25 s pauses end an utterance | `transcription/SpeechDetector.kt` | Utterance boundaries to classify |
| Per-utterance chunking (forced while separating speakers) | `RecordingController.commit` / `transcribeSaved` | Where the decision is made |
| Multi-channel capture + loudest-channel attribution | `audio/AudioRecorder.kt`, `conversation/ChannelAttribution.kt` | Inter-mic level difference (near-field cue) |
| Mic source choice (`VOICE_RECOGNITION` vs `UNPROCESSED`/`CAMCORDER`) | `AudioRecorder.sourceFor` | Close vs room pickup |
| Bluetooth/USB headset input selection | `audio/InputDevices.kt` | Close-talk mic = strong prior |
| CrashGuard for risky native work | `CrashGuard.kt` (`VOICE_ID`) | Same guard for verification |

Measured so far, from `SpeakerClustering`'s notes on CAM++ with sherpa-onnx test
recordings: the same speaker *within one recording* scores 0.49–0.82, and different
speakers score at most 0.41 (99th percentile). *Across* recordings one person can
drop much lower. Cross-condition drift is therefore the main risk for an enrolled
profile, and the plan is built around it.

---

## Signals for "is this the owner?"

No single signal is enough in an open office. The plan combines the four below,
strongest first.

### 1. Voice verification against an enrolled profile (primary)

- **Enrolment**: the owner reads 4–6 short prompts (about 30–40 s of speech in
  total). Each prompt gives one CAM++ embedding. The profile keeps **all** of them
  plus their normalised centroid, not just one average. A mix of prompts across
  the owner's languages (CAM++ is zh+en) helps.
- **Multi-condition**: enrolment records which input it used (built-in mic,
  headset, Bluetooth). The profile can hold several *condition groups*. Scoring
  uses the group for the current input when one exists, otherwise the best score
  over all groups.
- **Score**: the maximum of (cosine to the centroid) and (mean of the top 3
  cosines to individual enrolment embeddings). This is more robust to one odd
  enrolment take.
- **Adaptive update**: utterances scored as owner with high confidence (well
  above the threshold, long enough, good SNR) are added to a rolling pool. That
  pool is capped (e.g. 50) and never replaces the original enrolment, so the
  profile can't drift onto a colleague's voice. The centroid is recomputed from
  enrolment plus pool, and the pool can be reset from settings.
- **Calibration**: the owner/other threshold is set per device from data
  (Phase 0) and stored with the profile. The `0.45` used for clustering is the
  starting point, not the answer.

### 2. Proximity: the owner is the closest voice (secondary)

The owner is typically 0.3–0.6 m from the phone; colleagues are 1.5 m or more away.
Distance shows up in the audio:

- **Level relative to the noise floor (speech SNR)**: track the running noise
  floor from VAD-silent frames. Near speech sits much further above it.
- **Level relative to the owner's own level**: enrolment and confirmed owner
  utterances give a typical speech level for each input. Speech 10 dB or more
  below that is unlikely to be the owner.
- **Direct-to-reverberant proxies**: distant speech is more reverberant and
  loses high frequencies. Cheap per-utterance features: spectral centroid and
  roll-off, the energy ratio of 4–8 kHz to 0.3–1 kHz, and the modulation depth of
  the envelope (reverberation smears syllables).
- **Inter-mic level difference (near-field cue)**, where the phone gives two
  channels (many do with `UNPROCESSED`/`CAMCORDER` in stereo). Near-field speech
  shows a large, consistent level difference between bottom and top mics; far
  speech arrives at both at almost the same level. `ChannelAttribution.frameLevels`
  and `separationDb` already compute exactly this. Today they tell *people with
  separate mics* apart; here they would tell *near from far* on one phone.
  Availability is per device, so this has to be probed at runtime.

### 3. Hardware context (prior)

- A **headset, lapel or Bluetooth mic** is selected: the owner's voice dominates
  that signal. Raise the prior for the owner and loosen the threshold; others'
  speech is far quieter on it.
- **Built-in mic, phone on the desk**: neutral prior. This is the case where the
  proximity cues matter most.
- **Background voices on** (room pickup, Whisper turbo): the user has explicitly
  asked for others' speech, so "Only me" should warn or turn itself off.

### 4. Conversation context (tie-breaker)

- Continuity: an utterance that follows the owner after a short gap, in the same
  language, at a similar level, is probably still the owner.
- In conversation mode, the owner usually speaks their own language (the
  existing language bonus in `SpeakerClustering`).

### Fusion

Each utterance gets a feature vector: verification score, speech SNR, level
relative to the owner, spectral tilt, inter-mic level difference (if any),
hardware prior, and gap/language continuity. A small **logistic model** combines
them into P(owner). The weights are fitted offline on the Phase 0 corpus and
shipped as constants; there's no on-device training beyond the adaptive pool.

Smooth P(owner) along the note: a two-state model with sticky transitions, run
over the last few utterances. One noisy utterance then can't flip the label, but a
real change of speaker still shows through within about one utterance.

Each utterance gets one of three decisions:

- **Owner**: P ≥ high threshold.
- **Other**: P ≤ low threshold.
- **Unsure**: in between. How it's handled depends on the mode (below), and an
  unsure utterance is never silently dropped.

---

## Product behaviour

A new setting, **Whose speech to keep**, plus a **Your voice** screen.

| Mode | Owner | Other | Unsure |
| --- | --- | --- | --- |
| **Everyone** (today's default) | kept | kept | kept |
| **Label me** (needs *Separate speakers*) | `Me:` | `Speaker N:` | labelled by clustering as today |
| **Only me** | kept | dropped | kept, marked faint/italic in the note so it can be removed with a tap |

- **Your voice screen**: enrol, re-enrol, "add this input" (enrol again on a
  headset), a test meter ("say something: 0.81, that's you"), reset the adaptive
  pool, and delete the profile.
- **Only me without a profile**: falls back to proximity-only. The settings say
  it's less reliable and offer enrolment.
- **Others' audio**: in Only me, an option to **not keep others' speech in the
  saved recording**. Silence their spans in the WAV after transcription
  (re-transcribe then can't recover them, and the settings say so). The default is
  to keep audio, as today, because a misclassification is otherwise
  unrecoverable.
- **Live preview**: in Only me, the preview line shows the open utterance dimmed
  until it's classified (after about 1 s of speech the first embedding is
  possible). Utterances classified as other then disappear from the preview
  instead of flashing past.
- **Automation**: `lastVoiceAt` (auto-split by silence) and auto-record count
  only owner and unsure speech in Only me. A colleague's long call then doesn't
  keep the note open, and silence on the owner's side still splits.

## Privacy and consent

- The voice profile is **biometric data**. It's stored app-private
  (`filesDir/voice/owner.json`), never included in exports, never uploaded, and
  deletable in one tap. Enrolment says so in plain words.
- Only me reduces how much bystander speech is transcribed and, with the audio
  option, stored. The plan should still say plainly that **the microphone hears
  everyone**, and that classification happens after capture.
- Embeddings of others are never persisted beyond the note's own processing.
  There is no colleague gallery and no cross-note matching of non-owners.
- Local law (two-party consent, GDPR, BIPA-style biometric rules) is the user's
  responsibility. The app's part is defaults that minimise capture of others, and
  clear wording.

---

## Architecture sketch

```
mic ─► AudioRecorder (1–2 ch) ─► SpeechDetector (utterances)
                                      │
                                      ▼
                         UtteranceFeatures.extract(audio, channels, noiseFloor)
                         ├─ CAM++ embedding (shared with Separate speakers)
                         ├─ speech SNR, level vs owner, spectral tilt
                         └─ inter-mic level difference (if stereo)
                                      │
                                      ▼
                OwnerVerifier.score(features, profile, inputKey) ─► P(owner)
                                      │
                         OwnerSmoother (sticky 2-state) ─► Owner / Other / Unsure
                                      │
           ┌──────────────────────────┼──────────────────────────┐
           ▼                          ▼                          ▼
   SpeakerTranscript           RecordingController           ConversationController
   (owner anchor = "Me")       (drop / mark / silence,       (owner turns = "me")
                                lastVoiceAt, auto-record)
```

New code (pure Kotlin where possible, so it can be unit-tested like
`SpeakerClustering`):

- `voice/OwnerProfile.kt`: the enrolment embeddings grouped by input condition,
  the adaptive pool, the calibrated thresholds and the typical level per input.
  JSON (de)serialisation.
- `voice/UtteranceFeatures.kt`: SNR, spectral tilt, envelope modulation and
  inter-mic level difference, from raw samples (pure).
- `voice/OwnerVerifier.kt`: scoring and the logistic fusion (pure; weights are
  constants).
- `voice/OwnerSmoother.kt`: the sticky two-state smoothing (pure).
- `voice/Enrolment.kt` and `ui/YourVoiceSheet.kt`: the prompt flow, quality
  checks (too short, too noisy, clipped) and the test meter.
- Changes in `SpeakerTranscript` (a seeded owner cluster that always renders as
  `Me`), `RecordingController.commit`/`transcribeSaved` (decision per chunk,
  Only me filtering, WAV silencing) and `ConversationController.voiceSpeaker`.

The embedder is shared: one `SpeakerEmbedder` instance (today there are two, one
in each controller) behind one mutex, so enabling "Me" doesn't double the model
memory.

### Utterances with two voices

VAD utterances end at 0.25 s pauses, so a colleague cutting in without a pause
lands in the owner's utterance. Phase 4 adds **change-point detection inside an
utterance**: embed sliding 1.5 s windows with a 0.5 s hop, find where the
similarity to the owner drops, and split the chunk there before transcription.
This is costlier, so it only runs on utterances longer than about 3 s with a
mixed score.

---

## Evaluation (before building the UI)

**Corpus (Phase 0)**: record in the actual office on the devices that matter
(Retroid Pocket Flip2, the owner's phone, with and without a headset):

- the owner dictating at 0.3–0.6 m, normal and quiet voice;
- 3–4 colleagues at 1 m, 2 m and 3 m or more, talking to each other and on phone
  calls (one side audible);
- the owner in conversation with a colleague at about 1 m (both should be
  captured in Everyone and Label me; only the owner in Only me);
- background babble, a nearby meeting, a passer-by;
- 2 sessions on different days (cross-session drift is the real test).

Label at utterance level: owner / other / overlap. A debug build logs per-utterance
features (verification score, SNR, level, tilt, inter-mic level difference) to a
CSV next to the note, so the corpus can be scored offline.

**Metrics**:

- *Owner word recall* in Only me: owner words kept ÷ owner words spoken (target
  ≥ 98%; missing the owner is worse than leaking a colleague).
- *Leak rate*: words from others kept per minute of others' speech (target
  ≤ 5%).
- *Unsure rate*: share of utterances landing in Unsure (target ≤ 10%).
- *Label accuracy* for Label me, and *latency* added per utterance (embedding
  time on the Retroid; target ≤ 150 ms for a 3 s utterance).

**Harness**: a script under `scripts/` that replays labelled WAVs through the same
pure-Kotlin feature, verifier and smoother classes on the JVM. Embeddings are
pre-computed on the device and saved with the corpus, because the sherpa-onnx AAR
is Android-only. It prints the metrics and a threshold sweep, and the fusion
weights are fitted from the same data.

---

## Phases

0. **Measure**: debug feature logging, corpus recording, offline harness, first
   thresholds and weights. *Exit*: a scatter of verification score × SNR that
   separates the owner from others on the built-in mic at 1.5 m or more.
1. **Enrolment and "Me" labels**: OwnerProfile, Your voice screen,
   verification-only scoring, the owner anchor in SpeakerTranscript and the
   shared embedder. *Exit*: Label me is right on ≥ 95% of corpus utterances.
2. **Only me**: three-way decisions, filtering, Unsure marking, the live-preview
   dimming, automation keyed to the owner, and optional silencing of others'
   audio. *Exit*: owner recall and leak-rate targets met on the corpus.
3. **Proximity fusion**: SNR, level-vs-owner and spectral tilt; the inter-mic
   level difference where the device has stereo; the hardware prior; the logistic
   fusion and the smoother. *Exit*: leak rate improves on Phase 2 with no loss in
   owner recall.
4. **Robustness**: the adaptive pool with drift guard, condition groups per
   input, change-point splitting inside utterances, and conversation-mode
   integration.

Each phase ships behind its setting, defaulting to off until the corpus numbers
hold.

## Risks

- **Cross-condition drift** (different mic, cold, tired voice, quiet speech).
  *Mitigation*: condition groups, the adaptive pool and the Unsure band; Only me
  never drops Unsure.
- **Similar-sounding colleague** (same gender, accent and language).
  *Mitigation*: proximity cues carry it, and the leak rate is measured per
  colleague.
- **Owner on a phone call or turned away** (lower level, more reverb).
  *Mitigation*: the verification score dominates when it's confident;
  level-vs-owner is a soft cue, not a gate.
- **Short utterances** (< 1 s, "yeah", "ok") can't be embedded. *Mitigation*:
  inherit from neighbours through the smoother.
- **Devices without stereo capture** lose the inter-mic cue. *Mitigation*: it's
  optional; probe at runtime.
- **Enrolment fatigue**. *Mitigation*: 30–40 s once, with optional top-ups; the
  adaptive pool does the rest.

## Open questions for the owner

1. Which is the main goal: **privacy filtering** (Only me) or **labelling**
   (Me vs others)? It decides whether Phase 1 or Phase 2 comes first.
2. Which mics are used in the office: the built-in mic on the desk, a headset, or
   both? A headset makes this far easier.
3. In Only me, should others' audio be silenced in the saved recording by
   default, or kept so it can be re-transcribed?
4. Should the owner's own conversation partner (someone they're talking *to*) be
   kept in Only me? That would be a fourth mode, "Me and whoever I'm talking with",
   using turn-taking with the owner as the cue.
5. Are colleagues willing to help record the Phase 0 corpus? Synthetic mixes of
   public speech can stand in, but they won't capture the real room.
