import AVFoundation
import Foundation
import Observation
import SwiftUI

/// Owns the recorder and the speech engine, and turns audio into text.
///
/// Recordings are never thrown away because a model failed. Every note carries
/// a saved `TranscriptionStatus`; the audio stays on disk until it has text,
/// and notes that still need text are retried automatically whenever a model
/// becomes ready (up to `maxAutoAttempts` times, so a model that crashes the
/// app can't trap it in a loop) or on demand from the UI.
///
/// Live transcription works on chunks of up to `chunkSeconds`. While a chunk is
/// open it is re-transcribed every `tickSeconds` so the bubble updates as you
/// speak. Once it is long enough it is cut at the quietest nearby moment,
/// its text is committed, and a new chunk starts. This keeps each model call
/// short no matter how long the recording runs.
@MainActor
@Observable
final class RecordingController {
    enum EngineStatus: Equatable {
        case idle
        case loading(Double)
        case ready
        case failed(String)
    }

    static let maxAutoAttempts = 3

    // MARK: Observable state

    private(set) var isRecording = false
    private(set) var level: Float = 0
    private(set) var startedAt: Date?
    private(set) var liveText = ""
    private(set) var engineStatus: EngineStatus = .idle
    var errorMessage: String?
    private(set) var recordingNoteID: Note.ID?

    var engineChoice: EngineChoice {
        didSet {
            guard engineChoice != oldValue else { return }
            UserDefaults.standard.set(engineChoice.rawValue, forKey: Self.engineKey)
            loadEngine()
        }
    }

    var autoRecordOnOpen: Bool {
        didSet { UserDefaults.standard.set(autoRecordOnOpen, forKey: Self.autoRecordKey) }
    }

    /// Detect non-English speech and add an English translation (and pinyin).
    var autoTranslate: Bool {
        didSet {
            UserDefaults.standard.set(autoTranslate, forKey: Self.autoTranslateKey)
            if autoTranslate { processTranslations() }
        }
    }

    /// Chosen microphone's id; nil means iOS picks (automatic).
    var selectedInputID: String? {
        didSet {
            UserDefaults.standard.set(selectedInputID, forKey: Self.inputKey)
            inputs.apply(selectedInputID)
        }
    }

    let inputs = AudioInputs()
    let translator = EnglishTranslator()

    // MARK: Private

    private static let engineKey = "engineChoice"
    private static let autoRecordKey = "autoRecordOnOpen"
    private static let inputKey = "inputDevice"
    private static let autoTranslateKey = "autoTranslate"
    private let chunkSeconds: Double = 25
    private let minCutSeconds: Double = 18
    private let tickSeconds: Double = 1.2

    private let store: NoteStore
    @ObservationIgnored private let recorder = AudioRecorder()
    @ObservationIgnored private var engine: TranscriptionEngine?
    @ObservationIgnored private var engineLoadID = UUID()
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var liveTask: Task<Void, Never>?
    @ObservationIgnored private var meterTask: Task<Void, Never>?
    @ObservationIgnored private var queueTask: Task<Void, Never>?
    @ObservationIgnored private var serialTail: Task<Void, Never>?

    @ObservationIgnored private var committedText = ""
    @ObservationIgnored private var chunkStart = 0
    /// Languages the model reported for this recording's committed chunks.
    @ObservationIgnored private var chunkLanguages: [String] = []
    @ObservationIgnored private var tailLanguage: String?
    @ObservationIgnored private var translating = false
    /// Notes the user asked to translate; handled before automatic ones.
    @ObservationIgnored private var manualTranslations = Set<Note.ID>()
    /// Note+revision pairs already attempted since the app was last opened; failures wait for the next open.
    @ObservationIgnored private var translationTried = Set<String>()

