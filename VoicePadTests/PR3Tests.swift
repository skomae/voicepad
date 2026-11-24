import XCTest

/// Same cases as the Android app's TextSimilarityTest, NoteCleanupTest,
/// AudioLevelTest and EditProtectionTest.
final class TextSimilarityTests: XCTestCase {
    typealias Part = TranslationJoin.Part

    // From a real note: English speech labelled "Arabic" and "translated" back.
    private let spoken = "I'm trying to Trying to go with the flow and process the two together. Hey baby. Yeah. No, " +
        "I understand. None of that bullshit like oh can we stop talking about Yeah, yeah. Okay. He was really " +
        "happy that. Hey if it makes you feel any better if I got No. It doesn't make me feel any better. Yeah."
    private let returned = "I'm trying to Trying to go with the flow and process the two together. Hey baby. Yeah. No, " +
        "I understand. None of that bullshit like oh can we stop talking about Yeah, yeah. Okay. He was really " +
        "happy that. Hey if it makes you feel any better if I got It doesn't make me feel any better. Yeah."

    func testATranslationThatRepeatsTheOriginalIsRecognised() {
        XCTAssertTrue(TextSimilarity.nearlySame(spoken, returned))
        XCTAssertTrue(TextSimilarity.nearlySame("Okay.", "okay"))
    }

    func testARealTranslationIsNot() {
        XCTAssertFalse(TextSimilarity.nearlySame("我们明天去银行", "We are going to the bank tomorrow"))
        XCTAssertFalse(TextSimilarity.nearlySame("Ne vous demandez pas ce que votre pays peut faire pour vous", "Ask not what your country can do for you"))
        XCTAssertFalse(TextSimilarity.nearlySame("No preguntes qué puede hacer tu país por ti", "Don't ask what your country can do for you"))
    }

    func testEnglishWithOneMisdetectedShortUtteranceNeedsNoTranslation() throws {
        let t = try XCTUnwrap(TranslationJoin.combine([
            Part(text: "I'm trying to go with the flow", language: nil, english: "I'm trying to go with the flow", romanization: nil, translated: true),
            Part(text: "Yeah.", language: "ar", english: "Yeah.", romanization: nil, translated: true), // misdetected, came back unchanged
            Part(text: "It doesn't make me feel any better", language: nil, english: "It doesn't make me feel any better", romanization: nil, translated: true),
        ], revisionID: UUID()))
        XCTAssertEqual(t.status, .notNeeded)
    }

    func testMostlyEnglishWithRealMandarinStillShowsTheTranslationLabelledMandarin() throws {
        let t = try XCTUnwrap(TranslationJoin.combine([
            Part(text: "so tomorrow I'm going to", language: nil, english: "so tomorrow I'm going to", romanization: nil, translated: true),
            Part(text: "银行开会", language: "zh", english: "a meeting at the bank", romanization: "yín háng kāi huì", translated: true),
            Part(text: "Yeah.", language: "ar", english: "Yeah.", romanization: nil, translated: true),
        ], revisionID: UUID()))
        XCTAssertEqual(t.status, .done)
        XCTAssertEqual(t.language, "zh")
    }
}

