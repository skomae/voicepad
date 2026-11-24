import Foundation

/// What the conversation screen puts centre stage: the newest thought, big,
/// with everything older receding behind it.
struct Stage: Equatable {
    enum Phase {
        /// Nothing said yet.
        case idle
        /// Words are arriving.
        case speaking
        /// A pause mid-thought: the turn stays open, untranslated, in case the speaker goes on.
        case holding
        /// The models are working: transcribing the finished utterance or translating it.
        case working
        /// Translated and settled; it stays on stage until the next turn starts.
        case landed
    }

    /// The newest thought; nil before anyone has spoken.
    var turn: Turn?
    var phase: Phase
    /// Everything before `turn`, oldest first.
    var history: [Turn]

    static func of(_ turns: [Turn], live: Turn?, working: Bool) -> Stage {
        let last = turns.last
        if let live {
            // Speech resumed inside a held turn: show the whole thought, not only its newest piece.
            if let last, last.held, last.language == live.language {
                var turn = last
                turn.text = Turns.join(last.text, live.text)
                turn.translation = joinOrNil(last.translation, live.translation)
                turn.pending = true
                turn.held = false
                return Stage(turn: turn, phase: working ? .working : .speaking, history: Array(turns.dropLast()))
            }
            var turn = live
            turn.pending = true
            return Stage(turn: turn, phase: working ? .working : .speaking, history: turns)
        }
        guard let last else { return Stage(turn: nil, phase: .idle, history: []) }
        let phase: Phase = last.held ? .holding : (last.pending || working) ? .working : .landed
        return Stage(turn: last, phase: phase, history: Array(turns.dropLast()))
    }

    private static func joinOrNil(_ a: String?, _ b: String?) -> String? {
        guard let a else { return b }
        guard let b else { return a }
        return Turns.join(a, b)
    }
}

/// Stand-in characters shown while a translation is on its way.
enum Glyphs {
    private static let latin = "abcdefghijklmnopqrstuvwxyz"
    private static let han = "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用样道想作种开美总从无情面最但现前些所同日手又行意动方期它头经长回位分爱老因很给名法间知世什两次使身者被高已亲其进此话常与活正感"
    private static let kana = "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん"
    private static let hangul = "가나다라마바사아자차카타파하고노도로모보소오조초코토포호구누두루무부수우주"
    private static let cyrillic = "абвгдежзиклмнопрстуфхцчшыэюя"
    private static let greek = "αβγδεζηθικλμνξοπρστυφχψω"
    private static let arabic = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
    private static let hebrew = "אבגדהוזחטיכלמנסעפצקרשת"
    private static let devanagari = "अआइईउऊएऐओऔकखगघचछजझटठडढणतथदधनपफबभमयरलवशषसह"
    private static let thai = "กขคงจฉชซญดตถทนบปผฝพฟภมยรลวศสหอ"

    /// Characters that look like writing in `language`.
    static func pool(_ language: String) -> [Character] {
        switch language {
        case "zh": return Array(han)
        case "ja": return Array(kana) + Array(han.prefix(60))
        case "ko": return Array(hangul)
        case "ru", "uk", "bg", "be", "mk": return Array(cyrillic)
        case "el": return Array(greek)
        case "ar", "fa", "ur": return Array(arabic)
        case "he": return Array(hebrew)
        case "hi", "mr": return Array(devanagari)
        case "th": return Array(thai)
        default: return Array(latin)
        }
    }

    /// Whether `language` is written without spaces, a character or two per word.
    static func isDense(_ language: String) -> Bool { language == "zh" || language == "ja" }

    /// Roughly how many characters `source` in language `from` comes to in `to`.
    static func estimateLength(_ source: String, from: String, to: String) -> Int {
        let ratio = isDense(from) && !isDense(to) ? 3.1 : !isDense(from) && isDense(to) ? 0.34 : 1
        return min(160, max(2, Int(Double(source.count) * ratio)))
    }

    /// Whether position `index` of a stand-in for `language` is a gap between words.
    static func isGap(_ language: String, _ index: Int) -> Bool { !isDense(language) && language != "th" && index % 6 == 5 }
}
