import XCTest

@MainActor
final class NoteStoreRecoveryTests: XCTestCase {
    private var dir: URL!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private func store() -> NoteStore { NoteStore(baseURL: dir) }

    /// Writes a WAV the way the recorder does before its first checkpoint: header still says 0 bytes.
    @discardableResult
    private func crashedWav(_ store: NoteStore, _ name: String, seconds: Double) -> URL {
        let url = store.recordingsURL.appendingPathComponent(name)
        let samples = Int(seconds * Double(WavFile.sampleRate))
        var data = WavFile.header(dataBytes: 0)
        for i in 0..<samples {
            withUnsafeBytes(of: Int16((i % 100) * 100).littleEndian) { data.append(contentsOf: $0) }
        }
        data.append(7) // plus a torn half-sample
        FileManager.default.createFile(atPath: url.path, contents: data)
        return url
    }

    private func headerDataSize(_ url: URL) throws -> Int {
        let data = try Data(contentsOf: url)
        return Int(data[40..<44].withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) }.littleEndian)
    }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    func testRecordingInterruptedByACrashIsRepairedAndQueued() throws {
        let s = store()
        let wav = crashedWav(s, "a.wav", seconds: 3)
        var n = Note(audioFileName: "a.wav", status: .recording)
        n.partialText = "hello"
        s.add(n)

        let reopened = store()
        reopened.recoverAfterRestart()

        let note = try XCTUnwrap(reopened.note(id: n.id))
        XCTAssertEqual(note.status, .pending)
        XCTAssertEqual(note.duration, 3, accuracy: 0.001)
        XCTAssertEqual(note.partialText, "hello")
        XCTAssertTrue(note.needsTranscription)
        XCTAssertEqual(try headerDataSize(wav), 3 * WavFile.sampleRate * 2)
        XCTAssertEqual(try WavFile.read(wav).count, 3 * WavFile.sampleRate)
    }

    func testCrashBeforeAnythingWasSaidDropsTheEmptyRecording() {
        let s = store()
        let wav = crashedWav(s, "b.wav", seconds: 0.3)
        let n = Note(audioFileName: "b.wav", status: .recording)
        s.add(n)

        s.recoverAfterRestart()

        XCTAssertNil(s.note(id: n.id))
        XCTAssertFalse(exists(wav))
    }

    func testCrashDuringTranscriptionMarksItFailedInsteadOfRetryingForever() throws {
        let s = store()
        crashedWav(s, "c.wav", seconds: 2)
        crashedWav(s, "d.wav", seconds: 2)
        var c = Note(audioFileName: "c.wav", status: .inProgress)
        c.attempts = 1
        var d = Note(audioFileName: "d.wav", status: .inProgress)
        d.attempts = 2
        d.revisions = [Revision(text: "kept", source: .transcription)]
        s.add(c)
        s.add(d)

        s.recoverAfterRestart()

        let rc = try XCTUnwrap(s.note(id: c.id))
        XCTAssertEqual(rc.status, .failed)
        XCTAssertEqual(rc.attempts, 1)
        XCTAssertNotNil(rc.lastError)
        XCTAssertTrue(rc.needsTranscription)
        // A note that already had text keeps it as current.
        XCTAssertEqual(s.note(id: d.id)?.status, .done)
        XCTAssertEqual(s.note(id: d.id)?.currentText, "kept")
    }

    func testNotesSavedBeforeStatusesExistedGetQueuedIfTheyHaveAudioButNoText() throws {
        let s = store()
        crashedWav(s, "e.wav", seconds: 2)
        let e = UUID(), f = UUID()
        // The format PR #1 wrote: no status, attempts or pin fields.
        let legacy = """
        [{"id":"\(e)","createdAt":"2026-11-01T10:00:00Z","duration":2,"audioFileName":"e.wav","revisions":[]},
         {"id":"\(f)","createdAt":"2026-11-01T11:00:00Z","duration":0,
          "revisions":[{"id":"\(UUID())","text":"hi","date":"2026-11-01T11:00:00Z","source":"transcription"}]}]
        """
        try legacy.write(to: dir.appendingPathComponent("notes.json"), atomically: true, encoding: .utf8)

        let reopened = store()
        XCTAssertEqual(reopened.notes.count, 2, "legacy notes must still decode")
        reopened.recoverAfterRestart()

        XCTAssertEqual(reopened.note(id: e)?.status, .pending)
        XCTAssertEqual(reopened.note(id: f)?.status, .done)
        XCTAssertEqual(reopened.note(id: f)?.currentText, "hi")
    }

    func testCorruptIndexFallsBackToTheBackupInsteadOfLosingEveryNote() throws {
        let s = store()
        let g = Note(audioFileName: "g.wav", status: .pending)
        let h = Note(audioFileName: "h.wav", status: .pending)
        s.add(g)
        s.add(h)
        try "{ truncated".write(to: dir.appendingPathComponent("notes.json"), atomically: true, encoding: .utf8)

        XCTAssertNotNil(store().note(id: g.id))
    }

    func testStatusChangesSurviveARestart() throws {
        let s = store()
        let n = Note(audioFileName: "i.wav", status: .recording)
        s.add(n)
        s.update(n.id) {
            $0.status = .failed
            $0.lastError = "model crashed"
            $0.attempts = 3
        }

        let note = try XCTUnwrap(store().note(id: n.id))
        XCTAssertEqual(note.status, .failed)
        XCTAssertEqual(note.lastError, "model crashed")
        XCTAssertEqual(note.attempts, 3)
    }

    func testDeleteRemovesTheAudioFile() {
        let s = store()
        let wav = crashedWav(s, "j.wav", seconds: 1.5)
        let n = Note(audioFileName: "j.wav")
        s.add(n)

        s.delete(n.id)

        XCTAssertNil(s.note(id: n.id))
        XCTAssertFalse(exists(wav))
    }

    func testWavWriterKeepsTheHeaderValidWhileRecording() throws {
        let url = dir.appendingPathComponent("w.wav")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let writer = try WavWriter(url: url)
        let samples = [Float](repeating: 0.25, count: WavFile.sampleRate * 6)
        try samples.withUnsafeBufferPointer { try writer.append(WavFile.pcm16($0)) }
        // Past the ~5 s checkpoint the on-disk header already reflects the data, before close.
        XCTAssertEqual(try headerDataSize(url), samples.count * 2)
        try writer.close()
        let read = try WavFile.read(url)
        XCTAssertEqual(read.count, samples.count)
        XCTAssertEqual(read[100], 0.25, accuracy: 0.001)
    }
}