    init(store: NoteStore) {
        self.store = store
        let saved = UserDefaults.standard.string(forKey: Self.engineKey).flatMap(EngineChoice.init(rawValue:))
        engineChoice = saved ?? .parakeetV3
        autoRecordOnOpen = UserDefaults.standard.object(forKey: Self.autoRecordKey) as? Bool ?? true
        selectedInputID = UserDefaults.standard.string(forKey: Self.inputKey)
        autoTranslate = UserDefaults.standard.object(forKey: Self.autoTranslateKey) as? Bool ?? true

        // Before anything records: repair what a crash or kill left behind.
        store.recoverAfterRestart()
        // Translate whenever a note gets new text (transcription, edit, restore).
        store.onChange = { [weak self] in self?.processTranslations() }

        inputs.onDevicesChanged = { [weak self] before in
            // The chosen mic was just plugged back in: switch to it.
            guard let self, let id = self.selectedInputID, !before.contains(id),
                  self.inputs.devices.contains(where: { $0.id == id }) else { return }
            self.inputs.apply(id)
        }
        inputs.apply(selectedInputID)

        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            Task { @MainActor in await self?.stop() }
        }
    }

    // MARK: Engine

    var isEngineReady: Bool { engineStatus == .ready && engine != nil }

    func loadEngine() {
        let choice = engineChoice
        let loadID = UUID()
        engineLoadID = loadID
        engine = nil
        engineStatus = .loading(0)
        let newEngine = choice.makeEngine()
        loadTask = Task {
            do {
                try await newEngine.load { fraction in
                    Task { @MainActor in
                        guard self.engineLoadID == loadID, fraction < 1 else { return }
                        self.engineStatus = .loading(fraction)
                    }
                }
                guard engineLoadID == loadID else { return }
                engine = newEngine
                engineStatus = .ready
                processQueue()
            } catch {
                guard engineLoadID == loadID else { return }
                engineStatus = .failed(error.localizedDescription)
            }
        }
    }

    /// Runs model calls one at a time; engines are not re-entrant.
    private func transcribeSerially(_ samples: [Float]) async throws -> Transcript {
        guard let engine else { throw TranscriptionError.modelNotLoaded }
        let previous = serialTail
        let task = Task { @MainActor () async throws -> Transcript in
            await previous?.value
            return try await engine.transcribe(samples)
        }
        serialTail = Task { _ = try? await task.value }
        return try await task.value
    }

    // MARK: Recording

    /// Called whenever the app comes to the foreground.
    func appBecameActive() async {
        translationTried.removeAll()
        processTranslations()
        if engine == nil, case .idle = engineStatus { loadEngine() } else { processQueue() }
        guard autoRecordOnOpen, !isRecording, recordingNoteID == nil else { return }
        await start()
    }

    func start() async {
        guard !isRecording, recordingNoteID == nil else { return }
        errorMessage = nil

        guard await AVAudioApplication.requestRecordPermission() else {
            errorMessage = "Microphone access is off. Enable it in Settings → VoicePad."
            return
        }

        let fileName = "\(UUID().uuidString).wav"
        let url = store.recordingsURL.appendingPathComponent(fileName)
        do {
            try recorder.start(writingTo: url)
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        // Activating the session can reset the route; re-apply the chosen mic.
        inputs.apply(selectedInputID)

        // Saved before any audio arrives, so a crash can always find it.
        let note = Note(audioFileName: fileName, status: .recording)
        store.add(note)
        recordingNoteID = note.id

        committedText = ""
        liveText = ""
        chunkStart = 0
        chunkLanguages = []
        tailLanguage = nil
        startedAt = .now
        isRecording = true

        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.level = self.recorder.level
                try? await Task.sleep(for: .milliseconds(50))
            }
        }
        let noteID = note.id
        liveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(self?.tickSeconds ?? 1.2))
                guard let self, !Task.isCancelled else { return }
                if self.isEngineReady { _ = await self.processChunks(noteID: noteID, final: false) }
            }
        }
    }

    func stop() async {
        guard isRecording, let noteID = recordingNoteID else { return }
        recorder.stop()
        isRecording = false
        level = 0
        startedAt = nil
        meterTask?.cancel()
        liveTask?.cancel()
        await liveTask?.value
        liveTask = nil
        inputs.refresh()
        if let writeError = recorder.writeError {
            errorMessage = "Part of this recording couldn't be saved: \(writeError)"
        }

        let duration = Double(recorder.sampleCount) / AudioRecorder.sampleRate
        // Opening the app always starts a recording; drop ones that were
        // stopped before anything could have been said.
        if duration < NoteStore.minSeconds {
            store.delete(noteID)
            recordingNoteID = nil
            liveText = ""
            return
        }

        if !isEngineReady {
            // Audio is safe on disk; it is transcribed once the model is ready.
            let partial = liveText
            store.update(noteID) {
                $0.status = .pending
                $0.duration = duration
                $0.partialText = partial.isEmpty ? nil : partial
            }
        } else {
            store.update(noteID) {
                $0.status = .inProgress
                $0.duration = duration
                $0.attempts += 1
            }
            let error = await processChunks(noteID: noteID, final: true)
            let text = liveText
            let engineName = engineChoice.shortName
            let language = Self.mostCommon(chunkLanguages + [tailLanguage].compactMap { $0 })
            store.update(noteID) {
                if let error {
                    $0.status = .failed
                    $0.lastError = error
                    $0.partialText = text.isEmpty ? nil : text
                } else {
                    $0 = $0.withRevision(text, source: .transcription, engine: engineName, language: language)
                    $0.status = .done
                    $0.lastError = nil
                    $0.partialText = nil
                }
            }
        }
        recordingNoteID = nil
        liveText = ""
        processQueue()
    }

    func toggle() async {
        if isRecording { await stop() } else { await start() }
    }

    /// Commits every full chunk, then transcribes the open tail. Returns nil on
    /// success or the error message. Failed chunks are not skipped: the chunk
    /// start only advances after success, so the next pass retries them.
    private func processChunks(noteID: Note.ID, final: Bool) async -> String? {
        let chunkLength = Int(chunkSeconds * AudioRecorder.sampleRate)
        do {
            while recorder.sampleCount - chunkStart >= chunkLength {
                if !final, Task.isCancelled { return nil }
                let cut = quietestCut(from: chunkStart)
                let result = try await transcribeSerially(recorder.samples(in: chunkStart..<cut))
                committedText = join(committedText, result.text)
                if let language = result.language { chunkLanguages.append(language) }
                chunkStart = cut
                liveText = committedText
                // Keep committed text on disk in case the app dies.
                let committed = committedText
                store.update(noteID) { $0.partialText = committed.isEmpty ? nil : committed }
            }
            if !final, Task.isCancelled { return nil }
            let tail = try await transcribeSerially(recorder.samples(in: chunkStart..<recorder.sampleCount))
            tailLanguage = tail.language
            liveText = join(committedText, tail.text)
            return nil
        } catch {
            if final { errorMessage = "Transcription failed — the recording is saved and can be retried." }
            return error.localizedDescription
        }
    }

    /// Finds the quietest 100 ms window between `minCutSeconds` and
    /// `chunkSeconds` after `start`, so chunks are split between words.
    private func quietestCut(from start: Int) -> Int {
        let rate = AudioRecorder.sampleRate
        let lower = start + Int(minCutSeconds * rate)
        let upper = start + Int(chunkSeconds * rate)
        let window = recorder.samples(in: lower..<upper)
        let frame = Int(rate * 0.1)
        guard window.count >= frame else { return upper }
        var best = upper
        var bestEnergy = Float.greatestFiniteMagnitude
        var offset = 0
        while offset + frame <= window.count {
            var energy: Float = 0
            for i in offset..<(offset + frame) { energy += window[i] * window[i] }
            if energy < bestEnergy {
                bestEnergy = energy
                best = lower + offset + frame / 2
            }
            offset += frame
        }
        return best
    }

    private func join(_ a: String, _ b: String) -> String {
        [a, b].filter { !$0.isEmpty }.joined(separator: " ")
    }

    private static func mostCommon(_ languages: [String]) -> String? {
        Dictionary(languages.map { ($0, 1) }, uniquingKeysWith: +).max { $0.value < $1.value }?.key
    }

    // MARK: Saved-audio transcription and retries

    /// Re-runs the current model over a note's audio (manual retry or re-transcribe).
    func retranscribe(_ noteID: Note.ID) {
        Task { await transcribeSaved(noteID) }
    }

    /// Retries every recording that still needs text, ignoring the auto-retry limit.
    func retryAll() {
        Task {
            for note in store.notes where note.needsTranscription && note.id != recordingNoteID {
                await transcribeSaved(note.id)
            }
        }
    }

    /// Works through notes that still need text, oldest first. Skips ones that
    /// already failed `maxAutoAttempts` times; those wait for a manual retry.
    /// Runs only while not recording, so live text stays responsive.
    private func processQueue() {
        if queueRunning { return }
        queueRunning = true
        queueTask = Task {
            defer { queueRunning = false }
            // Each note gets at most one attempt per pass; the next pass comes
            // with the next app launch, model load or finished recording.
            var tried = Set<Note.ID>()
            while isEngineReady, !isRecording {
                guard let next = store.notes.first(where: {
                    $0.needsTranscription && $0.attempts < Self.maxAutoAttempts &&
                        $0.id != recordingNoteID && !tried.contains($0.id)
                }) else { break }
                tried.insert(next.id)
                await transcribeSaved(next.id)
            }
        }
    }

    @ObservationIgnored private var queueRunning = false

    private func transcribeSaved(_ noteID: Note.ID) async {
        guard let note = store.note(id: noteID), note.status != .inProgress, note.status != .recording else { return }
        guard let url = store.audioURL(for: note), FileManager.default.fileExists(atPath: url.path) else {
            store.update(noteID) {
                $0.status = .failed
                $0.lastError = "The audio file is missing."
            }
            return
        }
        guard isEngineReady else {
            store.update(noteID) { if $0.needsTranscription { $0.status = .pending } }
            errorMessage = "The model isn't ready yet — the recording will be transcribed when it is."
            return
        }
        let hadText = note.status == .done && !note.revisions.isEmpty
        let source: Revision.Source = note.revisions.isEmpty ? .transcription : .retranscription
        let engineName = engineChoice.shortName

        store.update(noteID) {
            $0.status = .inProgress
            $0.attempts += 1
        }
        do {
            let samples = try await Task.detached { try WavFile.read(url) }.value
            let chunkLength = Int(chunkSeconds * AudioRecorder.sampleRate)
            var text = ""
            var languages: [String] = []
            var start = 0
            while start < samples.count {
                let end = min(start + chunkLength, samples.count)
                let result = try await transcribeSerially(Array(samples[start..<end]))
                text = join(text, result.text)
                if let language = result.language { languages.append(language) }
                start = end
            }
            let language = Self.mostCommon(languages)
            store.update(noteID) {
                $0 = $0.withRevision(text, source: source, engine: engineName, language: language)
                $0.status = .done
                $0.lastError = nil
                $0.partialText = nil
                if $0.duration <= 0 { $0.duration = Double(samples.count) / AudioRecorder.sampleRate }
            }
        } catch {
            let message = error is CancellationError ? "Transcription was interrupted." : error.localizedDescription
            // A failed re-transcription keeps the existing text as current.
            store.update(noteID) {
                $0.status = hadText ? .done : .failed
                $0.lastError = message
            }
            if hadText { errorMessage = "Re-transcription failed: \(message)" }
        }
    }
}

