import Foundation

/// Joins per-utterance translations of a live recording into one. English
/// utterances count as their own translation, so a recording that mixes
/// Mandarin and English reads as one continuous English text. Pure Swift so
/// it can be unit-tested.
enum TranslationJoin {
    struct Part {
        var text: String
        /// Language the speech model or translator reported, if any.
        var language: String?
        /// English for this part (its own text when it was English), once translated.
        var english: String?
        var romanization: String?
        /// Translation finished (or was attempted) for this part.
        var translated: Bool
    }

    static func combine(_ parts: [Part], revisionID: UUID) -> NoteTranslation? {
        guard !parts.isEmpty else { return nil }
        // A part whose "translation" is its own words was English all along
        // (short utterances like "Yeah." are easily misdetected).
        func effectiveLanguage(_ p: Part) -> String? {
            if let english = p.english, TextSimilarity.nearlySame(english, p.text) { return "en" }
            return p.language
        }
        // The label is the foreign language with the most text, not the most utterances.
        var amount: [String: Int] = [:]
        var order: [String] = []
        for p in parts {
            guard let language = effectiveLanguage(p), !LanguageTags.isEnglish(language) else { continue }
            if amount[language] == nil { order.append(language) }
            amount[language, default: 0] += p.text.count
        }
        guard let most = amount.values.max(), let language = order.first(where: { amount[$0] == most }) else {
            return NoteTranslation(revisionID: revisionID, status: .notNeeded, language: "en")
        }
        let english = parts.compactMap(\.english).joined(separator: " ")
        let romanization = parts.compactMap(\.romanization).joined(separator: " ")
        let waiting = parts.contains { !$0.translated }
        // Nothing to show if the English reads the same as what was said.
        if !waiting, TextSimilarity.nearlySame(english, parts.map(\.text).joined(separator: " ")) {
            return NoteTranslation(revisionID: revisionID, status: .notNeeded, language: "en")
        }
        return NoteTranslation(
            revisionID: revisionID,
            status: english.isEmpty && waiting ? .pending : .done,
            language: language,
            english: english.isEmpty ? nil : english,
            romanization: romanization.isEmpty ? nil : romanization
        )
    }
}
