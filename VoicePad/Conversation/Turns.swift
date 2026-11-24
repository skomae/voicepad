import Foundation

/// How the conversation screen is laid out.
enum ConversationLayout: String, CaseIterable, Identifiable, Codable {
    /// Two halves, the far one upside down: for a phone lying between two people.
    case faceToFace
    /// The same, split left and right: for a landscape screen between two people at its short ends.
    case faceToFaceSideways
    /// One column per language: for a landscape screen both people look at.
    case sideBySide
    /// One list with original and translation: for following along yourself.
    case transcript

    var id: String { rawValue }

    var label: String {
        switch self {
        case .faceToFace: return "Across, top / bottom"
        case .faceToFaceSideways: return "Across, left / right"
        case .sideBySide: return "Side by side"
        case .transcript: return "Transcript"
        }
    }

    var systemImage: String {
        switch self {
        case .faceToFace: return "rectangle.split.1x2"
        case .faceToFaceSideways: return "rectangle.split.2x1"
        case .sideBySide: return "rectangle.split.2x1.fill"
        case .transcript: return "text.alignleft"
        }
    }

    /// The layout after this one, for the cycle button.
    func next() -> ConversationLayout {
        let all = Self.allCases
        return all[(all.firstIndex(of: self)! + 1) % all.count]
    }
}

/// How speakers are told apart: one microphone channel each, by the sound of
/// their voice, or (with neither available) simply by the language they speak.
enum SpeakerSource: String, Codable { case channel, voice, language }

/// Who said a turn. `index` counts from 0 within its `source`.
struct SpeakerRef: Hashable, Codable {
    var source: SpeakerSource
    var index: Int
}

/// One person's stretch of speech, with its translation into the other language.
struct Turn: Identifiable, Equatable {
    var id: Int
    /// Which listening session (one saved note each) it belongs to.
    var session: Int
    var speaker: SpeakerRef?
    /// Spoken language, one of the pair.
    var language: String
    var text: String
    /// Language `translation` is in (the other one of the pair).
    var target: String
    var translation: String?
    var translationFailed = false
    /// `translation` is missing or out of date: the turn is held open or being translated.
    var pending = false
    /// Held open, untranslated, because it sounds unfinished: the speaker may be thinking.
    var held = false
    /// Pinyin or other Latin letters for `text`, when it isn't written in Latin letters.
    var romanization: String?
    /// Same for `translation`.
    var translationRomanization: String?
    /// Position in its recording (16 kHz samples).
    var start: Int
    var end: Int

    /// What this turn says in `language`: the original, or its translation.
    func text(in language: String) -> String? { language == self.language ? text : translation }
}

enum Turns {
    /// Speech after a pause shorter than this continues the same turn.
    static let maxGapSeconds = 2.0
    /// Beyond this, a new turn starts anyway, so the newest words stay near the bottom.
    static let maxTurnChars = 240

    /// Whether new speech from `speaker` in `language` starting at `start`
    /// continues `previous`. `maxGapSeconds` is longer for a turn held open
    /// for a thinking pause.
    static func continues(_ previous: Turn?, session: Int, speaker: SpeakerRef?, language: String, start: Int,
                          sampleRate: Int, maxGapSeconds: Double = Turns.maxGapSeconds) -> Bool {
        guard let previous else { return false }
        return previous.session == session &&
            previous.speaker == speaker &&
            previous.language == language &&
            Double(start - previous.end) <= maxGapSeconds * Double(sampleRate) &&
            previous.text.count < maxTurnChars
    }

    private static let sentenceEnd: Set<Character> = [".", "?", "!", "。", "？", "！"]
    private static let closers: Set<Character> = ["\"", "'", "”", "’", "」", "』", "）", ")"]

    /// Words a sentence rarely ends on: the speaker is probably looking for what comes next.
    private static let trailingOff: Set<String> = [
        "and", "but", "or", "so", "because", "um", "uh", "er", "like", "the", "a", "an", "to", "of", "with", "that", "if", "when",
        "y", "o", "pero", "porque", "que", "de", "et", "ou", "mais", "parce", "und", "oder", "aber", "weil",
    ]
    private static let trailingOffCJK = ["然后", "就是", "那个", "这个", "因为", "所以", "但是", "而且", "还有", "嗯", "呃", "えっと", "あの", "그리고", "그래서"]

    /// Whether `text` sounds like its speaker isn't done: no sentence-ending
    /// punctuation, an ellipsis, or a last word that leads somewhere ("and",
    /// "because", 然后). Such a turn is held open for a thinking pause
    /// instead of being translated as a fragment.
    static func looksUnfinished(_ text: String) -> Bool {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        while let last = t.last, closers.contains(last) { t.removeLast() }
        guard let last = t.last else { return false }
        if t.hasSuffix("...") || t.hasSuffix("…") || t.hasSuffix("。。") { return true }
        if !sentenceEnd.contains(last) {
            // A short reply without punctuation ("ok", "好的") is complete; a longer run of words isn't.
            let han = t.filter(isCJK).count
            return han > 0 ? han >= 5 : t.split(separator: " ").count >= 3
        }
        var body = t
        while let c = body.last, sentenceEnd.contains(c) || c.isWhitespace { body.removeLast() }
        if trailingOffCJK.contains(where: { body.hasSuffix($0) }) { return true }
        // A lone "So." or "And." is a complete (if short) answer only when it's all there is.
        let words = body.split { $0 == " " || $0 == "," || $0 == "，" || $0 == "、" }
        return words.count > 1 && trailingOff.contains(words.last!.lowercased())
    }

    /// Joins two pieces of text: with a space, except between Chinese or Japanese characters.
    static func join(_ a: String, _ b: String) -> String {
        guard let last = a.last else { return b }
        guard let first = b.first else { return a }
        return isCJK(last) || isCJK(first) ? a + b : a + " " + b
    }

    private static func isCJK(_ c: Character) -> Bool {
        LanguageTags.containsHan(String(c)) || LanguageTags.containsKana(String(c)) || "，。？！：；、「」".contains(c)
    }

    /// Plain-text transcript of `turns` for the saved note: each turn, then its translation.
    static func transcript(_ turns: [Turn], label: (Turn) -> String) -> String {
        turns.map { turn in
            var s = label(turn) + ": " + turn.text
            if let translation = turn.translation { s += "\n→ " + translation }
            return s
        }.joined(separator: "\n\n")
    }
}
