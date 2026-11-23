import XCTest

final class TranslationTests: XCTestCase {
    func testPinyinHasToneMarksAndFixesWordsICUReadsOneCharacterAtATime() {
        XCTAssertEqual(Romanizer.romanize(language: "zh", text: "你好，我们明天去银行开会。"), "nǐ hǎo, wǒ men míng tiān qù yín háng kāi huì.")
        XCTAssertEqual(Romanizer.romanize(language: "zh", text: "我觉得这个计划很好"), "wǒ jué de zhè ge jì huà hěn hǎo")
        XCTAssertEqual(Romanizer.romanize(language: "zh", text: "我喜欢音乐"), "wǒ xǐ huan yīn yuè")
    }

    func testPinyinKeepsNumbersAndLatinWords() {
        XCTAssertEqual(Romanizer.romanize(language: "zh", text: "开放时间早上9点"), "kāi fàng shí jiān zǎo shang 9 diǎn")
        XCTAssertEqual(Romanizer.romanize(language: "zh", text: "我用iPhone"), "wǒ yòng iPhone")
    }

    func testOtherScriptsAreTransliteratedLatinScriptLanguagesAndCantoneseAreNot() {
        XCTAssertEqual(Romanizer.romanize(language: "ko", text: "안녕하세요"), "annyeonghaseyo")
        XCTAssertEqual(Romanizer.romanize(language: "ru", text: "привет"), "privet")
        XCTAssertNil(Romanizer.romanize(language: "fr", text: "Bonjour à tous"))
        XCTAssertNil(Romanizer.romanize(language: "es", text: "¿Dónde está la biblioteca?"))
        XCTAssertNil(Romanizer.romanize(language: "yue", text: "你哋好"))
        XCTAssertNil(Romanizer.romanize(language: "ja", text: "こんにちは"))
    }

    func testLanguageTagsFromSpeechModelsAreNormalised() {
        XCTAssertEqual(LanguageTags.fromModel("<|zh|>"), "zh")
        XCTAssertEqual(LanguageTags.fromModel(" fr "), "fr")
        XCTAssertEqual(LanguageTags.fromModel("<|yue|>"), "yue")
        XCTAssertNil(LanguageTags.fromModel(""))
        XCTAssertNil(LanguageTags.fromModel("<|nospeech|>"))
        XCTAssertNil(LanguageTags.fromModel("und"))
    }

    func testLanguageDecisionPrefersTheSpeechModelAndTrustsChineseCharacters() {
        XCTAssertEqual(LanguageTags.decide(modelLanguage: "fr", textGuess: "en", text: "Bonjour"), "fr")
        XCTAssertEqual(LanguageTags.decide(modelLanguage: nil, textGuess: "es", text: "Hola amigos"), "es")
        // Text detectors often call short Chinese "ja".
        XCTAssertEqual(LanguageTags.decide(modelLanguage: nil, textGuess: "ja", text: "你好"), "zh")
        XCTAssertEqual(LanguageTags.decide(modelLanguage: nil, textGuess: "ja", text: "こんにちは世界"), "ja")
        XCTAssertEqual(LanguageTags.decide(modelLanguage: "<|yue|>", textGuess: "zh", text: "你哋好"), "yue")
        // NaturalLanguage reports Chinese as "zh-Hans" / "zh-Hant".
        XCTAssertEqual(LanguageTags.decide(modelLanguage: nil, textGuess: "zh-Hant", text: "ni hao"), "zh")
        XCTAssertNil(LanguageTags.decide(modelLanguage: nil, textGuess: "und", text: "..."))
    }

    func testDisplayNames() {
        XCTAssertEqual(LanguageTags.displayName("zh"), "Mandarin Chinese")
        XCTAssertEqual(LanguageTags.displayName("yue"), "Cantonese")
        XCTAssertEqual(LanguageTags.displayName("fr"), "French")
        XCTAssertEqual(LanguageTags.displayName("es"), "Spanish")
    }

    private let revisionID = UUID()

    private func note(_ text: String, _ translation: NoteTranslation? = nil) -> Note {
        var n = Note()
        n.revisions = [Revision(id: revisionID, text: text, source: .transcription)]
        n.translation = translation
        return n
    }

    func testANoteNeedsTranslatingUntilItsCurrentTextHasAResult() {
        XCTAssertTrue(note("你好").needsTranslation(maxAttempts: 3))
        XCTAssertFalse(note("").needsTranslation(maxAttempts: 3))
        XCTAssertFalse(note("你好", NoteTranslation(revisionID: revisionID, status: .done, language: "zh", english: "Hello")).needsTranslation(maxAttempts: 3))
        XCTAssertFalse(note("hi", NoteTranslation(revisionID: revisionID, status: .notNeeded, language: "en")).needsTranslation(maxAttempts: 3))
        // Interrupted mid-way (app killed): try again.
        XCTAssertTrue(note("你好", NoteTranslation(revisionID: revisionID, status: .pending, attempts: 1)).needsTranslation(maxAttempts: 3))
        // Failed: retry until the limit.
        XCTAssertTrue(note("你好", NoteTranslation(revisionID: revisionID, status: .failed, attempts: 2)).needsTranslation(maxAttempts: 3))
        XCTAssertFalse(note("你好", NoteTranslation(revisionID: revisionID, status: .failed, attempts: 3)).needsTranslation(maxAttempts: 3))
    }

    func testEditingTheTextMakesTheOldTranslationStale() {
        let old = NoteTranslation(revisionID: revisionID, status: .done, language: "zh", english: "Hello")
        let edited = note("你好", old).withRevision("你好吗", source: .edit, engine: nil)
        XCTAssertNil(edited.currentTranslation)
        XCTAssertTrue(edited.needsTranslation(maxAttempts: 3))
    }

    func testSearchFindsNotesByTheirEnglishTranslationAndByTonelessPinyin() {
        let n = note("我们明天去银行", NoteTranslation(
            revisionID: revisionID, status: .done, language: "zh",
            english: "We are going to the bank tomorrow", romanization: "wǒ men míng tiān qù yín háng"
        ))
        XCTAssertEqual(NoteSearch.search([n], query: "bank").count, 1)
        XCTAssertEqual(NoteSearch.search([n], query: "\"yin hang\"").count, 1)
        XCTAssertEqual(NoteSearch.search([n], query: "银行").count, 1)
    }

    func testTranslationSurvivesARoundTripThroughJSON() throws {
        let n = note("你好", NoteTranslation(revisionID: revisionID, status: .done, language: "zh", english: "Hello", romanization: "nǐ hǎo"))
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode(Note.self, from: encoder.encode(n))
        XCTAssertEqual(decoded.currentTranslation?.english, "Hello")
        XCTAssertEqual(decoded.currentTranslation?.romanization, "nǐ hǎo")
    }
}
