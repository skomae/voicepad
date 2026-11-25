import Foundation

/// Splits Chinese text into dictionary words and, for each, ranks its possible
/// meanings and readings, marking the ones its English translation used.
/// Ported from Android's `WordBreakdown.kt` (Android #22/#25).
///
/// The scores are estimates from the dictionary, not a translation model's
/// probabilities: each entry is weighted by how well-attested it is (proper
/// nouns, surnames and "variant of" cross-references count for little), each
/// meaning by its place in the entry, and meanings that match a word of the
/// translation are boosted. They sum to 1 per word.
final class WordBreakdown {
    /// Cuts a run of Chinese characters into words, as [start, end) offsets
    /// into it. Android uses HanLP; iOS uses `NLTokenizer` (see `ChineseSegmenter`).
    protocol Segmenter {
        func segment(_ run: String) -> [(start: Int, end: Int)]
        /// Numbered pinyin for a word the dictionary doesn't know, if the
        /// segmenter has a table.
        func numberedPinyin(_ word: String) -> String?
    }

    struct Sense {
        let gloss: String
        /// Pinyin with tone marks.
        let pinyin: String
        /// Numbered pinyin, for tone colours.
        let numbered: String
        let score: Double
        /// A word of the translation matches this meaning.
        let matched: Bool
    }

    struct Reading {
        let pinyin: String
        let numbered: String
        let score: Double
    }

    /// One character of a longer word, with its own most likely reading and meaning.
    struct CharInfo {
        let char: String
        let pinyin: String?
        let numbered: String?
        let gloss: String?
    }

    /// One Chinese word, at `start` until `end` in the text.
    struct Card {
        let text: String
        let start: Int
        let end: Int
        /// Best first; empty when the dictionary doesn't know the word.
        let senses: [Sense]
        let readings: [Reading]
        /// The characters of a multi-character word; empty for a single character.
        let characters: [CharInfo]

        var best: Sense? { senses.first { $0.matched } ?? senses.first }
        /// The reading of the best sense: the meaning in context decides the
        /// reading (行 háng vs xíng).
        var pinyin: String? { best?.pinyin ?? readings.first?.pinyin }
        var numbered: String? { best?.numbered ?? readings.first?.numbered }
    }

    /// A word of the translation and the card it was matched to, if any.
    struct TranslationWord {
        let text: String
        let start: Int
        let end: Int
        let card: Int?
    }

    struct Result {
        let chinese: String
        let cards: [Card]
        let translationWords: [TranslationWord]

        /// The card covering `offset` in the Chinese text (or the nearest one before it).
        func cardAt(_ offset: Int) -> Int? {
            if let i = cards.firstIndex(where: { offset >= $0.start && offset < $0.end }) { return i }
            if let i = cards.lastIndex(where: { $0.start <= offset }) { return i }
            return cards.indices.first
        }

        /// The translation word covering `offset` in the translation (or the nearest one before it).
        func translationWordAt(_ offset: Int) -> Int? {
            if let i = translationWords.firstIndex(where: { offset >= $0.start && offset < $0.end }) { return i }
            if let i = translationWords.lastIndex(where: { $0.start <= offset }) { return i }
            return translationWords.indices.first
        }
    }

    /// A Chinese word for an English one, from the reverse lookup.
    struct Candidate {
        let word: String
        let pinyin: String
        let numbered: String
        let gloss: String
        let score: Double
    }

    private let dictionary: ZhDictionary
    /// The usual reading of a single character, with tone marks ("le" for 了),
    /// if known: on the phone, `CFStringTransform`'s Han–Latin, which picks
    /// each character's most common reading. Entries with that reading are
    /// weighted up, so particles read as particles (了 le, not liǎo).
    private let usualReading: ((String) -> String?)?
    /// How runs of Chinese characters are cut into words. nil (and any
    /// segmenter that returns nothing usable) falls back to the frequency
    /// search, which only uses the bundled dictionary.
    private let segmenter: Segmenter?

