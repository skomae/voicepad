import Foundation
import Observation

/// Persists notes as JSON in Documents and audio files in Documents/Recordings.
@MainActor
@Observable
final class NoteStore {
    private(set) var notes: [Note] = []

    static let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    static let recordingsURL: URL = {
        let url = documentsURL.appendingPathComponent("Recordings", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }()
    private let indexURL = NoteStore.documentsURL.appendingPathComponent("notes.json")

    init() {
        load()
    }

    func audioURL(for note: Note) -> URL? {
        note.audioFileName.map { Self.recordingsURL.appendingPathComponent($0) }
    }

    func note(id: Note.ID) -> Note? {
        notes.first { $0.id == id }
    }

    func add(_ note: Note) {
        notes.append(note)
        save()
    }

    /// Appends a new revision unless the text is unchanged.
    func addRevision(to id: Note.ID, text: String, source: Revision.Source, engine: String? = nil) {
        guard let index = notes.firstIndex(where: { $0.id == id }) else { return }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if notes[index].currentText == trimmed, !notes[index].revisions.isEmpty { return }
        notes[index].revisions.append(Revision(text: trimmed, source: source, engine: engine))
        save()
    }

    func setDuration(_ duration: TimeInterval, for id: Note.ID) {
        guard let index = notes.firstIndex(where: { $0.id == id }) else { return }
        notes[index].duration = duration
        save()
    }

    func restore(noteID: Note.ID, revision: Revision) {
        addRevision(to: noteID, text: revision.text, source: .restore, engine: revision.engine)
    }

    func delete(_ id: Note.ID) {
        guard let index = notes.firstIndex(where: { $0.id == id }) else { return }
        if let url = audioURL(for: notes[index]) {
            try? FileManager.default.removeItem(at: url)
        }
        notes.remove(at: index)
        save()
    }

    private func load() {
        guard let data = try? Data(contentsOf: indexURL) else { return }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        notes = (try? decoder.decode([Note].self, from: data)) ?? []
    }

    private func save() {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted]
        guard let data = try? encoder.encode(notes) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }
}