extension RecordingController {
    // MARK: Translation

    /// Translates one note now, even if automatic translation is off or gave up.
    /// Goes through the same queue so it never collides with a request in flight.
    func translateNow(_ noteID: Note.ID) {
        guard let note = store.note(id: noteID) else { return }
        translationTried.remove(Self.translationKey(note))
        manualTranslations.insert(noteID)
        processTranslations()
    }

    /// Works through notes whose current text has no translation yet, one at a
    /// time: ones the user asked for first, then (if enabled) the rest.
    fileprivate func processTranslations() {
        guard autoTranslate || !manualTranslations.isEmpty, !translating else { return }
        translating = true
        Task {
            defer { translating = false }
            while true {
                let next: Note.ID
                if let manual = manualTranslations.first {
                    manualTranslations.remove(manual)
                    guard let note = store.note(id: manual) else { continue }
                    translationTried.insert(Self.translationKey(note))
                    next = manual
                } else if autoTranslate, let note = store.notes.first(where: {
                    !translationTried.contains(Self.translationKey($0)) && $0.status == .done &&
                        $0.needsTranslation(maxAttempts: Self.maxAutoAttempts)
                }) {
                    translationTried.insert(Self.translationKey(note))
                    next = note.id
                } else {
                    break
                }
                await translateNote(next)
            }
        }
    }

