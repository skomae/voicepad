import Foundation

/// A named list of the user's own words (names, jargon, product names) that
/// the speech models tend to get wrong. Only enabled dictionaries are used.
struct UserDictionary: Identifiable, Codable, Equatable, Hashable {
    var id: String = UUID().uuidString
    var name: String
    var enabled: Bool = true
    /// In the user's spelling; a phrase ("Claude Code") counts as one entry.
    var words: [String] = []
}

/// Pure word handling for dictionaries, kept free of UI so it can be unit-tested.
enum DictionaryWords {
    private static let wordPattern = try! NSRegularExpression(pattern: "[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
    static let tokenPattern = try! NSRegularExpression(pattern: "[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*")

    /// The words of `text` as written, without surrounding punctuation.
    static func words(in text: String) -> [String] {
        matches(of: wordPattern, in: text).compactMap { raw in
            var word = Substring(raw)
            while let last = word.last, last == "'" || last == "’" || last == "-" { word = word.dropLast() }
            return word.isEmpty ? nil : String(word)
        }
    }

    /// An entry cleaned up for saving: trimmed, inner spaces collapsed; nil if
    /// it has no letters or digits.
    static func clean(_ word: String) -> String? {
        let collapsed = word.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        guard collapsed.contains(where: { $0.isLetter || $0.isNumber }) else { return nil }
        return collapsed
    }

    /// Words in `after` that weren't in `before`, in the order they first
    /// appear, each once. Case matters ("iphone" → "iPhone" is a new word),
    /// plain numbers don't count, and a possessive "'s" is dropped so
    /// "Kubernetes's" offers "Kubernetes". Words already in `known` (any
    /// dictionary, any case-exact match) are left out.
    static func newWords(before: String, after: String, known: Collection<String> = []) -> [String] {
        let old = Set(words(in: before).map(stripPossessive))
        let skip = Set(known)
        var seen = Set<String>()
        var result: [String] = []
        for word in words(in: after).map(stripPossessive) {
            guard !old.contains(word), !skip.contains(word), word.contains(where: \.isLetter) else { continue }
            if seen.insert(word).inserted { result.append(word) }
        }
        return result
    }

    private static func stripPossessive(_ word: String) -> String {
        for suffix in ["'s", "’s"] where word.hasSuffix(suffix) && word.count > suffix.count {
            return String(word.dropLast(suffix.count))
        }
        return word
    }

    /// Letters and digits only, lower case: how an entry and heard text are compared.
    static func key(_ text: String) -> String {
        String(text.filter { $0.isLetter || $0.isNumber }).lowercased()
    }

    static func editDistance(_ a: String, _ b: String, limit: Int) -> Int {
        let a = Array(a), b = Array(b)
        if abs(a.count - b.count) > limit { return limit + 1 }
        var previous = Array(0...b.count)
        var current = [Int](repeating: 0, count: b.count + 1)
        for i in 1...max(a.count, 1) where !a.isEmpty {
            current[0] = i
            var rowMin = current[0]
            for j in 1...max(b.count, 1) where !b.isEmpty {
                let cost = a[i - 1] == b[j - 1] ? 0 : 1
                current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                rowMin = min(rowMin, current[j])
            }
            if rowMin > limit { return limit + 1 }
            swap(&previous, &current)
        }
        return b.isEmpty ? a.count : previous[b.count]
    }

    static func ranges(of regex: NSRegularExpression, in text: String) -> [Range<String.Index>] {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return regex.matches(in: text, range: range).compactMap { Range($0.range, in: text) }
    }

    static func matches(of regex: NSRegularExpression, in text: String) -> [String] {
        ranges(of: regex, in: text).map { String(text[$0]) }
    }
}

/// Fixes how the speech model spelled the user's dictionary words, after
/// recognition. None of the on-device models takes a vocabulary list (Whisper
/// here has no prompt, and the Parakeet/SenseVoice models ship no BPE
/// vocabulary for hotwords), so this is where the dictionary improves their
/// output, and the corrected text is what gets translated.
///
/// A run of heard words is replaced by an entry when:
/// - their letters and digits match ignoring case, spaces and hyphens
///   ("chat gpt", "Chat-GPT" → "ChatGPT", "voice pad" → "VoicePad"); or
/// - the same number of words is one letter off (two for 10+ letters), for
///   entries of 7+ letters that start with the same letter ("Kubernetis" →
///   "Kubernetes"). Shorter words are too easily confused with ordinary ones.
/// A possessive "'s" on the last word is kept. An all-lower-case entry only
/// fixes spelling, never the case the model chose.
final class VocabularyCorrector {
    private struct Entry {
        let text: String
        let key: String
        let wordCount: Int
        var fixesCase: Bool { text.contains(where: \.isUppercase) }
    }

