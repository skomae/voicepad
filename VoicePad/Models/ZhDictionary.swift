import Foundation
import zlib

/// A Chinese–English dictionary: CC-CEDICT, with each entry's word frequency
/// (from jieba's word list) merged in at build time. Ported from Android's
/// `ZhDictionary.kt` (Android #22).
///
/// File format, one entry per line (lines starting with # are comments):
///
///     <frequency>\t<traditional> <simplified> [pin1 yin1] /meaning/meaning/
///
/// The frequency column is optional, so plain CC-CEDICT lines also load. Only
/// the raw lines and an index are kept in memory; entries are parsed when
/// looked up.
final class ZhDictionary {
    struct Entry {
        let traditional: String
        let simplified: String
        /// Numbered pinyin as in CC-CEDICT, e.g. "yin2 hang2".
        let pinyin: String
        let definitions: [String]
        /// How often the word occurs in jieba's corpus (0: not in it).
        let frequency: Int

        /// A proper noun (CC-CEDICT capitalises their pinyin).
        var isProperNoun: Bool { pinyin.first?.isUppercase ?? false }
    }

    /// Line text after the frequency column, per entry.
    private let bodies: [String]
    private let frequencies: [Int]
    private let index: [String: [Int]]

    /// The longest headword, in characters.
    let maxWordLength: Int
    /// Sum of all word frequencies, for turning them into probabilities.
    let totalFrequency: Int

    /// Number of entries.
    var size: Int { bodies.count }

    private init(bodies: [String], frequencies: [Int], index: [String: [Int]], maxWordLength: Int) {
        self.bodies = bodies
        self.frequencies = frequencies
        self.index = index
        self.maxWordLength = maxWordLength
        totalFrequency = max(frequencies.reduce(0, +), 1)
    }

    func contains(_ word: String) -> Bool { index[word] != nil }

    /// Entries whose simplified or traditional form is `word`, in file order.
    func lookup(_ word: String) -> [Entry] {
        (index[word] ?? []).map(entry)
    }

    /// The highest frequency among `word`'s entries, 0 if unknown.
    func frequency(_ word: String) -> Int {
        (index[word] ?? []).map { frequencies[$0] }.max() ?? 0
    }

    /// Calls `visit` with every entry, for reverse (English → Chinese) lookups.
    func forEachEntry(_ visit: (Entry) -> Void) {
        for i in bodies.indices { visit(entry(i)) }
    }

    /// Like `forEachEntry`, but only for entries whose line contains `needle`
    /// (case-insensitive), checked on the raw line before parsing.
    func forEachEntryContaining(_ needle: String, _ visit: (Entry) -> Void) {
        guard !needle.isEmpty else { return forEachEntry(visit) }
        let lowered = needle.lowercased()
        for (i, body) in bodies.enumerated() where body.lowercased().contains(lowered) {
            visit(entry(i))
        }
    }

    private func entry(_ i: Int) -> Entry {
        ZhDictionary.parseLine(bodies[i], frequency: frequencies[i])
            ?? Entry(traditional: "", simplified: "", pinyin: "", definitions: [], frequency: 0)
    }

    // MARK: Parsing

    private static let linePattern = try! NSRegularExpression(pattern: #"^(\S+) (\S+) \[([^\]]*)\] /(.*)/\s*$"#)

    /// Parses a whole dictionary file (UTF-8). Malformed lines are skipped.
    static func parse(_ text: String) -> ZhDictionary {
        var bodies: [String] = []
        var frequencies: [Int] = []
        var building: [String: [Int]] = [:]
        var longest = 1

        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            var line = String(rawLine)
            if line.hasSuffix("\r") { line.removeLast() }
            guard !line.isEmpty, !line.hasPrefix("#") else { continue }
            // Frequency column, if any.
            var frequency = 0
            var bodyStart = line.startIndex
            var cursor = line.startIndex
            var digits = ""
            while cursor < line.endIndex, line[cursor].isNumber, line[cursor].isASCII {
                digits.append(line[cursor])
                cursor = line.index(after: cursor)
            }
            if !digits.isEmpty, cursor < line.endIndex, line[cursor] == "\t" {
                frequency = Int(digits) ?? 0
                bodyStart = line.index(after: cursor)
            }
            let body = String(line[bodyStart...])
            // Headwords: up to the " [" that opens the pinyin.
            guard let open = body.range(of: " [") else { continue }
            let heads = body[body.startIndex..<open.lowerBound].split(separator: " ", omittingEmptySubsequences: false)
            guard heads.count == 2 else { continue }
            let traditional = String(heads[0]), simplified = String(heads[1])
            let i = bodies.count
            bodies.append(body)
            frequencies.append(frequency)
            building[simplified, default: []].append(i)
            if traditional != simplified { building[traditional, default: []].append(i) }
            longest = max(longest, simplified.count, traditional.count)
        }

        return ZhDictionary(bodies: bodies, frequencies: frequencies, index: building, maxWordLength: longest)
    }

    static func parseLine(_ line: String, frequency: Int) -> Entry? {
        let range = NSRange(line.startIndex..<line.endIndex, in: line)
        guard let match = linePattern.firstMatch(in: line, range: range),
              let traditional = Range(match.range(at: 1), in: line),
              let simplified = Range(match.range(at: 2), in: line),
              let pinyin = Range(match.range(at: 3), in: line),
              let definitions = Range(match.range(at: 4), in: line) else { return nil }
        return Entry(
            traditional: String(line[traditional]),
            simplified: String(line[simplified]),
            pinyin: String(line[pinyin]),
            definitions: String(line[definitions]).split(separator: "/", omittingEmptySubsequences: true)
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty },
            frequency: frequency
        )
    }
}

/// The dictionary that ships with the app (`VoicePad/Resources/zh_dict.dat`,
/// gzipped CC-CEDICT with jieba frequencies; see `scripts/fetch-zh-dictionary.py`).
enum BundledChineseDictionary {
    private static var cached: ZhDictionary?
    private static var loading = false

    /// The bundled dictionary, or nil when this build doesn't include it.
    /// Parsed once, on a background queue: a tap must not block the screen.
    static func load() -> ZhDictionary? {
        if let cached { return cached }
        guard !loading else { return nil }
        loading = true
        defer { loading = false }
        guard let url = Bundle.main.url(forResource: "zh_dict", withExtension: "dat"),
              let data = gunzip(url), let text = String(data: data, encoding: .utf8) else {
            return nil
        }
        let dictionary = ZhDictionary.parse(text)
        cached = dictionary
        return dictionary
    }

    private static func gunzip(_ url: URL) -> Data? {
        guard let file = gzopen(url.path, "rb") else { return nil }
        defer { gzclose(file) }
        var out = Data()
        var buffer = [UInt8](repeating: 0, count: 1 << 20)
        while true {
            let read = buffer.withUnsafeMutableBytes { raw in
                gzread(file, raw.baseAddress, UInt32(raw.count))
            }
            if read <= 0 { break }
            out.append(contentsOf: buffer[0..<Int(read)])
        }
        return out.isEmpty ? nil : out
    }
}
