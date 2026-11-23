import XCTest

final class NoteSearchTests: XCTestCase {
    private let ids = (a: UUID(), b: UUID(), c: UUID())

    private func note(_ id: UUID, _ texts: String..., partial: String? = nil, pinned: Bool = false, createdAt: TimeInterval = 0) -> Note {
        var n = Note(id: id, createdAt: Date(timeIntervalSince1970: createdAt))
        n.revisions = texts.map { Revision(text: $0, source: .transcription) }
        n.partialText = partial
        n.pinned = pinned
        return n
    }

    private func resultIDs(_ results: [NoteSearch.Result]) -> [UUID] { results.map(\.note.id) }

    func testEveryTermMustMatchInAnyOrderIgnoringCase() {
        let notes = [note(ids.a, "Buy milk and EGGS"), note(ids.b, "buy bread"), note(ids.c, "eggs only")]
        XCTAssertEqual(resultIDs(NoteSearch.search(notes, query: "eggs buy")), [ids.a])
    }

    func testQuotedPhrasesMustMatchAsWritten() {
        let notes = [note(ids.a, "call the dentist tomorrow"), note(ids.b, "tomorrow call the bank")]
        XCTAssertEqual(resultIDs(NoteSearch.search(notes, query: "\"dentist tomorrow\"")), [ids.a])
        XCTAssertEqual(Set(resultIDs(NoteSearch.search(notes, query: "call tomorrow"))), [ids.a, ids.b])
    }

    func testAccentsAreIgnored() {
        let notes = [note(ids.a, "Meet at the Café near Zürich")]
        XCTAssertEqual(resultIDs(NoteSearch.search(notes, query: "cafe zurich")), [ids.a])
        XCTAssertEqual(resultIDs(NoteSearch.search(notes, query: "CAFÉ")), [ids.a])
    }

    func testEarlierRevisionsAreSearchedAndRankedAfterCurrentMatches() {
        let notes = [
            note(ids.a, "project kestrel kickoff", "project renamed", createdAt: 10),
            note(ids.b, "kestrel launch plan", createdAt: 1),
        ]
        let results = NoteSearch.search(notes, query: "kestrel")
        XCTAssertEqual(resultIDs(results), [ids.b, ids.a])
        XCTAssertEqual(results[0].matchedIn, .current)
        XCTAssertEqual(results[1].matchedIn, .earlierRevision)
    }

    func testTextRecoveredFromAFailedTranscriptionIsSearchable() {
        let notes = [note(ids.a, partial: "remember the quarterly numbers")]
        XCTAssertEqual(resultIDs(NoteSearch.search(notes, query: "quarterly")), [ids.a])
    }

    func testPinnedNotesComeFirstAmongEqualMatches() {
        let notes = [note(ids.a, "groceries list", createdAt: 5), note(ids.b, "groceries list", pinned: true, createdAt: 1)]
        XCTAssertEqual(resultIDs(NoteSearch.search(notes, query: "groceries")), [ids.b, ids.a])
    }

    func testBlankQueryFindsNothing() {
        XCTAssertTrue(NoteSearch.search([note(ids.a, "x")], query: "   ").isEmpty)
    }

    func testHighlightRangesLineUpWithTheOriginalTextAndMergeOverlaps() {
        let text = "Café cafe CAFÉS"
        let ranges = NoteSearch.matchRanges(in: text, terms: NoteSearch.terms("cafe"))
        XCTAssertEqual(ranges.map { String(text[$0]) }, ["Café", "cafe", "CAFÉ"])
        let merged = NoteSearch.matchRanges(in: "abcdef", terms: NoteSearch.terms("abcd cdef"))
        XCTAssertEqual(merged.map { String("abcdef"[$0]) }, ["abcdef"])
        // Decomposed accents and emoji keep positions aligned too.
        let tricky = "e\u{301}clair 👍 Éclair"
        XCTAssertEqual(NoteSearch.matchRanges(in: tricky, terms: ["eclair"]).map { String(tricky[$0]) }, ["e\u{301}clair", "Éclair"])
    }

    @MainActor
    func testPinningIsSaved() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = NoteStore(baseURL: dir)
        store.add(note(ids.a, "x"))
        store.setPinned(ids.a, true)
        let reopened = try XCTUnwrap(NoteStore(baseURL: dir).note(id: ids.a))
        XCTAssertTrue(reopened.pinned)
        XCTAssertNotNil(reopened.pinnedAt)
    }
}
