import Foundation
import NaturalLanguage

/// Cuts Chinese into words with Apple's tokenizer, and reads hanzi with ICU's
/// Han–Latin transform: the iOS stand-ins for HanLP's segmenter and pinyin
/// table (see Android's `HanlpSegmenter`). Fully offline, no model download.
struct ChineseSegmenter: WordBreakdown.Segmenter {

    func segment(_ run: String) -> [(start: Int, end: Int)] {
        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = run
        tokenizer.setLanguage(.simplifiedChinese)
        var words: [(start: Int, end: Int)] = []
        tokenizer.enumerateTokens(in: run.startIndex..<run.endIndex) { range, _ in
            let start = run.distance(from: run.startIndex, to: range.lowerBound)
            let end = run.distance(from: run.startIndex, to: range.upperBound)
            if run[range].contains(where: WordBreakdown.isHan) { words.append((start: start, end: end)) }
            return true
        }
        // If the terms don't add up to the whole run, the caller falls back to
        // the dictionary rather than use spans that point at the wrong text.
        guard let first = words.first, let last = words.last, first.start == 0, last.end == run.count else { return [] }
        return words
    }

    /// Numbered pinyin ("bei3 jing1"), the form the dictionary and its tone
    /// colours use. ICU answers unpronounceable text unchanged, which the
    /// caller treats as "no reading".
    func numberedPinyin(_ word: String) -> String? {
        let marked = ChineseSegmenter.latin(word)
        let numbered = Pinyin.numbered(marked)
        guard !numbered.isEmpty, numbered.lowercased() != "none" else { return nil }
        return numbered
    }

    /// A character's usual reading with tone marks ("le" for 了), which keeps
    /// particles reading as particles in the breakdown.
    static func usualReading(_ character: String) -> String? {
        let marked = latin(character).lowercased()
        return marked.isEmpty ? nil : marked
    }

    private static func latin(_ text: String) -> String {
        let mutable = NSMutableString(string: text)
        CFStringTransform(mutable, nil, kCFStringTransformToLatin, false)
        return (mutable as String).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
