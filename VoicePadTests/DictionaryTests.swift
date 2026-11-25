import XCTest

/// Ported from Android's `DictionaryTest`.
@MainActor
final class DictionaryTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: New words from an edit

    func testOffersOnlyWordsTheEditIntroducedInOrderOnce() {
        XCTAssertEqual(
            DictionaryWords.newWords(
                before: "I set up cooper net ease with pria today",
                after: "I set up Kubernetes with Priya today, Kubernetes with Priya."
            ),
            ["Kubernetes", "Priya"]
        )
    }

    func testACaseFixCountsAsANewWord() {
        XCTAssertEqual(DictionaryWords.newWords(before: "my iphone broke", after: "my iPhone broke"), ["iPhone"])
    }

    func testNumbersPunctuationAndPossessives() {
        XCTAssertEqual(DictionaryWords.newWords(before: "call at 5", after: "call Anthropic's at 10!"), ["Anthropic"])
    }

    func testWordsAlreadyInADictionaryAreNotOfferedAgain() {
        XCTAssertEqual(
            DictionaryWords.newWords(before: "and", after: "Grafana and Kubernetes", known: ["Kubernetes"]),
            ["Grafana"]
        )
    }

    func testNothingNewNothingOffered() {
        XCTAssertEqual(DictionaryWords.newWords(before: "Hello there, world", after: "hello world there"), ["hello"])
        XCTAssertTrue(DictionaryWords.newWords(before: "hello there world", after: "world. hello, there").isEmpty)
    }

    // MARK: Correcting transcripts

    private func fix(_ text: String, _ words: String...) -> String {
        VocabularyCorrector(entries: words).correct(text)
    }

    func testFixesCase() {
        XCTAssertEqual(fix("I bought an iphone today.", "iPhone"), "I bought an iPhone today.")
    }

    func testJoinsWordsTheModelSplit() {
        XCTAssertEqual(fix("Ask chat GPT about it", "ChatGPT"), "Ask ChatGPT about it")
        XCTAssertEqual(fix("Open voice-pad now", "VoicePad"), "Open VoicePad now")
    }

    func testMatchesPhrasesAndPrefersTheLongerOne() {
        XCTAssertEqual(fix("I use claude code daily", "Claude", "Claude Code"), "I use Claude Code daily")
        XCTAssertEqual(fix("claude said hi", "Claude", "Claude Code"), "Claude said hi")
    }

    func testFixesANearMissOnALongWord() {
        XCTAssertEqual(fix("deploy to kubernetis now", "Kubernetes"), "deploy to Kubernetes now")
        XCTAssertEqual(fix("Dr Okonqwo called", "Okonkwo"), "Dr Okonkwo called")
    }

    func testLeavesOrdinaryShortWordsAlone() {
        XCTAssertEqual(fix("he parked the car", "Parker"), "he parked the car")
        XCTAssertEqual(fix("Sarah and Sara", "Sarah"), "Sarah and Sara")
    }

    func testKeepsThePossessiveAndPunctuation() {
        XCTAssertEqual(fix("That's priya's idea, right?", "Priya"), "That's Priya's idea, right?")
    }

    func testALowerCaseEntryFixesSpellingButNotCase() {
        XCTAssertEqual(fix("Kubectl apply", "kubectl"), "Kubectl apply")
        XCTAssertEqual(fix("run kube ctl apply", "kubectl"), "run kubectl apply")
    }

    func testDoesNotJoinAcrossPunctuation() {
        XCTAssertEqual(fix("chat. GPT", "ChatGPT"), "chat. GPT")
    }

    func testEmptyDictionaryChangesNothing() {
        XCTAssertEqual(fix("anything at all"), "anything at all")
    }

    // MARK: Store

    func testStoreKeepsDictionariesMovesWordsAndOnlyUsesEnabledOnes() throws {
        let store = DictionaryStore(baseURL: directory)
        let mine = try XCTUnwrap(store.dictionaries.first)
        let work = store.create("Work")
        store.addWords(mine.id, ["Priya", "Kubernetes", "Priya"])
        store.moveWords(["Kubernetes"], from: mine.id, to: work.id)
        store.setEnabled(work.id, false)

        let reloaded = DictionaryStore(baseURL: directory)
        XCTAssertEqual(reloaded.dictionaries.first { $0.id == mine.id }?.words, ["Priya"])
        XCTAssertEqual(reloaded.dictionaries.first { $0.id == work.id }?.words, ["Kubernetes"])
        XCTAssertEqual(reloaded.activeWords(), ["Priya"])
        XCTAssertEqual(reloaded.allWords(), ["Priya", "Kubernetes"])
        XCTAssertEqual(reloaded.lastUsedId, mine.id)
    }

    func testThereIsAlwaysOneDictionary() throws {
        let store = DictionaryStore(baseURL: directory)
        store.delete(try XCTUnwrap(store.dictionaries.first).id)
        XCTAssertEqual(store.dictionaries.count, 1)
    }

    func testEditingAWordKeepsItsPlace() throws {
        let store = DictionaryStore(baseURL: directory)
        let id = try XCTUnwrap(store.dictionaries.first).id
        store.addWords(id, ["alpha", "Betta", "gamma"])
        store.replaceWord(id, old: "Betta", new: " Beta ")
        XCTAssertEqual(store.dictionaries.first?.words, ["alpha", "Beta", "gamma"])
    }
}
