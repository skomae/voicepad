import Foundation

/// Turns non-Latin text into Latin letters: pinyin with tone marks for
/// Mandarin, a general transliteration for scripts like Korean or Cyrillic.
/// Uses the ICU transforms built into Foundation ("Han-Latin", "Any-Latin").
enum Romanizer {
    /// Romanised text for `language`, or nil when it wouldn't help.
    static func romanize(language: String?, text: String) -> String? {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              LanguageTags.hasNonLatinLetters(text) else { return nil }
        switch language {
        case "zh":
            return pinyin(text)
        case "yue", "ja":
            // Cantonese uses Jyutping, not pinyin, and kanji readings in
            // Japanese differ from Chinese; better none than a wrong guide.
            return nil
        default:
            return transliterate("Any-Latin; NFC", text).map(tidy)
        }
    }

    /// Pinyin with tone marks. ICU reads characters one at a time, which gets
    /// common multi-reading words wrong (银行 → "yín xíng"); whole words from
    /// `pinyinWords` are substituted first.
    static func pinyin(_ text: String) -> String? {
        let chars = Array(text)
        var out = ""
        var han = ""
        var other = ""
        func flushHan() {
            guard !han.isEmpty else { return }
            if let latin = transliterate("Han-Latin", han) { out += " \(latin) " }
            han = ""
        }
        func flushOther() {
            guard !other.isEmpty else { return }
            out += " \(other) "
            other = ""
        }
        var i = 0
        while i < chars.count {
            let word = stride(from: min(maxWord, chars.count - i), through: 2, by: -1)
                .lazy
                .map { String(chars[i..<(i + $0)]) }
                .first { pinyinWords[$0] != nil }
            if let word {
                flushHan(); flushOther()
                out += " \(pinyinWords[word]!) "
                i += word.count
            } else if LanguageTags.containsHan(String(chars[i])) {
                flushOther(); han.append(chars[i]); i += 1
            } else {
                // Numbers, Latin words and punctuation pass through untouched.
                flushHan(); other.append(chars[i]); i += 1
            }
        }
        flushHan(); flushOther()
        let result = tidy(out)
        return result.isEmpty ? nil : result
    }

    private static func transliterate(_ id: String, _ text: String) -> String? {
        text.applyingTransform(StringTransform(rawValue: id), reverse: false)
    }

    /// Collapses spacing and turns full-width CJK punctuation into plain punctuation.
    private static func tidy(_ text: String) -> String {
        var s = text
        for (from, to) in [("，", ","), ("。", "."), ("？", "?"), ("！", "!"), ("：", ":"), ("；", ";"), ("、", ",")] {
            s = s.replacingOccurrences(of: from, with: to)
        }
        s = s.replacingOccurrences(of: "\\s+([,.?!:;])", with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: "([,.?!:;])(?=\\S)", with: "$1 ", options: .regularExpression)
        s = s.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Everyday words whose reading differs from the characters' most common reading.
    static let pinyinWords: [String: String] = [
        "银行": "yín háng", "行业": "háng yè", "一行": "yì háng",
        "觉得": "jué de", "知道": "zhī dao", "东西": "dōng xi", "时候": "shí hou",
        "这个": "zhè ge", "那个": "nà ge", "一个": "yí gè", "哪个": "nǎ ge",
        "什么": "shén me", "怎么": "zěn me", "为什么": "wèi shén me", "多少": "duō shao",
        "谢谢": "xiè xie", "喜欢": "xǐ huan", "朋友": "péng you", "先生": "xiān sheng",
        "音乐": "yīn yuè", "长大": "zhǎng dà", "成长": "chéng zhǎng", "校长": "xiào zhǎng",
        "重新": "chóng xīn", "还是": "hái shi", "还有": "hái yǒu", "得到": "dé dào",
        "睡觉": "shuì jiào", "觉醒": "jué xǐng", "地方": "dì fang", "明白": "míng bai",
        "妈妈": "mā ma", "爸爸": "bà ba", "哥哥": "gē ge", "姐姐": "jiě jie",
        "弟弟": "dì di", "妹妹": "mèi mei", "孩子": "hái zi",
        "了解": "liǎo jiě", "为了": "wèi le", "因为": "yīn wèi", "不客气": "bú kè qi",
        "不是": "bú shì", "不要": "bú yào", "不会": "bú huì", "不对": "bú duì",
        "一样": "yí yàng", "一下": "yí xià", "一定": "yí dìng", "一起": "yì qǐ",
        "早上": "zǎo shang", "晚上": "wǎn shang", "上午": "shàng wǔ",
        "数据": "shù jù", "会计": "kuài jì", "调查": "diào chá", "空调": "kōng tiáo",
    ]

    private static let maxWord = pinyinWords.keys.map(\.count).max() ?? 2
}