    init(dictionary: ZhDictionary, usualReading: ((String) -> String?)? = nil, segmenter: Segmenter? = nil) {
        self.dictionary = dictionary
        self.usualReading = usualReading
        self.segmenter = segmenter
    }

    /// Breaks `chinese` into word cards. With an English `translation`, links
    /// its words to the cards whose meanings they match.
    func analyse(_ chinese: String, translation: String? = nil) -> Result {
        let words = translation.map(WordBreakdown.englishWords) ?? []
        let stems = Set(words.map { WordBreakdown.stem($0.text) })
        let cards = segment(chinese).map { span in
            card(String(Array(chinese)[span.start..<span.end]), start: span.start, end: span.end, stems: stems)
        }
        // Each English word goes to the card whose matching meaning scores best.
        let linked = words.map { word -> TranslationWord in
            let stemmed = WordBreakdown.stem(word.text)
            let best = cards.indices.compactMap { i -> (Int, Double)? in
                let scores = cards[i].senses
                    .filter { $0.matched && WordBreakdown.contentStems($0.gloss).contains(stemmed) }
                    .map(\.score)
                return scores.max().map { (i, $0) }
            }.max { $0.1 < $1.1 }?.0
            return TranslationWord(text: word.text, start: word.start, end: word.end, card: best)
        }
        return Result(chinese: chinese, cards: cards, translationWords: linked)
    }

    /// Splits `text` into words: runs of Chinese characters are cut where the
    /// product of the words' frequencies is highest (as jieba does); anything
    /// else (Latin letters, numbers, punctuation) is left out. Returns
    /// [start, end) ranges of the Chinese words, in characters.
    func segment(_ text: String) -> [(start: Int, end: Int)] {
        let characters = Array(text)
        var out: [(start: Int, end: Int)] = []
        var i = 0
        while i < characters.count {
            guard WordBreakdown.isHan(characters[i]) else { i += 1; continue }
            var j = i
            while j < characters.count, WordBreakdown.isHan(characters[j]) { j += 1 }
            out += segmentRun(characters, from: i, to: j)
            i = j
        }
        return out
    }

    private func segmentRun(_ characters: [Character], from: Int, to: Int) -> [(start: Int, end: Int)] {
        let run = String(characters[from..<to])
        // NLTokenizer knows words the dictionary doesn't, and cuts common words
        // apart where the frequency search would glue them into one unlikely
        // long one.
        if let segmenter {
            let words = segmenter.segment(run)
            if !words.isEmpty, words.allSatisfy({ $0.start >= 0 && $0.start < $0.end && $0.end <= run.count }) {
                return words.map { (start: from + $0.start, end: from + $0.end) }
            }
        }
        return frequencyRun(characters, from: from, to: to)
    }

    private func frequencyRun(_ characters: [Character], from: Int, to: Int) -> [(start: Int, end: Int)] {
        let n = to - from
        let logTotal = log(Double(dictionary.totalFrequency))
        // Unknown single characters still have to be words of their own.
        let unknown = log(0.5) - logTotal
        var best = [Double](repeating: -Double.infinity, count: n + 1)
        var next = [Int](repeating: 0, count: n + 1)
        best[n] = 0
        let maxLen = min(dictionary.maxWordLength, WordBreakdown.maxWord)
        if n > 0 {
            for i in stride(from: n - 1, through: 0, by: -1) {
                for length in 1...min(maxLen, n - i) {
                    let word = String(characters[(from + i)..<(from + i + length)])
                    let base: Double
                    if dictionary.contains(word) {
                        base = log(Double(dictionary.frequency(word)) + 1) - logTotal
                    } else if length == 1 {
                        base = unknown
                    } else {
                        continue
                    }
                    let score = base + best[i + length]
                    if score > best[i] { best[i] = score; next[i] = i + length }
                }
            }
        }
        var out: [(start: Int, end: Int)] = []
        var i = 0
        while i < n, next[i] > i {
            out.append((start: from + i, end: from + next[i]))
            i = next[i]
        }
        return out
    }

