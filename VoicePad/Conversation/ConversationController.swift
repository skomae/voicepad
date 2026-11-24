import AVFoundation
import Foundation
import Observation

/// Conversation mode: live two-way translation between two languages, shown
/// in big text, with each turn labelled by who said it. Ported from the
/// Android app's ConversationController.
///
/// - Each finished utterance (found by the voice detector at its pause) is
///   transcribed with the model notes use, assigned to one of the two
///   languages, and translated into the other; the utterance still being
///   spoken is previewed every `tick`.
/// - A turn that sounds unfinished ("…and", 然后) is held open for a thinking
///   pause instead of being translated as a fragment.
/// - Voices are told apart by their sound (`SpeakerEmbedder` +
///   `SpeakerClustering`), or, without the voice model, by language.
///   (Android can also use one microphone channel per person; not on iOS yet.)
/// - Every listening session is saved as a note (audio plus a speaker-labelled
///   transcript with translations), so nothing is lost.
@MainActor
@Observable
final class ConversationController {
    struct Settings: Equatable {
        var myLanguage = "en"
        var theirLanguage = "zh"
        var layout = ConversationLayout.faceToFace
        /// Main text size in points.
        var textSize = 34
        /// Tell speakers apart by voice (downloads a small model).
        var identifyVoices = true
        /// Read each translation aloud once its turn is finished.
        var autoSpeak = false
        /// Seconds a turn that sounds unfinished is held open before it's
        /// translated anyway; 0 turns holding off.
        var thinkingPause = 5
        /// Animate the stage even when Reduce Motion is on.
        var forceAnimations = false

        var pair: (String, String) { (myLanguage, theirLanguage) }
    }

    enum Readiness: Equatable {
        case off
        /// `progress` 0...1 while downloading, nil while loading.
        case preparing(Double?)
        case ready
        case failed(String)
    }

    /// A turn held open: translated at `endsAt` unless speech resumes.
    struct Hold: Equatable {
        var turnID: Int
        var endsAt: Date
        var total: TimeInterval
    }

    // MARK: Observable state

    private(set) var visible = false
    private(set) var listening = false
    private(set) var settings: Settings
    private(set) var turns: [Turn] = []
    /// Speech in progress: not final, may still change.
    private(set) var live: Turn?
    /// A voice is being heard right now.
    private(set) var hearing = false
    /// A finished utterance is being transcribed.
    private(set) var working = false
    private(set) var hold: Hold?
    private(set) var translation: Readiness = .off
    private(set) var voiceModel: Readiness = .off
    var message: String?

    var stage: Stage { Stage.of(turns, live: live, working: working) }

    /// How speakers are being told apart right now.
    var speakerSource: SpeakerSource {
        settings.identifyVoices && voiceModel == .ready ? .voice : .language
    }

    /// Live input level 0...1.
    var level: Float { recorder.level }

    let speech = SpeechOutput()

    // MARK: Private

    private static let rate = Int(AudioRecorder.sampleRate)
    /// Context kept either side of detected speech.
    private static let pad = rate / 4
    /// Longest preview of speech in progress.
    private static let maxLive = rate * 20
    private let tick: Duration = .milliseconds(500)

    @ObservationIgnored private let notes: RecordingController
    @ObservationIgnored private let recorder = AudioRecorder()
    @ObservationIgnored private var store: NoteStore { notes.store }
    @ObservationIgnored private var translator: EnglishTranslator { notes.translator }

    /// One listening session: one recording, one saved note.
    private final class Session {
        let number: Int
        let noteID: Note.ID
        let detector: SpeechDetector?
        var detectedUpTo = 0
        /// Last position the detector heard no speech; the current utterance began after it.
        var quietAt = 0
        /// End of the last finished utterance.
        var finishedUpTo = 0
        var pending: [ChunkPlanner.Span] = []
        var stepping = false

        init(number: Int, noteID: Note.ID, detector: SpeechDetector?) {
            self.number = number
            self.noteID = noteID
            self.detector = detector
        }
    }

