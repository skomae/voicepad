import Foundation

/// Language codes, names and script checks. No framework dependencies, so it
/// can be unit-tested.
enum LanguageTags {
    /// Normalises what a speech model reports ("<|zh|>", "zh", "") to a code like "zh", or nil.
    static func fromModel(_ raw: String?) -> String? {
        var code = (raw ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        if code.hasPrefix("<|") { code.removeFirst(2) }
        if code.hasSuffix("|>") { code.removeLast(2) }
        guard (2...3).contains(code.count), code.allSatisfy(\.isLetter), code != "und", code != "nospeech" else { return nil }
        return code
    }

    static func isEnglish(_ code: String?) -> Bool { code == "en" }

    /// Human name, e.g. "Mandarin Chinese", "French", "Cantonese".
    static func displayName(_ code: String) -> String {
        switch code {
        case "zh": return "Mandarin Chinese"
        case "yue": return "Cantonese"
        default:
            return Locale(identifier: "en").localizedString(forLanguageCode: code) ?? code
        }
    }

    static func containsHan(_ text: String) -> Bool { text.unicodeScalars.contains(where: isHan) }

    static func containsKana(_ text: String) -> Bool { text.unicodeScalars.contains(where: isKana) }

    /// True when the text has letters outside the Latin alphabet (so romanisation helps).
    static func hasNonLatinLetters(_ text: String) -> Bool {
        text.unicodeScalars.contains { isLetter($0) && !isLatin($0) }
    }

    /// Picks the spoken language. The speech model's own tag wins when it gave
    /// one; otherwise the text-based guess. Chinese characters without any
    /// Japanese kana are treated as Chinese, since text detectors often confuse
    /// short Chinese with Japanese.
    static func decide(modelLanguage: String?, textGuess: String?, text: String) -> String? {
        let model = fromModel(modelLanguage)
        let guess = fromModel(textGuess.map { String($0.split(separator: "-").first ?? "") })
        let chosen = model ?? guess
        if containsHan(text), !containsKana(text), chosen != "yue" { return "zh" }
        return chosen
    }

    // MARK: Scripts

    static func isHan(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x2E80...0x2FDF, 0x3005, 0x3007, 0x3021...0x3029, 0x3038...0x303B,
             0x3400...0x4DBF, 0x4E00...0x9FFF, 0xF900...0xFAFF, 0x20000...0x3134F:
            return true
        default:
            return false
        }
    }

    static func isKana(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x3040...0x309F, 0x30A0...0x30FF, 0x31F0...0x31FF, 0xFF66...0xFF9F, 0x1B000...0x1B16F:
            return true
        default:
            return false
        }
    }

    private static func isLetter(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    private static func isLatin(_ s: Unicode.Scalar) -> Bool {
        switch s.value {
        case 0x41...0x5A, 0x61...0x7A, 0xAA, 0xBA, 0xC0...0x24F, 0x250...0x2AF, 0x1D00...0x1D7F,
             0x1E00...0x1EFF, 0x2C60...0x2C7F, 0xA720...0xA7FF, 0xAB30...0xAB6F, 0xFF21...0xFF3A, 0xFF41...0xFF5A:
            return true
        default:
            return false
        }
    }
}
