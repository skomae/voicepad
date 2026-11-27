import AVFoundation
import FluidAudio
import Foundation
import Observation
import SwiftUI
import UIKit

/// Owns the recorder and the speech engine, and turns audio into text.
///
/// Recordings are never thrown away because a model failed. Every note carries
/// a saved `TranscriptionStatus`; the audio stays on disk until it has text,
/// and notes that still need text are retried automatically whenever a model
/// becomes ready (up to `maxAutoAttempts` times, so a model that crashes the
/// app can't trap it in a loop) or on demand from the UI.
///
/// Audio reaches the model in pieces chosen by a voice-activity detector
/// (Silero): silence is skipped, and pieces end at pauses (see `ChunkPlanner`).
/// While recording, each finished utterance is transcribed and committed as
/// soon as the pause after it is detected, and the utterance still being
/// spoken is re-transcribed every `tickSeconds` for the live preview.
/// Committed audio is dropped from memory (it is in the WAV), and saved
/// recordings are read from disk piece by piece, so length is limited only by
/// storage.
@MainActor
@Observable
final class RecordingController {
    enum EngineStatus: Equatable {
        case idle
        case loading(LoadProgress)
        case ready
        case failed(String)
    }

    typealias Span = ChunkPlanner.Span

    static let maxAutoAttempts = 3
    /// Most robust to distant, noisy speech: in Android's testing with echo
    /// and background noise it made 10% word errors where Parakeet made 41%.
    static let backgroundEngine = EngineChoice.whisperLargeV3Turbo

    // MARK: Observable state

    private(set) var isRecording = false
    private(set) var level: Float = 0
    /// When the current part started (resets on Split).
    private(set) var startedAt: Date?
    private(set) var liveText = ""
    /// Translation of the note being recorded, updated as it is spoken.
    private(set) var liveTranslation: NoteTranslation?
    /// Which part of a split recording is being recorded (1 = not split).
    private(set) var partNumber = 1
    private(set) var engineStatus: EngineStatus = .idle
    var errorMessage: String?
    /// The note being recorded right now, if any.
    private(set) var recordingNoteID: Note.ID?
    /// The microphone is delivering only digital silence right now: nothing is
    /// being captured. Shown as a warning while recording.
    private(set) var noSoundWarning = false

    /// The note being recorded started before the model was ready, so it is
    /// only being recorded; its text comes once it's stopped (or split).
    private(set) var liveRecordOnly = false
    /// Stopped or split-off notes still being transcribed.
    private(set) var finishingCount = 0
    /// Background voices (announcements, TV): room microphone, louder speech, Whisper turbo.
    private(set) var backgroundVoices: Bool
    /// A short message for the user (shown as a toast), e.g. "Transcript copied".
    var notice: String?

    /// Light, dark, pure black (OLED), or follow the phone.
    var themeMode: ThemeMode {
        didSet { UserDefaults.standard.set(themeMode.rawValue, forKey: Self.themeKey) }
    }

    /// When a recording (or split part) finishes transcribing, copy it to the clipboard.
    var copyWhenDone: Bool {
        didSet { UserDefaults.standard.set(copyWhenDone, forKey: Self.copyWhenDoneKey) }
    }

    /// Start a new note automatically after a silence or every few minutes.
    var autoSplit: AutoSplit {
        didSet { UserDefaults.standard.set(autoSplit.rawValue, forKey: Self.autoSplitKey) }
    }

    /// Automatic clean-up (Android #19): delete notes with no speech once they
    /// are this old.
    var autoDeleteEmpty: NoteCleanup.AutoAge {
        didSet {
            UserDefaults.standard.set(autoDeleteEmpty.rawValue, forKey: Self.autoDeleteEmptyKey)
            runAutoCleanup()
        }
    }

    /// Delete only the recordings of notes that have their text, once they are
    /// this old (Android #26). "Immediately" acts when a note gets its text.
    var autoDeleteAudio: NoteCleanup.AutoAge {
        didSet {
            UserDefaults.standard.set(autoDeleteAudio.rawValue, forKey: Self.autoDeleteAudioKey)
            runAutoCleanup()
        }
    }

    /// Shrink the recordings of notes this old (Android #19).
    var autoCompress: NoteCleanup.AutoAge {
        didSet {
            UserDefaults.standard.set(autoCompress.rawValue, forKey: Self.autoCompressKey)
            runAutoCleanup()
        }
    }

    /// Models that have loaded on this device, for the quick switcher.
    private(set) var installedEngines: Set<EngineChoice>

    /// The model chosen in Settings.
    var engineChoice: EngineChoice {
        didSet {
            guard engineChoice != oldValue else { return }
            UserDefaults.standard.set(engineChoice.rawValue, forKey: Self.engineKey)
            // While background voices is on, Whisper turbo stays in use.
            if !backgroundVoices { loadEngine() }
        }
    }

    /// The model actually in use: Whisper turbo while background voices is on.
    var activeEngine: EngineChoice { backgroundVoices ? Self.backgroundEngine : engineChoice }

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
    /// The user's dictionaries; the enabled ones correct every transcript.
    let dictionaries: DictionaryStore
    @ObservationIgnored private var vocabulary: VocabularyCorrector
    @ObservationIgnored private let liveActivity = LiveActivityPresenter()

    /// What the Live Activity shows; nil when there's nothing to show.
    var liveActivityState: RecordingActivityAttributes.ContentState? {
        if isRecording {
            let words = liveText.count > 120 ? "…" + liveText.suffix(120) : liveText
            return .init(startedAt: startedAt, part: partNumber, backgroundVoices: backgroundVoices,
                         latest: words, finishing: finishingCount)
        }
        if finishingCount > 0 {
            return .init(startedAt: nil, part: 1, backgroundVoices: false, latest: "", finishing: finishingCount)
        }
        return nil
    }

    // MARK: Private

    private static let engineKey = "engineChoice"
    private static let autoRecordKey = "autoRecordOnOpen"
    private static let inputKey = "inputDevice"
    private static let autoTranslateKey = "autoTranslate"
    private static let backgroundVoicesKey = "backgroundVoices"
    private static let installedKey = "installedEngines"
    private static let themeKey = "theme"
    private static let copyWhenDoneKey = "copyWhenDone"
    private static let autoSplitKey = "autoSplit"
    private static let autoDeleteEmptyKey = "autoDeleteEmpty"
    private static let autoDeleteAudioKey = "autoDeleteAudio"
    private static let autoCompressKey = "autoCompress"
    private static let onboardingKey = "onboardingDone"
    private static let rate = AudioRecorder.sampleRate
    /// Longest audio one model call gets (Whisper's window is 30 s).
    private static let maxChunk = Int(25 * rate)
    /// With Whisper, utterances are batched until about this long before committing.
    private static let whisperBatch = Int(15 * rate)
    /// Longest stretch kept in memory while the model loads (~38 MB), so the
    /// note being recorded can catch up instead of waiting until it's stopped.
    private static let maxCatchUp = Int(10 * 60 * rate)
    /// Context kept either side of detected speech.
    private static let pad = Int(0.25 * rate)
    private let tickSeconds: Double = 1.2

