import Foundation

/// Which notes "Clean up" touches. Pure Swift so it can be unit-tested.
///
/// Never: pinned notes, or a note that is recording or still transcribing.
/// Archive skips notes already archived; delete takes archived ones too.
enum NoteCleanup {
    enum Action { case archive, delete }

    static let day: TimeInterval = 24 * 60 * 60

    static func select(_ notes: [Note], action: Action, olderThanDays days: Int, now: Date = .now,
                       busy: Set<Note.ID> = []) -> [Note] {
        let cutoff = now.addingTimeInterval(-Double(days) * day)
        return notes.filter { note in
            !note.pinned &&
                note.createdAt < cutoff &&
                !busy.contains(note.id) &&
                note.status != .recording &&
                note.status != .inProgress &&
                (action == .delete || !note.archived)
        }
    }
}
