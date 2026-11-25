import Foundation

/// The label that separates notes, the way a chat app marks a break: relative
/// while it is recent ("Just now", "12 min later"), a day and a clock time once
/// it is older. Notes a few minutes apart get no label at all — a divider every
/// couple of minutes hides the conversation it is meant to separate.
enum NoteTime {
    /// Closer than this, two notes are one burst of speaking.
    static let quiet: TimeInterval = 5 * 60

    /// What to put between `date` and the note before it, or nil to put nothing.
    static func label(for date: Date, after previous: Date?, now: Date = .now,
                      calendar: Calendar = .current) -> String? {
        if let previous, calendar.isDate(date, inSameDayAs: previous) {
            let gap = date.timeIntervalSince(previous)
            if gap < quiet { return nil }
            // A break within the hour reads better as a length than a clock time.
            if gap < 3_600 { return "\(max(1, Int(gap / 60))) min later" }
            return clock(date)
        }
        let age = now.timeIntervalSince(date)
        if age >= 0, age < 60 { return "Just now" }
        if age >= 0, age < 3_600, calendar.isDateInToday(date) {
            return "\(max(1, Int(age / 60))) min ago"
        }
        if calendar.isDateInToday(date) { return "Today, " + clock(date) }
        if calendar.isDateInYesterday(date) { return "Yesterday, " + clock(date) }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date),
                                           to: calendar.startOfDay(for: now)).day ?? 99
        if days >= 0, days < 7 {
            return date.formatted(.dateTime.weekday(.wide)) + ", " + clock(date)
        }
        return date.formatted(.dateTime.day().month(.abbreviated).year())
    }

    /// The labels for a run of notes in the order they are shown.
    static func labels(for notes: [Note], now: Date = .now,
                       calendar: Calendar = .current) -> [Note.ID: String] {
        var out: [Note.ID: String] = [:]
        var previous: Date?
        for note in notes {
            if let label = label(for: note.createdAt, after: previous, now: now, calendar: calendar) {
                out[note.id] = label
            }
            previous = note.createdAt
        }
        return out
    }

    private static func clock(_ date: Date) -> String {
        date.formatted(date: .omitted, time: .shortened)
    }
}
