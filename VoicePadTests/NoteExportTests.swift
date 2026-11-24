import XCTest

/// Same cases as the Android app's NoteExportTest; the ZIP is read back by a
/// minimal reader that also checks every entry's CRC.
final class NoteExportTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_764_000_000) // 2025-11-24 16:00 UTC

    override func setUp() { NoteExport.timeZone = TimeZone(identifier: "UTC")! }

    private func note(_ id: String, _ created: Date, _ text: String, audio: String? = nil) -> Note {
        var n = Note(id: UUID(uuidString: id)!, createdAt: created, audioFileName: audio)
        n.duration = 75
        n.revisions = [Revision(text: text, date: created, source: .transcription, engine: "Parakeet v3", language: "en")]
        return n
    }

    private let a = "AAAAAAAA-0000-4000-8000-000000000001"
    private let b = "BBBBBBBB-0000-4000-8000-000000000002"
    private let c = "CCCCCCCC-0000-4000-8000-000000000003"

    private func temp() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString) }

    /// Reads a stored-entries ZIP through its central directory.
    private func unzip(_ url: URL) throws -> [String: Data] {
        let d = try Data(contentsOf: url)
        func u16(_ o: Int) -> Int { Int(d[o]) | Int(d[o + 1]) << 8 }
        func u32(_ o: Int) -> Int { u16(o) | u16(o + 2) << 16 }
        let end = d.count - 22
        XCTAssertEqual(u32(end), 0x0605_4b50)
        var at = u32(end + 16)
        var out: [String: Data] = [:]
        for _ in 0..<u16(end + 10) {
            XCTAssertEqual(u32(at), 0x0201_4b50)
            let crc = UInt32(u32(at + 16)), size = u32(at + 20), nameLength = u16(at + 28)
            let name = String(decoding: d[(at + 46)..<(at + 46 + nameLength)], as: UTF8.self)
            let local = u32(at + 42)
            let start = local + 30 + u16(local + 26) + u16(local + 28)
            let body = d[start..<(start + size)]
            XCTAssertEqual(CRC32.of(Data(body)), crc, name)
            out[name] = Data(body)
            at += 46 + nameLength + u16(at + 30) + u16(at + 32)
        }
        return out
    }

    func testZipHasJsonAndMarkdownOldestFirst() throws {
        let url = temp()
        try NoteExport.writeZip(to: url, notes: [note(b, t0 + 60, "Second"), note(a, t0, "First")], includeAudio: false, now: t0) { _ in nil }
        let files = try unzip(url)
        XCTAssertEqual(Set(files.keys), ["notes.json", "notes.md"])

        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: files["notes.json"]!) as? [String: Any])
        XCTAssertEqual(json["format"] as? String, "voicepad-notes")
        XCTAssertEqual(json["version"] as? Int, 1)
        let ids = (json["notes"] as? [[String: Any]])?.compactMap { ($0["note"] as? [String: Any])?["id"] as? String }
        XCTAssertEqual(ids, [a, b])

        let md = String(decoding: files["notes.md"]!, as: UTF8.self)
        XCTAssertTrue(md.hasPrefix("# VoicePad notes\n\nExported 2025-11-24 16:00 · 2 notes"))
        XCTAssertLessThan(md.range(of: "First")!.lowerBound, md.range(of: "Second")!.lowerBound)
        XCTAssertTrue(md.contains("## 2025-11-24 16:00\n\n*1:15 · language: en · Parakeet v3*\n\nFirst\n"))
    }

    func testRecordingsGoInWhenAskedAndAreLinked() throws {
        let dir = temp()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let wav = dir.appendingPathComponent("a.wav")
        try Data((0..<100).map { UInt8($0) }).write(to: wav)
        let notes = [note(a, t0, "With audio", audio: "a.wav"), note(c, t0 + 0.001, "Audio missing", audio: "gone.wav")]
        let find = { (n: Note) in n.audioFileName.map { dir.appendingPathComponent($0) } }

        let withURL = temp()
        try NoteExport.writeZip(to: withURL, notes: notes, includeAudio: true, now: t0, audioFile: find)
        let withAudio = try unzip(withURL)
        let path = "audio/2025-11-24_16-00-00_aaaaaaaa.wav"
        XCTAssertEqual(Set(withAudio.keys), ["notes.json", "notes.md", path])
        XCTAssertEqual(withAudio[path], try Data(contentsOf: wav))
        XCTAssertTrue(String(decoding: withAudio["notes.md"]!, as: UTF8.self).contains("audio: [\(path)](\(path))"))
        XCTAssertTrue(String(decoding: withAudio["notes.json"]!, as: UTF8.self).contains("\"audioPath\" : \"\(path)\""))

        let withoutURL = temp()
        try NoteExport.writeZip(to: withoutURL, notes: notes, includeAudio: false, now: t0, audioFile: find)
        XCTAssertFalse(try unzip(withoutURL).keys.contains { $0.hasPrefix("audio/") })
    }

    func testMarkdownShowsTranslationAndEarlierVersions() {
        var n = Note(createdAt: t0)
        let r2 = Revision(text: "你好，世界", date: t0 + 60, source: .edit, engine: nil)
        n.revisions = [Revision(text: "你好世界", date: t0, source: .transcription, engine: nil, language: "zh"), r2]
        n.translation = NoteTranslation(revisionID: r2.id, status: .done, language: "zh", english: "Hello, world", romanization: "nǐ hǎo, shì jiè")
        let md = NoteExport.markdown([NoteExport.ExportedNote(note: n)], now: t0)
        XCTAssertTrue(md.contains("你好，世界\n\n> nǐ hǎo, shì jiè\n\n**English:** Hello, world\n"))
        XCTAssertTrue(md.contains("<details><summary>Earlier versions (1)</summary>\n\n- 2025-11-24 16:00 · Transcribed: 你好世界\n"))
    }
}
