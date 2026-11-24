import XCTest

/// Same cases as the Android app's ConversationTest and StageTest (minus the
/// multi-channel ones, which iOS doesn't do yet).
final class ConversationLanguagesTests: XCTestCase {
    private let enZh = ("en", "zh")
    private let enEs = ("en", "es")
    private func spoken(_ pair: (String, String), _ model: String?, _ candidates: [(String, Double)], _ text: String, fallback: String? = nil) -> String {
        ConversationLanguages.spoken(pair, model: model, candidates: candidates, text: text, fallback: fallback)
    }

    func testChineseCharactersAreChineseWhateverTheModelSays() { XCTAssertEqual(spoken(enZh, "en", [], "你好，我很好"), "zh") }

    func testLatinTextIsTheLatinSideWhenTheOtherIsNot() {
        XCTAssertEqual(spoken(enZh, nil, [], "ok"), "en")
        XCTAssertEqual(spoken(("ja", "fr"), nil, [], "oui"), "fr")
    }

    func testModelTagWinsWhenTheScriptCantTell() { XCTAssertEqual(spoken(enEs, "es", [("en", 0.9)], "no"), "es") }

    func testTextDetectorIsRestrictedToThePair() {
        // "Italian" is likelier, but only English and Spanish are in this conversation.
        XCTAssertEqual(spoken(enEs, nil, [("it", 0.6), ("es", 0.3), ("en", 0.1)], "gracias amigo"), "es")
    }

    func testModelTagOutsideThePairIsIgnored() { XCTAssertEqual(spoken(enEs, "fr", [("en", 0.7), ("es", 0.2)], "thank you"), "en") }

    func testCantoneseCountsAsChineseWhenOnlyChineseIsInPlay() { XCTAssertEqual(spoken(("en", "zh"), "yue", [], "ok la"), "zh") }

    func testKanjiOnlyGoesToJapaneseWhenChineseIsntInPlay() { XCTAssertEqual(spoken(("en", "ja"), nil, [], "東京"), "ja") }

    func testFallsBackToTheGivenGuessThenTheFirstLanguage() {
        XCTAssertEqual(spoken(enEs, nil, [], "ok", fallback: "es"), "es")
        XCTAssertEqual(spoken(enEs, nil, [], "ok", fallback: "de"), "en")
    }

    func testTargetIsTheOtherLanguage() {
        XCTAssertEqual(ConversationLanguages.target(enZh, spoken: "en"), "zh")
        XCTAssertEqual(ConversationLanguages.target(enZh, spoken: "zh"), "en")
    }
}

final class SpeakerClusteringTests: XCTestCase {
    private var rng = SystemRandomNumberGenerator()
    private struct Seeded: RandomNumberGenerator {
        var state: UInt64
        mutating func next() -> UInt64 { state = state &* 6364136223846793005 &+ 1442695040888963407; return state }
    }
    private var random = Seeded(state: 7)
    private func r() -> Float { Float.random(in: -1...1, using: &random) }
    /// A voice: a fixed direction plus per-utterance noise, like real embeddings.
    private func voice() -> [Float] { (0..<192).map { _ in r() } }
    private func utterance(_ v: [Float], noise: Float = 0.6) -> [Float] { v.map { $0 + noise * r() } }

    func testSeparatesTwoVoicesTakingTurns() {
        let clustering = SpeakerClustering()
        let a = voice(), b = voice()
        let ids = (0..<20).map { clustering.assign(utterance($0 % 2 == 0 ? a : b)) }
        XCTAssertEqual(clustering.speakerCount, 2)
        XCTAssertTrue(ids.enumerated().filter { $0.offset % 2 == 0 }.allSatisfy { $0.element == ids[0] })
        XCTAssertTrue(ids.enumerated().filter { $0.offset % 2 == 1 }.allSatisfy { $0.element == ids[1] })
        XCTAssertNotEqual(ids[0], ids[1])
    }

    func testNeverMoreThanTheMaximum() {
        let clustering = SpeakerClustering(maxSpeakers: 3)
        for _ in 0..<10 { _ = clustering.assign(voice()) }
        XCTAssertEqual(clustering.speakerCount, 3)
    }

    func testLanguageNudgesABorderlineUtterance() {
        let clustering = SpeakerClustering(threshold: 0.45, languageBonus: 0.06)
        XCTAssertEqual(clustering.assign([1, 0, 0, 0], language: "en"), 0)
        XCTAssertEqual(clustering.assign([0, 1, 0, 0], language: "zh"), 1)
        // Equally close to both; the language decides.
        XCTAssertEqual(clustering.assign([1, 1, 0, 0], language: "zh"), 1)
    }
}

final class TurnsTests: XCTestCase {
    private let one = SpeakerRef(source: .voice, index: 0)
    private let two = SpeakerRef(source: .voice, index: 1)
    private func turn(_ speaker: SpeakerRef?, _ language: String, end: Int, text: String = "hello", session: Int = 1) -> Turn {
        Turn(id: 1, session: session, speaker: speaker, language: language, text: text, target: "zh", start: 0, end: end)
    }