    @ObservationIgnored private var session: Session?
    @ObservationIgnored private var sessionCount = 0
    @ObservationIgnored private var loopTask: Task<Void, Never>?
    /// Previous session still finishing (its audio lives in `recorder` until then).
    @ObservationIgnored private var finishingTask: Task<Void, Never>?
    @ObservationIgnored private var nextTurnID = 1
    @ObservationIgnored private var translationTasks: [Int: Task<Void, Never>] = [:]
    @ObservationIgnored private var liveTranslateTask: Task<Void, Never>?
    /// The turn held open for a thinking pause, if any.
    @ObservationIgnored private var heldTurnID: Int?
    /// Turns already read aloud (or that shouldn't be).
    @ObservationIgnored private var spokenTurns = Set<Int>()
    /// Stretches of the recording during which the phone was talking. Speech
    /// found there is the phone's own voice coming back through the mic.
    @ObservationIgnored private var muted: [Range<Int>] = []
    @ObservationIgnored private var mutedFrom: Int?
    /// Most recent voice per language, for utterances too short to fingerprint.
    @ObservationIgnored private var lastVoiceFor: [String: SpeakerRef] = [:]
    @ObservationIgnored private let voices = SpeakerClustering()
    @ObservationIgnored private var embedder: SpeakerEmbedder?
    @ObservationIgnored private var embedderTask: Task<Void, Never>?
    @ObservationIgnored private var prepareTask: Task<Void, Never>?

    init(notes: RecordingController) {
        self.notes = notes
        settings = Self.loadSettings()
        speech.onPlayingChanged = { [weak self] playing in self?.speechPlaying(playing) }
    }

    /// Who said `turn`, for labels and the saved transcript.
    static func speakerLabel(_ turn: Turn) -> String { speakerLabel(turn.speaker, language: turn.language) }

    static func speakerLabel(_ speaker: SpeakerRef?, language: String) -> String {
        switch speaker?.source {
        case .channel: return "Mic \(speaker!.index + 1)"
        case .voice: return "Speaker \(speaker!.index + 1)"
        case .language, nil: return LanguageNames.native(language)
        }
    }

    /// Identifies a turn read out in one language, for the speaker icons.
    static func speechID(_ turnID: Int, _ language: String) -> String { "\(turnID):\(language)" }

    // MARK: Settings

    private static let prefix = "conversation."

    private static func loadSettings() -> Settings {
        let d = UserDefaults.standard
        var s = Settings()
        s.myLanguage = d.string(forKey: prefix + "myLanguage") ?? s.myLanguage
        s.theirLanguage = d.string(forKey: prefix + "theirLanguage") ?? s.theirLanguage
        s.layout = d.string(forKey: prefix + "layout").flatMap(ConversationLayout.init(rawValue:)) ?? s.layout
        s.textSize = d.object(forKey: prefix + "textSize") as? Int ?? s.textSize
        s.identifyVoices = d.object(forKey: prefix + "identifyVoices") as? Bool ?? s.identifyVoices
        s.autoSpeak = d.bool(forKey: prefix + "autoSpeak")
        s.thinkingPause = d.object(forKey: prefix + "thinkingPause") as? Int ?? s.thinkingPause
        s.forceAnimations = d.bool(forKey: prefix + "forceAnimations")
        return s
    }

    private func change(_ transform: (inout Settings) -> Void) {
        transform(&settings)
        let d = UserDefaults.standard, p = Self.prefix
        d.set(settings.myLanguage, forKey: p + "myLanguage")
        d.set(settings.theirLanguage, forKey: p + "theirLanguage")
        d.set(settings.layout.rawValue, forKey: p + "layout")
        d.set(settings.textSize, forKey: p + "textSize")
        d.set(settings.identifyVoices, forKey: p + "identifyVoices")
        d.set(settings.autoSpeak, forKey: p + "autoSpeak")
        d.set(settings.thinkingPause, forKey: p + "thinkingPause")
        d.set(settings.forceAnimations, forKey: p + "forceAnimations")
    }

    /// Sets the two languages; picking the other side's language swaps them.
    func setLanguages(mine: String, theirs: String) {
        let (a, b) = mine == theirs ? (settings.theirLanguage, settings.myLanguage) : (mine, theirs)
        change { $0.myLanguage = a; $0.theirLanguage = b }
        if visible { prepareTranslation() }
    }

    func swapLanguages() { setLanguages(mine: settings.theirLanguage, theirs: settings.myLanguage) }

    /// Switches to the next layout (the layout button).
    func cycleLayout() { change { $0.layout = $0.layout.next() } }
    func setLayout(_ layout: ConversationLayout) { change { $0.layout = layout } }
    func setTextSize(_ size: Int) { change { $0.textSize = min(96, max(18, size)) } }
    func setThinkingPause(_ seconds: Int) { change { $0.thinkingPause = min(15, max(0, seconds)) } }
    func setForceAnimations(_ enabled: Bool) { change { $0.forceAnimations = enabled } }

