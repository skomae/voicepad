import XCTest

/// Ported from Android's `WordBreakdownTest` (and `PinyinBracketsTest`).
final class WordBreakdownTests: XCTestCase {
    /// A few real CC-CEDICT lines, with jieba frequencies merged in as the build does.
    private let dictionary = ZhDictionary.parse([
        "# CC-CEDICT excerpt",
        "22128\t行 行 [hang2] /(bound form) row; line/(bound form) line of business; trade; profession/classifier for rows or lines/",
        "22128\t行 行 [xing2] /(bound form) to walk; to go; to travel/(literary) trip; journey; visit/capable; competent/all right; OK!; will do/",
        "13\t銀 银 [yin2] /silver/silver-colored/relating to money or currency/surname Yin/",
        "7684\t銀行 银行 [yin2 hang2] /bank/CL:家[jia1],個|个[ge4]/",
        "1\t銀行 银行 [Yin2 hang2] /Yinhang (place name)/",
        "2900\t昨天 昨天 [zuo2 tian1] /yesterday/",
        "1200\t昨 昨 [zuo2] /yesterday/",
        "3000\t天 天 [tian1] /day/sky/heaven/",
        "8000\t我 我 [wo3] /I; me; my/",
        "9000\t去 去 [qu4] /to go/to go to (a place)/to leave/",
        "500\t錢 钱 [qian2] /coin; money/CL:筆|笔[bi3]/surname Qian/",
        "2\t一行 一行 [yi1 xing2] /a group traveling together/",
        "883634\t了 了 [le5] /(completed action marker)/(modal particle indicating change of state, situation now)/",
        "883634\t了 了 [liao3] /to finish/(literary) (usually followed by a negative) fully; clearly/to understand clearly/unofficial variant of 瞭[liao4]/",
    ].joined(separator: "\n"))

    private lazy var breakdown = WordBreakdown(dictionary: dictionary)

    // MARK: Pinyin

    func testPinyinMarks() {
        XCTAssertEqual(Pinyin.marks("yin2 hang2"), "yín háng")
        XCTAssertEqual(Pinyin.marks("nu:3"), "nǚ")
        XCTAssertEqual(Pinyin.marks("ma5"), "ma")
        XCTAssertEqual(Pinyin.marks("zhou1"), "zhōu")
        XCTAssertEqual(Pinyin.marks("gui4"), "guì")
        XCTAssertEqual(Pinyin.marks("Bei3 jing1"), "Běi jīng")
        XCTAssertEqual(Pinyin.marks("r5"), "r")
        XCTAssertEqual(Pinyin.tone("wo3"), 3)
    }

    func testNumberedIsTheInverseOfMarks() {
        XCTAssertEqual(Pinyin.numbered("yín háng"), "yin2 hang2")
        XCTAssertEqual(Pinyin.numbered("nǚ"), "nü3")
        XCTAssertEqual(Pinyin.numbered("yín háng"), "yin2 hang2")
    }

    func testMarksInBrackets() {
        XCTAssertEqual(Pinyin.marksInBrackets("you 您[nin2]"), "you 您[nín]")
        XCTAssertEqual(Pinyin.marksInBrackets("see [ABC]"), "see [ABC]")
    }

    // MARK: Dictionary

    func testParsesBothScriptsAndFrequency() {
        XCTAssertEqual(dictionary.size, 14)
        XCTAssertEqual(dictionary.lookup("银行").count, 2)
        XCTAssertEqual(dictionary.lookup("銀行").count, 2)
        XCTAssertEqual(dictionary.frequency("银行"), 7684)
        XCTAssertEqual(dictionary.lookup("银行").first?.definitions, ["bank", "CL:家[jia1],個|个[ge4]"])
    }

    func testPlainCedictLinesLoadWithoutFrequency() {
        let plain = ZhDictionary.parse("銀行 银行 [yin2 hang2] /bank/\n")
        XCTAssertEqual(plain.frequency("银行"), 0)
        XCTAssertEqual(plain.lookup("银行").count, 1)
        XCTAssertEqual(plain.lookup("银行").first?.definitions, ["bank"])
    }

    // MARK: Segmentation

    func testSegmentsIntoTheLikeliestWords() {
        let text = "我昨天去银行。"
        let characters = Array(text)
        let words = breakdown.segment(text).map { String(characters[$0.start..<$0.end]) }
        XCTAssertEqual(words, ["我", "昨天", "去", "银行"])
    }

