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
        let foreign = parts.compactMap(\.language).filter { !LanguageTags.isEnglish($0) }
        let counts = Dictionary(foreign.map { ($0, 1) }, uniquingKeysWith: +)
        // Ties go to the language heard first.
        guard let language = foreign.first(where: { counts[$0] == counts.values.max() }) else {
            return NoteTranslation(revisionID: revisionID, status: .notNeeded, language: parts.lazy.compactMap(\.language).first)
        }
        let english = parts.compactMap(\.english).joined(separator: " ")
        let romanization = parts.compactMap(\.romanization).joined(separator: " ")
        let waiting = parts.contains { !$0.translated }
        return NoteTranslation(
            revisionID: revisionID,
            status: english.isEmpty && waiting ? .pending : .done,
            language: language,
            english: english.isEmpty ? nil : english,
            romanization: romanization.isEmpty ? nil : romanization
        )
    }
}
