import Foundation

/// The on-device speech models the user can pick from.
enum EngineChoice: String, CaseIterable, Identifiable, Codable {
    case parakeetV3 = "parakeet-tdt-v3"
    case autoAsian = "auto-asian"
    case parakeetV2 = "parakeet-tdt-v2"
    case senseVoice = "sensevoice-small"
    case whisperTinyEn = "openai_whisper-tiny.en"
    case whisperBaseEn = "openai_whisper-base.en"
    case whisperSmallEn = "openai_whisper-small.en"
    case whisperSmall = "openai_whisper-small"
    case whisperLargeV3Turbo = "openai_whisper-large-v3-v20240930_turbo_632MB"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .parakeetV3: return "Auto: Western (Parakeet v3)"
        case .autoAsian: return "Auto: Asian + English (SenseVoice + Parakeet)"
        case .parakeetV2: return "Parakeet TDT v2 (English)"
        case .senseVoice: return "SenseVoice (Chinese & East Asian)"
        case .whisperTinyEn: return "Whisper Tiny (English)"
        case .whisperBaseEn: return "Whisper Base (English)"
        case .whisperSmallEn: return "Whisper Small (English)"
        case .whisperSmall: return "Whisper Small (multilingual)"
        case .whisperLargeV3Turbo: return "Whisper Large v3 Turbo"
        }
    }

    var shortName: String {
        switch self {
        case .parakeetV3: return "Parakeet v3"
        case .autoAsian: return "Auto Asian"
        case .parakeetV2: return "Parakeet v2"
        case .senseVoice: return "SenseVoice"
        case .whisperTinyEn: return "Whisper tiny"
        case .whisperBaseEn: return "Whisper base"
        case .whisperSmallEn: return "Whisper small"
        case .whisperSmall: return "Whisper small ML"
        case .whisperLargeV3Turbo: return "Whisper turbo"
        }
    }

    var approximateSize: String {
        switch self {
        case .parakeetV3, .parakeetV2: return "~480 MB"
        case .autoAsian: return "~930 MB"
        case .senseVoice: return "~450 MB"
        case .whisperTinyEn: return "~75 MB"
        case .whisperBaseEn: return "~145 MB"
        case .whisperSmallEn, .whisperSmall: return "~480 MB"
        case .whisperLargeV3Turbo: return "~630 MB"
        }
    }

    /// Which spoken languages the model understands (shown in Settings).
    var languages: String {
        switch self {
        case .parakeetV3: return "English, French, Spanish, German and 21 more European languages, switching per sentence — not Chinese"
        case .autoAsian: return "Mandarin, Cantonese, Japanese, Korean via SenseVoice and English via Parakeet, picked per sentence"
        case .parakeetV2, .whisperTinyEn, .whisperBaseEn, .whisperSmallEn: return "English only"
        case .senseVoice: return "Mandarin, Cantonese, English, Japanese, Korean"
        case .whisperSmall: return "~100 languages incl. Mandarin, French, Spanish"
        case .whisperLargeV3Turbo: return "~100 languages incl. Mandarin, French, Spanish (best accuracy, slowest)"
        }
    }

    /// Whether this model understands speech in `language` (a code like "zh").
    func understands(_ language: String) -> Bool {
        switch self {
        case .parakeetV3: return Self.parakeetV3Languages.contains(language)
        case .autoAsian, .senseVoice: return Self.eastAsianAndEnglish.contains(language)
        case .parakeetV2, .whisperTinyEn, .whisperBaseEn, .whisperSmallEn: return language == "en"
        // Whisper's ~100 languages cover everything the translator offers well enough.
        case .whisperSmall, .whisperLargeV3Turbo: return true
        }
    }

    /// The 25 European languages Parakeet TDT v3 was trained on.
    static let parakeetV3Languages: Set<String> = [
        "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it", "lv", "lt",
        "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
    ]
    private static let eastAsianAndEnglish: Set<String> = ["en", "zh", "yue", "ja", "ko"]

    /// The model to use for a conversation between `a` and `b`: the fastest
    /// one that understands both, preferring one already downloaded.
    static func recommended(for a: String, _ b: String, installed: Set<EngineChoice> = []) -> EngineChoice {
        // Auto Asian adds Parakeet for better English; without English, SenseVoice alone is enough.
        let asian: [EngineChoice] = a == "en" || b == "en" ? [.autoAsian, .senseVoice] : [.senseVoice, .autoAsian]
        let order = [.parakeetV3] + asian + [.whisperSmall, .whisperLargeV3Turbo]
        let fitting = order.filter { $0.understands(a) && $0.understands(b) }
        // Whisper small is only worth it over turbo when it saves a download.
        return fitting.first(where: installed.contains) ?? fitting.first { $0 != .whisperSmall }!
    }
}