    private static func translationKey(_ note: Note) -> String {
        "\(note.id):\(note.revisions.last?.id.uuidString ?? "")"
    }

    private func translateNote(_ noteID: Note.ID) async {
        guard let note = store.note(id: noteID), let revision = note.revisions.last,
              !revision.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let previousAttempts = note.translation?.revisionID == revision.id ? note.translation?.attempts ?? 0 : 0
        store.update(noteID) {
            $0.translation = NoteTranslation(revisionID: revision.id, status: .pending, attempts: previousAttempts + 1)
        }
        var outcome: NoteTranslation
        do {
            let result = try await translator.translate(revision.text, modelLanguage: revision.language)
            outcome = NoteTranslation(
                revisionID: revision.id,
                status: result.english == nil ? .notNeeded : .done,
                language: result.language,
                english: result.english,
                romanization: result.romanization,
                attempts: previousAttempts + 1
            )
        } catch {
            let failure = error as? TranslationFailure
            outcome = NoteTranslation(
                revisionID: revision.id, status: .failed,
                language: failure?.language,
                romanization: failure?.romanization,
                attempts: previousAttempts + 1,
                error: error.localizedDescription
            )
        }
        // Only store it if the text didn't change while translating.
        store.update(noteID) { if $0.revisions.last?.id == revision.id { $0.translation = outcome } }
    }
}

enum TranscriptionError: LocalizedError {
    case modelNotLoaded
    var errorDescription: String? { "The model isn't loaded." }
}
