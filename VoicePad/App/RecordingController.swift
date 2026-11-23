import AVFoundation
import Foundation
import Observation
import SwiftUI

/// Owns the recorder and the speech engine, and turns live audio into text.
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

    // MARK: Observable state

    private(set) var isRecording = false
    private(set) var level: Float = 0
    private(set) var startedAt: Date?
    private(set) var liveText = ""
    private(set) var engineStatus: EngineStatus = .idle
    private(set) var errorMessage: String?
    private(set) var recordingNoteID: Note.ID?
    /// Notes currently being (re)transcribed from their audio file.
    private(set) var busyNoteIDs: Set<Note.ID> = []

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

    // MARK: Private

    private static let engineKey = "engineChoice"
    private static let autoRecordKey = "autoRecordOnOpen"
    private let chunkSeconds: Double = 25
    private let minCutSeconds: Double = 18
    private let tickSeconds: Double = 1.2

    private let store: NoteStore
    private let recorder = AudioRecorder()
    private var engine: TranscriptionEngine?
    private var engineLoadID = UUID()
    private var liveTask: Task<Void, Never>?
    private var meterTask: Task<Void, Never>?
    private var serialTail: Task<Void, Never>?

    private var committedText = ""
    private var pendingText = ""
    private var chunkStart = 0

    init(store: NoteStore) {
        self.store = store
        let saved = UserDefaults.standard.string(forKey: Self.engineKey).flatMap(EngineChoice.init(rawValue:))
        engineChoice = saved ?? .parakeetV3
        autoRecordOnOpen = UserDefaults.standard.object(forKey: Self.autoRecordKey) as? Bool ?? true

        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            Task { @MainActor in await self?.stop() }
        }
    }

    // MARK: Engine

    func loadEngine() {
        let choice = engineChoice
        let loadID = UUID()
        engineLoadID = loadID
        engine = nil
        engineStatus = .loading(0)
        let newEngine = choice.makeEngine()
        Task {
            do {
                try await newEngine.load { fraction in
                    Task { @MainActor [weak self] in
                        guard let self, self.engineLoadID == loadID, fraction < 1 else { return }
                        self.engineStatus = .loading(fraction)
                    }
                }
                guard engineLoadID == loadID else { return }
                engine = newEngine
                engineStatus = .ready
                await transcribeUntranscribedNotes()
            } catch {
                guard engineLoadID == loadID else { return }
                engineStatus = .failed(error.localizedDescription)
            }
        }
    }

    /// Runs model calls one at a time; engines are not re-entrant.
    private func transcribeSerially(_ samples: [Float]) async throws -> String {
        guard let engine else { return "" }
        let previous = serialTail
        let task = Task { @MainActor () async throws -> String in
            await previous?.value
            return try await engine.transcribe(samples)
        }
        serialTail = Task { _ = try? await task.value }
        return try await task.value
    }

    // MARK: Recording

    /// Called whenever the app comes to the foreground.
    func appBecameActive() async {
        if engine == nil, case .idle = engineStatus { loadEngine() }
        guard autoRecordOnOpen, !isRecording else { return }
        await start()
    }

    func start() async {
        guard !isRecording else { return }
        errorMessage = nil

        guard await AVAudioApplication.requestRecordPermission() else {
            errorMessage = "Microphone access is off. Enable it in Settings → VoicePad."
            return
        }

        let fileName = "\(UUID().uuidString).wav"
        let url = NoteStore.recordingsURL.appendingPathComponent(fileName)
        do {
            try recorder.start(writingTo: url)
        } catch {
            errorMessage = error.localizedDescription
            return
        }

        var note = Note()
        note.audioFileName = fileName
        store.add(note)
        recordingNoteID = note.id

        committedText = ""
        pendingText = ""
        liveText = ""
        chunkStart = 0
        startedAt = .now
        isRecording = true

        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.level = self.recorder.level
                try? await Task.sleep(for: .milliseconds(50))
            }
        }
        liveTask = Task { [weak self] in
            await self?.liveLoop()
        }
    }

    func stop() async {
        guard isRecording, let noteID = recordingNoteID else { return }
        recorder.stop()
        isRecording = false
        level = 0
        meterTask?.cancel()
        liveTask?.cancel()
        await liveTask?.value
        liveTask = nil

        let duration = Double(recorder.sampleCount) / AudioRecorder.sampleRate
        startedAt = nil
        // Opening the app always starts a recording; drop ones that were
        // stopped before anything could have been said.
        if duration < 1 {
            store.delete(noteID)
            recordingNoteID = nil
            liveText = ""
            return
        }
        store.setDuration(duration, for: noteID)

        if engine != nil {
            busyNoteIDs.insert(noteID)
            await finishChunks(final: true)
            busyNoteIDs.remove(noteID)
            store.addRevision(to: noteID, text: liveText, source: .transcription, engine: engineChoice.shortName)
        }
        // Without a ready engine the note keeps its audio and is transcribed
        // automatically once the model finishes loading.
        recordingNoteID = nil
        liveText = ""
    }

    func toggle() async {
        if isRecording { await stop() } else { await start() }
    }

    private func liveLoop() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(tickSeconds))
            guard !Task.isCancelled, engine != nil else { continue }
            await finishChunks(final: false)
        }
    }

    /// Commits every full chunk, then transcribes the open tail.
    private func finishChunks(final: Bool) async {
        let chunkLength = Int(chunkSeconds * AudioRecorder.sampleRate)
        do {
            while recorder.sampleCount - chunkStart >= chunkLength {
                if !final, Task.isCancelled { return }
                let cut = quietestCut(from: chunkStart)
                let text = try await transcribeSerially(recorder.samples(in: chunkStart..<cut))
                committedText = join(committedText, text)
                chunkStart = cut
                pendingText = ""
                liveText = committedText
            }
            if !final, Task.isCancelled { return }
            let end = recorder.sampleCount
            let tail = try await transcribeSerially(recorder.samples(in: chunkStart..<end))
            pendingText = tail
            liveText = join(committedText, pendingText)
        } catch {
            errorMessage = "Transcription failed: \(error.localizedDescription)"
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

    // MARK: File-based transcription

    /// Re-runs the current model over a note's saved audio and stores the
    /// result as a new revision.
    func retranscribe(_ noteID: Note.ID) async {
        guard let note = store.note(id: noteID), let url = store.audioURL(for: note), engine != nil else { return }
        let source: Revision.Source = note.revisions.isEmpty ? .transcription : .retranscription
        busyNoteIDs.insert(noteID)
        defer { busyNoteIDs.remove(noteID) }
        do {
            let samples = try await Task.detached { try AudioFileLoader.loadSamples(from: url) }.value
            let text = try await transcribeFile(samples)
            store.addRevision(to: noteID, text: text, source: source, engine: engineChoice.shortName)
        } catch {
            errorMessage = "Transcription failed: \(error.localizedDescription)"
        }
    }

    private func transcribeFile(_ samples: [Float]) async throws -> String {
        let chunkLength = Int(chunkSeconds * AudioRecorder.sampleRate)
        var text = ""
        var start = 0
        while start < samples.count {
            let end = min(start + chunkLength, samples.count)
            text = join(text, try await transcribeSerially(Array(samples[start..<end])))
            start = end
        }
        return text
    }

    private func transcribeUntranscribedNotes() async {
        let pending = store.notes.filter { $0.revisions.isEmpty && $0.id != recordingNoteID && $0.audioFileName != nil }
        for note in pending {
            await retranscribe(note.id)
        }
    }
}
