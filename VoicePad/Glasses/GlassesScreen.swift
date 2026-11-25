import Foundation

/// What the glasses show of the conversation.
enum GlassesScope: String, CaseIterable, Identifiable, Codable {
    /// Only the other person, translated: the glasses as a private subtitle track.
    case them
    /// Both sides, in your language, marked with who said it.
    case both

    var id: String { rawValue }

    var label: String {
        switch self {
        case .them: return "Them"
        case .both: return "Both"
        }
    }

    var detail: String {
        switch self {
        case .them: return "Only the other person's speech, translated into your language."
        case .both: return "Everything said, in your language, marked with who said it."
        }
    }
}

/// The conversation as it fits on a pair of Even Realities G1 glasses.
///
/// The waveguide is a 40-column, 5-line green display (640x200: a 16-pixel
/// character cell, five rows), so a page holds about 200 characters — roughly two
/// short turns. There is nothing to scroll with and no room for chrome, so this is
/// a subtitle track rather than a transcript: the newest lines fill the page from
/// the bottom up and older turns scroll off the top.
///
/// Wide characters (Chinese, Japanese, Korean) count as two of the 40 columns, which
/// is why a Chinese line fits around 20 characters. When the reader's language is
/// Chinese, a Chinese turn gets a pinyin line under it.
///
/// Pure Swift, no UIKit: `GlassesScreenTests` is the same set of cases the Android
/// version is checked against.
enum GlassesScreen {
    static let lines = 5
    static let columns = 40

    /// Marks a turn said by the other person, when both sides are shown.
    private static let them = "> "
    /// Marks a turn of your own.
    private static let me = "- "

    /// The page for `stage`: exactly five lines of at most 40 columns, empty ones
    /// padded at the top so text grows upwards from the bottom of the display.
    static func page(
        stage: Stage,
        myLanguage: String,
        theirLanguage: String,
        scope: GlassesScope,
        listening: Bool
    ) -> [String] {
        let turns = stage.history + [stage.turn].compactMap { $0 }
        let shown: [Turn]
        switch scope {
        case .them: shown = turns.filter { $0.language != myLanguage }
        case .both: shown = turns
        }
        guard !shown.isEmpty else { return idle(myLanguage: myLanguage, theirLanguage: theirLanguage, listening: listening) }
        let wrapped = shown.flatMap { block($0, myLanguage: myLanguage, scope: scope) }
        let page = Array(wrapped.suffix(lines))
        return Array(repeating: "", count: lines - page.count) + page
    }

    /// Nothing said yet: which two languages, and whether anyone is listening.
    private static func idle(myLanguage: String, theirLanguage: String, listening: Bool) -> [String] {
        let pair = "\(theirLanguage.uppercased()) → \(myLanguage.uppercased())"
        return ["", "", centre(pair), centre(listening ? "listening…" : "paused"), ""]
    }

    /// One turn as `myLanguage`'s reader sees it, wrapped and marked.
    private static func block(_ turn: Turn, myLanguage: String, scope: GlassesScope) -> [String] {
        let spelled = turn.language == myLanguage
        let gutter: String
        switch scope {
        case .them: gutter = ""
        case .both: gutter = spelled ? me : them
        }
        guard let text = turn.text(in: myLanguage) else {
            // Translation still on its way: keep the last page and show where the
            // new one will be, rather than flashing a script they may not read.
            return turn.translationFailed
                ? mark(gutter, wrap(turn.text, columns: columns - width(gutter)))
                : [gutter + "…"]
        }
        let wrapped = mark(gutter, wrap(text, columns: columns - width(gutter)))

        // Chinese on the glasses gets a pinyin line under it.
        guard isChinese(myLanguage), text.contains(where: isWide) else { return wrapped }
        guard let pinyin = MandarinPinyin.of(text, language: myLanguage) else { return wrapped }
        return wrapped + mark(gutter, wrap(pinyin, columns: columns - width(gutter)))
    }

    /// Puts `gutter` at the start of a turn's first line; later lines line up under it.
    private static func mark(_ gutter: String, _ lines: [String]) -> [String] {
        guard !gutter.isEmpty else { return lines }
        return lines.enumerated().map { index, line in
            index == 0 ? gutter + line : String(repeating: " ", count: gutter.count) + line
        }
    }

    private static func isChinese(_ language: String) -> Bool {
        let lower = language.lowercased()
        return lower.hasPrefix("zh") || lower.hasPrefix("yue")
    }