    func setIdentifyVoices(_ enabled: Bool) {
        change { $0.identifyVoices = enabled }
        if enabled, visible { ensureVoiceModel() }
    }

    func setAutoSpeak(_ enabled: Bool) {
        // Only what's said from now on is read out, not the conversation so far.
        if enabled { spokenTurns.formUnion(turns.map(\.id)) }
        change { $0.autoSpeak = enabled }
        if !enabled { speech.stop() }
    }

    /// Says `turn` in `language` (its original or its translation); tapping again stops it.
    func speak(_ turn: Turn, in language: String) {
        guard let text = turn.text(in: language) else { return }
        speech.say(Self.speechID(turn.id, language), text, language: language)
    }

    /// Clears the screen (everything is already saved as notes).
    func clearTurns() {
        heldTurnID = nil
        turns = []
        live = nil
        hold = nil
    }

    /// The model that understands both languages best (switches the notes' model too).
    var recommendedEngine: EngineChoice {
        EngineChoice.recommended(for: settings.myLanguage, settings.theirLanguage, installed: notes.installedEngines)
    }

    var activeEngineUnderstandsBoth: Bool {
        notes.activeEngine.understands(settings.myLanguage) && notes.activeEngine.understands(settings.theirLanguage)
    }

    func useRecommendedEngine() { notes.selectModel(recommendedEngine) }

    // MARK: Opening and closing

    func open() {
        guard !visible else { return }
        // Speakers are numbered afresh for each conversation (pausing keeps them).
        voices.reset()
        lastVoiceFor.removeAll()
        notes.enterConversation()
        visible = true
        prepareTranslation()
        if settings.identifyVoices { ensureVoiceModel() }
        startListening()
    }

    func close() {
        guard visible else { return }
        stopListening()
        visible = false
        live = nil
        message = nil
        speech.stop()
        releaseHeld()
        notes.leaveConversation()
    }

    // MARK: Listening

    func toggleListening() { if listening { stopListening() } else { startListening() } }

    func startListening() {
        guard !listening, visible else { return }
        listening = true
        let previous = finishingTask
        Task {
            // The recorder still holds the previous session's audio until it's done.
            await previous?.value
            guard listening, session == nil else { return }
            await begin()
        }
    }