    let store: NoteStore
    @ObservationIgnored private let recorder = AudioRecorder()
    @ObservationIgnored private var engine: TranscriptionEngine?
    @ObservationIgnored private var vad: VadManager?
    @ObservationIgnored private var vadLoading = false
    @ObservationIgnored private var engineLoadID = UUID()
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var liveTask: Task<Void, Never>?
    @ObservationIgnored private var meterTask: Task<Void, Never>?
    @ObservationIgnored private var queueTask: Task<Void, Never>?
    @ObservationIgnored private var autoCleanupTask: Task<Void, Never>?

    /// Notes automatic compression already failed on, so it isn't retried forever.
    @ObservationIgnored private var autoCompressFailed = Set<Note.ID>()
    /// Notes already given their "no speech" second opinion in this process.
    @ObservationIgnored private var settledEmpty = Set<Note.ID>()
    @ObservationIgnored private var serialTail: Task<Void, Never>?
    @ObservationIgnored private var queueRunning = false

    /// The note being recorded.
    @ObservationIgnored private var session: LiveSession?
    /// Notes finishing their transcription after Stop or Split.
    @ObservationIgnored private var finishing: [LiveSession] = [] {
        didSet { finishingCount = finishing.count }
    }
    @ObservationIgnored private var tailTranslateTask: Task<Void, Never>?

    @ObservationIgnored fileprivate var translating = false
    /// Notes the user asked to translate; handled before automatic ones.
    @ObservationIgnored fileprivate var manualTranslations = Set<Note.ID>()
    /// Note+revision pairs already attempted since the app was last opened; failures wait for the next open.
    @ObservationIgnored fileprivate var translationTried = Set<String>()

    init(store: NoteStore) {
        self.store = store
        let dictionaries = DictionaryStore(baseURL: NoteStore.defaultBaseURL)
        self.dictionaries = dictionaries
        vocabulary = VocabularyCorrector(entries: dictionaries.activeWords())
        let saved = UserDefaults.standard.string(forKey: Self.engineKey).flatMap(EngineChoice.init(rawValue:))
        engineChoice = saved ?? .parakeetV3
        autoRecordOnOpen = UserDefaults.standard.object(forKey: Self.autoRecordKey) as? Bool ?? true
        selectedInputID = UserDefaults.standard.string(forKey: Self.inputKey)
        autoTranslate = UserDefaults.standard.object(forKey: Self.autoTranslateKey) as? Bool ?? true
        backgroundVoices = UserDefaults.standard.bool(forKey: Self.backgroundVoicesKey)
        themeMode = UserDefaults.standard.string(forKey: Self.themeKey).flatMap(ThemeMode.init(rawValue:)) ?? .system
        copyWhenDone = UserDefaults.standard.object(forKey: Self.copyWhenDoneKey) as? Bool ?? true
        autoSplit = UserDefaults.standard.string(forKey: Self.autoSplitKey).flatMap(AutoSplit.init(rawValue:)) ?? .default
        autoDeleteEmpty = UserDefaults.standard.string(forKey: Self.autoDeleteEmptyKey).flatMap(NoteCleanup.AutoAge.init(rawValue:)) ?? .off
        autoDeleteAudio = UserDefaults.standard.string(forKey: Self.autoDeleteAudioKey).flatMap(NoteCleanup.AutoAge.init(rawValue:)) ?? .off
        autoCompress = UserDefaults.standard.string(forKey: Self.autoCompressKey).flatMap(NoteCleanup.AutoAge.init(rawValue:)) ?? .off
        // People already using the app (mic allowed, notes saved) skip the walkthrough.
        onboardingDone = UserDefaults.standard.object(forKey: Self.onboardingKey) as? Bool
            ?? (AVAudioApplication.shared.recordPermission == .granted && !store.notes.isEmpty)
        microphoneDenied = AVAudioApplication.shared.recordPermission == .denied
        installedEngines = Set((UserDefaults.standard.stringArray(forKey: Self.installedKey) ?? []).compactMap(EngineChoice.init(rawValue:)))

        // Before anything records: repair what a crash or kill left behind.
        store.recoverAfterRestart()
        // Translate whenever a note gets new text (transcription, edit, restore).
        store.onChange = { [weak self] in self?.processTranslations() }
        // The user's dictionary words, spelled their way in every transcript.
        dictionaries.onChange = { [weak self] in
            guard let self else { return }
            self.vocabulary = VocabularyCorrector(entries: self.dictionaries.activeWords())
        }

        inputs.onDevicesChanged = { [weak self] before in
            // The chosen mic was just plugged back in: switch to it.
            guard let self, let id = self.selectedInputID, !before.contains(id),
                  self.inputs.devices.contains(where: { $0.id == id }) else { return }
            self.inputs.apply(id)
        }
        inputs.apply(selectedInputID)

        // Stop and Split from the Lock Screen / Dynamic Island.
        RecordingActions.stop = { [weak self] in self?.stop() }
        RecordingActions.split = { [weak self] in self?.split() }
        if !DebugFlags.on("noLiveActivity") { liveActivity.observe { [weak self] in self?.liveActivityState } }

        NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            Task { @MainActor in
                guard let self else { return }
                switch type {
                case .began:
                    // A call or another app took the microphone: save what we have.
                    Diagnostics.log("interruption began")
                    self.resumeAfterInterruption = self.isRecording
                    self.stop()
                case .ended:
                    // Pick up again with a fresh note once the microphone is free.
                    Diagnostics.log("interruption ended")
                    if self.resumeAfterInterruption, !self.isRecording, !self.conversationActive {
                        self.resumeAfterInterruption = false
                        await self.start()
                    }
                @unknown default: break
                }
            }
        }
        recorder.onNeedsRestart = { [weak self] newEngine in
            guard let self, self.isRecording else { return }
            Diagnostics.log("audio reconfigured (new engine: \(newEngine)); reopening the microphone")
            try? self.recorder.restartCapture(newEngine: newEngine)
        }