final class NoteCleanupTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 100 * NoteCleanup.day)

    private func note(_ name: String, _ ageDays: Double, pinned: Bool = false, archived: Bool = false,
                      status: TranscriptionStatus = .done, text: String? = "something said",
                      audio: String? = nil, partial: String? = nil) -> (String, Note) {
        var n = Note(createdAt: now.addingTimeInterval(-ageDays * NoteCleanup.day), audioFileName: audio ?? "\(name).wav", status: status)
        n.pinned = pinned
        n.archived = archived
        n.partialText = partial
        if let text { n.revisions = [Revision(text: text, source: .transcription, engine: nil)] }
        return (name, n)
    }

    private lazy var notes: [(String, Note)] = [
        note("fresh", 0.5),
        note("twoDays", 2),
        note("tenDays", 10),
        note("fortyDays", 40),
        note("pinnedOld", 40, pinned: true),
        note("archivedOld", 40, archived: true, audio: "archivedOld.m4a"),
        note("stillTranscribing", 40, status: .inProgress),
        note("emptyOld", 40, text: ""),
        note("emptyFresh", 0.1, text: ""),
        note("waitingForModel", 40, status: .pending, text: nil),
        note("failedWithPartial", 40, status: .failed, text: nil, partial: "half a sentence"),
    ]

    private func ids(_ action: NoteCleanup.Action, _ days: Int, busy: Set<String> = []) -> Set<String> {
        let names = Dictionary(uniqueKeysWithValues: notes.map { ($0.1.id, $0.0) })
        let busyIDs = Set(notes.filter { busy.contains($0.0) }.map(\.1.id))
        return Set(NoteCleanup.select(notes.map(\.1), action: action, olderThanDays: days, now: now, busy: busyIDs).map { names[$0.id]! })
    }

    private func named(_ name: String) -> Note { notes.first { $0.0 == name }!.1 }

    func testOlderThan1_7And30Days() {
        XCTAssertEqual(ids(.archive, 1), ["twoDays", "tenDays", "fortyDays", "emptyOld", "waitingForModel", "failedWithPartial"])
        XCTAssertEqual(ids(.archive, 7), ["tenDays", "fortyDays", "emptyOld", "waitingForModel", "failedWithPartial"])
    }

    func testPinnedAndBusyNotesAreNeverDeletedOrArchived() {
        let deleted = ids(.delete, 30, busy: ["fortyDays"])
        XCTAssertFalse(deleted.contains("pinnedOld"))
        XCTAssertFalse(deleted.contains("fortyDays"))
        XCTAssertFalse(deleted.contains("stillTranscribing"))
        XCTAssertTrue(deleted.contains("archivedOld"))
    }

    func testArchivingSkipsNotesAlreadyArchived() {
        XCTAssertFalse(ids(.archive, 30).contains("archivedOld"))
    }

    func testCompressTakesEveryUncompressedRecordingPinnedOnesTooButNotBusyOnes() {
        let c = ids(.compress, 0)
        XCTAssertTrue(c.contains("pinnedOld"))
        XCTAssertTrue(c.contains("fresh"))
        XCTAssertFalse(c.contains("archivedOld")) // already compressed
        XCTAssertFalse(c.contains("stillTranscribing"))
    }

    func testDeleteEmptyOnlyTakesFinishedNotesWithNoText() {
        XCTAssertEqual(ids(.deleteEmpty, 0), ["emptyOld", "emptyFresh"])
        XCTAssertEqual(ids(.deleteEmpty, 7), ["emptyOld"])
        // Waiting for a model, or a failure with recovered text, may hold speech: never "empty".
        XCTAssertFalse(NoteCleanup.isEmpty(named("waitingForModel")))
        XCTAssertFalse(NoteCleanup.isEmpty(named("failedWithPartial")))
    }

    func testAnyAgeMeansZeroDays() {
        XCTAssertTrue(ids(.delete, 0).contains("fresh"))
    }
}

/// Same cases as the Android app's AutoSplitTest.
final class AutoSplitTests: XCTestCase {
    private let r = 100 // samples per second, to keep numbers readable
    private func s(_ seconds: Double) -> Int { Int((seconds * Double(r)).rounded()) }
    private func split(_ m: AutoSplit, _ start: Double, _ now: Double, _ speaking: Bool, _ last: Double?) -> Bool {
        m.shouldSplit(noteStart: s(start), now: s(now), speaking: speaking, lastVoiceAt: last.map(s), rate: r)
    }

    func testOffNeverSplits() {
        XCTAssertFalse(split(.off, 0, 3600, false, 1))
    }

    func testSilenceModeSplitsOnceThePauseIsLongEnough() {
        XCTAssertFalse(split(.silence10s, 0, 19, false, 10))  // 9 s quiet
        XCTAssertTrue(split(.silence10s, 0, 20, false, 10))   // 10 s quiet
        XCTAssertFalse(split(.silence10s, 0, 20, true, 10))   // talking again
    }

    func testNothingSaidYetMeansNoSplit() {
        XCTAssertFalse(split(.silence10s, 0, 300, false, nil))
        XCTAssertFalse(split(.every5min, 0, 400, false, nil))
    }

    func testIntervalModeWaitsForAPauseAfterTheInterval() {
        XCTAssertFalse(split(.every5min, 0, 299, false, 290))   // before 5 min
        XCTAssertFalse(split(.every5min, 0, 301, true, 301))    // still talking
        XCTAssertFalse(split(.every5min, 0, 301, false, 300.5)) // pause too short
        XCTAssertTrue(split(.every5min, 0, 301, false, 300))    // 1 s pause
    }

    func testIntervalModeSplitsAnywayAMinutePastTheInterval() {
        XCTAssertFalse(split(.every5min, 0, 359, true, 359))
        XCTAssertTrue(split(.every5min, 0, 360, true, 360))
    }

