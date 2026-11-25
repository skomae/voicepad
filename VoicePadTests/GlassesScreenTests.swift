import XCTest

/// The page is what the waveguide shows, so the numbers are the contract: five
/// lines, forty columns, wide scripts counted as two.
final class GlassesScreenTests: XCTestCase {
    private func page(
        _ turns: [Turn],
        my: String = "en",
        their: String = "zh",
        scope: GlassesScope = .them,
        listening: Bool = true
    ) -> [String] {
        GlassesScreen.page(stage: Stage(turn: turns.first, phase: .landed, history: Array(turns.dropFirst())),
                           myLanguage: my, theirLanguage: their, scope: scope, listening: listening)
    }

    private func turn(_ language: String, _ text: String, _ translation: String? = nil) -> Turn {
        Turn(id: 0, session: 0, speaker: nil, language: language, text: text, target: "en",
             translation: translation, start: 0, end: 1)
    }

    func testAlwaysFiveLines() {
        XCTAssertEqual(page([]).count, 5)
        XCTAssertEqual(page([turn("zh", "你好", "Hello")]).count, 5)
    }

    func testNothingSaidYetShowsTheLanguagePair() {
        let lines = page([], my: "en", their: "zh", listening: true)
        XCTAssertTrue(lines[2].contains("ZH → EN"), lines.joined(separator: "\n"))
        XCTAssertTrue(lines[3].contains("listening"), lines.joined(separator: "\n"))
    }

    func testOnlyTheOtherPersonInThemScope() {
        let lines = page([turn("en", "Hello there"), turn("zh", "你好", "Hi")], scope: .them)
        let text = lines.joined(separator: "\n")
        XCTAssertTrue(text.contains("Hi"))
        XCTAssertFalse(text.contains("Hello there"))
    }

    func testBothScopeMarksWhoSpoke() {
        let me = Turn(id: 0, session: 0, speaker: nil, language: "en", text: "Hello there", target: "en",
                      translation: nil, start: 0, end: 1)
        let them = Turn(id: 1, session: 0, speaker: nil, language: "zh", text: "你好", target: "en",
                        translation: "Hi", start: 1, end: 2)
        let lines = page([them, me], scope: .both)
        let text = lines.joined(separator: "\n")
        XCTAssertTrue(text.contains("- Hello there"), text)
        XCTAssertTrue(text.contains("> Hi"), text)
    }

    func testWrapCountsWideCharactersAsTwo() {
        // 25 Han characters = 50 columns, so it cannot be one 40-column line.
        let text = String(repeating: "中", count: 25)
        let lines = GlassesScreen.wrap(text)
        XCTAssertGreaterThan(lines.count, 1)
        for line in lines { XCTAssertLessThanOrEqual(GlassesScreen.width(line), 40, line) }
        XCTAssertEqual(lines.joined(), text)
    }

    func testWrapNeverLeavesACloserAtTheStartOfALine() {
        let text = String(repeating: "中文，", count: 20)
        for line in GlassesScreen.wrap(text) {
            XCTAssertFalse(line.hasPrefix("，"), line)
            XCTAssertFalse(line.hasPrefix("。"), line)
        }
    }

    func testOverlongTokenIsCutByWidth() {
        let text = String(repeating: "W", count: 95)
        let lines = GlassesScreen.wrap(text)
        XCTAssertEqual(lines.count, 3)
        for line in lines { XCTAssertLessThanOrEqual(line.count, 40) }
        XCTAssertEqual(lines.joined(), text)
    }

    func testEmptyTextStillGivesOneLine() {
        XCTAssertEqual(GlassesScreen.wrap("   "), [""])
    }

    func testCentrePadsByDisplayWidth() {
        // Six columns of text inside forty: 17 spaces each side.
        XCTAssertEqual(GlassesScreen.centre("ZH → EN"), String(repeating: " ", count: 16) + "ZH → EN")
    }

    func testChinesePageGetsAPinyinLine() {
        let lines = page([turn("zh", "你好", "你好")], my: "zh", their: "en", scope: .both)
        let filled = lines.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        XCTAssertEqual(filled.count, 2, "characters and their pinyin: " + lines.joined(separator: " | "))
        XCTAssertTrue(filled[0].contains("你好"), filled[0])
        XCTAssertNotEqual(filled[0], filled[1])
    }
}
