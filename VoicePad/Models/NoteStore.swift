import Foundation
import Observation

/// Persists notes as JSON and audio as WAV files under `baseURL`.
///
/// Every state change is written to disk immediately (atomically, with a
/// `.bak` copy of the previous index), so after a crash `recoverAfterRestart`
/// can tell exactly which recordings still need transcription.
@MainActor
@Observable
final class NoteStore {
    nonisolated static let minSeconds: Double = 1

    private(set) var notes: [Note] = []

    /// Called after every saved change (used to translate new text).
    @ObservationIgnored var onChange: (() -> Void)?

    let recordingsURL: URL
    private let indexURL: URL
    private var backupURL: URL { indexURL.appendingPathExtension("bak") }

    nonisolated static var defaultBaseURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    init(baseURL: URL = NoteStore.defaultBaseURL) {
        recordingsURL = baseURL.appendingPathComponent("Recordings", isDirectory: true)
        indexURL = baseURL.appendingPathComponent("notes.json")
        try? FileManager.default.createDirectory(at: recordingsURL, withIntermediateDirectories: true)
        notes = load()
    }

    func audioURL(for note: Note) -> URL? {
        note.audioFileName.map { recordingsURL.appendingPathComponent($0) }
    }

    func note(id: Note.ID) -> Note? {
        notes.first { $0.id == id }
    }

    func add(_ note: Note) {
        mutate { $0.append(note) }
    }

    /// Applies `transform` to one note and saves.
    func update(_ id: Note.ID, _ transform: (inout Note) -> Void) {
        mutate { list in
            guard let index = list.firstIndex(where: { $0.id == id }) else { return }
            transform(&list[index])
        }
    }

    /// Appends a new revision unless the text is unchanged. Text the user
    /// wrote or restored is final: the note is marked done, so no pending or
    /// retried transcription comes along later and replaces it.
    func addRevision(to id: Note.ID, text: String, source: Revision.Source, engine: String? = nil) {
        update(id) {
            $0 = $0.withRevision(text, source: source, engine: engine)
            if source == .edit || source == .restore { $0.settle() }
        }
    }

    func restore(noteID: Note.ID, revision: Revision) {
        update(noteID) {
            $0 = $0.withRevision(revision.text, source: .restore, engine: revision.engine, language: revision.language)
            $0.settle()
        }
    }

    /// Deletes several notes and their audio in one save.
    func deleteMany(_ ids: some Collection<Note.ID>) {
        let set = Set(ids)
        for note in notes where set.contains(note.id) {
            if let url = audioURL(for: note) { try? FileManager.default.removeItem(at: url) }
        }
        mutate { $0.removeAll { set.contains($0.id) } }
    }

    func setPinned(_ id: Note.ID, _ pinned: Bool) {
        update(id) {
            $0.pinned = pinned
            $0.pinnedAt = pinned ? .now : nil
        }
    }

    /// Deletes the note and its audio file.
    func delete(_ id: Note.ID) {
        if let note = note(id: id), let url = audioURL(for: note) {
            try? FileManager.default.removeItem(at: url)
        }
        mutate { $0.removeAll { $0.id == id } }
    }

    /// Deletes the audio of these notes and keeps their text: the recording is
    /// gone, the words aren't (Android #26).
    func deleteAudio(_ ids: some Collection<Note.ID>) {
        let set = Set(ids)
        let withAudio = notes.filter { set.contains($0.id) && $0.audioFileName != nil }
        guard !withAudio.isEmpty else { return }
        for note in withAudio {
            if let url = audioURL(for: note) { try? FileManager.default.removeItem(at: url) }
        }
        mutate { list in
            for index in list.indices where set.contains(list[index].id) {
                list[index].audioFileName = nil
            }
        }
    }

    /// Run once at startup, before anything records or transcribes. Repairs
    /// whatever an unexpected exit left behind:
    /// - a note still `.recording`: the app died mid-recording, so fix the WAV
    ///   header from what reached disk and queue it for transcription;
    /// - a note still `.inProgress`: the app died during a model run (possibly
    ///   the model crashed it), so mark it failed rather than silently
    ///   retrying forever;
    /// - an older note saved before statuses existed, with audio but no text:
    ///   queue it.
    func recoverAfterRestart() {
        mutate { list in
            list = list.compactMap { note in
                var note = note
                let audio = audioURL(for: note)
                switch note.status {
                case .recording:
                    let duration = audio.map { WavFile.repair($0) } ?? 0
                    if duration < Self.minSeconds, note.partialText?.trimmingCharacters(in: .whitespaces).isEmpty ?? true {
                        if let audio { try? FileManager.default.removeItem(at: audio) }
                        return nil // nothing was said before the app died
                    }
                    note.status = .pending
                    note.duration = duration
                case .inProgress:
                    note.status = note.revisions.isEmpty ? .failed : .done
                    note.lastError = "The app closed while transcribing this recording."
                case .done where note.revisions.isEmpty && audio.map { FileManager.default.fileExists(atPath: $0.path) } == true:
                    note.status = .pending
                default:
                    break
                }
                return note
            }
        }
    }

    // MARK: Persistence

    private func mutate(_ transform: (inout [Note]) -> Void) {
        var updated = notes
        transform(&updated)
        save(updated)
        notes = updated
        onChange?()
    }

    private func load() -> [Note] {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        for url in [indexURL, backupURL] {
            guard let data = try? Data(contentsOf: url) else { continue }
            // Corrupt: fall through to the backup rather than losing every note.
            if let notes = try? decoder.decode([Note].self, from: data) { return notes }
        }
        return []
    }

    private func save(_ notes: [Note]) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted]
        guard let data = try? encoder.encode(notes) else { return }
        let tmp = indexURL.appendingPathExtension("tmp")
        let fm = FileManager.default
        do {
            fm.createFile(atPath: tmp.path, contents: nil)
            let handle = try FileHandle(forWritingTo: tmp)
            try handle.write(contentsOf: data)
            try handle.synchronize()
            try handle.close()
            if fm.fileExists(atPath: indexURL.path) {
                try? fm.removeItem(at: backupURL)
                try? fm.copyItem(at: indexURL, to: backupURL)
            }
            _ = try fm.replaceItemAt(indexURL, withItemAt: tmp)
        } catch {
            // Last resort: a plain atomic write.
            try? data.write(to: indexURL, options: .atomic)
        }
    }
}
