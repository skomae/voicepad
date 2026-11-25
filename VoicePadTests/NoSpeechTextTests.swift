import XCTest

/// Ported from Android's `NoSpeechTextTest` (#26): the transcripts the model
/// actually produced for silent audio, plus real speech.
final class NoSpeechTextTests: XCTestCase {
    func testTheModelsAnswersToSilenceAreNotWords() {
        // Measured: these are what came back for audio the detector heard nothing in.
        XCTAssertFalse(NoSpeechText.isSubstantive("."))
        XCTAssertFalse(NoSpeechText.isSubstantive("。 ."))
        XCTAssertFalse(NoSpeechText.isSubstantive("。 。 。"))
        XCTAssertFalse(NoSpeechText.isSubstantive("Yeah."))
        XCTAssertFalse(NoSpeechText.isSubstantive("Thank you."))
        XCTAssertFalse(NoSpeechText.isSubstantive(""))
        XCTAssertFalse(NoSpeechText.isSubstantive("  "))
    }

    func testShortRealWordsStillCount() {
        XCTAssertTrue(NoSpeechText.isSubstantive("你好。"))
        XCTAssertTrue(NoSpeechText.isSubstantive("这是一个测试。"))
        XCTAssertTrue(NoSpeechText.isSubstantive("This is a test."))
        XCTAssertTrue(NoSpeechText.isSubstantive("I wanna know."))
    }

    func testThreeLettersIsTheFloor() {
        XCTAssertFalse(NoSpeechText.isSubstantive("ok"))
        XCTAssertTrue(NoSpeechText.isSubstantive("okay then")) // a filler plus a real word
    }
}

/// Ported from Android's `NoteCleanupTest` delete-audio case (#26).
final class DeleteAudioCleanupTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 100 * NoteCleanup.day)

    private func note(_ ageDays: Double, pinned: Bool = false, status: TranscriptionStatus = .done,
                      text: String = "something said", audio: String? = "a.wav") -> Note {
        var note = Note(createdAt: now.addingTimeInterval(-ageDays * NoteCleanup.day), audioFileName: audio, status: status)
        note.pinned = pinned
        if !text.isEmpty { note.revisions = [Revision(text: text, source: .transcription, engine: nil)] }
        return note
    }

    func testDeleteAudioTakesNotesWithTextAndAudioNeverPinnedOrUnfinished() {
        let notes = [
            note(0.01),                       // fresh
            note(5),                          // old
            note(1, pinned: true),            // pinned
            note(1, status: .pending),        // waiting for a model
            note(1, audio: nil),              // no recording left
            note(2, text: ""),                // no text yet: keeps its audio
        ]
        func auto(_ age: NoteCleanup.AutoAge) -> Set<Note.ID> {
            Set(NoteCleanup.autoSelect(notes, action: .deleteAudio, age: age, now: now).map(\.id))
        }
        XCTAssertEqual(auto(.hours1), [notes[1].id])
        XCTAssertEqual(auto(.hours24), [notes[1].id])
        XCTAssertEqual(auto(.days7), [])
        XCTAssertEqual(auto(.off), [])
        XCTAssertEqual(auto(.now), [notes[0].id, notes[1].id])
    }

    func testManualCleanUpCanDeleteAudioToo() {
        let notes = [note(5), note(1), note(5, pinned: true)]
        let chosen = Set(NoteCleanup.select(notes, action: .deleteAudio, olderThanDays: 3, now: now).map(\.id))
        XCTAssertEqual(chosen, [notes[0].id])
    }
}