    func testSameSpeakerAfterAShortPauseContinuesTheTurn() {
        let previous = turn(one, "en", end: 16_000)
        XCTAssertTrue(Turns.continues(previous, session: 1, speaker: one, language: "en", start: 32_000, sampleRate: 16_000))
        XCTAssertFalse(Turns.continues(previous, session: 1, speaker: two, language: "en", start: 32_000, sampleRate: 16_000))
        XCTAssertFalse(Turns.continues(previous, session: 1, speaker: one, language: "zh", start: 32_000, sampleRate: 16_000))
        XCTAssertFalse(Turns.continues(previous, session: 1, speaker: one, language: "en", start: 16_000 * 4, sampleRate: 16_000))
        XCTAssertFalse(Turns.continues(previous, session: 2, speaker: one, language: "en", start: 32_000, sampleRate: 16_000))
        XCTAssertFalse(Turns.continues(turn(one, "en", end: 16_000, text: String(repeating: "x", count: 300)), session: 1, speaker: one, language: "en", start: 20_000, sampleRate: 16_000))
    }

    func testJoinsChineseWithoutSpaces() {
        XCTAssertEqual(Turns.join("你好。", "我很好"), "你好。我很好")
        XCTAssertEqual(Turns.join("Hello", "there"), "Hello there")
        XCTAssertEqual(Turns.join("", "there"), "there")
    }

    func testTranscriptListsTurnsWithTranslations() {
        var a = turn(one, "en", end: 10, text: "Hello"); a.translation = "你好"
        var b = turn(two, "zh", end: 20, text: "谢谢"); b.target = "en"
        XCTAssertEqual(Turns.transcript([a, b], label: { "Speaker \(($0.speaker?.index ?? 0) + 1)" }), "Speaker 1: Hello\n→ 你好\n\nSpeaker 2: 谢谢")
    }

    func testEachPaneShowsItsOwnLanguage() {
        var t = turn(one, "en", end: 10, text: "Hello"); t.translation = "你好"
        XCTAssertEqual(t.text(in: "en"), "Hello")
        XCTAssertEqual(t.text(in: "zh"), "你好")
    }

    func testRecommendsAModelThatUnderstandsBothLanguages() {
        XCTAssertEqual(EngineChoice.recommended(for: "en", "es"), .parakeetV3)
        XCTAssertEqual(EngineChoice.recommended(for: "en", "zh"), .autoAsian)
        XCTAssertEqual(EngineChoice.recommended(for: "zh", "ja"), .senseVoice)
        XCTAssertEqual(EngineChoice.recommended(for: "en", "ar"), .whisperLargeV3Turbo)
        XCTAssertEqual(EngineChoice.recommended(for: "zh", "es"), .whisperLargeV3Turbo)
    }

    func testPrefersAModelAlreadyOnThePhone() {
        XCTAssertEqual(EngineChoice.recommended(for: "en", "ar", installed: [.whisperSmall]), .whisperSmall)
        XCTAssertEqual(EngineChoice.recommended(for: "en", "zh", installed: [.senseVoice]), .senseVoice)
    }

    func testLayoutsCycleAndWrapAround() {
        var layout = ConversationLayout.faceToFace
        var seen = [layout]
        for _ in 1..<ConversationLayout.allCases.count { layout = layout.next(); seen.append(layout) }
        XCTAssertEqual(seen, ConversationLayout.allCases)
        XCTAssertEqual(layout.next(), .faceToFace)
    }
}

final class StageTests: XCTestCase {
    private func turn(_ id: Int, _ language: String, _ text: String, _ translation: String? = nil, pending: Bool = false, held: Bool = false) -> Turn {
        Turn(id: id, session: 1, speaker: nil, language: language, text: text, target: language == "en" ? "zh" : "en",
             translation: translation, pending: pending, held: held, start: 0, end: 0)
    }

    func testFinishedSentencesAreFinished() {
        XCTAssertFalse(Turns.looksUnfinished("Maybe meet near the station around six?"))
        XCTAssertFalse(Turns.looksUnfinished("Seven works."))
        XCTAssertFalse(Turns.looksUnfinished("七点可以吗？"))
        XCTAssertFalse(Turns.looksUnfinished("He said \"no.\""))
    }

    func testARunOfWordsWithoutAnEndingIsUnfinished() {
        XCTAssertTrue(Turns.looksUnfinished("So I was thinking we could"))
        XCTAssertTrue(Turns.looksUnfinished("Six is a bit early, I have to first,"))
        XCTAssertTrue(Turns.looksUnfinished("六点有点早，我得先"))
    }