    private func card(_ word: String, start: Int, end: Int, stems: Set<String>) -> Card {
        let found = senses(word, translationStems: stems)
        var readings: [Reading] = []
        var order: [String] = []
        var totals: [String: (Reading, Double)] = [:]
        for sense in found {
            let key = sense.numbered.lowercased()
            if let (existing, score) = totals[key] {
                totals[key] = (existing, score + sense.score)
            } else {
                totals[key] = (Reading(pinyin: sense.pinyin, numbered: sense.numbered, score: 0), sense.score)
                order.append(key)
            }
        }
        readings = order.compactMap { key in
            totals[key].map { Reading(pinyin: $0.0.pinyin, numbered: $0.0.numbered, score: $0.1) }
        }.sorted { $0.score > $1.score }
        // A word the dictionary doesn't know still has a reading: the segmenter's
        // pinyin table covers the hanzi CC-CEDICT leaves out.
        let fallback = readings.isEmpty ? segmenter?.numberedPinyin(word) : nil
        var characters: [CharInfo] = []
        if word.count > 1 {
            characters = word.map { character in
                let text = String(character)
                if let top = senses(text, translationStems: []).first {
                    return CharInfo(char: text, pinyin: top.pinyin, numbered: top.numbered, gloss: top.gloss)
                }
                if let numbered = segmenter?.numberedPinyin(text) {
                    return CharInfo(char: text, pinyin: Pinyin.marks(numbered), numbered: numbered, gloss: nil)
                }
                return CharInfo(char: text, pinyin: nil, numbered: nil, gloss: nil)
            }
        }
        if readings.isEmpty, let fallback {
            readings = [Reading(pinyin: Pinyin.marks(fallback), numbered: fallback, score: 1)]
        }
        return Card(text: word, start: start, end: end, senses: found, readings: readings, characters: characters)
    }

    /// `word`'s meanings, best first, with scores that sum to 1.
    func senses(_ word: String, translationStems: Set<String> = []) -> [Sense] {
        var raw: [Sense] = []
        let usual = word.count == 1 ? usualReading?(word)?.trimmingCharacters(in: .whitespaces).lowercased() : nil
        for entry in dictionary.lookup(word) {
            let definitions = entry.definitions.filter { !$0.hasPrefix("CL:") }
            if definitions.isEmpty { continue }
            // Entries with more meanings tend to be the common reading (行 xíng
            // over háng); proper nouns rarely are what's meant.
            let usualBoost = (usual != nil && Pinyin.marks(entry.pinyin).lowercased() == usual)
                ? WordBreakdown.usualReadingBoost : 1
            let entryWeight = Double(definitions.count).squareRoot() * (entry.isProperNoun ? 0.15 : 1) * usualBoost
            let positional = definitions.indices.reduce(0.0) { $0 + 1 / Double($1 + 1) }
            for (i, definition) in definitions.enumerated() {
                let matched = !translationStems.isEmpty &&
                    !WordBreakdown.contentStems(definition).isDisjoint(with: translationStems)
                let score = entryWeight * (1 / Double(i + 1)) / positional
                    * WordBreakdown.definitionWeight(definition)
                    * (matched ? WordBreakdown.matchBoost : 1)
                raw.append(Sense(gloss: definition, pinyin: Pinyin.marks(entry.pinyin), numbered: entry.pinyin,
                                 score: score, matched: matched))
            }
        }
        let total = raw.reduce(0) { $0 + $1.score }
        guard total > 0 else { return [] }
        return raw.map { Sense(gloss: $0.gloss, pinyin: $0.pinyin, numbered: $0.numbered,
                               score: $0.score / total, matched: $0.matched) }
            .sorted { $0.score > $1.score }
    }

