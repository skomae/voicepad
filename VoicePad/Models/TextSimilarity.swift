import Foundation

/// Tells when a "translation" is really the same text, e.g. English speech
/// that was misdetected as another language and came back from the
/// translator almost unchanged. Pure Swift so it can be unit-tested.
enum TextSimilarity {
    /// Words, ignoring case, accents and punctuation.
    static func words(_ text: String) -> [String] {
        NoteSearch.fold(text)
            .split { c in !(c.isLetter || c.isNumber || c == "'") }
            .map(String.init)
    }

    /// Share of words the two texts have in common, in order (0...1): twice
    /// the longest common word sequence over the total word count.
    static func ratio(_ a: String, _ b: String) -> Double {
        let x = words(a), y = words(b)
        if x.isEmpty && y.isEmpty { return 1 }
        if x.isEmpty || y.isEmpty { return 0 }
        var prev = [Int](repeating: 0, count: y.count + 1)
        var cur = prev
        for i in 1...x.count {
            for j in 1...y.count {
                cur[j] = x[i - 1] == y[j - 1] ? prev[j - 1] + 1 : max(prev[j], cur[j - 1])
            }
            swap(&prev, &cur)
            for j in cur.indices { cur[j] = 0 }
        }
        return 2 * Double(prev[y.count]) / Double(x.count + y.count)
    }

    /// True when showing both would just repeat the same words.
    static func nearlySame(_ a: String?, _ b: String?, threshold: Double = 0.8) -> Bool {
        guard let a, let b else { return false }
        return ratio(a, b) >= threshold
    }
}