        // Automatic clean-up at launch and every 15 minutes, as Android sweeps.
        autoCleanupTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: RecordingController.autoCleanupInterval)
                guard let self, !Task.isCancelled else { return }
                self.runAutoCleanup()
            }
        }
        Task { [weak self] in self?.runAutoCleanup() }
    }

    // MARK: Engine

    var isEngineReady: Bool { engineStatus == .ready && engine != nil }

    /// Background voices on: switches the microphone to room capture right
    /// away (even mid-recording), normalises loudness, and loads Whisper turbo.
    func setBackgroundVoices(_ enabled: Bool) {
        guard enabled != backgroundVoices else { return }
        applyBackgroundVoices(enabled)
        loadEngine()
    }

    private func applyBackgroundVoices(_ enabled: Bool) {
        backgroundVoices = enabled
        UserDefaults.standard.set(enabled, forKey: Self.backgroundVoicesKey)
        if isRecording {
            do {
                try recorder.setRoomCapture(enabled)
            } catch {
                errorMessage = "Couldn't switch the microphone: \(error.localizedDescription)"
            }
            if enabled, let s = session { store.update(s.noteID) { $0.backgroundVoices = true } }
        }
    }

    /// Quick switcher: use `choice` from now on. Picking a regular model turns
    /// background voices off; picking the background model turns it on.
    func selectModel(_ choice: EngineChoice) {
        if choice == Self.backgroundEngine {
            setBackgroundVoices(true)
            return
        }
        let wasBackground = backgroundVoices
        if wasBackground { applyBackgroundVoices(false) }
        if engineChoice != choice {
            engineChoice = choice // its didSet loads the model
        } else if wasBackground {
            loadEngine()
        }
    }

    func loadEngine() {
        if DebugFlags.on("skipModelLoad") { Diagnostics.log("debug: model load skipped"); return }
        if DebugFlags.on("skipVAD") { Diagnostics.log("debug: VAD skipped") } else { loadVoiceActivity() }
        let choice = activeEngine
        let loadID = UUID()
        engineLoadID = loadID
        engine = nil
        engineStatus = .loading(.starting)
        Diagnostics.log("model load start: \(choice.rawValue)")
        let newEngine = choice.makeEngine()
        loadTask = Task {
            do {
                // Off the main thread: compiling a model can take minutes and must
                // never hold up recording or the screen.
                let loading = UncheckedBox(newEngine)
                try await Task.detached(priority: .utility) { try await loading.value.load { progress in
                    Task { @MainActor in
                        guard self.engineLoadID == loadID, case .loading(let old) = self.engineStatus else { return }
                        if old.stage != progress.stage { Diagnostics.log("model stage: \(progress.stage)") }
                        self.engineStatus = .loading(progress)
                    }
                } }.value
                guard engineLoadID == loadID else { return }
                engine = newEngine
                engineStatus = .ready
                Diagnostics.log("model ready: \(choice.rawValue)")
                installedEngines.insert(choice)
                UserDefaults.standard.set(installedEngines.map(\.rawValue), forKey: Self.installedKey)
                processQueue()
            } catch {
                guard engineLoadID == loadID else { return }
                engineStatus = .failed(error.localizedDescription)
            }
        }
    }

    /// Loads the voice-activity model once (a small download the first time).
    /// Until it's ready, audio is handled in whole stretches.
    private func loadVoiceActivity() {
        guard vad == nil, !vadLoading else { return }
        vadLoading = true
        Task {
            defer { vadLoading = false }
            vad = try? await VoiceActivity.load()
        }
    }

    private func newDetector() -> SpeechDetector? { vad.map(SpeechDetector.init(vad:)) }

    /// A speech detector for conversation mode (nil until the model is loaded).
    func newSpeechDetector() -> SpeechDetector? { newDetector() }

    // MARK: Conversation mode

    /// True while conversation mode has the microphone and the model; notes don't record meanwhile.
    @ObservationIgnored private(set) var conversationActive = false
    @ObservationIgnored private var resumeAfterConversation = false

    /// Hands the microphone and the model to conversation mode, saving any note being recorded.
    func enterConversation() {
        guard !conversationActive else { return }
        resumeAfterConversation = isRecording
        if isRecording { stop() }
        conversationActive = true
        if engine == nil, case .idle = engineStatus { loadEngine() }
    }

    /// Back to notes: recording resumes if it was on before conversation mode.
    func leaveConversation() {
        guard conversationActive else { return }
        conversationActive = false
        if resumeAfterConversation { Task { await start() } }
        processQueue()
    }

    /// One model call for conversation mode (shares the loaded model with notes).
    func transcribeForConversation(_ samples: [Float]) async throws -> Transcript {
        try await transcribeSerially(samples, normalize: false)
    }

    private var grouping: ChunkPlanner.Grouping { engine?.grouping ?? .utterances }

    /// Runs model calls one at a time; engines are not re-entrant.
    private func transcribeSerially(_ samples: [Float], normalize: Bool? = nil) async throws -> Transcript {
        guard let engine else { throw TranscriptionError.modelNotLoaded }
        let previous = serialTail
        let audio = normalize ?? backgroundVoices ? AudioLevel.normalize(samples) : samples
        let task = Task { @MainActor () async throws -> Transcript in
            await previous?.value
            let result = try await engine.transcribe(audio)
            // The user's dictionary words, spelled their way.
            return Transcript(text: vocabulary.correct(result.text), language: result.language, engine: result.engine)
        }
        serialTail = Task { _ = try? await task.value }
        return try await task.value
    }

    // MARK: Recording

    /// Called whenever the app comes to the foreground.
    func appBecameActive() async {
        // The user may have changed it in Settings meanwhile.
        microphoneDenied = AVAudioApplication.shared.recordPermission == .denied
        if !microphoneDenied, errorMessage?.hasPrefix("Microphone access is off") == true { errorMessage = nil }
        translationTried.removeAll()
        processTranslations()
        if engine == nil, case .idle = engineStatus { loadEngine() } else { processQueue() }
        // Never record before onboarding has asked for the microphone.
        guard onboardingDone, autoRecordOnOpen, !isRecording, !conversationActive else { return }
        await start()
    }

    // MARK: Stuck microphone

    /// For the diagnostics log: is the app really in front, and is the phone unlocked?
    private var appState: String {
        let state = ["active", "inactive", "background"][UIApplication.shared.applicationState.rawValue]
        return "app=\(state) unlocked=\(UIApplication.shared.isProtectedDataAvailable)"
    }

    @ObservationIgnored private var resumeAfterInterruption = false
    @ObservationIgnored private var silenceRestarts = 0
    @ObservationIgnored private var loggedFirstSignal = false
    @ObservationIgnored private var lastSilenceRestart = Date.distantPast
    private static let stuckSilence = Int(3 * rate)

    /// A few seconds of exact digital silence means the input is stuck (a real
    /// microphone always hears something): reopen it, at most every 8 s, and
    /// say so if that keeps failing.
    private func checkForStuckMicrophone() {
        guard isRecording else { noSoundWarning = false; return }
        // Warn quickly: 1.5 s of exact zeros means nothing at all is reaching VoicePad.
        let silent = recorder.silentSamples >= Int(1.5 * Self.rate)
        if silent != noSoundWarning {
            noSoundWarning = silent
            Diagnostics.log((silent ? "warning shown: no sound " : "warning cleared: sound is coming in ") + appState)
        }
        guard recorder.silentSamples >= Self.stuckSilence else {
            if !loggedFirstSignal, recorder.hasSignal {
                loggedFirstSignal = true
                Diagnostics.log("first sound after start")
            }
            if silenceRestarts > 0, recorder.hasSignal {
                Diagnostics.log("microphone recovered after \(silenceRestarts) reopen(s)")
                silenceRestarts = 0
                if errorMessage?.hasPrefix("The microphone") == true { errorMessage = nil }
            }
            return
        }
        guard !DebugFlags.on("noReopen") else { return }
        guard Date.now.timeIntervalSince(lastSilenceRestart) >= 8 else { return }
        lastSilenceRestart = .now
        silenceRestarts += 1
        Diagnostics.log("silent input, reopening (\(silenceRestarts)): " + appState + " " + recorder.describeInput())
        if AVAudioApplication.shared.isInputMuted { try? AVAudioApplication.shared.setInputMuted(false) }
        try? recorder.restartCapture(newEngine: silenceRestarts >= 2)
        if silenceRestarts >= 3 {
            errorMessage = "The microphone is giving only silence. If another app or a call is using it, close that; otherwise close VoicePad and open it again."
        }
    }

    // MARK: Permissions and onboarding

    /// The first-run walkthrough has been finished (it asks for each permission
    /// before anything records).
    var onboardingDone: Bool {
        didSet { UserDefaults.standard.set(onboardingDone, forKey: Self.onboardingKey) }
    }

    var microphonePermission: AVAudioApplication.recordPermission { AVAudioApplication.shared.recordPermission }

    /// The microphone was refused: recording needs the user to allow it in Settings.
    private(set) var microphoneDenied = false

    /// Shows iOS's microphone prompt (only the first time; afterwards it answers at once).
    func requestMicrophone() async -> Bool {
        let granted = await AVAudioApplication.requestRecordPermission()
        microphoneDenied = !granted
        return granted
    }

    /// Ends onboarding and, if wanted, starts recording right away.
    func finishOnboarding() async {
        onboardingDone = true
        if autoRecordOnOpen, !isRecording { await start() }
    }

    func start() async {
        guard !isRecording, !conversationActive else { return }
        errorMessage = nil

        guard await requestMicrophone() else {
            errorMessage = "Microphone access is off. Allow it in Settings to record."
            return
        }
        guard !isRecording else { return }

        let fileName = "\(UUID().uuidString).wav"
        do {
            try recorder.start(writingTo: store.recordingsURL.appendingPathComponent(fileName), roomCapture: backgroundVoices)
        } catch {
            errorMessage = error.localizedDescription
            return
        }
        // Activating the session can reset the route; re-apply the chosen mic.
        if DebugFlags.on("noApplyInput") { Diagnostics.log("debug: input not re-applied") } else { inputs.apply(selectedInputID) }

        beginNote(fileName: fileName, origin: 0, part: 1)
        isRecording = true

        Diagnostics.log("start: " + appState + " " + recorder.describeInput())
        silenceRestarts = 0
        lastSilenceRestart = .distantPast
        loggedFirstSignal = false

        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                self.level = self.recorder.level
                try? await Task.sleep(for: .milliseconds(50))
            }
        }
        liveTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(self?.tickSeconds ?? 1.2))
                guard let self, !Task.isCancelled else { return }
                guard let current = self.session else { continue }
                if self.isEngineReady, !current.recordOnly {
                    // The model may have just become ready: this pass catches up on
                    // everything said while it was loading.
                    self.liveRecordOnly = false
                    _ = await self.step(current, final: false)
                } else {
                    // The model isn't ready (still downloading or loading). Keep the
                    // audio in memory so the note catches up the moment it is; only a
                    // very long wait falls back to recording alone (the whole note is
                    // then transcribed from its file once the model is ready).
                    self.liveRecordOnly = true
                    if !current.recordOnly, self.recorder.sampleCount - current.committedUpTo > Self.maxCatchUp {
                        current.recordOnly = true
                    }
                    if current.recordOnly { self.freeCommittedAudio() }
                }
                self.maybeAutoSplit(current)
                self.checkForStuckMicrophone()
            }
        }
    }

    /// Creates the note (saved before any audio arrives, so a crash can always
    /// find it) and its live session.
    private func beginNote(fileName: String, origin: Int, part: Int) {
        var note = Note(audioFileName: fileName, status: .recording)
        note.backgroundVoices = backgroundVoices
        store.add(note)
        session = LiveSession(noteID: note.id, origin: origin, detector: newDetector())
        tailTranslateTask?.cancel()
        recordingNoteID = note.id
        liveRecordOnly = false
        startedAt = .now
        liveText = ""
        liveTranslation = nil
        partNumber = part
    }

    /// Like a stopwatch lap: ends the current note and starts a new one at this
    /// exact moment. The microphone keeps running, so nothing is lost between
    /// the two; the finished note transcribes in the background.
    func split() {
        guard isRecording, let old = session else { return }
        let fileName = "\(UUID().uuidString).wav"
        let switched: (position: Int, writeError: String?)
        do {
            switched = try recorder.switchFile(to: store.recordingsURL.appendingPathComponent(fileName))
        } catch {
            errorMessage = "Couldn't split: \(error.localizedDescription)"
            return
        }
        if let writeError = switched.writeError {
            errorMessage = "Part of the last recording couldn't be saved: \(writeError)"
        }
        old.end = switched.position
        finishing.append(old)
        beginNote(fileName: fileName, origin: switched.position, part: partNumber + 1)
        Task { await finish(old) }
    }

    /// Stops the microphone at once; the note finishes transcribing in the
    /// background, and a new recording can start right away.
    func stop() {
        guard isRecording, let current = session else { return }
        recorder.stop()
        current.end = recorder.sampleCount
        finishing.append(current)
        session = nil
        isRecording = false
        noSoundWarning = false
        level = 0
        startedAt = nil
        recordingNoteID = nil
        liveRecordOnly = false
        liveText = ""
        liveTranslation = nil
        partNumber = 1
        tailTranslateTask?.cancel()
        meterTask?.cancel()
        // Taken now, so a quick restart's new live task isn't the one cancelled.
        let live = liveTask
        liveTask = nil
        live?.cancel()
        inputs.refresh()
        if let writeError = recorder.writeError {
            errorMessage = "Part of this recording couldn't be saved: \(writeError)"
        }
        Task {
            await live?.value
            await finish(current)
        }
    }

    func toggle() async {
        if isRecording { stop() } else { await start() }
    }

    /// Final transcription of a stopped or split-off note, then its saved
    /// revision (and the live translation, when it finished).
    private func finish(_ s: LiveSession) async {
        let noteID = s.noteID
        // After Stop the microphone is off, so iOS may suspend the app: ask
        // for time to finish. (iOS grants seconds to minutes; anything left
        // over resumes from the queue the next time the app opens.)
        let background = UIApplication.shared.beginBackgroundTask(withName: "Finish transcript")
        defer {
            if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
            finishing.removeAll { $0 === s }
            freeCommittedAudio()
            processQueue()
        }
        // Wait for a live step still running on this note.
        while s.stepping { try? await Task.sleep(for: .milliseconds(50)) }

        let duration = Double((s.end ?? recorder.sampleCount) - s.origin) / Self.rate
        // Opening the app always starts a recording; drop ones stopped before
        // anything could have been said.
        if duration < NoteStore.minSeconds {
            store.delete(noteID)
            return
        }
        guard isEngineReady, !s.recordOnly else {
            // Audio is safe on disk; it is transcribed from the file once the model is ready.
            let partial = s.committedText
            store.update(noteID) {
                $0.status = .pending
                $0.duration = duration
                $0.partialText = partial.isEmpty ? nil : partial
            }
            return
        }
        store.update(noteID) {
            $0.status = .inProgress
            $0.duration = duration
            $0.attempts += 1
        }
        let error = await step(s, final: true)
        let text = s.committedText
        let engineName = engineLabel(s.engines)
        let language = Self.mostCommon(s.languages)
        // Let live translations still in flight land (briefly: a language-pack
        // prompt must not hold up saving), then reuse them if complete.
        await s.translations.wait(upTo: .seconds(5))
        let autoTranslate = self.autoTranslate
        store.update(noteID) {
            if let error {
                $0.status = .failed
                $0.lastError = error
                $0.partialText = text.isEmpty ? nil : text
            } else {
                // Never replaces an edit made meanwhile (it goes behind it in the history).
                $0 = $0.withTranscription(text, source: .transcription, engine: engineName, language: language)
                $0.status = .done
                $0.lastError = nil
                $0.partialText = nil
                // Reuse the live translation only if this transcription is the current text.
                if autoTranslate, let current = $0.revisions.last, current.source == .transcription,
                   current.text == text.trimmingCharacters(in: .whitespacesAndNewlines) {
                    $0.translation = Self.finishedTranslation(s.segments, revisionID: current.id)
                }
            }
        }
        // Every finished part is copied: after Stop, a manual split or an auto-split.
        if error == nil { copyWhenDone(noteID) }
        dropAudioIfImmediate(noteID)
    }

    /// One live step for `s`: finds newly finished utterances, commits them,
    /// and (while it's the note being recorded) refreshes the preview of speech
    /// in progress. Returns nil on success or the error message. Nothing is
    /// skipped on failure: utterances leave `pending` only once their text is
    /// committed, so the next pass retries them.
    private func step(_ s: LiveSession, final: Bool) async -> String? {
        s.stepping = true
        defer { s.stepping = false }
        do {
            let now = s.end ?? recorder.sampleCount
            try await feedDetector(s, now: now, final: final)

            let batchReady = final || grouping == .utterances ||
                (s.pending.first.map { now - $0.start >= Self.whisperBatch } ?? false)
            if batchReady, !s.pending.isEmpty { try await commit(s, audioEnd: now) }

            if !final, s === session {
                // Preview: whatever is still uncommitted, but only while there's speech.
                let speaking = s.detector?.inSpeech ?? true
                var tail = Transcript.empty
                if speaking || !s.pending.isEmpty {
                    // The detector confirms speech a moment after it starts; back up a second.
                    let openStart = s.detector == nil ? s.committedUpTo : s.quietAt - Int(Self.rate)
                    let from = min(s.pending.first.map { $0.start - Self.pad } ?? openStart, openStart)
                    let lower = max(from, s.committedUpTo, s.origin, now - Self.maxChunk)
                    tail = try await transcribeSerially(recorder.samples(in: lower..<max(lower, now)))
                }
                guard s === session else { return nil }
                if tail.text != s.tail.text {
                    s.tail = LiveSegment(text: tail.text, language: tail.language)
                    translateTail(s)
                }
                liveText = Self.join(s.committedText, tail.text)
                publishLiveTranslation(s)
            }
            return nil
        } catch {
            if final { errorMessage = "Transcription failed — the recording is saved and can be retried." }
            return error.localizedDescription
        }
    }

    /// Passes new audio of `s` to its speech detector, collecting finished
    /// utterances and noting when speech was last heard (for auto-split).
    /// Runs even while no model is loaded, so nothing is missed.
    private func feedDetector(_ s: LiveSession, now: Int, final: Bool) async throws {
        // The detector model may have finished loading after this note began:
        // attach it if nothing has been fed or committed yet.
        if s.detector == nil, s.detectedUpTo == s.origin, s.committedUpTo == s.origin { s.detector = newDetector() }
        if let vad = s.detector {
            if now > s.detectedUpTo {
                // The detector counts from the first sample it was given: this note's origin.
                let found = try await vad.feed(recorder.samples(in: s.detectedUpTo..<now))
                    .map { Span(start: $0.start + s.origin, end: $0.end + s.origin) }
                s.pending += found
                if let last = found.last { s.lastVoiceAt = max(s.lastVoiceAt ?? 0, last.end) }
                s.detectedUpTo = now
                if vad.inSpeech { s.lastVoiceAt = now } else { s.quietAt = now }
            }
            if final {
                let found = try await vad.flush()
                s.pending += found.map { Span(start: $0.start + s.origin, end: min($0.end + s.origin, now)) }
            }
        } else {
            s.lastVoiceAt = now // no detector: can't tell silence apart, so assume speech
            if final || now - s.committedUpTo >= Self.maxChunk {
                // Treat everything as speech; the planner cuts at quiet points.
                s.pending = [Span(start: s.committedUpTo, end: now)]
            }
        }
    }

    /// Splits the note being recorded when the auto-split rule says so.
    private func maybeAutoSplit(_ s: LiveSession) {
        guard s === session, isRecording else { return }
        let speaking = s.detector?.inSpeech ?? true
        if autoSplit.shouldSplit(noteStart: s.origin, now: recorder.sampleCount, speaking: speaking,
                                 lastVoiceAt: s.lastVoiceAt, rate: Int(Self.rate)) {
            split()
        }
    }

    /// Puts a just-finished recording's transcript on the clipboard and says so.
    private func copyWhenDone(_ noteID: Note.ID) {
        guard copyWhenDone, let text = store.note(id: noteID)?.currentText,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        UIPasteboard.general.string = text
        notice = "Transcript copied to clipboard"
    }

    /// Transcribes and commits every pending utterance of `s`, chunk by chunk.
    private func commit(_ s: LiveSession, audioEnd: Int) async throws {
        let recorder = self.recorder
        let chunks = ChunkPlanner.plan(s.pending, audioEnd: audioEnd, maxLength: Self.maxChunk, pad: Self.pad, grouping: grouping) { from, to in
            Self.quietestPoint(recorder.samples(in: from..<to), start: from)
        }
        for chunk in chunks {
            // Padding never reaches into the previous note's audio.
            let audio = chunk.flatMap { recorder.samples(in: max($0.start, s.origin)..<$0.end) }
            let result = try await transcribeSerially(audio)
            s.committedText = Self.join(s.committedText, result.text)
            if let language = result.language { s.languages.append(language) }
            if let engine = result.engine { s.engines.append(engine) }
            if !result.text.isEmpty {
                let segment = LiveSegment(text: result.text, language: result.language)
                s.segments.append(segment)
                translateSegment(s, segment)
            }
            let end = chunk.last!.end
            s.pending.removeAll { $0.end <= end }
            // A hard cut inside one long utterance: keep its unsent remainder.
            s.pending = s.pending.map { $0.start < end ? Span(start: end, end: $0.end) : $0 }
            s.committedUpTo = max(s.committedUpTo, end)
            if s === session {
                liveText = s.committedText
                publishLiveTranslation(s)
            }
            // Keep committed text on disk in case the app dies.
            let committed = s.committedText
            store.update(s.noteID) { $0.partialText = committed.isEmpty ? nil : committed }
            freeCommittedAudio()
        }
    }

    fileprivate var finishingNoteIDs: [Note.ID] { finishing.map(\.noteID) }

    /// Drops audio from memory that no live or finishing note still needs.
    private func freeCommittedAudio() {
        // A record-only note needs nothing in memory: it's transcribed from its file.
        let now = recorder.sampleCount
        guard let needed = (finishing + [session].compactMap { $0 }).map({ $0.recordOnly ? now : $0.committedUpTo }).min() else { return }
        recorder.discardBefore(needed - Self.pad)
    }

    // MARK: Live translation
    //
    // Each committed utterance is translated once, as soon as its pause is
    // detected; the utterance still being spoken is re-translated whenever its
    // preview text changes. All requests share the translator's single queue.

    private func translateSegment(_ s: LiveSession, _ segment: LiveSegment) {
        guard autoTranslate else { return }
        s.translations.add(Task {
            await translate(segment)
            if s === session { publishLiveTranslation(s) }
        })
    }

    private func translateTail(_ s: LiveSession) {
        tailTranslateTask?.cancel()
        let tail = s.tail
        guard autoTranslate, !tail.text.isEmpty else { return }
        tailTranslateTask = Task {
            try? await Task.sleep(for: .milliseconds(300)) // let a burst of preview updates settle
            guard !Task.isCancelled else { return }
            await translate(tail)
            if s === session, s.tail === tail { publishLiveTranslation(s) }
        }
    }

    private func translate(_ segment: LiveSegment) async {
        guard !segment.translated else { return }
        do {
            let result = try await translator.translate(segment.text, modelLanguage: segment.language)
            segment.english = result.english ?? (LanguageTags.isEnglish(result.language) ? segment.text : nil)
            segment.romanization = result.romanization
            if let language = result.language { segment.detectedLanguage = language }
        } catch is CancellationError {
            return // superseded by a newer preview
        } catch {
            // Live translation is best effort; the saved note is translated (and retried) afterwards.
            if let failure = error as? TranslationFailure {
                segment.romanization = failure.romanization
                segment.detectedLanguage = failure.language
            }
        }
        segment.translated = true
    }

    /// Pushes the combined live translation of `s` to the UI.
    private func publishLiveTranslation(_ s: LiveSession) {
        guard autoTranslate else { liveTranslation = nil; return }
        let parts = s.segments + (s.tail.text.isEmpty ? [] : [s.tail])
        liveTranslation = TranslationJoin.combine(parts.map(\.part), revisionID: LiveSession.liveRevisionID)
    }

    /// The finished live translation for a saved revision, or nil to translate
    /// it afresh (so a failed or missing part gets the normal retries).
    private static func finishedTranslation(_ segments: [LiveSegment], revisionID: UUID) -> NoteTranslation? {
        guard !segments.isEmpty, segments.allSatisfy(\.translated) else { return nil }
        let complete = segments.allSatisfy { $0.english != nil || LanguageTags.isEnglish($0.detectedLanguage ?? $0.language) }
        guard complete, let joined = TranslationJoin.combine(segments.map(\.part), revisionID: revisionID),
              joined.status != .pending else { return nil }
        return joined
    }

    // MARK: Helpers

    /// Position (in the recording) of the quietest 100 ms in `window`, which starts at `start`.
    nonisolated private static func quietestPoint(_ window: [Float], start: Int) -> Int {
        let frame = Int(rate / 10)
        guard window.count >= frame else { return start + window.count }
        var best = start + window.count
        var bestEnergy = Float.greatestFiniteMagnitude
        var offset = 0
        while offset + frame <= window.count {
            var energy: Float = 0
            for i in offset..<(offset + frame) { energy += window[i] * window[i] }
            if energy < bestEnergy {
                bestEnergy = energy
                best = start + offset + frame / 2
            }
            offset += frame
        }
        return best
    }

    /// "Parakeet v3", or "SenseVoice + Parakeet v3" when Auto used both.
    private func engineLabel(_ engines: [String]) -> String {
        var seen = Set<String>()
        let names = engines.filter { seen.insert($0).inserted }
        return names.isEmpty ? activeEngine.shortName : names.joined(separator: " + ")
    }

    private static func join(_ a: String, _ b: String) -> String {
        [a, b].filter { !$0.isEmpty }.joined(separator: " ")
    }

    private static func mostCommon(_ languages: [String]) -> String? {
        let counts = Dictionary(languages.map { ($0, 1) }, uniquingKeysWith: +)
        return languages.first { counts[$0] == counts.values.max() }
    }

    // MARK: Saved-audio transcription and retries

    /// Re-runs the current model over a note's audio (manual retry or re-transcribe).
    func retranscribe(_ noteID: Note.ID) {
        Task { await transcribeSaved(noteID, userRequested: true) }
    }

    /// Retries every recording that still needs text, ignoring the auto-retry limit.
    func retryAll() {
        Task {
            for note in store.notes where note.needsTranscription && note.id != recordingNoteID {
                await transcribeSaved(note.id, userRequested: true)
            }
        }
    }

    /// Works through notes that still need text, oldest first. Skips ones that
    /// already failed `maxAutoAttempts` times; those wait for a manual retry.
    /// Runs while recording as well; model calls take turns with the live text.
    private func processQueue() {
        if queueRunning { return }
        queueRunning = true
        queueTask = Task {
            defer { queueRunning = false }
            // Each note gets at most one attempt per pass; the next pass comes
            // with the next app launch, model load or finished recording.
            var tried = Set<Note.ID>()
            // Runs while recording too (model calls take turns with the live
            // text): VoicePad records from the moment it opens, so waiting for
            // the microphone to stop left recordings without text indefinitely.
            while isEngineReady, !conversationActive {
                guard let next = store.notes.first(where: {
                    $0.needsTranscription && $0.attempts < Self.maxAutoAttempts &&
                        $0.id != recordingNoteID && !tried.contains($0.id)
                }) else { break }
                tried.insert(next.id)
                await transcribeSaved(next.id)
            }
            // Notes left at "No speech detected" get their second opinion while
            // the app is idle: a few per pass, never while anything is recorded.
            await sweepEmptyNotes()
        }
    }

    private func transcribeSaved(_ noteID: Note.ID, userRequested: Bool = false) async {
        guard let note = store.note(id: noteID), note.status != .inProgress, note.status != .recording else { return }
        guard let stored = store.audioURL(for: note), FileManager.default.fileExists(atPath: stored.path) else {
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

        store.update(noteID) {
            $0.status = .inProgress
            $0.attempts += 1
        }
        // Archived notes are compressed: decode to a temporary WAV first.
        let decoded: URL? = stored.pathExtension.lowercased() == "wav" ? nil
            : FileManager.default.temporaryDirectory.appendingPathComponent("\(note.id).decoded.wav")
        defer { if let decoded { try? FileManager.default.removeItem(at: decoded) } }
        let normalize = note.backgroundVoices
        do {
            let url: URL
            if let decoded {
                try await Task.detached { try AudioArchive.decodeToWav(stored, to: decoded) }.value
                url = decoded
            } else {
                url = stored
            }
            let total = WavFile.sampleCount(url)
            // Find the speech by streaming the file through the detector in
            // 10 s blocks, so memory stays small however long it is.
            var speech: [Span]
            if let detector = newDetector() {
                speech = []
                let block = Int(Self.rate) * 10
                var at = 0
                while at < total {
                    let samples = try await Task.detached { try WavFile.readRange(url, from: at, to: at + block) }.value
                    speech += try await detector.feed(samples)
                    at += block
                }
                speech += try await detector.flush()
            } else {
                speech = [Span(start: 0, end: total)]
            }
            let chunks = ChunkPlanner.plan(speech, audioEnd: total, maxLength: Self.maxChunk, pad: Self.pad, grouping: grouping) { from, to in
                Self.quietestPoint((try? WavFile.readRange(url, from: from, to: to)) ?? [], start: from)
            }
            var text = ""
            var languages: [String] = []
            var engines: [String] = []
            for chunk in chunks {
                let audio = try await Task.detached {
                    try chunk.flatMap { try WavFile.readRange(url, from: $0.start, to: $0.end) }
                }.value
                let result = try await transcribeSerially(audio, normalize: normalize)
                text = Self.join(text, result.text)
                if let language = result.language { languages.append(language) }
                if let engine = result.engine { engines.append(engine) }
            }
            let language = Self.mostCommon(languages)
            let engineName = engineLabel(engines)
            if text.isEmpty, !(store.note(id: noteID)?.currentText.isEmpty ?? true) {
                errorMessage = "No speech found on re-transcription — the existing text was kept."
            }
            store.update(noteID) {
                $0 = $0.withTranscription(text, source: source, engine: engineName, language: language, userRequested: userRequested)
                $0.status = .done
                $0.lastError = nil
                $0.partialText = nil
                if $0.duration <= 0 { $0.duration = Double(total) / Self.rate }
            }
            dropAudioIfImmediate(noteID)
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

/// One finished utterance (or chunk) of a live recording, with its translation.
@MainActor
private final class LiveSegment {
    let text: String
    let language: String?
    /// Language the translator settled on (the model's tag may be missing).
    var detectedLanguage: String?
    var english: String?
    var romanization: String?
    var translated = false

    init(text: String, language: String?) {
        self.text = text
        self.language = language
    }

    var part: TranslationJoin.Part {
        TranslationJoin.Part(text: text, language: detectedLanguage ?? language, english: english,
                             romanization: romanization, translated: translated)
    }
}

/// Live state of one note: the one being recorded, or one still finishing its
/// transcription after Stop or Split. Positions are in the recording (the
/// microphone stream), which runs on across splits.
@MainActor
private final class LiveSession {
    static let liveRevisionID = UUID()

    let noteID: Note.ID
    let origin: Int
    var detector: SpeechDetector?
    var committedText = ""
    var committedUpTo: Int
    /// Recording position fed to the speech detector so far.
    var detectedUpTo: Int
    /// Last position at which the detector heard no speech; the current utterance began after it.
    var quietAt: Int
    /// Finished utterances not yet committed.
    var pending: [ChunkPlanner.Span] = []
    var languages: [String] = []
    var engines: [String] = []
    var segments: [LiveSegment] = []
    var tail = LiveSegment(text: "", language: nil)
    /// Where this note's audio ends; nil while it is still being recorded.
    var end: Int?
    /// A step is running (live and final steps must not overlap).
    var stepping = false
    /// Last moment speech was heard in this note (nil: nothing said yet).
    var lastVoiceAt: Int?
    /// The model wasn't ready while this note was recorded: no live processing;
    /// it is transcribed from its file afterwards.
    var recordOnly = false
    let translations = TaskGroupTracker()

    init(noteID: Note.ID, origin: Int, detector: SpeechDetector?) {
        self.noteID = noteID
        self.origin = origin
        self.detector = detector
        committedUpTo = origin
        detectedUpTo = origin
        quietAt = origin
    }
}

/// Keeps the tasks started for a session so its end can wait for them.
@MainActor
private final class TaskGroupTracker {
    private var tasks: [Task<Void, Never>] = []
    func add(_ task: Task<Void, Never>) { tasks.append(task) }
    func wait(upTo limit: Duration) async {
        let all = tasks
        await withTaskGroup(of: Void.self) { group in
            group.addTask { for task in all { await task.value } }
            group.addTask { try? await Task.sleep(for: limit) }
            await group.next()
            group.cancelAll()
        }
    }
}

extension RecordingController {
    // MARK: Archive and clean-up

    /// Notes that must not be archived or deleted right now.
    private var busyIDs: Set<Note.ID> {
        Set(finishingNoteIDs + [recordingNoteID].compactMap { $0 })
    }

    /// Archives notes: compresses each one's audio to AAC (about a tenth of the
    /// size) and moves it out of the main list. If compression fails the note
    /// stays as it was and you're told why.
    func archive(_ ids: [Note.ID]) {
        runOnNotes(ids) { note in
            if note.archived { return true }
            let ok = await self.compressAudio(note.id)
            if ok {
                self.store.update(note.id) {
                    $0.archived = true
                    $0.archivedAt = .now
                }
            }
            return ok
        }
    }

    /// Compresses notes' audio to AAC (about a tenth of the size) but keeps them in the list.
    func compress(_ ids: [Note.ID]) {
        runOnNotes(ids) { note in await self.compressAudio(note.id) }
    }

    /// Runs `work` on each idle note in turn and reports any that couldn't be compressed.
    private func runOnNotes(_ ids: [Note.ID], _ work: @escaping @MainActor (Note) async -> Bool) {
        Task {
            var failed = 0
            for id in ids {
                guard let note = store.note(id: id), !busyIDs.contains(id), note.status != .inProgress else { continue }
                if await !work(note) { failed += 1 }
            }
            if failed > 0 {
                errorMessage = failed == 1
                    ? "1 note couldn't be compressed, so it was left as it was."
                    : "\(failed) notes couldn't be compressed, so they were left as they were."
            }
        }
    }

    /// WAV → AAC for one note. The WAV is only deleted once the compressed copy
    /// has been checked and the note saved. True if done (or nothing to do).
    private func compressAudio(_ id: Note.ID) async -> Bool {
        guard let note = store.note(id: id), let wav = store.audioURL(for: note),
              wav.pathExtension.lowercased() == "wav", FileManager.default.fileExists(atPath: wav.path) else { return true }
        let m4aName = wav.deletingPathExtension().lastPathComponent + ".m4a"
        let m4a = store.recordingsURL.appendingPathComponent(m4aName)
        do {
            try await Task.detached { try AudioArchive.compress(wav, to: m4a) }.value
        } catch {
            return false
        }
        store.update(id) { $0.audioFileName = m4aName }
        try? FileManager.default.removeItem(at: wav)
        return true
    }

    /// Current audio size of `notes` and roughly what it would be compressed (bytes).
    func audioSizes(_ notes: [Note]) -> (now: Int, after: Int) {
        var now = 0, after = 0
        for note in notes {
            guard let url = store.audioURL(for: note) else { continue }
            let size = WavFile.fileSize(url)
            now += size
            // 16 kHz 16-bit WAV is 256 kbit/s; the archive format is 24 kbit/s.
            after += url.pathExtension.lowercased() == "wav" ? size * 24 / 256 : size
        }
        return (now, after)
    }

    // MARK: Export

    /// Notes an export would include (never the one being recorded).
    func exportCandidates(includeArchived: Bool) -> [Note] {
        store.notes.filter { $0.id != recordingNoteID && (includeArchived || !$0.archived) }
    }

    /// Bytes of audio an export with recordings would hold.
    func exportAudioBytes(includeArchived: Bool) -> Int {
        exportCandidates(includeArchived: includeArchived).reduce(0) { $0 + (store.audioURL(for: $1).map(WavFile.fileSize) ?? 0) }
    }

    /// Writes every note (see `NoteExport`) as a ZIP in a temporary folder,
    /// ready to share or save to Files.
    func exportNotes(includeAudio: Bool, includeArchived: Bool) async throws -> (url: URL, count: Int) {
        let notes = exportCandidates(includeArchived: includeArchived)
        let paths = Dictionary(uniqueKeysWithValues: notes.map { ($0.id, store.audioURL(for: $0)) })
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Export", isDirectory: true)
        try? FileManager.default.removeItem(at: folder)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appendingPathComponent(NoteExport.fileName())
        let count = try await Task.detached {
            try NoteExport.writeZip(to: url, notes: notes, includeAudio: includeAudio) { paths[$0.id] ?? nil }
        }.value
        return (url, count)
    }

    /// Back to the main list (the audio stays compressed).
    func unarchive(_ id: Note.ID) {
        store.update(id) {
            $0.archived = false
            $0.archivedAt = nil
        }
    }

    func delete(_ ids: [Note.ID]) {
        let busy = busyIDs
        store.deleteMany(ids.filter { !busy.contains($0) })
    }

    /// Unpinned notes older than `days` that `cleanUp` would touch.
    func cleanupCandidates(_ action: NoteCleanup.Action, days: Int) -> [Note] {
        NoteCleanup.select(store.notes, action: action, olderThanDays: days, busy: busyIDs)
    }

    func cleanUp(_ action: NoteCleanup.Action, days: Int) {
        let ids = cleanupCandidates(action, days: days).map(\.id)
        switch action {
        case .archive: archive(ids)
        case .compress: compress(ids)
        case .deleteAudio: deleteAudio(ids)
        case .deleteEmpty, .delete: delete(ids)
        }
    }

    /// Deletes the audio of `ids` and keeps the notes and their text. Notes
    /// being recorded or transcribed are never touched here (Android #26).
    func deleteAudio(_ ids: [Note.ID]) {
        let keep = ids.filter { !busyIDs.contains($0) }
        if !keep.isEmpty { store.deleteAudio(keep) }
    }

    /// "Delete audio: immediately" — a note that just got its text loses its
    /// recording now, rather than at the next sweep. Pinned notes keep theirs.
    private func dropAudioIfImmediate(_ noteID: Note.ID) {
        guard autoDeleteAudio == .now, let note = store.note(id: noteID) else { return }
        // Only once there are words to keep. A note with no text keeps its audio
        // so the second opinion can still be given, and so "no speech" stays
        // explainable.
        guard !note.pinned, note.status == .done, note.audioFileName != nil,
              !note.currentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        store.deleteAudio([noteID])
    }

    // MARK: Automatic clean-up

    /// Deletes notes with no speech, deletes audio and compresses audio older
    /// than the chosen ages (Android #19/#26). Runs quietly: a note that can't
    /// be compressed is left as it was, and not retried until the next launch.
    func runAutoCleanup() {
        guard autoDeleteEmpty != .off || autoDeleteAudio != .off || autoCompress != .off else { return }
        Task {
            let empty = NoteCleanup.autoSelect(store.notes, action: .deleteEmpty, age: autoDeleteEmpty, busy: busyIDs)
            if !empty.isEmpty { delete(empty.map(\.id)) }
            // Age-based only: "Immediately" is handled the moment a note gets its
            // text, so turning it on doesn't wipe the whole library in one sweep.
            if autoDeleteAudio != .off, autoDeleteAudio != .now {
                let silent = NoteCleanup.autoSelect(store.notes, action: .deleteAudio, age: autoDeleteAudio, busy: busyIDs)
                if !silent.isEmpty { deleteAudio(silent.map(\.id)) }
            }
            guard !isRecording else { return }
            let toCompress = NoteCleanup.autoSelect(store.notes, action: .compress, age: autoCompress, busy: busyIDs)
                .map(\.id).filter { !autoCompressFailed.contains($0) }
            if !toCompress.isEmpty { compress(toCompress) }
        }
    }

    private static let autoCleanupInterval: Duration = .seconds(15 * 60)

    // MARK: Settling "No speech detected" (Android #26)

    /// Below this peak the microphone was hearing a room, not a voice: -40 dBFS.
    private static let silentPeak: Float = 0.01

    /// A note the detector heard nothing in gets the loudest parts of its audio
    /// sent straight to the model, without the detector in the way. If words
    /// come back the note gets them; if they don't, nothing in the audio was
    /// worth keeping and the note goes, audio included. Two independent
    /// opinions, then gone.
    private func settleEmptyNote(_ note: Note) async -> Bool {
        guard let file = store.audioURL(for: note), FileManager.default.fileExists(atPath: file.path) else { return false }
        let total = WavFile.sampleCount(file)
        guard total > 0 else { return false }
        let block = 20 * Int(Self.rate)
        let blocks = (total + block - 1) / block
        // Loudest few blocks: a bang at the start shouldn't hide speech later on.
        var energies: [(index: Int, energy: Double)] = []
        for i in 0..<blocks {
            let from = i * block
            let samples = (try? WavFile.readRange(file, from: from, to: min(total, from + block))) ?? []
            let energy = samples.isEmpty ? 0 : samples.reduce(0.0) { $0 + Double($1 * $1) } / Double(samples.count)
            energies.append((i, energy))
        }
        let loudest = energies.sorted { $0.energy > $1.energy }.prefix(3).map(\.index)
        var peak: Float = 0
        for i in loudest {
            let from = i * block
            let samples = (try? WavFile.readRange(file, from: from, to: min(total, from + block))) ?? []
            peak = max(peak, samples.reduce(0) { max($0, abs($1)) })
        }
        // Below this the microphone was hearing the room, not a voice: measured
        // on Android's phone, clean speech stops being detected around -46 dBFS
        // peak, and the model asked to transcribe silence answers with "." or
        // "Yeah." rather than nothing.
        guard peak >= Self.silentPeak else { return false }
        var text = ""
        for i in loudest {
            let from = i * block
            let samples = (try? WavFile.readRange(file, from: from, to: min(total, from + block))) ?? []
            if samples.isEmpty { continue }
            guard let result = try? await transcribeSerially(samples) else { continue }
            text = Self.join(text, result.text)
        }
        // Silence makes the model answer with fillers; only words count as proof.
        guard NoSpeechText.isSubstantive(text) else { return false }
        store.update(note.id) {
            $0 = $0.withTranscription(text, source: .transcription, engine: engineLabel([]), language: nil)
        }
        return true
    }

    /// Settles "No speech detected" notes while the app is idle, a few at a
    /// time, and deletes the ones whose audio held nothing at all.
    private func sweepEmptyNotes(limit: Int = 6) async {
        guard isEngineReady, !isRecording, !conversationActive else { return }
        var tried = Set<Note.ID>()
        while true {
            let candidates = Array(store.notes.filter { note in
                !tried.contains(note.id) && !settledEmpty.contains(note.id) && !note.pinned &&
                    note.audioFileName != nil && note.status == .done &&
                    (NoteCleanup.isEmpty(note) || !note.currentText.contains(where: \.isLetter))
            }.prefix(limit))
            if candidates.isEmpty { break }
            for note in candidates {
                guard isEngineReady, !isRecording, !conversationActive else { return }
                tried.insert(note.id)
                settledEmpty.insert(note.id)
                if !(await settleEmptyNote(note)) { delete([note.id]) }
            }
        }
    }

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
    func processTranslations() {
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
                // A translation that repeats the original (misdetected English) isn't one.
                status: result.english == nil || TextSimilarity.nearlySame(result.english, revision.text) ? .notNeeded : .done,
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

/// Hands a non-Sendable object to a detached task that is its only user.
private struct UncheckedBox<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
