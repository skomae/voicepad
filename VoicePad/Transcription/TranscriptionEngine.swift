import Foundation

/// The on-device speech models the user can pick from.
enum EngineChoice: String, CaseIterable, Identifiable, Codable {
    case parakeetV3 = "parakeet-tdt-v3"
    case parakeetV2 = "parakeet-tdt-v2"
    case whisperTinyEn = "openai_whisper-tiny.en"
    case whisperBaseEn = "openai_whisper-base.en"
    case whisperSmallEn = "openai_whisper-small.en"
    case whisperLargeV3Turbo = "openai_whisper-large-v3-v20240930_turbo_632MB"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .parakeetV3: return "Parakeet TDT v3 (multilingual)"
        case .parakeetV2: return "Parakeet TDT v2 (English)"
        case .whisperTinyEn: return "Whisper Tiny (English)"
        case .whisperBaseEn: return "Whisper Base (English)"
        case .whisperSmallEn: return "Whisper Small (English)"
        case .whisperLargeV3Turbo: return "Whisper Large v3 Turbo"
        }
    }

    var shortName: String {
        switch self {
        case .parakeetV3: return "Parakeet v3"
        case .parakeetV2: return "Parakeet v2"
        case .whisperTinyEn: return "Whisper tiny"
        case .whisperBaseEn: return "Whisper base"
        case .whisperSmallEn: return "Whisper small"
        case .whisperLargeV3Turbo: return "Whisper large-v3 turbo"
        }
    }

    var approximateSize: String {
        switch self {
        case .parakeetV3, .parakeetV2: return "~480 MB"
        case .whisperTinyEn: return "~75 MB"
        case .whisperBaseEn: return "~145 MB"
        case .whisperSmallEn: return "~480 MB"
        case .whisperLargeV3Turbo: return "~630 MB"
        }
    }

    func makeEngine() -> TranscriptionEngine {
        switch self {
        case .parakeetV3: return ParakeetEngine(version: .v3)
        case .parakeetV2: return ParakeetEngine(version: .v2)
        case .whisperTinyEn, .whisperBaseEn, .whisperSmallEn, .whisperLargeV3Turbo:
            return WhisperEngine(model: rawValue)
        }
    }
}

/// A speech-to-text model that turns 16 kHz mono Float32 samples into text.
protocol TranscriptionEngine: AnyObject {
    /// Downloads (first run only) and loads the model. `progress` reports 0...1.
    func load(progress: @escaping @Sendable (Double) -> Void) async throws
    func transcribe(_ samples: [Float]) async throws -> String
}
