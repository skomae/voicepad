import Foundation

/// Pinyin for Mandarin text, when the translator didn't provide any (no
/// translation yet, translation off, or still live). ICU's Han–Latin gives the
/// characters' usual readings with tone marks. Ported from Android's
/// `DeviceRomanizer` (#22).
enum MandarinPinyin {
    /// The pinyin line for `text`, or nil when it isn't Mandarin (Japanese uses
    /// kanji but reads them differently, and Cantonese is written as it sounds).
    static func of(_ text: String, language: String?) -> String? {
        guard language != "ja", language != "yue", language != "ko" else { return nil }
        guard LanguageTags.containsHan(text), !LanguageTags.containsKana(text) else { return nil }
        let mutable = NSMutableString(string: text)
        CFStringTransform(mutable, nil, kCFStringTransformToLatin, false)
        let pinyin = (mutable as String).trimmingCharacters(in: .whitespacesAndNewlines)
        return pinyin.isEmpty || pinyin == text ? nil : pinyin
    }
}