    func testTrailingOffIsUnfinished() {
        XCTAssertTrue(Turns.looksUnfinished("Do you know that noodle place..."))
        XCTAssertTrue(Turns.looksUnfinished("I went there and."))
        XCTAssertTrue(Turns.looksUnfinished("我想去那家面馆，然后。"))
    }

    func testShortRepliesAreCompleteEvenWithoutPunctuation() {
        XCTAssertFalse(Turns.looksUnfinished("ok"))
        XCTAssertFalse(Turns.looksUnfinished("yes please"))
        XCTAssertFalse(Turns.looksUnfinished("好的"))
        XCTAssertFalse(Turns.looksUnfinished("So."))
        XCTAssertFalse(Turns.looksUnfinished(""))
    }

    func testAHeldTurnCanBePickedUpAfterALongPause() {
        let sr = 16_000
        var previous = turn(1, "en", "So I was thinking we could", held: true)
        previous.end = sr
        let start = sr + Int(3.4 * Double(sr))
        XCTAssertFalse(Turns.continues(previous, session: 1, speaker: nil, language: "en", start: start, sampleRate: sr))
        XCTAssertTrue(Turns.continues(previous, session: 1, speaker: nil, language: "en", start: start, sampleRate: sr, maxGapSeconds: 5))
        XCTAssertFalse(Turns.continues(previous, session: 1, speaker: nil, language: "en", start: sr + 6 * sr, sampleRate: sr, maxGapSeconds: 5))
    }

    func testNothingSaidIsIdle() {
        let stage = Stage.of([], live: nil, working: false)
        XCTAssertNil(stage.turn)
        XCTAssertEqual(stage.phase, .idle)
    }

    func testLiveSpeechTakesTheStageAndEarlierTurnsRecede() {
        let done = turn(1, "zh", "你好。", "Hello.")
        let stage = Stage.of([done], live: turn(-1, "en", "Hi, how"), working: false)
        XCTAssertEqual(stage.phase, .speaking)
        XCTAssertEqual(stage.turn?.text, "Hi, how")
        XCTAssertEqual(stage.history, [done])
    }

    func testSpeechResumingInsideAHeldTurnShowsTheWholeThought() {
        let held = turn(2, "en", "So I was thinking we could", "我在想我们可以", pending: true, held: true)
        let stage = Stage.of([held], live: turn(-1, "en", "maybe meet near", "也许在附近见面"), working: false)
        XCTAssertEqual(stage.phase, .speaking)
        XCTAssertEqual(stage.turn?.id, 2)
        XCTAssertEqual(stage.turn?.text, "So I was thinking we could maybe meet near")
        XCTAssertEqual(stage.turn?.translation, "我在想我们可以也许在附近见面")
        XCTAssertTrue(stage.history.isEmpty)
    }

    func testAHeldTurnInAnotherLanguageIsNotJoinedToNewSpeech() {
        let held = turn(2, "en", "So I was thinking we could", pending: true, held: true)
        let stage = Stage.of([held], live: turn(-1, "zh", "好的"), working: false)
        XCTAssertEqual(stage.turn?.text, "好的")
        XCTAssertEqual(stage.history, [held])
    }

    func testPhasesFollowTheNewestTurn() {
        let earlier = turn(1, "zh", "你好。", "Hello.")
        func phase(_ last: Turn, working: Bool = false) -> Stage.Phase { Stage.of([earlier, last], live: nil, working: working).phase }
        XCTAssertEqual(phase(turn(2, "en", "So I was thinking we could", pending: true, held: true)), .holding)
        XCTAssertEqual(phase(turn(2, "en", "Seven works.", pending: true)), .working)
        XCTAssertEqual(phase(turn(2, "en", "Seven works.", "七点可以。"), working: true), .working)
        XCTAssertEqual(phase(turn(2, "en", "Seven works.", "七点可以。")), .landed)
    }

    func testWhileAnUtteranceIsBeingTranscribedTheStageIsWorking() {
        XCTAssertEqual(Stage.of([], live: turn(-1, "en", "Seven works"), working: true).phase, .working)
    }

    func testStandInsUseTheReadersScript() {
        XCTAssertTrue(Glyphs.pool("zh").allSatisfy { LanguageTags.containsHan(String($0)) })
        XCTAssertTrue(Glyphs.pool("es").allSatisfy { ("a"..."z").contains(String($0)) })
        XCTAssertTrue(Glyphs.pool("ru").allSatisfy { ConversationLanguages.scriptCandidates(String($0)).first == "ru" })
    }

    func testLengthEstimateFollowsHowDenseTheScriptsAre() {
        let english = "So I was thinking we could maybe meet near the station around six?"
        XCTAssertLessThan(Glyphs.estimateLength(english, from: "en", to: "zh"), english.count / 2)
        XCTAssertGreaterThan(Glyphs.estimateLength("六点左右在车站附近见面？", from: "zh", to: "en"), 24)
        XCTAssertEqual(Glyphs.estimateLength(english, from: "en", to: "es"), english.count)
    }
}
