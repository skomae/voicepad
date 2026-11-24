import Foundation

/// Decides which of a conversation's two languages an utterance is in, so it
/// can be translated into the other. Pure Swift so it can be unit-tested.
///
/// Language detection on a few spoken words is unreliable on its own, but a
/// conversation only has two candidates, which makes the choice much easier:
/// the writing system usually settles it, then the speech model's tag, then
/// the text detector's confidence for just those two.
enum ConversationLanguages {
    /// Languages not written in the Latin alphabet.
    private static let nonLatin: Set<String> = [
        "zh", "yue", "ja", "ko", "ru", "uk", "bg", "be", "mk", "el", "ar", "fa", "ur", "he",
        "hi", "mr", "bn", "gu", "kn", "ta", "te", "th", "ka",
    ]

    static func usesLatinScript(_ code: String) -> Bool { !nonLatin.contains(code) }

    /// The language of `text`, one of `pair` unless nothing points to either
    /// (then `fallback`, else the first of the pair).
    /// - Parameters:
    ///   - model: what the speech model reported, if anything ("zh", "en", "<|ja|>")
    ///   - candidates: the text detector's guesses with confidence, e.g. ("es", 0.8)
    static func spoken(_ pair: (String, String), model: String?, candidates: [(String, Double)], text: String,
                       fallback: String? = nil) -> String {
        let both = [pair.0, pair.1]

        // 1. The writing system: Chinese characters, kana, Hangul, Cyrillic…
        let byScript = scriptCandidates(text).filter(both.contains)
        if byScript.count == 1 { return byScript[0] }

        // 2. The speech model's own tag (Cantonese counts as Chinese if only Chinese is in play).
        if let tag = normalize(LanguageTags.fromModel(model), both), both.contains(tag) { return tag }

        // 3. The text detector, restricted to the two languages.
        let restricted = candidates.compactMap { code, confidence -> (String, Double)? in
            let base = String(code.split(separator: "-").first ?? Substring(code))
            guard let c = normalize(LanguageTags.fromModel(base), both), both.contains(c) else { return nil }
            return (c, confidence)
        }
        if let best = restricted.max(by: { $0.1 < $1.1 }) { return best.0 }

        // 4. Plain Latin letters when only one side writes in Latin letters.
        if text.contains(where: \.isLetter), !LanguageTags.hasNonLatinLetters(text) {
            let latin = both.filter(usesLatinScript)
            if latin.count == 1 { return latin[0] }
        }
        if let fallback, both.contains(fallback) { return fallback }
        return pair.0
    }

    /// The other language of the pair: what `spoken` text is translated into.
    static func target(_ pair: (String, String), spoken: String) -> String { spoken == pair.0 ? pair.1 : pair.0 }

    private static func normalize(_ code: String?, _ both: [String]) -> String? {
        guard let code else { return nil }
        if code == "yue", !both.contains("yue"), both.contains("zh") { return "zh" }
        if code == "zh", !both.contains("zh"), both.contains("yue") { return "yue" }
        return code
    }

    /// Languages the letters of `text` could belong to, most likely first.
    static func scriptCandidates(_ text: String) -> [String] {
        var han = false, kana = false, hangul = false, cyrillic = false, greek = false
        var arabic = false, hebrew = false, thai = false, devanagari = false
        for s in text.unicodeScalars where s.properties.isAlphabetic {
            let v = s.value
            if LanguageTags.isKana(s) { kana = true }
            else if LanguageTags.isHan(s) { han = true }
            else if (0xAC00...0xD7AF).contains(v) || (0x1100...0x11FF).contains(v) || (0x3130...0x318F).contains(v) { hangul = true }
            else if (0x0400...0x052F).contains(v) { cyrillic = true }
            else if (0x0370...0x03FF).contains(v) || (0x1F00...0x1FFF).contains(v) { greek = true }
            else if (0x0600...0x06FF).contains(v) || (0x0750...0x077F).contains(v) || (0xFB50...0xFDFF).contains(v) { arabic = true }
            else if (0x0590...0x05FF).contains(v) { hebrew = true }
            else if (0x0E00...0x0E7F).contains(v) { thai = true }
            else if (0x0900...0x097F).contains(v) { devanagari = true }
        }
        if kana { return ["ja"] }
        if hangul { return ["ko"] }
        if han { return ["zh", "yue", "ja"] }
        if cyrillic { return ["ru", "uk", "bg", "be", "mk"] }
        if greek { return ["el"] }
        if arabic { return ["ar", "fa", "ur"] }
        if hebrew { return ["he"] }
        if thai { return ["th"] }
        if devanagari { return ["hi", "mr"] }
        return []
    }
}

/// Language names for the conversation screen.
enum LanguageNames {
    /// Offered first in the language pickers.
    static let common = ["en", "zh", "es", "fr", "de", "ja", "ko", "it", "pt", "ru", "ar", "hi", "nl", "pl", "tr", "uk", "vi", "th", "id"]

    static func english(_ code: String) -> String { LanguageTags.displayName(code) }

    /// The language's name in itself ("中文", "Español"), so each person can find their side.
    static func native(_ code: String) -> String {
        if code == "zh" { return "中文" }
        if code == "yue" { return "粵語" }
        let locale = Locale(identifier: code)
        guard let name = locale.localizedString(forLanguageCode: code), !name.isEmpty else { return english(code) }
        return name.prefix(1).uppercased() + name.dropFirst()
    }
}
