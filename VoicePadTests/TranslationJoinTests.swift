import XCTest

/// Same cases as the Android app's TranslationJoinTest.
final class TranslationJoinTests: XCTestCase {
    typealias Part = TranslationJoin.Part
    private let id = UUID()

    func testMixedMandarinAndEnglishReadsAsOneEnglishText() throws {
        let t = try XCTUnwrap(TranslationJoin.combine([
            Part(text: "我们明天去银行", language: "zh", english: "We're going to the bank tomorrow", romanization: "wǒ men míng tiān qù yín háng", translated: true),
            Part(text: "and then lunch", language: "en", english: "and then lunch", romanization: nil, translated: true),
            Part(text: "你好", language: "zh", english: "Hello", romanization: "nǐ hǎo", translated: true),
        ], revisionID: id))
        XCTAssertEqual(t.status, .done)
        XCTAssertEqual(t.language, "zh")
        XCTAssertEqual(t.english, "We're going to the bank tomorrow and then lunch Hello")
        XCTAssertEqual(t.romanization, "wǒ men míng tiān qù yín háng nǐ hǎo")
    }

    func testAllEnglishNeedsNoTranslation() throws {
        let t = try XCTUnwrap(TranslationJoin.combine([Part(text: "hi", language: "en", english: "hi", romanization: nil, translated: true)], revisionID: id))
        XCTAssertEqual(t.status, .notNeeded)
    }

    func testShowsTranslatingUntilTheFirstPartArrivesThenPartialEnglish() throws {
        let waiting = try XCTUnwrap(TranslationJoin.combine([Part(text: "你好", language: "zh", english: nil, romanization: nil, translated: false)], revisionID: id))
        XCTAssertEqual(waiting.status, .pending)
        let partial = try XCTUnwrap(TranslationJoin.combine([
            Part(text: "你好", language: "zh", english: "Hello", romanization: "nǐ hǎo", translated: true),
            Part(text: "谢谢", language: "zh", english: nil, romanization: nil, translated: false),
        ], revisionID: id))
        XCTAssertEqual(partial.status, .done)
        XCTAssertEqual(partial.english, "Hello")
    }

    func testNothingSpokenYet() {
        XCTAssertNil(TranslationJoin.combine([], revisionID: id))
    }
}
