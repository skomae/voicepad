import Foundation

/// CC-CEDICT writes pinyin with tone numbers ("yin2 hang2"); people read tone
/// marks ("yín háng"). Pure Swift so it can be unit-tested.
///
/// Ported from Android's `Pinyin.kt` (Android #25).
enum Pinyin {
    private static let marksByVowel: [Character: String] = [
        "a": "āáǎà", "e": "ēéěè", "i": "īíǐì", "o": "ōóǒò", "u": "ūúǔù", "ü": "ǖǘǚǜ",
        "A": "ĀÁǍÀ", "E": "ĒÉĚÈ", "I": "ĪÍǏÌ", "O": "ŌÓǑÒ", "U": "ŪÚǓÙ", "Ü": "ǕǗǙǛ",
    ]

    /// "yin2 hang2" → "yín háng"; "nu:3" → "nǚ"; anything that isn't a numbered
    /// syllable passes through.
    static func marks(_ numbered: String) -> String {
        splitOnSpaces(numbered).map(syllable).joined(separator: " ")
    }

    /// One syllable: "hang2" → "háng". Tone 5 (neutral) gets no mark.
    static func syllable(_ s: String) -> String {
        var characters = Array(s)
        var tone: Int?
        if let last = characters.last, let digit = last.wholeNumberValue, last.isNumber {
            tone = digit
            characters.removeLast()
        }
        var body = String(characters)
            .replacingOccurrences(of: "u:", with: "ü")
            .replacingOccurrences(of: "U:", with: "Ü")
            .replacingOccurrences(of: "v", with: "ü")
            .replacingOccurrences(of: "V", with: "Ü")
        guard let tone, (1...5).contains(tone), !body.isEmpty,
              body.lowercased().contains(where: { "aeiouü".contains($0) }) else {
            return body.isEmpty ? s : body
        }
        if tone == 5 { return body }
        let lower = body.lowercased()
        // Standard rule: a or e takes the mark; in "ou" the o; otherwise the last vowel.
        let index: Int
        if let at = lower.firstIndex(of: "a") {
            index = lower.distance(from: lower.startIndex, to: at)
        } else if let at = lower.firstIndex(of: "e") {
            index = lower.distance(from: lower.startIndex, to: at)
        } else if let range = lower.range(of: "ou") {
            index = lower.distance(from: lower.startIndex, to: range.lowerBound)
        } else {
            let vowels = Array(lower)
            guard let last = vowels.lastIndex(where: { "aeiouü".contains($0) }) else { return body }
            index = last
        }
        let charactersOfBody = Array(body)
        guard index < charactersOfBody.count else { return body }
        let vowel = charactersOfBody[index]
        guard let marked = marksByVowel[vowel].map({ Array($0) }), tone - 1 < marked.count else { return body }
        var out = charactersOfBody
        out[index] = marked[tone - 1]
        body = String(out)
        return body
    }

    /// The tone of a numbered syllable (1–4, 5 for neutral), or 0 when there's none.
    static func tone(_ numbered: String) -> Int {
        guard let last = numbered.last, last.isNumber, let digit = last.wholeNumberValue, (1...5).contains(digit) else { return 0 }
        return digit
    }

    private static let bracketed = try! NSRegularExpression(pattern: "\\[([^]]+)]")

    /// The inverse of `marks`: ICU's Han–Latin gives tone marks ("yín háng"),
    /// CC-CEDICT and HanLP write tone numbers ("yin2 hang2"). Text that already
    /// carries numbers passes through unchanged.
    static func numbered(_ marked: String) -> String {
        splitOnSpaces(marked).map { syllable in
            var base = ""
            var tone = 0
            for character in syllable {
                if let accent = accents[character] {
                    base.append(accent.base)
                    tone = max(tone, accent.tone)
                } else {
                    base.append(character)
                }
            }
            return tone > 0 ? base + String(tone) : base
        }.joined(separator: " ")
    }

    private static let accents: [Character: (base: Character, tone: Int)] = {
        var table: [Character: (Character, Int)] = [:]
        for (base, marked) in marksByVowel {
            for (index, character) in marked.enumerated() {
                table[character] = (base, index + 1)
            }
        }
        // n and m take a mark too: 嗯 ǹg, 呣 ḿ.
        let nasal: [(String, Character)] = [("n̄ǹńň", "n"), ("N̄ǸŃŇ", "N"), ("m̄ḿ", "m"), ("M̄Ḿ", "M")]
        for (marked, base) in nasal {
            for (index, character) in marked.enumerated() where index < 4 {
                table[character] = (base, index == 0 ? 1 : index + 1)
            }
        }
        return table
    }()

    /// Glosses cite other words with numbered pinyin in brackets — CC-CEDICT
    /// writes "you (informal, as opposed to courteous 您[nin2])". The characters
    /// elsewhere in the sheet carry tone marks, so convert the bracket too;
    /// brackets without a tone digit are left exactly as they are.
    static func marksInBrackets(_ text: String) -> String {
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        var out = ""
        var copiedUpTo = text.startIndex
        for match in bracketed.matches(in: text, range: range) {
            guard let whole = Range(match.range, in: text), let inner = Range(match.range(at: 1), in: text) else { continue }
            let inside = String(text[inner])
            out += text[copiedUpTo..<whole.lowerBound]
            out += inside.contains(where: \.isNumber) ? "[\(marks(inside))]" : String(text[whole])
            copiedUpTo = whole.upperBound
        }
        out += text[copiedUpTo...]
        return out
    }

    private static func splitOnSpaces(_ text: String) -> [String] {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" })
            .map(String.init)
    }
}
