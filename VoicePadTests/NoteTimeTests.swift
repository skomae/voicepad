import XCTest

/// The break labels between notes: recent reads relative, a burst of notes gets
/// one label at its start, and a gap of a few minutes gets none at all.
final class NoteTimeTests: XCTestCase {
    private var calendar: Calendar = {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = TimeZone(identifier: "UTC")!
        return c
    }()

    private func date(_ day: Int, _ hour: Int, _ minute: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day,
                                           hour: hour, minute: minute))!
    }

    func testNotesAMinuteApartGetNoDivider() {
        let now = date(25, 10, 0)
        XCTAssertNil(NoteTime.label(for: date(25, 9, 3), after: date(25, 9, 1), now: now, calendar: calendar))
        XCTAssertNil(NoteTime.label(for: date(25, 9, 4), after: date(25, 9, 1), now: now, calendar: calendar))
    }

    func testTheQuietGapIsFiveMinutes() {
        let now = date(25, 10, 0)
        let previous = date(25, 9, 0)
        XCTAssertEqual(NoteTime.label(for: previous.addingTimeInterval(299), after: previous,
                                      now: now, calendar: calendar), nil)
        XCTAssertEqual(NoteTime.label(for: previous.addingTimeInterval(300), after: previous,
                                      now: now, calendar: calendar), "5 min later")
    }

    func testABreakWithinTheHourReadsAsALength() {
        let now = date(25, 10, 0)
        XCTAssertEqual(NoteTime.label(for: date(25, 9, 12), after: date(25, 9, 0),
                                      now: now, calendar: calendar), "12 min later")
        XCTAssertEqual(NoteTime.label(for: date(25, 9, 59), after: date(25, 9, 0),
                                      now: now, calendar: calendar), "59 min later")
    }

    func testABreakOverAnHourShowsTheClock() {
        let now = date(25, 13, 0)
        let label = NoteTime.label(for: date(25, 11, 30), after: date(25, 9, 0),
                                   now: now, calendar: calendar)
        XCTAssertNotNil(label)
        XCTAssertFalse(label!.contains("later"))
        XCTAssertEqual(label, date(25, 11, 30).formatted(date: .omitted, time: .shortened))
    }

    func testTheNewestNoteOfADaySaysHowLongAgo() {
        let now = date(25, 10, 0)
        XCTAssertEqual(NoteTime.label(for: now.addingTimeInterval(-30), after: nil,
                                      now: now, calendar: calendar), "Just now")
        XCTAssertEqual(NoteTime.label(for: date(25, 9, 50), after: nil,
                                      now: now, calendar: calendar), "10 min ago")
    }

    func testOlderBreaksNameTheDay() {
        let now = date(25, 10, 0)
        XCTAssertEqual(NoteTime.label(for: date(25, 8, 15), after: nil, now: now, calendar: calendar),
                       "Today, " + date(25, 8, 15).formatted(date: .omitted, time: .shortened))
        XCTAssertEqual(NoteTime.label(for: date(24, 22, 0), after: nil, now: now, calendar: calendar),
                       "Yesterday, " + date(24, 22, 0).formatted(date: .omitted, time: .shortened))
        let wednesday = date(21, 22, 0)   // four days back: still a weekday, not a date
        XCTAssertEqual(NoteTime.label(for: wednesday, after: nil, now: now, calendar: calendar),
                       wednesday.formatted(.dateTime.weekday(.wide)) + ", "
                           + wednesday.formatted(date: .omitted, time: .shortened))
    }

    func testOnlyADayAtThisDistance() {
        let now = date(25, 10, 0)
        let old = date(12, 9, 0)
        let expected = old.formatted(.dateTime.day().month(.abbreviated).year())
        XCTAssertEqual(NoteTime.label(for: old, after: nil, now: now, calendar: calendar), expected)
    }

    func testOneDividerPerBurst() {
        let now = date(25, 10, 30)
        let notes = [
            Note(createdAt: date(25, 9, 0)),
            Note(createdAt: date(25, 9, 1)),
            Note(createdAt: date(25, 9, 3)),
            Note(createdAt: date(25, 10, 0)),
        ]
        let labels = NoteTime.labels(for: notes, now: now, calendar: calendar)
        XCTAssertEqual(labels.count, 2)
        XCTAssertNotNil(labels[notes[0].id])
        XCTAssertNil(labels[notes[1].id])
        XCTAssertNil(labels[notes[2].id])
        XCTAssertEqual(labels[notes[3].id], "57 min later")   // to 9:03, not to 9:00
    }
}