    private let entries: [Entry]
    private let maxWindow: Int

    var isEmpty: Bool { entries.isEmpty }

    init(entries words: [String]) {
        var seen = Set<String>()
        self.entries = words
            .compactMap(DictionaryWords.clean)
            .filter { seen.insert($0).inserted }
            .map { Entry(text: $0, key: DictionaryWords.key($0),
                         wordCount: max(1, DictionaryWords.words(in: $0).count)) }
            .filter { !$0.key.isEmpty }
            // Longer phrases first, so "Claude Code" wins over "Claude".
            .sorted { a, b in
                a.wordCount != b.wordCount ? a.wordCount > b.wordCount : a.key.count > b.key.count
            }
        maxWindow = (self.entries.map(\.wordCount).max() ?? 0) + 2
    }

    func correct(_ text: String) -> String {
        guard !entries.isEmpty, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return text }
        let source = text as NSString
        let tokens: [(start: Int, end: Int)] = DictionaryWords.ranges(of: DictionaryWords.tokenPattern, in: text)
            .compactMap { range in
                guard let lower = range.lowerBound.samePosition(in: text.utf16),
                      let upper = range.upperBound.samePosition(in: text.utf16) else { return nil }
                let start = text.utf16.distance(from: text.utf16.startIndex, to: lower)
                return (start: start, end: start + text.utf16.distance(from: lower, to: upper))
            }
        guard !tokens.isEmpty else { return text }

        var out = ""
        var copiedUpTo = 0
        var i = 0
        while i < tokens.count {
            guard let match = matchAt(source, tokens, i) else { i += 1; continue }
            out += source.substring(with: NSRange(location: copiedUpTo, length: tokens[i].start - copiedUpTo))
            out += match.replacement
            copiedUpTo = tokens[i + match.count - 1].end
            i += match.count
        }
        out += source.substring(from: copiedUpTo)
        return out
    }

    /// The number of tokens from `i` to replace and their replacement, or nil.
    private func matchAt(_ text: NSString, _ tokens: [(start: Int, end: Int)], _ i: Int) -> (count: Int, replacement: String)? {
        // Joining stops at anything but spaces or hyphens between words.
        var windows = 1
        while windows < maxWindow, i + windows < tokens.count {
            let gap = text.substring(with: NSRange(location: tokens[i + windows - 1].end,
                                                   length: tokens[i + windows].start - tokens[i + windows - 1].end))
            if gap.isEmpty || !gap.allSatisfy({ $0 == " " || $0 == "-" }) { break }
            windows += 1
        }
        for entry in entries {
            var n = windows
            while n >= 1 {
                let raw = text.substring(with: NSRange(location: tokens[i].start,
                                                       length: tokens[i + n - 1].end - tokens[i].start))
                let (heard, possessive) = splitPossessive(raw)
                let heardKey = DictionaryWords.key(heard)
                var replacement: String?
                if heardKey == entry.key {
                    replacement = (entry.fixesCase || n > 1) ? entry.text : heard
                } else if n == entry.wordCount, fuzzy(entry, heard: heardKey) {
                    replacement = entry.fixesCase ? entry.text : entry.text.lowercased()
                }
                if let replacement { return (n, replacement + possessive) }
                n -= 1
            }
        }
        return nil
    }

    private func fuzzy(_ entry: Entry, heard: String) -> Bool {
        guard entry.key.count >= 7, let first = heard.first, first == entry.key.first else { return false }
        let limit = entry.key.count >= 10 ? 2 : 1
        return DictionaryWords.editDistance(entry.key, heard, limit: limit) <= limit
    }

    private func splitPossessive(_ word: String) -> (word: String, possessive: String) {
        for suffix in ["'s", "’s"] where word.count > suffix.count && word.hasSuffix(suffix) {
            return (String(word.dropLast(suffix.count)), String(word.suffix(suffix.count)))
        }
        return (word, "")
    }
}
