import Foundation

/// What the word breakdown shows: a Chinese text, its translation (if any),
/// and which word was tapped. Ported from Android's `BreakdownRequest` (#22).
struct BreakdownRequest: Identifiable {
    enum Focus: Equatable {
        /// A character offset in `chinese`.
        case chinese(Int)
        /// A character offset in `translation`.
        case translation(Int)
    }

    var id = UUID()
    let chinese: String
    var translation: String?
    /// Language of `translation`; meanings are only matched against English.
    var translationLanguage: String?

    var focus: Focus?

    var translationIsEnglish: Bool { translationLanguage == "en" }

    /// Mandarin, or Cantonese (written in the same characters).
    static func isChinese(_ language: String?) -> Bool {
        language == "zh" || language == "yue"
    }

    /// The breakdown for a tap on a conversation `turn`: in what was said
    /// (`tappedTranslation` false) or in its translation, at `offset`. Nil when
    /// neither side is Chinese (or both are).
    static func forTurn(_ turn: Turn, tappedTranslation: Bool, offset: Int?) -> BreakdownRequest? {
        let spokenChinese = isChinese(turn.language)
        guard spokenChinese != isChinese(turn.target) else { return nil }
        let chinese = spokenChinese ? turn.text : (turn.translation ?? "")
        guard !chinese.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let other = spokenChinese ? turn.translation : turn.text
        let otherLanguage = spokenChinese ? turn.target : turn.language
        let tappedChinese = tappedTranslation != spokenChinese
        let focus = offset.map { tappedChinese ? Focus.chinese($0) : Focus.translation($0) }
        let translated = other?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? other : nil
        return BreakdownRequest(chinese: chinese, translation: translated, translationLanguage: otherLanguage, focus: focus)
    }
}
