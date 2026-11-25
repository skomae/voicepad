import Foundation

/// Which notes "Clean up" touches. Pure Swift so it can be unit-tested.
///
/// Never a note that is recording or still transcribing. Pinned notes are
/// only touched by Compress, which keeps them as they are, just smaller.
enum NoteCleanup {
    enum Action: String, CaseIterable, Identifiable {
        case archive, compress, deleteAudio, deleteEmpty, delete

        var id: String { rawValue }

        var label: String {
            switch self {
            case .archive: return "Archive"
            case .compress: return "Compress audio"
            case .deleteAudio: return "Delete audio"
            case .deleteEmpty: return "Delete empty"
            case .delete: return "Delete"
            }
        }
    }

    /// How old a note is before the automatic clean-up touches it (Android #19/#26).
    enum AutoAge: String, CaseIterable, Identifiable, Codable {
        case off
        /// As soon as the note has its text (any age).
        case now
        case hours1, hours3, hours24, days7, days30

        var id: String { rawValue }

        var label: String {
            switch self {
            case .off: return "Off"
            case .now: return "Immediately"
            case .hours1: return "1 hour"
            case .hours3: return "3 hours"
            case .hours24: return "24 hours"
            case .days7: return "7 days"
            case .days30: return "30 days"
            }
        }

        /// Age in seconds; nil when off, 0 for "immediately".
        var interval: TimeInterval? {
            switch self {
            case .off: return nil
            case .now: return 0
            case .hours1: return 60 * 60
            case .hours3: return 3 * 60 * 60
            case .hours24: return NoteCleanup.day
            case .days7: return 7 * NoteCleanup.day
            case .days30: return 30 * NoteCleanup.day
            }
        }
    }

    static let day: TimeInterval = 24 * 60 * 60

    /// - Parameter days: 0 means any age.
    static func select(_ notes: [Note], action: Action, olderThanDays days: Int, now: Date = .now,
                       busy: Set<Note.ID> = []) -> [Note] {
        select(notes, action: action, olderThan: Double(days) * day, now: now, busy: busy)
    }

    /// Notes `action` would touch that are at least `age` old (0: any age).
    static func select(_ notes: [Note], action: Action, olderThan age: TimeInterval, now: Date = .now,
                       busy: Set<Note.ID> = []) -> [Note] {
        let cutoff = now.addingTimeInterval(-age)
        return notes.filter { note in
            let idle = !busy.contains(note.id) && note.status != .recording && note.status != .inProgress
            let oldEnough = age <= 0 || note.createdAt < cutoff
            guard idle, oldEnough else { return false }
            switch action {
            case .archive: return !note.pinned && !note.archived
            case .delete: return !note.pinned
            case .compress: return note.audioFileName?.lowercased().hasSuffix(".wav") == true
            case .deleteAudio:
                return !note.pinned && note.audioFileName != nil && note.status == .done
                    && !note.currentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            case .deleteEmpty: return !note.pinned && isEmpty(note)
            }
        }
    }

    /// What the automatic clean-up would do for one setting.
    static func autoSelect(_ notes: [Note], action: Action, age: AutoAge, now: Date = .now,
                           busy: Set<Note.ID> = []) -> [Note] {
        guard let interval = age.interval else { return [] }
        return select(notes, action: action, olderThan: interval, now: now, busy: busy)
    }

    /// Transcribed and nothing was said ("No speech detected"). A note still
    /// waiting for, or retrying, its transcription is not empty: its audio may
    /// well hold speech.
    static func isEmpty(_ note: Note) -> Bool {
        note.status == .done && note.currentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (note.partialText?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }
}