    func testUnknownCharactersBecomeTheirOwnWords() {
        let text = "我嗯去"
        let characters = Array(text)
        XCTAssertEqual(breakdown.segment(text).map { String(characters[$0.start..<$0.end]) }, ["我", "嗯", "去"])
        let card = breakdown.analyse(text).cards[1]
        XCTAssertTrue(card.senses.isEmpty)
        XCTAssertNil(card.pinyin)
    }

    func testLatinWordsAndPunctuationAreSkipped() {
        let text = "我去 iPhone 银行!"
        let characters = Array(text)
        let words = breakdown.segment(text).map { String(characters[$0.start..<$0.end]) }
        XCTAssertEqual(words, ["我", "去", "银行"])
    }

    func testTheAppleSegmenterCutsTheSameWords() {
        let dictionary = ZhDictionary.parse([
            "8000\t我 我 [wo3] /I; me; my/",
            "2900\t昨天 昨天 [zuo2 tian1] /yesterday/",
            "9000\t去 去 [qu4] /to go/",
            "7684\t銀行 银行 [yin2 hang2] /bank/",
        ].joined(separator: "\n"))
        let breakdown = WordBreakdown(dictionary: dictionary, segmenter: ChineseSegmenter())
        let text = "我昨天去银行"
        let characters = Array(text)
        let words = breakdown.segment(text).map { String(characters[$0.start..<$0.end]) }
        XCTAssertEqual(words, ["我", "昨天", "去", "银行"])
    }

    // MARK: Senses and readings

    func testScoresSumToOneAndCommonMeaningsComeFirst() {
        let senses = breakdown.senses("银行")
        XCTAssertEqual(senses.reduce(0) { $0 + $1.score }, 1, accuracy: 1e-9)
        XCTAssertEqual(senses.first?.gloss, "bank")
        XCTAssertEqual(senses.first?.pinyin, "yín háng")
        // Classifier notes aren't meanings; the place name is unlikely.
        XCTAssertFalse(senses.contains { $0.gloss.hasPrefix("CL:") })
        XCTAssertTrue(senses.last?.gloss.contains("place name") ?? false)
    }

    func testTheTranslationPicksTheMeaningAndTheReading() {
        // On its own, 行 most likely reads xíng ("to walk")…
        let alone = breakdown.analyse("行").cards.first!
        XCTAssertEqual(alone.pinyin, "xíng")
        // …but next to "a row of" in the translation, the háng meaning wins.
        let inContext = breakdown.analyse("行", translation: "a row of trees").cards.first!
        XCTAssertEqual(inContext.pinyin, "háng")
        XCTAssertTrue(inContext.best?.matched ?? false)
        XCTAssertEqual(inContext.readings.count, 2)
    }

    func testTheUsualReadingBreaksTies() {
        // liǎo has more meanings, so without a hint it would win…
        XCTAssertEqual(breakdown.analyse("了").cards.first?.pinyin, "liǎo")
        // …but ICU's usual reading for 了 is le, the particle.
        let withHint = WordBreakdown(dictionary: dictionary, usualReading: ChineseSegmenter.usualReading)
        XCTAssertEqual(withHint.analyse("了").cards.first?.pinyin, "le")
    }

    func testLinksTranslationWordsToCards() {
        let result = breakdown.analyse("我昨天去银行", translation: "Yesterday I went to the bank.")
        var byWord: [String: String?] = [:]
        for word in result.translationWords {
            byWord[word.text] = word.card.map { result.cards[$0].text }
        }
        XCTAssertEqual(byWord["Yesterday"] ?? nil, "昨天")
        XCTAssertEqual(byWord["I"] ?? nil, "我")
        XCTAssertEqual(byWord["went"] ?? nil, "去")
        XCTAssertEqual(byWord["bank"] ?? nil, "银行")
        XCTAssertNil(byWord["the"] ?? nil)
        XCTAssertEqual(result.cardAt(4), 3) // 银 → 银行
        XCTAssertEqual(result.translationWordAt(result.translationWords[1].start), 1)
    }

    func testMultiCharacterWordsListTheirCharacters() {
        let card = breakdown.analyse("银行").cards.first!
        XCTAssertEqual(card.characters.map(\.char), ["银", "行"])
        XCTAssertEqual(card.characters.first?.pinyin, "yín")
        XCTAssertEqual(card.characters.first?.gloss, "silver")
    }

    // MARK: English → Chinese