    private func begin() async {
        guard await AVAudioApplication.requestRecordPermission() else {
            listening = false
            message = "Microphone access is needed for conversation mode. Enable it in Settings → VoicePad."
            return
        }
        let fileName = "\(UUID().uuidString).wav"
        do {
            try recorder.start(writingTo: store.recordingsURL.appendingPathComponent(fileName))
        } catch {
            listening = false
            message = error.localizedDescription
            return
        }
        notes.inputs.apply(notes.selectedInputID)
        store.add(Note(audioFileName: fileName, status: .recording))
        let noteID = store.notes.last!.id
        sessionCount += 1
        let s = Session(number: sessionCount, noteID: noteID, detector: notes.newSpeechDetector())
        session = s
        muted = []
        mutedFrom = speech.playing ? 0 : nil
        live = nil
        loopTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: self?.tick ?? .milliseconds(500))
                guard let self, !Task.isCancelled else { return }
                await self.step(s, final: false)
            }
        }
    }

    func stopListening() {
        guard listening else { return }
        listening = false
        live = nil
        hearing = false
        working = false
        liveTranslateTask?.cancel()
        guard let s = session else {
            releaseHeld()
            return
        }
        session = nil
        recorder.stop()
        let loop = loopTask
        loopTask = nil
        loop?.cancel()
        finishingTask = Task {
            await loop?.value
            while s.stepping { try? await Task.sleep(for: .milliseconds(50)) }
            if notes.isEngineReady { await step(s, final: true) }
            releaseHeld()
            // Give translations of the last turns a moment to land in the note.
            let pendingTranslations = Array(translationTasks.values)
            await withTaskGroup(of: Void.self) { group in
                group.addTask { for task in pendingTranslations { await task.value } }
                group.addTask { try? await Task.sleep(for: .seconds(15)) }
                await group.next()
                group.cancelAll()
            }
            save(s)
        }
    }

    private func speechPlaying(_ playing: Bool) {
        guard session != nil else { return }
        let now = recorder.sampleCount
        if playing {
            mutedFrom = now
        } else {
            // The room keeps echoing for a moment after the voice stops.
            muted.append((mutedFrom ?? now)..<(now + Self.rate * 3 / 10))
            mutedFrom = nil
        }
    }

    private func isEcho(_ span: ChunkPlanner.Span) -> Bool {
        if let from = mutedFrom, span.end > from { return true }
        return muted.contains { span.start < $0.upperBound && span.end > $0.lowerBound }
    }

    /// Finds newly finished utterances and turns them into turns, oldest
    /// first; then previews speech still in progress.
    private func step(_ s: Session, final: Bool) async {
        guard !s.stepping || final else { return }
        s.stepping = true
        defer { s.stepping = false }
        do {
            let now = recorder.sampleCount
            try await feed(s, now: now, final: final)
            let isHearing = !final && (s.detector?.inSpeech ?? false)
            if hearing != isHearing { hearing = isHearing }
            guard notes.isEngineReady else { // audio waits in memory (and on disk) until the model is ready
                releaseHeldIfDue(s, now: now, hearing: isHearing)
                return
            }
            while !s.pending.isEmpty {
                let span = s.pending.removeFirst()
                s.finishedUpTo = max(s.finishedUpTo, span.end)
                if !final { working = true }
                defer { working = false }
                try await finishUtterance(s, span, final: final)
            }
            if final { releaseHeld() } else { releaseHeldIfDue(s, now: recorder.sampleCount, hearing: isHearing) }
            if !final, session === s {
                if !speech.playing { try await updateLive(s, now: now) }
                if settings.autoSpeak { speakSettled(s, now: now) }
            }
            // Audio still needed: utterances waiting for the model, and speech in progress.
            let keep = s.pending.first?.start
                ?? ((s.detector?.inSpeech ?? false) ? max(s.quietAt - Self.rate, now - Self.maxLive - Self.rate) : s.detectedUpTo)
            recorder.discardBefore(keep - Self.pad - Self.rate)
        } catch {
            // While the model is being switched, calls fail; that's not worth a message.
            if notes.isEngineReady { message = "Transcription failed: \(error.localizedDescription)" }
        }
    }

    private func feed(_ s: Session, now: Int, final: Bool) async throws {
        guard let vad = s.detector else {
            // No detector: fixed four-second pieces.
            if now > s.detectedUpTo, final || now - s.detectedUpTo >= 4 * Self.rate {
                s.pending.append(.init(start: s.detectedUpTo, end: now))
                s.detectedUpTo = now
            }
            return
        }
        if now > s.detectedUpTo {
            // The detector counts from the first sample it was given: the recording's start.
            s.pending += try await vad.feed(recorder.samples(in: s.detectedUpTo..<now))
            s.detectedUpTo = now
            if !vad.inSpeech { s.quietAt = now }
        }
        if final { s.pending += try await vad.flush().map { .init(start: $0.start, end: min($0.end, now)) } }
    }

    /// Translates the held turn once its pause has outlasted the thinking window with nobody speaking.
    private func releaseHeldIfDue(_ s: Session, now: Int, hearing: Bool) {
        guard let id = heldTurnID else { return }
        guard let turn = turns.first(where: { $0.id == id }), turn.session == s.number else { return releaseHeld() }
        if hearing || !s.pending.isEmpty { return }
        if now - turn.end >= settings.thinkingPause * Self.rate { releaseHeld() }
    }

    /// Stops holding the held turn open and translates it.
    private func releaseHeld() {
        guard let id = heldTurnID else { return }
        heldTurnID = nil
        hold = nil
        if let i = turns.firstIndex(where: { $0.id == id }) { turns[i].held = false }
        translate(id)
    }

    private func finishUtterance(_ s: Session, _ span: ChunkPlanner.Span, final: Bool) async throws {
        if isEcho(span) { return }
        let audio = recorder.samples(in: max(0, span.start - Self.pad)..<(span.end + Self.pad))
        let transcript = try await notes.transcribeForConversation(audio)
        let text = transcript.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        let pair = settings.pair
        let previous = turns.last { $0.session == s.number }
        // People take turns: without other clues, it's the other language.
        let fallback = previous.map { ConversationLanguages.target(pair, spoken: $0.language) }
        let language = ConversationLanguages.spoken(pair, model: transcript.language, candidates: translator.languageCandidates(text),
                                                    text: text, fallback: fallback)
        let speaker = await voiceSpeaker(audio, language: language, pair: pair)

        let window = settings.thinkingPause
        // A turn held open for a thinking pause can be picked up again for as long as it's held.
        let maxGap = previous?.held == true ? max(Double(window), Turns.maxGapSeconds) : Turns.maxGapSeconds
        var turn: Turn
        if let previous, previous.id == turns.last?.id, !spokenTurns.contains(previous.id),
           Turns.continues(previous, session: s.number, speaker: speaker, language: language, start: span.start,
                           sampleRate: Self.rate, maxGapSeconds: maxGap) {
            turn = previous
            turn.text = Turns.join(previous.text, text)
            turn.end = span.end
            turn.romanization = translator.romanize(language, turn.text)
            turn.pending = true
        } else {
            turn = Turn(
                id: nextTurnID, session: s.number, speaker: speaker, language: language, text: text,
                target: ConversationLanguages.target(pair, spoken: language),
                // Until the real translation lands, the preview's is better than nothing.
                translation: live?.language == language ? live?.translation : nil,
                pending: true, romanization: translator.romanize(language, text), start: span.start, end: span.end
            )
            nextTurnID += 1
        }
        // Sounds unfinished: hold it open rather than translate a fragment.
        let holdIt = !final && window > 0 && Turns.looksUnfinished(turn.text) && turn.text.count < Turns.maxTurnChars
        turn.held = holdIt
        if let held = heldTurnID, held != turn.id { releaseHeld() }
        heldTurnID = holdIt ? turn.id : nil
        let total = TimeInterval(window)
        let left = min(total, max(0, total - Double(recorder.sampleCount - span.end) / Double(Self.rate)))
        if turns.last?.id == turn.id { turns[turns.count - 1] = turn } else { turns.append(turn) }
        live = nil
        hold = holdIt ? Hold(turnID: turn.id, endsAt: .now + left, total: total) : nil
        liveTranslateTask?.cancel()
        if holdIt { translationTasks.removeValue(forKey: turn.id)?.cancel() } else { translate(turn.id) }
        // Keep the transcript on disk in case the app dies.
        let soFar = transcriptOf(s.number)
        store.update(s.noteID) { $0.partialText = soFar }
    }

    /// Who spoke `audio`: by voice when the model is ready, else by language.
    private func voiceSpeaker(_ audio: [Float], language: String, pair: (String, String)) async -> SpeakerRef {
        let byLanguage = SpeakerRef(source: .language, index: language == pair.0 ? 0 : 1)
        guard settings.identifyVoices, let model = embedder else { return byLanguage }
        let embedding = await Task.detached { model.embed(audio) }.value
        guard let embedding else { return lastVoiceFor[language] ?? byLanguage }
        let ref = SpeakerRef(source: .voice, index: voices.assign(embedding, language: language))
        lastVoiceFor[language] = ref
        return ref
    }

    /// Reads out translations of turns that are finished: translated, and no
    /// longer able to continue (the speaker has been quiet long enough).
    private func speakSettled(_ s: Session, now: Int) {
        for turn in turns where turn.session == s.number && !spokenTurns.contains(turn.id) {
            guard let translation = turn.translation, !turn.held, !turn.pending,
                  translationTasks[turn.id] == nil, Double(now - turn.end) >= Turns.maxGapSeconds * Double(Self.rate) else { continue }
            spokenTurns.insert(turn.id)
            speech.enqueue(Self.speechID(turn.id, turn.target), translation, language: turn.target)
        }
    }

    /// Previews what's being said right now.
    private func updateLive(_ s: Session, now: Int) async throws {
        guard s.detector?.inSpeech == true else {
            if live != nil { live = nil }
            return
        }
        // The detector confirms speech a moment after it starts; back up a second.
        let from = max(s.quietAt - Self.rate, s.finishedUpTo, now - Self.maxLive, 0)
        guard now - from >= Self.rate / 2 else { return }
        let result = try await notes.transcribeForConversation(recorder.samples(in: from..<now))
        let text = result.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard session === s, listening, !text.isEmpty else { return }
        let pair = settings.pair
        let language = ConversationLanguages.spoken(pair, model: result.language, candidates: translator.languageCandidates(text), text: text)
        let old = live
        live = Turn(
            id: -1, session: s.number, speaker: nil, language: language, text: text,
            target: ConversationLanguages.target(pair, spoken: language),
            // Keep showing the previous translation until the new one arrives.
            translation: old?.language == language ? old?.translation : nil,
            romanization: translator.romanize(language, text), start: from, end: now
        )
        guard old?.text != text, let current = live else { return }
        liveTranslateTask?.cancel()
        liveTranslateTask = Task {
            try? await Task.sleep(for: .milliseconds(200)) // let quick changes settle
            guard !Task.isCancelled,
                  let translated = try? await translator.translate(text, from: current.language, to: current.target),
                  live?.text == text else { return }
            live?.translation = translated
        }
    }

    /// Translates turn `id` (again, if its text grew) into the other language.
    private func translate(_ id: Int) {
        translationTasks.removeValue(forKey: id)?.cancel()
        guard let turn = turns.first(where: { $0.id == id }) else { return }
        let source = turn.text
        let task = Task {
            var failure: Error?
            var result: String?
            do {
                result = try await translator.translate(source, from: turn.language, to: turn.target)
            } catch is CancellationError {
                return
            } catch {
                failure = error
            }
            if let i = turns.firstIndex(where: { $0.id == id }), turns[i].text == source {
                if let result {
                    turns[i].translation = result
                    turns[i].translationFailed = false
                    turns[i].translationRomanization = translator.romanize(turns[i].target, result)
                } else {
                    turns[i].translationFailed = true
                }
                turns[i].pending = false
            }
            if let failure { message = "Translation failed: \(failure.localizedDescription)" }
            translationTasks.removeValue(forKey: id)
        }
        translationTasks[id] = task
    }

    private func transcriptOf(_ sessionNumber: Int) -> String {
        Turns.transcript(turns.filter { $0.session == sessionNumber }, label: Self.speakerLabel)
    }

    /// Saves the session as a note: the speaker-labelled transcript with translations, plus its audio.
    private func save(_ s: Session) {
        let sessionTurns = turns.filter { $0.session == s.number }
        let duration = Double(recorder.sampleCount) / Double(Self.rate)
        if let error = recorder.writeError { message = "Part of this recording couldn't be saved: \(error)" }
        guard !sessionTurns.isEmpty else {
            if !notes.isEngineReady, duration >= NoteStore.minSeconds {
                // The model never got ready: keep the audio, it's transcribed like any note.
                store.update(s.noteID) {
                    $0.status = .pending
                    $0.duration = duration
                }
            } else {
                store.delete(s.noteID)
            }
            return
        }
        let counts = Dictionary(sessionTurns.map { ($0.language, 1) }, uniquingKeysWith: +)
        let language = counts.max { $0.value < $1.value }?.key
        let text = transcriptOf(s.number)
        let engine = "Conversation · " + notes.activeEngine.shortName
        store.update(s.noteID) {
            $0 = $0.withTranscription(text, source: .transcription, engine: engine, language: language)
            $0.status = .done
            $0.duration = duration
            $0.partialText = nil
            $0.lastError = nil
            // The translations are already in the transcript.
            if let revision = $0.revisions.last {
                $0.translation = NoteTranslation(revisionID: revision.id, status: .notNeeded)
            }
        }
    }

    // MARK: Models

    /// Gets both directions' translation packs ready (iOS asks to download them once).
    private func prepareTranslation() {
        prepareTask?.cancel()
        let (a, b) = settings.pair
        prepareTask = Task {
            translation = .preparing(nil)
            do {
                try await translator.prepare(a, b)
                translation = .ready
            } catch is CancellationError {
            } catch {
                translation = .failed(error.localizedDescription)
            }
        }
    }

    func retryModels() {
        prepareTranslation()
        if settings.identifyVoices { ensureVoiceModel() }
    }

    private func ensureVoiceModel() {
        guard embedder == nil, embedderTask == nil else { return }
        voiceModel = .preparing(nil)
        embedderTask = Task {
            defer { embedderTask = nil }
            do {
                embedder = try await SpeakerEmbedder.load { p in
                    Task { @MainActor [weak self] in
                        if case .preparing = self?.voiceModel, p < 1 { self?.voiceModel = .preparing(p) }
                    }
                }
                voiceModel = .ready
            } catch {
                voiceModel = .failed(error.localizedDescription)
            }
        }
    }
}
