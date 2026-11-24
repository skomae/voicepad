import Foundation

/// Exports notes to a ZIP of open formats, readable without VoicePad (same
/// layout as the Android app's export):
///
/// - `notes.json`: every note with everything VoicePad knows about it —
///   transcript revisions, translations, language, model, timestamps, pinned
///   and archived state — plus the audio file's path inside the ZIP.
/// - `notes.md`: the same notes as readable Markdown, oldest first.
/// - `audio/…`: each recording as WAV (or AAC .m4a once compressed), if asked for.
enum NoteExport {
    static let formatVersion = 1

    struct Export: Codable {
        var format = "voicepad-notes"
        var version = NoteExport.formatVersion
        var exportedAt: Date
        var notes: [ExportedNote]
    }

    struct ExportedNote: Codable {
        var note: Note
        /// Path of the recording inside the ZIP, when audio was included and found.
        var audioPath: String?
    }

    /// Dates in the phone's time zone (UTC in tests).
    nonisolated(unsafe) static var timeZone = TimeZone.current

    /// Notes in the order they're exported: oldest first.
    static func ordered(_ notes: [Note]) -> [Note] { notes.sorted { $0.createdAt < $1.createdAt } }

    /// Writes the ZIP to `url`. `audioFile` finds a note's recording;
    /// `includeAudio` decides whether recordings go in. Returns how many
    /// notes were written.
    @discardableResult
    static func writeZip(to url: URL, notes: [Note], includeAudio: Bool, now: Date = .now,
                         audioFile: (Note) -> URL?) throws -> Int {
        let list = ordered(notes)
        let zip = try ZipWriter(url: url)
        var exported: [ExportedNote] = []
        for note in list {
            var path: String?
            if includeAudio, let file = audioFile(note), FileManager.default.fileExists(atPath: file.path) {
                let ext = file.pathExtension.isEmpty ? "wav" : file.pathExtension
                let name = "audio/\(baseName(note)).\(ext)"
                try zip.add(name, file: file, modified: note.createdAt)
                path = name
            }
            exported.append(ExportedNote(note: note, audioPath: path))
        }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        try zip.add("notes.json", data: encoder.encode(Export(exportedAt: now, notes: exported)), modified: now)
        try zip.add("notes.md", data: Data(markdown(exported, now: now).utf8), modified: now)
        try zip.finish()
        return list.count
    }

    /// A file name for a note's recording: its date and time, plus a short id so names never clash.
    static func baseName(_ note: Note) -> String {
        stamp(note.createdAt, "yyyy-MM-dd_HH-mm-ss") + "_" + note.id.uuidString.prefix(8).lowercased()
    }

    static func markdown(_ notes: [ExportedNote], now: Date = .now) -> String {
        var out = "# VoicePad notes\n\n"
        out += "Exported \(stamp(now, "yyyy-MM-dd HH:mm")) · \(notes.count) note\(notes.count == 1 ? "" : "s")\n"
        for item in notes {
            let note = item.note
            out += "\n---\n\n## " + stamp(note.createdAt, "yyyy-MM-dd HH:mm")
            if note.pinned { out += " · pinned" }
            if note.archived { out += " · archived" }
            out += "\n\n"
            let details = [
                note.duration > 0 ? duration(note.duration) : nil,
                note.revisions.last?.language.map { "language: \($0)" },
                note.revisions.last?.engine,
                item.audioPath.map { "audio: [\($0)](\($0))" },
            ].compactMap { $0 }
            if !details.isEmpty { out += "*" + details.joined(separator: " · ") + "*\n\n" }
            let text = note.currentText.isEmpty ? note.partialText ?? "" : note.currentText
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            out += (trimmed.isEmpty ? "(no text)" : trimmed) + "\n"
            if let t = note.currentTranslation, t.status == .done {
                if let r = t.romanization { out += "\n> " + r.replacingOccurrences(of: "\n", with: "\n> ") + "\n" }
                if let e = t.english { out += "\n**English:** " + e.trimmingCharacters(in: .whitespacesAndNewlines) + "\n" }
            }
            if note.revisions.count > 1 {
                out += "\n<details><summary>Earlier versions (\(note.revisions.count - 1))</summary>\n\n"
                for r in note.revisions.dropLast().reversed() {
                    let line = r.text.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
                    out += "- \(stamp(r.date, "yyyy-MM-dd HH:mm")) · \(r.source.label): \(line)\n"
                }
                out += "\n</details>\n"
            }
        }
        return out
    }

    /// Suggested file name for the export.
    static func fileName(now: Date = .now) -> String { "voicepad-notes-\(stamp(now, "yyyy-MM-dd")).zip" }

    private static func duration(_ seconds: Double) -> String {
        let s = Int(seconds)
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }

    private static func stamp(_ date: Date, _ pattern: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = timeZone
        f.dateFormat = pattern
        return f.string(from: date)
    }
}