    /// Chinese words meaning `english`, most likely first (scores sum to 1).
    /// Ranked by word frequency, how early the meaning comes in its entry, and
    /// whether the meaning is exactly that word.
    func englishCandidates(_ english: String, limit: Int = 8) -> [Candidate] {
        let target = WordBreakdown.stem(english.lowercased().trimmingCharacters(in: .whitespaces))
        if target.isEmpty || WordBreakdown.stopStems.contains(target) { return [] }
        var found: [Candidate] = []
        // Lines without the word's first letters can't match; skip them before parsing.
        let trimmed = english.trimmingCharacters(in: .whitespaces).lowercased()
        let needle = trimmed.hasPrefix(String(target.prefix(3))) ? String(target.prefix(3)) : ""
        dictionary.forEachEntryContaining(needle) { entry in
            if entry.isProperNoun { return }
            let definitions = entry.definitions.filter { !$0.hasPrefix("CL:") }
            guard let i = definitions.firstIndex(where: { WordBreakdown.contentStems($0).contains(target) }) else { return }
            let definition = definitions[i]
            let parts = definition.split(separator: ";").map {
                WordBreakdown.stem(WordBreakdown.cleanGloss(String($0)).trimmingCharacters(in: .whitespaces).lowercased())
            }
            // "money" says it outright; "silver; money" a little less; "money order" least.
            let exactness: Double = parts.count == 1 && parts[0] == target ? 4 : (parts.contains(target) ? 2.5 : 1)
            let score = log(Double(entry.frequency) + 2) / (Double(i + 1)).squareRoot()
                * exactness * WordBreakdown.definitionWeight(definition)
            found.append(Candidate(word: entry.simplified, pinyin: Pinyin.marks(entry.pinyin), numbered: entry.pinyin,
                                   gloss: definition, score: score))
        }
        var seen = Set<String>()
        let top = found.sorted { $0.score > $1.score }.filter { seen.insert($0.word).inserted }.prefix(limit)
        let total = top.reduce(0) { $0 + $1.score }
        guard total > 0 else { return [] }
        return top.map { Candidate(word: $0.word, pinyin: $0.pinyin, numbered: $0.numbered,
                                   gloss: $0.gloss, score: $0.score / total) }
    }

    // MARK: Pure helpers

    private static let maxWord = 8
    private static let matchBoost = 4.0
    private static let usualReadingBoost = 4.0

    /// CJK ideographs: Han, plus the common and compatibility extensions.
    static func isHan(_ character: Character) -> Bool {
        guard let scalar = character.unicodeScalars.first else { return false }
        switch scalar.value {
        case 0x4E00...0x9FFF, 0x3400...0x4DBF, 0xF900...0xFAFF, 0x20000...0x2FA1F, 0x30000...0x323AF:
            return true
        default:
            return false
        }
    }

    /// Cross-references and surnames are rarely the meaning a reader wants.
    static func definitionWeight(_ definition: String) -> Double {
        let prefixes: [(String, Double)] = [
            ("surname ", 0.15),
            ("variant of", 0.1), ("old variant of", 0.1), ("used in", 0.1), ("see ", 0.1),
            ("Japanese variant", 0.1), ("unofficial variant", 0.1),
            ("(onom.)", 0.3),
            ("abbr. for", 0.5), ("(coll.)", 0.5), ("(old)", 0.5), ("(archaic)", 0.5), ("(dialect)", 0.5),
        ]
        for (prefix, weight) in prefixes where definition.hasPrefix(prefix) { return weight }
        return 1
    }

    private static let wordPattern = try! NSRegularExpression(pattern: "[A-Za-z]+(?:'[A-Za-z]+)?")

    /// The words of an English sentence, with where they are (character offsets).
    static func englishWords(_ text: String) -> [TranslationWord] {
        let ns = text as NSString
        return wordPattern.matches(in: text, range: NSRange(location: 0, length: ns.length)).compactMap { match in
            let word = ns.substring(with: match.range)
            let start = text.distance(from: text.startIndex, to: Range(match.range, in: text)!.lowerBound)
            return TranslationWord(text: word, start: start, end: start + word.count, card: nil)
        }
    }