    func testEnglishToChinese() {
        let candidates = breakdown.englishCandidates("banks")
        XCTAssertEqual(candidates.first?.word, "银行")
        XCTAssertEqual(candidates.reduce(0) { $0 + $1.score }, 1, accuracy: 1e-9)
        XCTAssertTrue(breakdown.englishCandidates("the").isEmpty)
        // "money" is in both 钱 and 银; 钱 is the commoner word and says just that.
        XCTAssertEqual(breakdown.englishCandidates("money").first?.word, "钱")
    }

    func testStemming() {
        XCTAssertEqual(WordBreakdown.stem("bank"), WordBreakdown.stem("banks"))
        XCTAssertEqual(WordBreakdown.stem("go"), WordBreakdown.stem("went"))
        XCTAssertEqual(WordBreakdown.stem("make"), WordBreakdown.stem("made"))
        XCTAssertEqual(WordBreakdown.stem("run"), WordBreakdown.stem("running"))
        XCTAssertEqual(WordBreakdown.stem("city"), WordBreakdown.stem("cities"))
    }

    // MARK: Requests

    func testRequestForAConversationTurn() {
        let spokenChinese = Turn(id: 1, session: 0, speaker: nil, language: "zh", text: "我去银行",
                                 target: "en", translation: "I'm going to the bank", start: 0, end: 0)
        let tapChinese = BreakdownRequest.forTurn(spokenChinese, tappedTranslation: false, offset: 2)!
        XCTAssertEqual(tapChinese.chinese, "我去银行")
        XCTAssertEqual(tapChinese.translation, "I'm going to the bank")
        XCTAssertTrue(tapChinese.translationIsEnglish)
        XCTAssertEqual(tapChinese.focus, .chinese(2))
        XCTAssertEqual(BreakdownRequest.forTurn(spokenChinese, tappedTranslation: true, offset: 5)!.focus, .translation(5))

        // English spoken, Chinese translation: the Chinese side is the translation.
        let spokenEnglish = Turn(id: 2, session: 0, speaker: nil, language: "en", text: "the bank",
                                 target: "zh", translation: "银行", start: 0, end: 0)
        let request = BreakdownRequest.forTurn(spokenEnglish, tappedTranslation: true, offset: 1)!
        XCTAssertEqual(request.chinese, "银行")
        XCTAssertEqual(request.translation, "the bank")
        XCTAssertEqual(request.focus, .chinese(1))

        // No Chinese on either side, or not translated yet.
        XCTAssertNil(BreakdownRequest.forTurn(
            Turn(id: 3, session: 0, speaker: nil, language: "en", text: "hi", target: "es", translation: "hola", start: 0, end: 0),
            tappedTranslation: false, offset: 0))
        XCTAssertNil(BreakdownRequest.forTurn(
            Turn(id: 4, session: 0, speaker: nil, language: "en", text: "hi", target: "zh", start: 0, end: 0),
            tappedTranslation: false, offset: 0))
    }
}

/// The real bundled dictionary: it must ship, unzip and parse, and answer a
/// breakdown for a sentence. Guards against a build that silently drops the
/// resource (Android #25 shipped an APK without it).
final class BundledChineseDictionaryTests: XCTestCase {
    func testTheBundledDictionaryLoadsAndBreaksDownASentence() throws {
        let bundle = Bundle(for: BundledChineseDictionaryTests.self)
        let dictionary = try XCTUnwrap(BundledChineseDictionary.load(bundle: bundle),
                                       "zh_dict.dat is missing from this build")
        XCTAssertGreaterThan(dictionary.size, 100_000)

        let breakdown = WordBreakdown(dictionary: dictionary,
                                      usualReading: ChineseSegmenter.usualReading,
                                      segmenter: ChineseSegmenter())
        let result = breakdown.analyse("我昨天去银行", translation: "Yesterday I went to the bank.")
        let bank = try XCTUnwrap(result.cards.first { $0.text == "银行" })
        XCTAssertEqual(bank.pinyin, "yín háng")
        XCTAssertEqual(bank.best?.gloss, "bank")
        XCTAssertTrue(bank.best?.matched ?? false)
        XCTAssertEqual(result.cards.first { $0.text == "昨天" }?.pinyin, "zuó tiān")
    }

    func testVeryCommonWordsAreKnown() throws {
        let dictionary = try XCTUnwrap(BundledChineseDictionary.load(bundle: Bundle(for: Self.self)))
        XCTAssertTrue(dictionary.lookup("你").contains { $0.definitions.contains { $0.contains("you") } })
        XCTAssertGreaterThan(dictionary.frequency("的"), 0)
    }
}
