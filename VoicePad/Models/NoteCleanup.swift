import Foundation

/// Which notes "Clean up" touches. Pure Swift so it can be unit-tested.
///
/// Never a note that is recording or still transcribing. Pinned notes are
/// only touched by Compress, which keeps them as they are, just smaller.
enum NoteCleanup {
    enum Action: String, CaseIterable, Identifiable {
        case archive, compress, deleteEmpty, delete

        var id: String { rawValue }

        var label: String {
            switch self {
            case .archive: return "Archive"
            case .compress: return "Compress audio"
            case .deleteEmpty: return "Delete empty"
            case .delete: return "Delete"
            }
        }
    }

    static let day: TimeInterval = 24 * 60 * 60

    /// - Parameter days: 0 means any age.
    static func select(_ notes: [Note], action: Action, olderThanDays days: Int, now: Date = .now,
                       busy: Set<Note.ID> = []) -> [Note] {
        let cutoff = now.addingTimeInterval(-Double(days) * day)
        return notes.filter { note in
            let idle = !busy.contains(note.id) && note.status != .recording && note.status != .inProgress
            let oldEnough = days == 0 || note.createdAt < cutoff
            guard idle, oldEnough else { return false }
            switch action {
            case .archive: return !note.pinned && !note.archived
            case .delete: return !note.pinned
            case .compress: return note.audioFileName?.lowercased().hasSuffix(".wav") == true
            case .deleteEmpty: return !note.pinned && isEmpty(note)
            }
        }
    }

    /// Transcribed and nothing was said ("No speech detected"). A note still
    /// waiting for, or retrying, its transcription is not empty: its audio may
    /// well hold speech.
    static func isEmpty(_ note: Note) -> Bool {
        note.status == .done && note.currentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (note.partialText?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }
}