    /// Drops parenthesised notes ("(bound form)") and pinyin cross-references ("[hang2]").
    static func cleanGloss(_ gloss: String) -> String {
        gloss.replacingOccurrences(of: "\\([^)]*\\)", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\[[^]]*\\]", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "[^\\x00-\\x7F]+", with: " ", options: .regularExpression)
    }

    /// The meaningful words of a gloss, stemmed.
    static func contentStems(_ gloss: String) -> Set<String> {
        let cleaned = cleanGloss(gloss)
        let ns = cleaned as NSString
        let words = wordPattern.matches(in: cleaned, range: NSRange(location: 0, length: ns.length))
            .map { stem(ns.substring(with: $0.range)) }
        return Set(words.filter { !$0.isEmpty && !stopStems.contains($0) })
    }

    /// A crude English stemmer: enough to match "banks" with "bank" and "went" with "go".
    static func stem(_ word: String) -> String {
        var w = word.lowercased()
        if w.hasSuffix("'s") { w.removeLast(2) }
        if let irregular = irregularForms[w] { w = irregular }
        let characters = Array(w)
        let n = characters.count
        if n > 4, w.hasSuffix("ies") {
            w = String(w.dropLast(3)) + "y"
        } else if n > 4, ["ches", "shes", "sses", "xes"].contains(where: { w.hasSuffix($0) }) {
            w = String(w.dropLast(2))
        } else if n > 3, w.hasSuffix("s"), !w.hasSuffix("ss"), !w.hasSuffix("us"), !w.hasSuffix("is") {
            w = String(w.dropLast(1))
        } else if n > 5, w.hasSuffix("ing") {
            w = String(w.dropLast(3))
        } else if n > 4, w.hasSuffix("ed") {
            w = String(w.dropLast(2))
        }
        // "running" → "run", "stopped" → "stop".
        let trimmed = Array(w)
        if trimmed.count > 3, let last = trimmed.last, last == trimmed[trimmed.count - 2], !"lsz".contains(last) {
            w = String(w.dropLast(1))
        }
        if w.hasSuffix("e") { w = String(w.dropLast(1)) }
        return w
    }

    private static let irregularForms: [String: String] = [
        "went": "go", "gone": "go", "goes": "go", "was": "be", "were": "be", "is": "be", "are": "be", "am": "be",
        "did": "do", "does": "do", "done": "do", "had": "have", "has": "have", "ate": "eat", "eaten": "eat",
        "saw": "see", "seen": "see", "said": "say", "says": "say", "made": "make", "took": "take", "taken": "take",
        "came": "come", "got": "get", "gave": "give", "given": "give", "knew": "know", "known": "know",
        "thought": "think", "told": "tell", "bought": "buy", "brought": "bring", "left": "leave", "felt": "feel",
        "found": "find", "heard": "hear", "met": "meet", "paid": "pay", "sat": "sit", "slept": "sleep",
        "spoke": "speak", "spoken": "speak", "wrote": "write", "written": "write", "drank": "drink", "ran": "run",
        "children": "child", "men": "man", "women": "woman", "people": "person", "me": "i", "my": "i", "mine": "i",
        "us": "we", "our": "we", "him": "he", "his": "he", "her": "she", "them": "they", "their": "they",
        "better": "good", "best": "good", "worse": "bad", "worst": "bad",
    ]

    private static let stopwords: Set<String> = [
        "a", "an", "the", "to", "of", "and", "or", "in", "on", "at", "for", "with", "by", "from", "as", "be",
        "it", "that", "this", "one", "one'", "sb", "sth", "etc", "so", "not", "no", "than", "into", "up", "out", "about",
        "also", "used", "form", "bound", "literary", "fig", "lit", "abbr", "pr", "tw", "cl", "esp", "classifier", "particle",
        "do", "have", "very", "some", "any", "kind", "sort", "way",
    ]

    /// `stopwords` as `stem` leaves them, since glosses and translations are compared stemmed.
    private static let stopStems: Set<String> = Set(stopwords.map(stem))
}