    func testIntervalCountsFromTheStartOfTheCurrentNote() {
        XCTAssertFalse(split(.every15min, 1000, 1000 + 899, false, 1000 + 890))
        XCTAssertTrue(split(.every15min, 1000, 1000 + 901, false, 1000 + 900))
    }

    func testShortSilenceOptionsSplitQuickly() {
        XCTAssertFalse(split(.silence2s, 0, 11.9, false, 10))
        XCTAssertTrue(split(.silence2s, 0, 12, false, 10))
        XCTAssertTrue(split(.silence5s, 0, 15, false, 10))
    }
}

final class AudioLevelTests: XCTestCase {
    private func tone(_ amplitude: Float, _ n: Int = 16_000) -> [Float] { (0..<n).map { amplitude * Float(sin(Double($0) * 0.05)) } }
    private func rms(_ a: [Float]) -> Float { (a.reduce(0) { $0 + $1 * $1 } / Float(a.count)).squareRoot() }

    func testFaintDistantSpeechIsBroughtUpToASteadyLevel() {
        XCTAssertEqual(rms(AudioLevel.normalize(tone(0.005))), 0.08, accuracy: 0.01)
    }

    func testGainIsCappedSoNearSilenceIsNotBlownUpIntoNoise() {
        let hiss = tone(0.0001)
        XCTAssertEqual(rms(AudioLevel.normalize(hiss)), rms(hiss) * 30, accuracy: 1e-5)
    }

    func testLoudAudioIsNotClipped() {
        XCTAssertLessThanOrEqual(AudioLevel.normalize(tone(0.9)).map(abs).max()!, 0.951)
    }

    func testAudioAlreadyAtTheTargetLevelIsPassedThroughUntouched() {
        let ok = tone(0.08 * Float(2).squareRoot())
        XCTAssertEqual(AudioLevel.normalize(ok), ok)
    }
}

@MainActor
final class EditProtectionTests: XCTestCase {
    private func note(_ revisions: [(String, Revision.Source)]) -> Note {
        var n = Note()
        n.revisions = revisions.map { Revision(text: $0.0, source: $0.1, engine: nil) }
        return n
    }

    func testEditingAPendingNoteSettlesItSoTheQueueWontTranscribeOverIt() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = NoteStore(baseURL: dir)
        var n = Note(status: .failed)
        n.lastError = "model failed"
        n.partialText = "half"
        store.add(n)
        store.addRevision(to: n.id, text: "What I actually said", source: .edit)
        let saved = try XCTUnwrap(store.note(id: n.id))
        XCTAssertEqual(saved.status, .done)
        XCTAssertFalse(saved.needsTranscription)
        XCTAssertNil(saved.lastError)
        XCTAssertEqual(saved.currentText, "What I actually said")
    }

    func testAnAutomaticTranscriptionThatFinishesAfterAnEditGoesBehindIt() {
        let after = note([("my edit", .edit)]).withTranscription("machine text", source: .transcription, engine: "Parakeet v3", language: nil)
        XCTAssertEqual(after.currentText, "my edit")
        XCTAssertEqual(after.revisions.map(\.text), ["machine text", "my edit"])
    }

    func testAnEmptyTranscriptionNeverReplacesText() {
        let edited = note([("hello there", .edit)])
        XCTAssertEqual(edited.withTranscription("", source: .retranscription, engine: "Parakeet v3", language: nil, userRequested: true), edited)
        let transcribed = note([("hello there", .transcription)])
        XCTAssertEqual(transcribed.withTranscription("  ", source: .retranscription, engine: "x", language: nil), transcribed)
    }

    func testAReTranscriptionTheUserAskedForBecomesCurrent() {
        let after = note([("old", .transcription), ("my edit", .edit)])
            .withTranscription("fresh text", source: .retranscription, engine: "Whisper turbo", language: nil, userRequested: true)
        XCTAssertEqual(after.currentText, "fresh text")
    }

    func testAFirstTranscriptionWithNoEditsIsSimplyAdded() {
        let after = Note().withTranscription("first", source: .transcription, engine: "Parakeet v3", language: "en")
        XCTAssertEqual(after.currentText, "first")
        XCTAssertEqual(after.revisions.map(\.language), ["en"])
    }

    func testNewFieldsSurviveARoundTripThroughJSON() throws {
        var n = Note(audioFileName: "a.m4a")
        n.backgroundVoices = true
        n.archived = true
        n.archivedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let back = try decoder.decode(Note.self, from: encoder.encode(n))
        XCTAssertTrue(back.backgroundVoices)
        XCTAssertTrue(back.archived)
        XCTAssertEqual(back.archivedAt, n.archivedAt)
    }
}