    // MARK: Wrapping

    /// A piece of text that must not be split unless it is wider than a whole line.
    private struct Token {
        var text: String
        var spaceBefore: Bool
    }

    /// Splits `text` into lines of at most `columns` display columns.
    static func wrap(_ text: String, columns: Int = columns) -> [String] {
        let cleaned = text
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return [""] }

        var lines: [String] = []
        var line = ""
        var filled = 0

        func breakLine() {
            if !line.isEmpty {
                lines.append(line.trimmingCharacters(in: .whitespaces))
                line = ""
                filled = 0
            }
        }

        for token in tokenise(cleaned) {
            let w = width(token.text)
            let gap = token.spaceBefore && filled > 0 ? 1 : 0
            if filled + gap + w <= columns {
                if gap == 1 { line += " " }
                line += token.text
                filled += gap + w
                continue
            }
            breakLine()
            if w <= columns {
                line = token.text
                filled = w
                continue
            }
            // One token wider than the whole line: cut it by width, not by word.
            let pieces = cut(token.text, columns: columns)
            if pieces.count > 1 { lines.append(contentsOf: pieces.dropLast()) }
            line = pieces.last ?? ""
            filled = width(line)
        }
        breakLine()
        return lines.isEmpty ? [""] : lines
    }

    /// Splits text into words, single wide characters, and punctuation that belongs
    /// to the word before it.
    private static func tokenise(_ text: String) -> [Token] {
        var out: [Token] = []
        var word = ""
        var pendingSpace = false

        func emit(_ piece: String, wide: Bool) {
            guard !piece.isEmpty else { return }
            // Closing punctuation never starts a line, so it rides with the character
            // before it.
            let closes = wide && closers.contains(piece.first!)
            if closes, var last = out.popLast() {
                last.text += piece
                out.append(last)
            } else {
                out.append(Token(text: piece, spaceBefore: pendingSpace && !out.isEmpty))
            }
            pendingSpace = false
        }

        func flushWord() {
            guard !word.isEmpty else { return }
            emit(word, wide: false)
            word = ""
        }

        for character in text {
            if character == " " {
                flushWord()
                pendingSpace = true
            } else if isWide(character) {
                flushWord()
                emit(String(character), wide: true)
            } else {
                word.append(character)
            }
        }
        flushWord()
        return out
    }

    /// Cuts `text` into pieces of at most `columns` columns each.
    private static func cut(_ text: String, columns: Int) -> [String] {
        var out: [String] = []
        var piece = ""
        var filled = 0
        let limited = Swift.min(columns, 1_000)
        for character in text {
            let w = width(character)
            if filled + w > limited, !piece.isEmpty {
                out.append(piece)
                piece = ""
                filled = 0
            }
            piece.append(character)
            filled += w
        }
        if !piece.isEmpty { out.append(piece) }
        return out.isEmpty ? [""] : out
    }

    /// How many of the display's columns a character takes: 2 for wide scripts.
    static func width(_ character: Character) -> Int {
        isWide(character) ? 2 : 1
    }

    static func width(_ text: String) -> Int {
        text.reduce(0) { $0 + width($1) }
    }

    /// A display-column check: 1 unless the character is a wide script.
    static func isWide(_ character: Character) -> Bool {
        guard let scalar = character.unicodeScalars.first else { return false }
        let value = scalar.value
        if value >= 0x20000 && value <= 0x2FA1F { return true }   // rare ideographs
        if value >= 0xFF00 && value <= 0xFF60 { return true }     // full-width forms
        if value >= 0x3000 && value <= 0x303F { return true }     // CJK punctuation
        if value >= 0xAC00 && value <= 0xD7A3 { return true }     // Hangul syllables
        if value >= 0x4E00 && value <= 0x9FFF { return true }     // Han
        if value >= 0x3400 && value <= 0x4DBF { return true }     // Han ext A
        if value >= 0x3040 && value <= 0x30FF { return true }     // kana
        if value >= 0x31F0 && value <= 0x31FF { return true }     // kana ext
        return false
    }

    /// Punctuation that may not start a line.
    private static let closers = Set("，。！？、；：）」』】》〉’”%")

    /// Centres `text` within the display, by display width.
    static func centre(_ text: String) -> String {
        let pad = Swift.max(0, (columns - width(text)) / 2)
        return String(repeating: " ", count: pad) + text
    }
}
